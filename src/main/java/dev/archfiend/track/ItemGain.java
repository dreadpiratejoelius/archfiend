package dev.archfiend.track;

import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * An item that newly arrived in the player's possession.
 *
 * @param name        plain item name
 * @param skyblockId  SkyBlock id from the item's custom data; {@code null} for sack reports and vanilla items
 * @param count       how many arrived
 * @param stack       a single-item copy for rendering; {@code null} for sack reports
 * @param destination where it went: {@code "Inventory"} or the sack's name, e.g. {@code "Combat Sack"}
 * @param atMillis    wall-clock arrival time
 */
public record ItemGain(String name, @Nullable String skyblockId, int count, @Nullable ItemStack stack, String destination, long atMillis)
{
    public static final String INVENTORY = "Inventory";

    public boolean inSack()
    {
        return !INVENTORY.equals(destination);
    }
}
