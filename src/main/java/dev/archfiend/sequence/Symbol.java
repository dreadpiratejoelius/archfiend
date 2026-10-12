package dev.archfiend.sequence;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/**
 * Visual representation of an item icon, label, and rarity colour.
 *
 * @param icon  item rendered in the reveal
 * @param label name shown on reveal
 * @param color RGB accent used for text and rarity glow
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
