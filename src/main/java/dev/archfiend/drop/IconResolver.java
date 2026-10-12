package dev.archfiend.drop;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Finds an icon for a dropped item by name. SkyBlock items are custom-named vanilla items
 * (often textured player heads), so the most faithful icon is the real stack once it lands in the
 * inventory. A vanilla item with a matching id is used as a secondary guess.
 */
public final class IconResolver
{
    private static final Pattern COUNT_SUFFIX = Pattern.compile("\\s+x[\\d,]+$");
    private static final Pattern NON_ID_CHARS = Pattern.compile("[^a-z0-9_]");

    private IconResolver() {}

    public static Optional<ItemStack> findInInventory(Minecraft minecraft, String itemName)
    {
        LocalPlayer player = minecraft.player;
        if (player == null) return Optional.empty();

        String wanted = normalize(itemName);
        if (wanted.isEmpty()) return Optional.empty();

        Inventory inventory = player.getInventory();
        ItemStack partialMatch = null;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
        {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;

            String name = normalize(stack.getHoverName().getString());
            if (name.equals(wanted)) return Optional.of(stack.copyWithCount(1));
            // Pets are named "[Lvl 1] Scatha" while the drop message only says "Scatha".
            if (partialMatch == null && name.endsWith(wanted)) partialMatch = stack;
        }
        return Optional.ofNullable(partialMatch).map(stack -> stack.copyWithCount(1));
    }

    public static Optional<ItemStack> guessVanilla(String itemName)
    {
        String path = NON_ID_CHARS.matcher(normalize(itemName).replace(' ', '_')).replaceAll("");
        if (path.isEmpty()) return Optional.empty();

        return BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(path))
            .filter(item -> item != Items.AIR)
            .map(ItemStack::new);
    }

    private static String normalize(String name)
    {
        String stripped = ChatFormatting.stripFormatting(name);
        if (stripped == null) return "";
        return COUNT_SUFFIX.matcher(stripped.trim()).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
