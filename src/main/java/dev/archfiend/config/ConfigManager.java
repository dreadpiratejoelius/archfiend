package dev.archfiend.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.archfiend.ArchfiendClient;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ConfigManager
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final int REPLACE_ATTEMPTS = 3;
    private static final long REPLACE_RETRY_DELAY_MS = 20;

    private final Path path;
    private ArchfiendConfig config = new ArchfiendConfig();

    public ConfigManager()
    {
        this(FabricLoader.getInstance().getConfigDir().resolve(ArchfiendClient.MOD_ID + ".json"));
    }

    public ConfigManager(Path path)
    {
        this.path = path;
    }

    public ArchfiendConfig get()
    {
        return config;
    }

    /**
     * Loads the config from disk. A missing file is created with defaults; a corrupt file is
     * backed up and replaced so a single bad edit never leaves the mod unusable.
     *
     * @return {@code true} if the file was read successfully (or freshly created)
     */
    public boolean load()
    {
        if (!Files.exists(path))
        {
            config = new ArchfiendConfig();
            save();
            return true;
        }

        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
        {
            ArchfiendConfig loaded = GSON.fromJson(reader, ArchfiendConfig.class);
            config = loaded != null ? loaded : new ArchfiendConfig();
            config.sanitize();
            save(); // writes back repaired values and any newly added fields
            return true;
        }
        catch (IOException | JsonParseException e)
        {
            ArchfiendClient.LOGGER.error("Failed to read config {}, restoring defaults", path, e);
            backupCorrupt();
            config = new ArchfiendConfig();
            save();
            return false;
        }
    }

    /**
     * Writes to a temp file and swaps it in so a crash mid-write can't truncate the config.
     * On Windows the swap can transiently fail while antivirus or the indexer holds the target open,
     * so it is retried briefly before falling back to a direct write.
     */
    public void save()
    {
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        try
        {
            Files.createDirectories(path.getParent());
            String json = GSON.toJson(config);
            Files.writeString(temp, json, StandardCharsets.UTF_8);

            for (int attempt = 1; ; attempt++)
            {
                try
                {
                    replace(temp, path);
                    return;
                }
                catch (AccessDeniedException e)
                {
                    if (attempt >= REPLACE_ATTEMPTS)
                    {
                        ArchfiendClient.LOGGER.warn("Config {} is locked, writing it in place", path);
                        Files.writeString(path, json, StandardCharsets.UTF_8);
                        return;
                    }
                    sleepQuietly(REPLACE_RETRY_DELAY_MS * attempt);
                }
            }
        }
        catch (IOException e)
        {
            ArchfiendClient.LOGGER.error("Failed to save config {}", path, e);
        }
        finally
        {
            try
            {
                Files.deleteIfExists(temp);
            }
            catch (IOException e)
            {
                ArchfiendClient.LOGGER.debug("Could not delete temp config {}", temp, e);
            }
        }
    }

    private static void replace(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void sleepQuietly(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private void backupCorrupt()
    {
        try
        {
            Files.move(path, path.resolveSibling(path.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
        }
        catch (IOException e)
        {
            ArchfiendClient.LOGGER.warn("Could not back up corrupt config {}", path, e);
        }
    }
}
