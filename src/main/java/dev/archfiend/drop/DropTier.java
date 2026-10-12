package dev.archfiend.drop;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Drop tiers in ascending rarity. Declaration order is the rank used for {@code minTier} filtering,
 * so new tiers must be inserted at their correct position.
 */
public enum DropTier
{
    RARE("Rare Drop", 0xFFAA00, () -> Items.GOLD_INGOT),
    VERY_RARE("Very Rare Drop", 0x5555FF, () -> Items.DIAMOND),
    CRAZY_RARE("Crazy Rare Drop", 0xFF55FF, () -> Items.AMETHYST_SHARD),
    PET("Pet Drop", 0xFFAA00, () -> Items.NAME_TAG),
    INSANE("Insane Drop", 0xFF5555, () -> Items.REDSTONE_BLOCK),
    PRAY_TO_RNGESUS("Pray To RNGesus", 0xAA00AA, () -> Items.NETHER_STAR),
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
