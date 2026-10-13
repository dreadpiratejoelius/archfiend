package dev.archfiend.drop;

import dev.archfiend.config.ArchfiendConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestDropsTest
{
    private static final Path MODELS = Path.of("src/main/resources/assets/archfiend/models/items");

    private final ArchfiendConfig config = new ArchfiendConfig();

    @Test
    void everyTierHasItemsInCatalog()
    {
        for (DropTier tier : DropTier.values())
        {
            List<String> items = TestDrops.itemsForTier(tier);
            assertFalse(items.isEmpty(), "Catalog should have items for tier " + tier);
            assertEquals(items.getFirst(), TestDrops.defaultItem(tier), "First item should be defaultItem for tier " + tier);
        }
    }

    @Test
    void randomItemPicksFromTierCatalog()
    {
        for (DropTier tier : DropTier.values())
        {
            List<String> items = TestDrops.itemsForTier(tier);
            String picked = TestDrops.randomItem(tier);
            assertTrue(items.contains(picked));
        }
    }

    @Test
    void createWithNullItemUsesDefaultItem()
    {
        RngDrop drop = TestDrops.create(config, DropTier.CRAZY_RARE, null);
        assertEquals(DropTier.CRAZY_RARE, drop.tier());
        assertEquals(TestDrops.defaultItem(DropTier.CRAZY_RARE), drop.itemName());
    }

    @Test
    void createWithCustomItemUsesProvidedName()
    {
        RngDrop drop = TestDrops.create(config, DropTier.PRAY_TO_RNGESUS, "Judgement Core");
        assertEquals(DropTier.PRAY_TO_RNGESUS, drop.tier());
        assertEquals("Judgement Core", drop.itemName());
    }

    @Test
    void everyCatalogItemHasExactlyOneModelDirectory() throws IOException
    {
        Set<String> expected = new TreeSet<>();
        for (DropTier tier : DropTier.values())
        {
            for (String item : TestDrops.itemsForTier(tier))
            {
                assertTrue(expected.add(ItemNames.toId(item)), "Duplicate model id for " + item);
            }
        }

        Set<String> actual = new TreeSet<>();
        try (Stream<Path> dirs = Files.list(MODELS))
        {
            dirs.filter(dir -> Files.isRegularFile(dir.resolve("model.gltf")))
                .forEach(dir -> actual.add(dir.getFileName().toString()));
        }
        assertEquals(expected, actual);
    }

    @Test
    void toIdMatchesResourcePathRules()
    {
        assertEquals("necrons_handle", ItemNames.toId("Necron's Handle"));
        assertEquals("fangtastic_chocolate_chip", ItemNames.toId("Fang-tastic Chocolate Chip"));
        assertEquals("couture_rune_i", ItemNames.toId("◆ Couture Rune I"));
    }

    @Test
    void normalizesRuneSymbolsAndPetPrefixes()
    {
        assertEquals("couture rune i", ItemNames.normalize("§6◆ Couture Rune I"));
        assertEquals("snake rune i", ItemNames.normalize("◇ Snake Rune I ◇"));
        assertEquals("scatha", ItemNames.normalize("§6[Lvl 100] Scatha"));
        assertEquals("scatha", ItemNames.normalize("§6[Lvl {LVL}] Scatha"));
    }
}
