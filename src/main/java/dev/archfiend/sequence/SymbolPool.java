package dev.archfiend.sequence;

import dev.archfiend.ArchfiendClient;
import dev.archfiend.config.ArchfiendConfig;
import dev.archfiend.drop.DropTier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;

/** Weighted bag of filler symbols the reels are populated from. */
public final class SymbolPool
{
    private static final int HISTORY_WEIGHT = 2;
    private static final int DEFAULT_COLOR = 0xFFFFFF;

    private final List<Symbol> symbols;
    private final int[] cumulativeWeights;
    private final int totalWeight;
    private final Symbol bestTease;

    private SymbolPool(List<Symbol> symbols, List<Integer> weights, Symbol bestTease)
    {
        this.symbols = List.copyOf(symbols);
        this.cumulativeWeights = new int[weights.size()];
        int sum = 0;
        for (int i = 0; i < weights.size(); i++)
        {
            sum += weights.get(i);
            cumulativeWeights[i] = sum;
        }
        this.totalWeight = sum;
        this.bestTease = bestTease;
    }

    /**
     * @param history real drops seen earlier this session; they double as believable teases
     */
    public static SymbolPool build(ArchfiendConfig config, Collection<Symbol> history)
    {
        List<Symbol> symbols = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();

        for (ArchfiendConfig.Filler filler : config.fillers)
        {
            resolveItem(filler.item()).ifPresentOrElse(
                item -> {
                    symbols.add(Symbol.of(item, filler.glint(), filler.name(), parseColor(filler.color())));
                    weights.add(filler.weight());
                },
                () -> ArchfiendClient.LOGGER.warn("Unknown filler item '{}' in config, skipping", filler.item())
            );
        }

        Symbol bestTease = null;
        if (config.teaseSymbols)
        {
            for (DropTier tier : DropTier.values())
            {
                Symbol tease = Symbol.of(tier.fallbackIcon(), true, tier.displayName, tier.defaultColor);
                symbols.add(tease);
                weights.add(tier.isJackpot() ? 1 : 2);
                bestTease = tease; // tiers iterate in ascending rarity, so the last one is the juiciest
            }
            for (Symbol previous : history)
            {
                symbols.add(previous);
                weights.add(HISTORY_WEIGHT);
            }
        }

        if (symbols.isEmpty())
        {
            symbols.add(Symbol.of(Items.COBBLESTONE, false, "Cobblestone", DEFAULT_COLOR));
            weights.add(1);
        }
        return new SymbolPool(symbols, weights, bestTease);
    }

    public Symbol next(RandomGenerator random)
    {
        int roll = random.nextInt(totalWeight);
        for (int i = 0; i < cumulativeWeights.length; i++)
        {
            if (roll < cumulativeWeights[i]) return symbols.get(i);
        }
        return symbols.getLast();
    }

    /** Highest-tier tease symbol, used for near-miss placement; empty when teases are disabled. */
    public Optional<Symbol> bestTease()
    {
        return Optional.ofNullable(bestTease);
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
