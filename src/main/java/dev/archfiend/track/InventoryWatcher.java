package dev.archfiend.track;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Detects items newly arriving in the player's inventory by diffing item counts between ticks.
 * <p>
 * Counts include the stack held on the cursor, so moving items around your own inventory is not a
 * gain. Gains are ignored while another container (chest, backpack, storage, auction house...) is
 * open and for a short settle period afterwards, because items taken out of storage are not drops.
 */
public final class InventoryWatcher
{
    /** Ticks after joining a world or closing a container during which changes only refresh the baseline. */
    private static final int SETTLE_TICKS = 10;

    private final Map<Key, Integer> baseline = new HashMap<>();
    /** Separate from {@code baseline.isEmpty()}: an empty inventory is a perfectly valid baseline. */
    private boolean hasBaseline;
    /**
     * Per-slot stack identity and count from the previous tick (last entry is the cursor). Used instead
     * of {@code Inventory#getTimesChanged()}, which server-driven slot updates do not bump.
     */
    private ItemStack[] lastStacks = new ItemStack[0];
    private int[] lastCounts = new int[0];
    private @Nullable Object lastPlayer;
    private int settleTicks = SETTLE_TICKS;

    /** Identity of an item for counting: the SkyBlock id when present, otherwise the plain name. */
    private record Key(@Nullable String skyblockId, String name)
    {
        @Override
        public boolean equals(Object o)
        {
            if (!(o instanceof Key other)) return false;
            return skyblockId != null || other.skyblockId != null
                ? Objects.equals(skyblockId, other.skyblockId)
                : name.equals(other.name);
        }

        @Override
        public int hashCode()
        {
            return skyblockId != null ? skyblockId.hashCode() : name.hashCode();
        }
    }

    public void reset()
    {
        baseline.clear();
        hasBaseline = false;
        lastStacks = new ItemStack[0];
        lastCounts = new int[0];
        lastPlayer = null;
        settleTicks = SETTLE_TICKS;
    }

    /** @return items gained since the last tick; empty while settling or when nothing changed */
    public List<ItemGain> tick(Minecraft minecraft)
    {
        LocalPlayer player = minecraft.player;
        if (player == null)
        {
            reset();
            return List.of();
        }
        if (player != lastPlayer)
        {
            // New world or respawn: the whole inventory "arrives" at once and must not count.
            reset();
            lastPlayer = player;
        }
        if (isForeignContainer(minecraft.gui.screen())) settleTicks = SETTLE_TICKS;

        Inventory inventory = player.getInventory();
        ItemStack carried = player.containerMenu.getCarried();
        boolean settling = settleTicks > 0;
        if (settling) settleTicks--;
        if (!changedSinceLastTick(inventory, carried) && !settling) return List.of();

        Map<Key, Integer> counts = new HashMap<>();
        Map<Key, ItemStack> samples = new HashMap<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) count(inventory.getItem(slot), counts, samples);
        count(carried, counts, samples);

        List<ItemGain> gains = new ArrayList<>();
        if (!settling && hasBaseline)
        {
            long now = System.currentTimeMillis();
            counts.forEach((key, count) -> {
                int gained = count - baseline.getOrDefault(key, 0);
                if (gained > 0)
                {
                    ItemStack sample = samples.get(key).copyWithCount(1);
                    gains.add(new ItemGain(key.name(), key.skyblockId(), gained, sample, ItemGain.INVENTORY, now));
                }
            });
        }
        baseline.clear();
        baseline.putAll(counts);
        hasBaseline = true;
        return gains;
    }

    /** Cheap per-slot check so the full recount (which copies custom data) only runs when something moved. */
    private boolean changedSinceLastTick(Inventory inventory, ItemStack carried)
    {
        int size = inventory.getContainerSize() + 1;
        boolean changed = lastStacks.length != size;
        if (changed)
        {
            lastStacks = new ItemStack[size];
            lastCounts = new int[size];
        }
        for (int slot = 0; slot < size; slot++)
        {
            ItemStack stack = slot < size - 1 ? inventory.getItem(slot) : carried;
            if (lastStacks[slot] != stack || lastCounts[slot] != stack.getCount())
            {
                lastStacks[slot] = stack;
                lastCounts[slot] = stack.getCount();
                changed = true;
            }
        }
        return changed;
    }

    /** SkyBlock item id from custom data, in either the current ({@code id}) or legacy ({@code ExtraAttributes.id}) layout. */
    public static @Nullable String skyblockId(ItemStack stack)
    {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) return null;
        CompoundTag tag = data.copyTag();
        return tag.getString("id")
            .or(() -> tag.getCompound("ExtraAttributes").flatMap(extra -> extra.getString("id")))
            .filter(id -> !id.isBlank())
            .orElse(null);
    }

    private static void count(ItemStack stack, Map<Key, Integer> counts, Map<Key, ItemStack> samples)
    {
        if (stack.isEmpty()) return;
        Key key = new Key(skyblockId(stack), stack.getHoverName().getString());
        counts.merge(key, stack.getCount(), Integer::sum);
        samples.putIfAbsent(key, stack);
    }

    private static boolean isForeignContainer(@Nullable Screen screen)
    {
        return screen instanceof AbstractContainerScreen<?> && !(screen instanceof InventoryScreen);
    }
}
