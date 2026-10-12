package dev.archfiend.drop;

import dev.archfiend.config.ArchfiendConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestDropsTest
{
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
    void allKnownItemsContainsAllTierItems()
    {
        List<String> all = TestDrops.allKnownItems();
        assertFalse(all.isEmpty());
        for (DropTier tier : DropTier.values())
        {
            for (String item : TestDrops.itemsForTier(tier))
            {
                assertTrue(all.contains(item), "allKnownItems should contain " + item);
            }
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
    void createWithNullItemUsesTierItem()
    {
        RngDrop drop = TestDrops.create(config, DropTier.CRAZY_RARE, null);
        assertEquals(DropTier.CRAZY_RARE, drop.tier());
        assertTrue(TestDrops.itemsForTier(DropTier.CRAZY_RARE).contains(drop.itemName()));
    }

    @Test
    void createWithCustomItemUsesProvidedName()
    {
        RngDrop drop = TestDrops.create(config, DropTier.PRAY_TO_RNGESUS, "Judgement Core");
        assertEquals(DropTier.PRAY_TO_RNGESUS, drop.tier());
        assertEquals("Judgement Core", drop.itemName());
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
