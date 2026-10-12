package dev.archfiend.repo;

import dev.archfiend.sequence.Symbol;
import dev.archfiend.sequence.SymbolPool;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;

/** Builds reel pools from a mob's real loot table, so the reels show what that mob can actually drop. */
public final class LootPools
{
    /** Below this many renderable entries a reel looks repetitive; the generic pool is used instead. */
    private static final int MIN_ENTRIES = 3;
    /**
     * Drop chances span 100% down to 0.0004%. Square-rooting compresses that range so common drops
     * dominate without ultra-rare ones disappearing entirely; the floor keeps them occasionally visible.
     */
    private static final double MIN_WEIGHT = 0.15;

    private LootPools() {}

    /**
     * @param targetInternalName the winning item; excluded from near-miss teasing
     * @return a pool weighted by drop chance, or empty if no usable loot table drops the target
     */
    public static Optional<SymbolPool> forDrop(NeuRepo repo, NeuItemStacks stacks, String targetInternalName)
    {
        Optional<DropTable> table = repo.lootTableFor(targetInternalName);
        if (table.isEmpty()) return Optional.empty();

        SymbolPool.Builder builder = SymbolPool.builder();
        Symbol rarest = null;
        double rarestChance = Double.MAX_VALUE;

        for (DropTable.Entry entry : table.get().entries())
        {
            Optional<NeuItem> item = repo.item(entry.internalName());
            if (item.isEmpty()) continue;
            Optional<ItemStack> stack = stacks.create(item.get());
            if (stack.isEmpty()) continue;

            Symbol symbol = new Symbol(stack.get(), Component.literal(item.get().name()).withColor(item.get().nameColor()), item.get().nameColor());
            builder.add(symbol, Math.max(Math.sqrt(entry.chance()), MIN_WEIGHT));

            if (!entry.internalName().equals(targetInternalName) && entry.chance() < rarestChance)
            {
                rarestChance = entry.chance();
                rarest = symbol;
            }
        }

        if (builder.size() < MIN_ENTRIES) return Optional.empty();
        if (rarest != null) builder.bestTease(rarest);
        return Optional.of(builder.build());
    }
}
