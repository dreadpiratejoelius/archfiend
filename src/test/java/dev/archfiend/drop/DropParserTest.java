package dev.archfiend.drop;

import dev.archfiend.config.ArchfiendConfig;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DropParserTest
{
    private final DropParser parser = new DropParser(new ArchfiendConfig().triggers);

    @Test
    void parsesLegacyFormattedSlayerDrop()
    {
        RngDrop drop = parse("§d§lCRAZY RARE DROP! §r§7(§r§f§r§6Warden Heart§r§7) §r§b(+312% ✯ Magic Find)").orElseThrow();

        assertEquals(DropTier.CRAZY_RARE, drop.tier());
        assertEquals("CRAZY RARE DROP!", drop.label());
        assertEquals("Warden Heart", drop.itemName());
        assertEquals(0xFF55FF, drop.labelColor());
        assertEquals(0xFFAA00, drop.itemColor());
    }

    @Test
    void parsesComponentStyledDrop()
    {
        Component message = Component.empty()
            .append(Component.literal("VERY RARE DROP! ").withColor(0x5555FF))
            .append(Component.literal("(").withColor(0xAAAAAA))
            .append(Component.literal("Foul Flesh").withColor(0x5555FF))
            .append(Component.literal(")").withColor(0xAAAAAA));

        RngDrop drop = parser.parse(message).orElseThrow();

        assertEquals(DropTier.VERY_RARE, drop.tier());
        assertEquals("Foul Flesh", drop.itemName());
        assertEquals(0x5555FF, drop.itemColor());
    }

    @Test
    void longestPrefixWins()
    {
        assertEquals(DropTier.VERY_RARE, parse("VERY RARE DROP! Foul Flesh").orElseThrow().tier());
        assertEquals(DropTier.RARE, parse("RARE DROP! Revenant Viscera (+100% ✯ Magic Find)").orElseThrow().tier());
    }

    @Test
    void stripsMagicFindWithoutParentheses()
    {
        assertEquals("Revenant Viscera", parse("RARE DROP! Revenant Viscera (+100% ✯ Magic Find)").orElseThrow().itemName());
    }

    @Test
    void ignoresPlayerChatAndUnrelatedMessages()
    {
        assertTrue(parse("[MVP+] Someone: CRAZY RARE DROP! (Warden Heart)").isEmpty());
        assertTrue(parse("You picked up some coins").isEmpty());
        assertTrue(parse("CRAZY RARE DROP!").isEmpty());
        assertTrue(parse("CRAZY RARE DROP! ()").isEmpty());
    }

    @Test
    void parsesRealSlayerFormatsCapturedBySkyHanni()
    {
        RngDrop veryRare = parse("§5§lVERY RARE DROP!  §r§7(§r§f§r§5Revenant Catalyst§r§7) §r§b(+123% ✯ Magic Find)").orElseThrow();
        assertEquals(DropTier.VERY_RARE, veryRare.tier());
        assertEquals("Revenant Catalyst", veryRare.itemName());
        assertEquals(0xAA00AA, veryRare.labelColor());

        RngDrop rare = parse("§b§lRARE DROP! §r§7(§r§f§r§9Revenant Viscera§r§7) §r§b(+50% ✯ Magic Find)").orElseThrow();
        assertEquals(DropTier.RARE, rare.tier());
        assertEquals(0x55FFFF, rare.labelColor()); // slayer RARE DROP! is aqua, unlike gold mob drops
    }

    @Test
    void parsesExtremelyRareDrop()
    {
        RngDrop drop = parse("EXTREMELY RARE DROP! (Scythe Blade)").orElseThrow();
        assertEquals(DropTier.EXTREMELY_RARE, drop.tier());
        assertEquals("Scythe Blade", drop.itemName());
    }

    @Test
    void parsesPetDrop()
    {
        RngDrop drop = parse("§6§lPET DROP! §r§5Scatha §r§b(+250% ✯ Magic Find)").orElseThrow();
        assertEquals(DropTier.PET, drop.tier());
        assertEquals("Scatha", drop.itemName());
        assertEquals(0xAA00AA, drop.itemColor());
    }

    private Optional<RngDrop> parse(String raw)
    {
        return parser.parse(Component.literal(raw));
    }
}
