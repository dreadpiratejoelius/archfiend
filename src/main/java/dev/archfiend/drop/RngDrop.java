package dev.archfiend.drop;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * A detected RNG drop.
 *
 * @param tier       rarity tier the drop was classified as
 * @param label      the drop announcement, e.g. {@code CRAZY RARE DROP!}
 * @param labelColor RGB colour of the announcement as sent by the server
 * @param itemName   plain item name, e.g. {@code Warden Heart}
 * @param itemColor  RGB colour of the item name (its SkyBlock rarity colour)
 * @param message    the original chat message, re-sent to chat once the sequence reveals the drop
 */
public record RngDrop(DropTier tier, String label, int labelColor, String itemName, int itemColor, Component message)
{
    public MutableComponent labelComponent()
    {
        return Component.literal(label).withStyle(Style.EMPTY.withBold(true)).withColor(labelColor);
    }

    public MutableComponent itemComponent()
    {
        return Component.literal(itemName).withColor(itemColor);
    }
}
