package dev.archfiend.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dev.archfiend.ArchfiendClient;
import dev.archfiend.util.AtomicFiles;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ConfigManager
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

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

    public void save()
    {
        try
        {
            AtomicFiles.writeString(path, GSON.toJson(config));
        }
        catch (IOException e)
        {
            ArchfiendClient.LOGGER.error("Failed to save config {}", path, e);
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
