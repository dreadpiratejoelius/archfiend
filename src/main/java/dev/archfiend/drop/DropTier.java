package dev.archfiend.drop;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Drop tiers in ascending rarity. Declaration order is the rank used for {@code minTier} filtering,
 * so new tiers must be inserted at their correct position. Config files store tiers by name.
 * <p>
 * Colours match Hypixel's chat formatting where it could be verified against captured messages
 * (SkyHanni chat filters, Scatha-Pro). Live drops always use the colour from the actual message;
 * these defaults only apply to previews, teases and the settings screen.
 */
public enum DropTier
{
    /** {@code §6} for mob drops; slayer drops use aqua {@code §b}. */
    RARE("Rare Drop", 0xFFAA00, () -> Items.GOLD_INGOT),
    VERY_RARE("Very Rare Drop", 0xAA00AA, () -> Items.DIAMOND),
    CRAZY_RARE("Crazy Rare Drop", 0xFF55FF, () -> Items.AMETHYST_SHARD),
    PET("Pet Drop", 0xFFAA00, () -> Items.NAME_TAG),
    /** Colour unverified: no captured message found. */
    EXTREMELY_RARE("Extremely Rare Drop", 0xFF5555, () -> Items.HEART_OF_THE_SEA),
    /** Colour unverified: no captured message found. */
    INSANE("Insane Drop", 0xAA0000, () -> Items.REDSTONE_BLOCK),
    PRAY_TO_RNGESUS("Pray To RNGesus", 0xAA00AA, () -> Items.NETHER_STAR),
    /** Unverified as a chat announcement; it is an RNG meter odds tier. Kept so existing configs stay valid. */
    RNGESUS_INCARNATE("RNGesus Incarnate", 0x55FFFF, () -> Items.ENCHANTED_GOLDEN_APPLE);

    /** Human readable name used for fake "tease" symbols and command feedback. */
    public final String displayName;
    /** Fallback colour used when the chat message carries no colour for the drop label. */
    public final int defaultColor;
    /** Lazy so this enum can be loaded (e.g. by config parsing or tests) before the item registry. */
    private final Supplier<Item> fallbackIcon;

    DropTier(String displayName, int defaultColor, Supplier<Item> fallbackIcon)
    {
        this.displayName = displayName;
        this.defaultColor = defaultColor;
        this.fallbackIcon = fallbackIcon;
    }

    /** Icon shown when the real item cannot be located in the player's inventory. */
    public Item fallbackIcon()
    {
        return fallbackIcon.get();
    }

    /** Name without the trailing "Drop", for space-constrained UI such as buttons. */
    public String shortName()
    {
        return displayName.endsWith(" Drop") ? displayName.substring(0, displayName.length() - " Drop".length()) : displayName;
    }

    public boolean isAtLeast(DropTier other)
    {
        return ordinal() >= other.ordinal();
    }

    /** Tiers at or above this one get the "big" reveal treatment (fanfare, screen flash, strobing banner). */
    public boolean isJackpot()
    {
        return isAtLeast(INSANE);
    }

    public String commandName()
    {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<DropTier> byCommandName(String name)
    {
        for (DropTier tier : values())
        {
            if (tier.commandName().equalsIgnoreCase(name)) return Optional.of(tier);
        }
        return Optional.empty();
    }
}
