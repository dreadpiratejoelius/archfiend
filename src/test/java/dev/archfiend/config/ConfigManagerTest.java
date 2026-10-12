package dev.archfiend.config;

import dev.archfiend.drop.DropTier;
import dev.archfiend.sequence.SequenceStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerTest
{
    @TempDir
    Path dir;

    @Test
    void createsDefaultsWhenMissing()
    {
        Path file = dir.resolve("archfiend.json");
        ConfigManager manager = new ConfigManager(file);

        assertTrue(manager.load());
        assertTrue(Files.exists(file));
        assertFalse(Files.exists(dir.resolve("archfiend.json.tmp")));
        assertFalse(manager.get().triggers.isEmpty());
    }

    @Test
    void roundTripsChanges()
    {
        Path file = dir.resolve("archfiend.json");
        ConfigManager manager = new ConfigManager(file);
        manager.load();
        manager.get().style = SequenceStyle.SLOTS;
        manager.get().minTier = DropTier.INSANE;
        manager.get().spinTicks = 77;
        manager.save();

        ConfigManager reloaded = new ConfigManager(file);
        assertTrue(reloaded.load());
        assertEquals(SequenceStyle.SLOTS, reloaded.get().style);
        assertEquals(DropTier.INSANE, reloaded.get().minTier);
        assertEquals(77, reloaded.get().spinTicks);
    }

    @Test
    void sanitizesOutOfRangeAndMissingValues() throws IOException
    {
        Path file = dir.resolve("archfiend.json");
        Files.writeString(file, "{\"spinTicks\": 99999, \"scale\": -3, \"style\": null, \"triggers\": [], \"fillers\": null}");

        ConfigManager manager = new ConfigManager(file);
        assertTrue(manager.load());
        ArchfiendConfig config = manager.get();
        assertEquals(ArchfiendConfig.MAX_SPIN_TICKS, config.spinTicks);
        assertEquals(ArchfiendConfig.MIN_SCALE, config.scale);
        assertEquals(SequenceStyle.REEL, config.style);
        assertFalse(config.triggers.isEmpty());
        assertFalse(config.fillers.isEmpty());
    }

    @Test
    void migrationAddsNewTriggerToOldConfigsOnce() throws IOException
    {
        Path file = dir.resolve("archfiend.json");
        Files.writeString(file, "{\"triggers\": [{\"prefix\": \"CRAZY RARE DROP!\", \"tier\": \"CRAZY_RARE\"}]}");

        ConfigManager manager = new ConfigManager(file);
        assertTrue(manager.load());
        assertEquals(2, manager.get().triggers.size());
        assertTrue(manager.get().triggers.stream().anyMatch(t -> t.tier() == DropTier.EXTREMELY_RARE));

        // A user who deletes it afterwards must not get it back on the next load.
        manager.get().triggers.removeIf(t -> t.tier() == DropTier.EXTREMELY_RARE);
        manager.save();
        ConfigManager reloaded = new ConfigManager(file);
        reloaded.load();
        assertEquals(1, reloaded.get().triggers.size());
    }

    @Test
    void recoversFromCorruptFile() throws IOException
    {
        Path file = dir.resolve("archfiend.json");
        Files.writeString(file, "{ this is not json");

        ConfigManager manager = new ConfigManager(file);
        assertFalse(manager.load());
        assertTrue(Files.exists(dir.resolve("archfiend.json.corrupt")));
        assertTrue(manager.get().enabled);
        assertTrue(Files.readString(file).contains("\"enabled\""));
    }
}
