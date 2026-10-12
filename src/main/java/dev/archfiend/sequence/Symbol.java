package dev.archfiend.sequence;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/**
 * One face on a reel.
 *
 * @param icon  item rendered in the cell
 * @param label name shown when the symbol passes the marker
 * @param color RGB accent used for the cell border and rarity bar
 */
public record Symbol(ItemStack icon, Component label, int color)
{
    public static Symbol of(ItemLike item, boolean glint, String label, int color)
    {
        ItemStack stack = new ItemStack(item);
        if (glint) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        return new Symbol(stack, Component.literal(label).withColor(color), color);
    }

    public Symbol withIcon(ItemStack newIcon)
    {
        return new Symbol(newIcon, label, color);
    }
}
