package dev.archfiend.repo;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.archfiend.ArchfiendClient;
import dev.archfiend.config.ArchfiendConfig;
import dev.archfiend.util.AtomicFiles;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Downloads, caches and hot-swaps the NEU item repo.
 * <p>
 * The archive is pinned to an exact commit: the latest commit SHA of the configured branch is
 * fetched first, and only a changed SHA triggers a download. The cached archive is used immediately
 * on startup and whenever GitHub is unreachable. All work runs on a single background thread; the
 * loaded {@link NeuRepo} is immutable and published through a volatile field.
 */
public final class NeuRepoManager implements AutoCloseable
{
    public enum State { DISABLED, IDLE, CHECKING, DOWNLOADING, LOADING, READY, FAILED }

    private static final String ARCHIVE_FILE = "neu-repo.zip";
    private static final String META_FILE = "neu-repo.json";
    private static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;
    private static final Pattern SHA = Pattern.compile("^[0-9a-f]{40}$");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SHA_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(5);
    private static final Gson GSON = new Gson();

    private final Path archive;
    private final Path metaFile;
    private final Supplier<ArchfiendConfig.Repo> settings;
    private final String userAgent;
    private final HttpClient http;
    private final ExecutorService executor;
    private CompletableFuture<Void> inFlight = CompletableFuture.completedFuture(null);

    private volatile @Nullable NeuRepo repo;
    private volatile @Nullable Meta meta;
    private volatile State state = State.IDLE;
    private volatile String detail = "";

    /** What the cached archive was downloaded from, so a changed config forces a fresh download. */
    private record Meta(String repository, String branch, String sha, long downloadedAt) {}

    private record Settings(boolean enabled, boolean autoUpdate, String repository, String branch)
    {
        static Settings of(ArchfiendConfig.Repo repo)
        {
            return new Settings(repo.enabled, repo.autoUpdate, repo.repository, repo.branch);
        }
    }

    public NeuRepoManager(Path directory, Supplier<ArchfiendConfig.Repo> settings, String userAgent)
    {
        this.archive = directory.resolve(ARCHIVE_FILE);
        this.metaFile = directory.resolve(META_FILE);
        this.settings = settings;
        this.userAgent = userAgent;
        this.http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Archfiend NEU Repo");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Optional<NeuRepo> repo()
    {
        return Optional.ofNullable(repo);
    }

    public State state()
    {
        return state;
    }

    /** One-line human readable status for commands and logs. */
    public String describe()
    {
        NeuRepo current = repo;
        Meta currentMeta = meta;
        StringBuilder text = new StringBuilder(state.name().toLowerCase(Locale.ROOT));
        if (current != null) text.append(" | ").append(current.itemCount()).append(" items");
        if (currentMeta != null) text.append(" | ").append(currentMeta.repository()).append('@').append(currentMeta.sha(), 0, 7);
        if (!detail.isEmpty()) text.append(" | ").append(detail);
        return text.toString();
    }

    /**
     * Loads the cached repo and/or checks for updates in the background.
     *
     * @param force re-download even if the cached commit is current
     * @return completes when this refresh (or the one already running, which is reused) finishes; never fails
     */
    public synchronized CompletableFuture<Void> refreshAsync(boolean force)
    {
        if (!inFlight.isDone()) return inFlight;

        Settings snapshot = Settings.of(settings.get());
        inFlight = CompletableFuture.runAsync(() -> {
            try
            {
                refresh(snapshot, force);
            }
            catch (RuntimeException e)
            {
                fail("unexpected error: " + e, e);
            }
        }, executor);
        return inFlight;
    }

    private void refresh(Settings settings, boolean force)
    {
        if (!settings.enabled())
        {
            repo = null;
            setState(State.DISABLED, "disabled in config");
            return;
        }

        Meta cached = readMeta();
        boolean cacheMatches = cached != null
            && cached.repository().equals(settings.repository())
            && cached.branch().equals(settings.branch())
            && Files.isRegularFile(archive);

        if (repo == null && cacheMatches && !loadCached(cached)) cacheMatches = false;
        if (cacheMatches && !force && !settings.autoUpdate()) return;

        setState(State.CHECKING, "");
        String latest;
        try
        {
            latest = fetchLatestSha(settings);
        }
        catch (IOException e)
        {
            if (repo != null) setState(State.READY, "update check failed, using cache");
            else fail("update check failed: " + e.getMessage(), e);
            return;
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return;
        }

        if (cacheMatches && !force && latest.equals(cached.sha()))
        {
            setState(State.READY, "up to date");
            return;
        }
        download(settings, latest);
    }

    private boolean loadCached(Meta cached)
    {
        setState(State.LOADING, "reading cache");
        try
        {
            NeuRepo loaded = NeuRepo.load(archive);
            if (loaded.itemCount() == 0) throw new IOException("cached archive contains no items");
            publish(loaded, cached, "cached");
            return true;
        }
        catch (IOException e)
        {
            ArchfiendClient.LOGGER.warn("Cached NEU repo is unusable, re-downloading", e);
            return false;
        }
    }

    private void download(Settings settings, String sha)
    {
        Path temp = AtomicFiles.tempSibling(archive);
        try
        {
            setState(State.DOWNLOADING, sha.substring(0, 7));
            Files.createDirectories(archive.getParent());
            URI uri = URI.create("https://codeload.github.com/" + settings.repository() + "/zip/" + sha);
            HttpResponse<InputStream> response = http.send(request(uri, DOWNLOAD_TIMEOUT).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body())
            {
                if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " from " + uri);
                copyBounded(body, temp);
            }

            setState(State.LOADING, "indexing");
            NeuRepo loaded = NeuRepo.load(temp);
            if (loaded.itemCount() == 0) throw new IOException("downloaded archive contains no items");

            AtomicFiles.replace(temp, archive);
            Meta fresh = new Meta(settings.repository(), settings.branch(), sha, System.currentTimeMillis());
            AtomicFiles.writeString(metaFile, GSON.toJson(fresh));
            publish(loaded, fresh, "updated");
        }
        catch (IOException e)
        {
            if (repo != null) setState(State.READY, "download failed, using cache");
            else fail("download failed: " + e.getMessage(), e);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        finally
        {
            try
            {
                Files.deleteIfExists(temp);
            }
            catch (IOException e)
            {
                ArchfiendClient.LOGGER.debug("Could not delete {}", temp, e);
            }
        }
    }

    private String fetchLatestSha(Settings settings) throws IOException, InterruptedException
    {
        URI uri = URI.create("https://api.github.com/repos/" + settings.repository() + "/commits/" + settings.branch());
        HttpRequest request = request(uri, SHA_TIMEOUT).header("Accept", "application/vnd.github.sha").build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " from " + uri);

        String sha = response.body().trim();
        if (!SHA.matcher(sha).matches()) throw new IOException("Unexpected commit response from " + uri);
        return sha;
    }

    private HttpRequest.Builder request(URI uri, Duration timeout)
    {
        return HttpRequest.newBuilder(uri).timeout(timeout).header("User-Agent", userAgent).GET();
    }

    private static void copyBounded(InputStream in, Path target) throws IOException
    {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (OutputStream out = Files.newOutputStream(target))
        {
            int read;
            while ((read = in.read(buffer)) != -1)
            {
                total += read;
                if (total > MAX_ARCHIVE_BYTES) throw new IOException("Repo archive exceeds " + MAX_ARCHIVE_BYTES + " bytes");
                out.write(buffer, 0, read);
            }
        }
    }

    private @Nullable Meta readMeta()
    {
        if (!Files.isRegularFile(metaFile)) return null;
        try
        {
            Meta loaded = GSON.fromJson(Files.readString(metaFile, StandardCharsets.UTF_8), Meta.class);
            if (loaded == null || loaded.repository() == null || loaded.branch() == null
                || loaded.sha() == null || !SHA.matcher(loaded.sha()).matches())
            {
                return null;
            }
            return loaded;
        }
        catch (IOException | JsonParseException e)
        {
            ArchfiendClient.LOGGER.warn("Ignoring unreadable {}", metaFile, e);
            return null;
        }
    }

    private void publish(NeuRepo loaded, Meta source, String how)
    {
        repo = loaded;
        meta = source;
        setState(State.READY, how);
        ArchfiendClient.LOGGER.info("NEU repo {}: {} items from {}@{}", how, loaded.itemCount(), source.repository(), source.sha().substring(0, 7));
    }

    private void fail(String message, Exception cause)
    {
        setState(State.FAILED, message);
        ArchfiendClient.LOGGER.error("NEU repo unavailable: {}", message, cause);
    }

    private void setState(State newState, String newDetail)
    {
        state = newState;
        detail = newDetail;
    }

    @Override
    public void close()
    {
        executor.shutdownNow();
    }
}
