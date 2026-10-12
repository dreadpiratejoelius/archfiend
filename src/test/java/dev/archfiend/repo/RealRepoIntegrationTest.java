package dev.archfiend.repo;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs against a real downloaded NEU archive with Minecraft's registries bootstrapped.
 * Skipped unless {@code ARCHFIEND_NEU_ZIP} points at the archive, e.g.
 * {@code ARCHFIEND_NEU_ZIP=path/to/repo.zip ./gradlew test}.
 */
class RealRepoIntegrationTest
{
    private static NeuRepo repo;

    @BeforeAll
    static void load() throws IOException
    {
        String path = System.getenv("ARCHFIEND_NEU_ZIP");
        assumeTrue(path != null && Files.isRegularFile(Path.of(path)), "ARCHFIEND_NEU_ZIP not set");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        repo = NeuRepo.load(Path.of(path));
    }

    @Test
    void nearlyEveryItemResolvesToAModernItem()
    {
        List<String> unresolved = new ArrayList<>();
        int total = 0;
        for (String id : allIds())
        {
            total++;
            NeuItem item = repo.item(id).orElseThrow();
            if (NeuItemStacks.resolveBase(item) == null) unresolved.add(id + "(" + item.legacyItemId() + ":" + item.damage() + ")");
        }
        System.out.printf("Resolved %d/%d repo items; unresolved sample: %s%n",
            total - unresolved.size(), total, unresolved.subList(0, Math.min(25, unresolved.size())));
        assertTrue(total > 5000, "repo looks truncated: " + total);
        assertTrue(unresolved.size() <= total / 100, "too many unresolved items: " + unresolved.size());
    }

    @Test
    void wardenHeartIsATexturedHeadWithARealLootTable()
    {
        // ItemStack creation needs data-pack-bound components, which only exist in a running client.
        NeuItem heart = repo.findByName("Warden Heart", 0xFFAA00).orElseThrow();
        assertEquals(Items.PLAYER_HEAD, NeuItemStacks.resolveBase(heart));
        assertNotNull(heart.skullTexture());

        DropTable table = repo.lootTableFor(heart.internalName()).orElseThrow();
        System.out.println("Warden Heart drops from " + table.source() + " with " + table.entries().size() + " entries");
        assertTrue(table.entries().size() >= 5);
    }

    @Test
    void scathaPetResolvesByRarityColour()
    {
        assertEquals("SCATHA;4", repo.findByName("Scatha", 0xFFAA00).orElseThrow().internalName());
    }

    private static List<String> allIds()
    {
        List<String> ids = new ArrayList<>();
        repo.forEachItemId(ids::add);
        return ids;
    }
}
