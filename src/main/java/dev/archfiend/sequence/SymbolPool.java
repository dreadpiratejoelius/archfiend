package dev.archfiend.sequence;

import dev.archfiend.ArchfiendClient;
import dev.archfiend.config.ArchfiendConfig;
import dev.archfiend.drop.DropTier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** Weighted bag of filler symbols the reels are populated from. */
public final class SymbolPool
{
    private static final double HISTORY_WEIGHT = 2;
    private static final int DEFAULT_COLOR = 0xFFFFFF;

    private final List<Symbol> symbols;
    private final double[] cumulativeWeights;
    private final double totalWeight;
    private final @Nullable Symbol bestTease;

    private SymbolPool(List<Symbol> symbols, double[] cumulativeWeights, @Nullable Symbol bestTease)
    {
        this.symbols = List.copyOf(symbols);
        this.cumulativeWeights = cumulativeWeights;
        this.totalWeight = cumulativeWeights[cumulativeWeights.length - 1];
        this.bestTease = bestTease;
    }

    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * Generic pool from the configured fillers, used when no loot table is known for a drop.
     *
     * @param history real drops seen earlier this session; they double as believable teases
     */
    public static SymbolPool fromConfig(ArchfiendConfig config, Collection<Symbol> history)
    {
        Builder builder = builder();
        for (ArchfiendConfig.Filler filler : config.fillers)
        {
            resolveItem(filler.item()).ifPresentOrElse(
                item -> builder.add(Symbol.of(item, filler.glint(), filler.name(), parseColor(filler.color())), filler.weight()),
                () -> ArchfiendClient.LOGGER.warn("Unknown filler item '{}' in config, skipping", filler.item())
            );
        }

        if (config.teaseSymbols)
        {
            for (DropTier tier : DropTier.values())
            {
                Symbol tease = Symbol.of(tier.fallbackIcon(), true, tier.displayName, tier.defaultColor);
                builder.add(tease, tier.isJackpot() ? 1 : 2);
                builder.bestTease(tease); // tiers iterate in ascending rarity, so the last one is the juiciest
            }
            for (Symbol previous : history) builder.add(previous, HISTORY_WEIGHT);
        }

        if (builder.size() == 0) builder.add(Symbol.of(Items.COBBLESTONE, false, "Cobblestone", DEFAULT_COLOR), 1);
        return builder.build();
    }

    public Symbol next(RandomGenerator random)
    {
        double roll = random.nextDouble(totalWeight);
        int index = Arrays.binarySearch(cumulativeWeights, roll);
        // binarySearch returns (-(insertion point) - 1) on a miss; the insertion point is the bucket.
        int bucket = index >= 0 ? index + 1 : -index - 1;
        return symbols.get(Math.min(bucket, symbols.size() - 1));
    }

    /** Most desirable non-winning symbol, used for near-miss placement; empty when there is none. */
    public Optional<Symbol> bestTease()
    {
        return Optional.ofNullable(bestTease);
    }

    public static final class Builder
    {
        private final List<Symbol> symbols = new ArrayList<>();
        private final List<Double> weights = new ArrayList<>();
        private @Nullable Symbol bestTease;

        private Builder() {}

        /** Adds a symbol; non-positive or non-finite weights are ignored. */
        public Builder add(Symbol symbol, double weight)
        {
            if (weight > 0 && Double.isFinite(weight))
            {
                symbols.add(symbol);
                weights.add(weight);
            }
            return this;
        }

        public Builder bestTease(Symbol tease)
        {
            this.bestTease = tease;
            return this;
        }

        public int size()
        {
            return symbols.size();
        }

        public SymbolPool build()
        {
            if (symbols.isEmpty()) throw new IllegalStateException("SymbolPool needs at least one symbol");
            double[] cumulative = new double[weights.size()];
            double sum = 0;
            for (int i = 0; i < cumulative.length; i++)
            {
                sum += weights.get(i);
                cumulative[i] = sum;
            }
            return new SymbolPool(symbols, cumulative, bestTease);
        }
    }

    private static Optional<Item> resolveItem(String id)
    {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) return Optional.empty();
        return BuiltInRegistries.ITEM.getOptional(identifier).filter(item -> item != Items.AIR);
    }

    private static int parseColor(String hex)
    {
        if (hex == null) return DEFAULT_COLOR;
        String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        try
        {
            return Integer.parseInt(digits, 16) & 0xFFFFFF;
        }
        catch (NumberFormatException e)
        {
            ArchfiendClient.LOGGER.warn("Invalid filler colour '{}', using white", hex);
            return DEFAULT_COLOR;
        }
    }
}
