package dev.archfiend.drop;

import dev.archfiend.config.ArchfiendConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Hypixel-shaped fake drops for previews and {@code /archfiend test}. */
public final class TestDrops
{
    private static final int ITEM_COLOR = 0xFFAA00;
    private static final int GRAY = 0xAAAAAA;
    private static final int AQUA = 0x55FFFF;

    private static final Map<DropTier, List<String>> TIER_ITEMS = createTierCatalog();

    private TestDrops() {}

    /**
     * Builds a fake drop message and runs it through the real parser, so previews exercise the same
     * path as live drops (including repo lookup by name).
     *
     * @param itemName item to "drop"; the tier's {@link #defaultItem default item} when blank
     */
    public static RngDrop create(ArchfiendConfig config, DropTier tier, @Nullable String itemName)
    {
        String name = itemName != null && !itemName.isBlank() ? itemName.trim() : defaultItem(tier);
        String label = label(config, tier);
        Component message = Component.empty()
            .append(Component.literal(label).withStyle(Style.EMPTY.withBold(true)).withColor(tier.defaultColor))
            .append(Component.literal(" (").withColor(GRAY))
            .append(Component.literal(name).withColor(ITEM_COLOR))
            .append(Component.literal(") ").withColor(GRAY))
            .append(Component.literal("(+250% ✯ Magic Find)").withColor(AQUA));

        return new DropParser(config.triggers).parse(message)
            .filter(parsed -> parsed.tier() == tier)
            .orElseGet(() -> new RngDrop(tier, label, tier.defaultColor, name, ITEM_COLOR, 250, message));
    }

    /** The tier's representative item, so repeated previews are directly comparable. */
    public static String defaultItem(DropTier tier)
    {
        return itemsForTier(tier).getFirst();
    }

    public static String randomItem(DropTier tier)
    {
        List<String> items = itemsForTier(tier);
        return items.get(ThreadLocalRandom.current().nextInt(items.size()));
    }

    /** Never empty. */
    public static List<String> itemsForTier(DropTier tier)
    {
        return TIER_ITEMS.get(tier);
    }

    private static String label(ArchfiendConfig config, DropTier tier)
    {
        return config.triggers.stream()
            .filter(trigger -> trigger.tier() == tier)
            .map(ArchfiendConfig.Trigger::prefix)
            .findFirst()
            .orElse(tier.displayName.toUpperCase(Locale.ROOT) + "!");
    }

    private static Map<DropTier, List<String>> createTierCatalog()
    {
        Map<DropTier, List<String>> catalog = new EnumMap<>(DropTier.class);
        for (DropTier tier : DropTier.values())
        {
            List<String> items = catalogFor(tier);
            if (items.isEmpty()) throw new IllegalStateException("No preview items for tier " + tier);
            catalog.put(tier, items);
        }
        return Collections.unmodifiableMap(catalog);
    }

    /** Exhaustive so a new tier fails to compile until it has preview items. */
    private static List<String> catalogFor(DropTier tier)
    {
        return switch (tier)
        {
            case RARE -> List.of(
                "Revenant Viscera",
                "Tarantula Silk",
                "Golden Tooth",
                "Null Ovoid",
                "Hamster Wheel",
                "Twilight Arrow Poison",
                "Pestilence Rune I",
                "Spirit Rune I",
                "Endersnake Rune I",
                "Lavatears Rune I"
            );
            case VERY_RARE -> List.of(
                "Foul Flesh",
                "Undead Catalyst",
                "Revenant Catalyst",
                "Spider Catalyst",
                "Tarantula Catalyst",
                "Null Atom",
                "Bubba Blister",
                "Fang-tastic Chocolate Chip",
                "Guardian Lucky Block",
                "Mana Disintegrator",
                "Kelvin Inverter",
                "Scorched Books"
            );
            case CRAZY_RARE -> List.of(
                "Warden Heart",
                "Archfiend Dice",
                "Transmission Tuner",
                "Hazmat Enderman",
                "McGrubber's Burger",
                "Unfanged Vampire Part",
                "Scorched Power Crystal"
            );
            case PET -> List.of(
                "Scatha",
                "Baby Yeti",
                "Flying Fish",
                "Phoenix",
                "Megalodon"
            );
            case EXTREMELY_RARE -> List.of(
                "Scythe Blade",
                "Smite VII",
                "Bane of Arthropods VI",
                "Critical VI",
                "Fire Aspect III",
                "Duplex I",
                "End Rune I",
                "Fiery Burst Rune I",
                "Soultwist Rune I"
            );
            case INSANE -> List.of(
                "Overflux Capacitor",
                "Red Claw Egg",
                "Grizzly Salmon",
                "Darkness Within Rune I",
                "Fly Swatter",
                "Tarantula Talisman",
                "Pocket Espresso Machine",
                "Sinful Dice",
                "Etherwarp Merger",
                "Wilson's Engineering Plans",
                "Subzero Inverter"
            );
            case PRAY_TO_RNGESUS -> List.of(
                "Judgement Core",
                "Digested Mosquito",
                "Shriveled Wasp",
                "Vial of Venom",
                "Ensnared Snail",
                "Beheaded Horror",
                "Snake Rune I",
                "Festering Maggot",
                "Shredded Sinew",
                "Severed Hand",
                "Summoning Eye",
                "Handy Blood Chalice",
                "Void Conqueror Enderman Skin",
                "Enchant Rune I",
                "High Class Archfiend Dice",
                "Couture Rune I",
                "Paragon Shard"
            );
            case RNGESUS_INCARNATE -> List.of(
                "Necron's Handle",
                "Primordial Eye",
                "Exceedingly Rare Ender Artifact Upgrade",
                "End Stone Idol",
                "Matcha Dye",
                "Brick Red Dye",
                "Celeste Dye",
                "Byzantium Dye",
                "Sangria Dye",
                "Flame Dye"
            );
        };
    }
}
