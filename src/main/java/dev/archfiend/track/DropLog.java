package dev.archfiend.track;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.archfiend.ArchfiendClient;
import dev.archfiend.drop.DropTier;
import dev.archfiend.util.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Persistent record of every recognised drop, in {@code config/archfiend/drops.json}.
 * <p>
 * Lifetime totals per tier and per item are kept as counters so stats survive even though only the
 * most recent {@link #MAX_RECORDS} individual drops are stored. Mutated on the client thread only;
 * serialisation happens there and the file write runs on a background thread.
 */
public final class DropLog implements AutoCloseable
{
    public static final int MAX_RECORDS = 500;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path path;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Archfiend Drop Log");
        thread.setDaemon(true);
        return thread;
    });
    private Data data = new Data();

    /** On-disk layout. */
    private static final class Data
    {
        int version = 1;
        long trackingSince = System.currentTimeMillis();
        Map<DropTier, Integer> tierCounts = new EnumMap<>(DropTier.class);
        Map<String, Integer> itemCounts = new HashMap<>();
        List<DropRecord> recent = new ArrayList<>();
    }

    public DropLog(Path path)
    {
        this.path = path;
    }

    public void load()
    {
        if (!Files.isRegularFile(path)) return;
        try
        {
            Data loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
            if (loaded == null) return;
            if (loaded.tierCounts == null) loaded.tierCounts = new EnumMap<>(DropTier.class);
            loaded.tierCounts.keySet().removeIf(tier -> tier == null); // tiers removed in a later version
            if (loaded.itemCounts == null) loaded.itemCounts = new HashMap<>();
            if (loaded.recent == null) loaded.recent = new ArrayList<>();
            loaded.recent.removeIf(record -> record == null || !record.valid());
            data = loaded;
        }
        catch (IOException | JsonParseException e)
        {
            ArchfiendClient.LOGGER.error("Could not read drop log {}; starting a new one", path, e);
            try
            {
                Files.move(path, path.resolveSibling(path.getFileName() + ".corrupt"), StandardCopyOption.REPLACE_EXISTING);
            }
            catch (IOException moveError)
            {
                ArchfiendClient.LOGGER.warn("Could not back up {}", path, moveError);
            }
        }
    }

    public void add(DropRecord record)
    {
        data.recent.add(record);
        while (data.recent.size() > MAX_RECORDS) data.recent.removeFirst();
        data.tierCounts.merge(record.tier, 1, Integer::sum);
        data.itemCounts.merge(record.item, 1, Integer::sum);
        save();
    }

    /** Persists a change made to a record already in the log (e.g. a late confirmation). */
    public void updated()
    {
        save();
    }

    /** Most recent drops, newest first. */
    public List<DropRecord> recent(int limit)
    {
        List<DropRecord> copy = new ArrayList<>(data.recent);
        Collections.reverse(copy);
        return copy.subList(0, Math.min(limit, copy.size()));
    }

    public int total()
    {
        return data.tierCounts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int count(DropTier tier)
    {
        return data.tierCounts.getOrDefault(tier, 0);
    }

    /** Item names with their lifetime counts, most frequent first. */
    public List<Map.Entry<String, Integer>> topItems(int limit)
    {
        return data.itemCounts.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(limit)
            .toList();
    }

    public long trackingSince()
    {
        return data.trackingSince;
    }

    private void save()
    {
        String json = GSON.toJson(data);
        writer.execute(() -> {
            try
            {
                AtomicFiles.writeString(path, json);
            }
            catch (IOException e)
            {
                ArchfiendClient.LOGGER.error("Could not save drop log {}", path, e);
            }
        });
    }

    /** Finishes pending writes so nothing is lost on shutdown. */
    @Override
    public void close()
    {
        writer.shutdown();
        try
        {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) ArchfiendClient.LOGGER.warn("Drop log did not finish saving");
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
