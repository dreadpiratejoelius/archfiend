package dev.archfiend.repo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeuRepoTest
{
    private static final String ROOT = "NotEnoughUpdates-REPO-abc123/";

    @TempDir
    Path dir;

    @Test
    void indexesItemsNamesAndSkullData() throws IOException
    {
        NeuRepo repo = NeuRepo.load(sampleArchive());

        assertEquals(7, repo.itemCount());
        NeuItem heart = repo.findByName("Warden Heart", 0xFFAA00).orElseThrow();
        assertEquals("WARDEN_HEART", heart.internalName());
        assertEquals(0xFFAA00, heart.nameColor());
        assertEquals("minecraft:skull", heart.legacyItemId());
        assertEquals(3, heart.damage());
        assertEquals("minecraft:player_head", heart.itemModel());
        assertEquals("dGV4dHVyZQ==", heart.skullTexture());
        assertEquals("eef9b6d7-a195-3d8a-a216-0e87a585cd83", heart.skullOwner());
        assertFalse(heart.glint());
    }

    @Test
    void lookupIsCaseAndFormattingInsensitive() throws IOException
    {
        NeuRepo repo = NeuRepo.load(sampleArchive());
        assertEquals("WARDEN_HEART", repo.findByName("§6warden  HEART", 0).orElseThrow().internalName());
        assertTrue(repo.findByName("Nonexistent Thing", 0).isEmpty());
    }

    @Test
    void prefersPetRarityMatchingDropColour() throws IOException
    {
        NeuRepo repo = NeuRepo.load(sampleArchive());
        assertEquals("SCATHA;4", repo.findByName("Scatha", 0xFFAA00).orElseThrow().internalName());
        assertEquals("SCATHA;3", repo.findByName("Scatha", 0xAA00AA).orElseThrow().internalName());
        // Unknown colour falls back deterministically to the first id.
        assertEquals("SCATHA;3", repo.findByName("[Lvl 100] Scatha", 0x123456).orElseThrow().internalName());
    }

    @Test
    void parsesLeatherColourAndEnchantGlint() throws IOException
    {
        NeuRepo repo = NeuRepo.load(sampleArchive());
        NeuItem boots = repo.item("ABYSSAL_BOOTS").orElseThrow();
        assertEquals(943725, boots.dyeColor());
        assertTrue(boots.glint());
        assertNull(repo.item("WARDEN_HEART").orElseThrow().dyeColor());
    }

    @Test
    void buildsLootTablesWithChances() throws IOException
    {
        NeuRepo repo = NeuRepo.load(sampleArchive());
        DropTable table = repo.lootTableFor("WARDEN_HEART").orElseThrow();

        assertEquals("Revenant Horror V", table.source());
        assertEquals(3, table.entries().size());
        assertEquals(100.0, table.entries().get(0).chance(), 1e-9);
        assertEquals(0.0136, table.entries().get(2).chance(), 1e-9);
        assertTrue(repo.lootTableFor("FOUL_FLESH").isPresent());
        assertTrue(repo.lootTableFor("ABYSSAL_BOOTS").isEmpty());
    }

    @Test
    void skipsMalformedEntries() throws IOException
    {
        Path archive = dir.resolve("broken.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive)))
        {
            put(zip, ROOT + "items/BROKEN.json", "{ not json");
            put(zip, ROOT + "items/MISSING_FIELDS.json", "{\"internalname\":\"X\"}");
            put(zip, ROOT + "items/ARRAY.json", "[1,2,3]");
            put(zip, ROOT + "items/FOUL_FLESH.json", item("FOUL_FLESH", "§9Foul Flesh", "minecraft:coal", 1, "", ""));
        }
        NeuRepo repo = NeuRepo.load(archive);
        assertEquals(1, repo.itemCount());
    }

    @Test
    void parsesChanceFormats()
    {
        assertEquals(16.1616, NeuRepo.parseChance("16.1616%"), 1e-9);
        assertEquals(5.0, NeuRepo.parseChance("§a5%"), 1e-9);
        assertEquals(0.2, NeuRepo.parseChance("1/500"), 1e-9);
        assertEquals(2.5, NeuRepo.parseChance("2,5% per Summoning Eye"), 1e-9);
        assertEquals(1.0, NeuRepo.parseChance(null), 1e-9);
        assertEquals(1.0, NeuRepo.parseChance("sometimes"), 1e-9);
        assertEquals(100.0, NeuRepo.parseChance("250%"), 1e-9);
    }

    private Path sampleArchive() throws IOException
    {
        Path archive = dir.resolve("repo.zip");
        String skullNbt = "{ExtraAttributes:{id:\\\"WARDEN_HEART\\\"},ItemModel:\\\"minecraft:player_head\\\","
            + "SkullOwner:{Id:\\\"eef9b6d7-a195-3d8a-a216-0e87a585cd83\\\",Properties:{textures:[0:{Name:\\\"textures\\\","
            + "Signature:\\\"c2ln/+=\\\",Value:\\\"dGV4dHVyZQ==\\\"}]}},display:{Name:\\\"§6Warden Heart\\\"}}";
        String bootsNbt = "{ench:[],display:{Lore:[0:\\\"§7}tricky\\\"],color:943725},ItemModel:\\\"minecraft:leather_boots\\\"}";
        String drops = ",\"recipes\":[{\"type\":\"drops\",\"name\":\"§6Revenant Horror V\",\"drops\":["
            + "{\"id\":\"REVENANT_FLESH\",\"chance\":\"100%\"},"
            + "{\"id\":\"FOUL_FLESH\",\"chance\":\"16.1616%\"},"
            + "{\"id\":\"WARDEN_HEART\",\"chance\":\"0.0136%\"}]}]";

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive)))
        {
            put(zip, ROOT + "README.md", "not an item");
            put(zip, ROOT + "constants/pets.json", "{}");
            put(zip, ROOT + "items/WARDEN_HEART.json", item("WARDEN_HEART", "§6Warden Heart", "minecraft:skull", 3, skullNbt, ""));
            put(zip, ROOT + "items/FOUL_FLESH.json", item("FOUL_FLESH", "§9Foul Flesh", "minecraft:coal", 1, "", ""));
            put(zip, ROOT + "items/REVENANT_FLESH.json", item("REVENANT_FLESH", "§aRevenant Flesh", "minecraft:rotten_flesh", 0, "", ""));
            put(zip, ROOT + "items/REVENANT_HORROR_5_BOSS.json", item("REVENANT_HORROR_5_BOSS", "§6Revenant Horror V (Boss)", "minecraft:skull", 3, "", drops));
            put(zip, ROOT + "items/SCATHA;3.json", item("SCATHA;3", "§f§f§7[Lvl {LVL}] §5Scatha", "minecraft:skull", 3, "", ""));
            put(zip, ROOT + "items/SCATHA;4.json", item("SCATHA;4", "§f§f§7[Lvl {LVL}] §6Scatha", "minecraft:skull", 3, "", ""));
            put(zip, ROOT + "items/ABYSSAL_BOOTS.json", item("ABYSSAL_BOOTS", "§9Abyssal Boots", "minecraft:leather_boots", 0, bootsNbt, ""));
        }
        return archive;
    }

    private static String item(String id, String displayName, String itemId, int damage, String nbt, String extra)
    {
        return "{\"internalname\":\"" + id + "\",\"displayname\":\"" + displayName + "\",\"itemid\":\"" + itemId
            + "\",\"damage\":" + damage + ",\"nbttag\":\"" + nbt + "\"" + extra + "}";
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException
    {
        zip.putNextEntry(new ZipEntry(name));
        OutputStream out = zip;
        out.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
