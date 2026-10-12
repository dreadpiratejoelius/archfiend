package dev.archfiend.drop;

import dev.archfiend.config.ArchfiendConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import org.jspecify.annotations.Nullable;

import java.util.Locale;

/** Hypixel-shaped fake drops for previews and {@code /archfiend test}. */
public final class TestDrops
{
    private static final int ITEM_COLOR = 0xFFAA00;
    private static final int GRAY = 0xAAAAAA;
    private static final int AQUA = 0x55FFFF;

    private TestDrops() {}

    /**
     * Builds a fake drop message and runs it through the real parser, so previews exercise the same
     * path as live drops (including repo lookup by name).
     *
     * @param itemName item to "drop"; a well-known item for the tier when blank
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
            .orElseGet(() -> new RngDrop(tier, label, tier.defaultColor, name, ITEM_COLOR, message));
    }

    public static String defaultItem(DropTier tier)
    {
        return switch (tier)
        {
            case RARE -> "Revenant Viscera";
            case VERY_RARE -> "Foul Flesh";
            case CRAZY_RARE -> "Warden Heart";
            case PET -> "Scatha";
            case EXTREMELY_RARE -> "Scythe Blade";
            case INSANE -> "Overflux Capacitor";
            case PRAY_TO_RNGESUS -> "Judgement Core";
            case RNGESUS_INCARNATE -> "Necron's Handle";
        };
    }

    private static String label(ArchfiendConfig config, DropTier tier)
    {
        return config.triggers.stream()
            .filter(trigger -> trigger.tier() == tier)
            .map(ArchfiendConfig.Trigger::prefix)
            .findFirst()
            .orElse(tier.displayName.toUpperCase(Locale.ROOT) + "!");
    }
}
