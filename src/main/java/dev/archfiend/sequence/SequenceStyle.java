package dev.archfiend.sequence;

import dev.archfiend.render3d.ChestAssets;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

public enum SequenceStyle
{
    /** 3D treasure chest in the GUI that drops in, bursts open and reveals the item. */
    CHEST(ctx -> ChestAssets.get().<DropSequence>map(assets -> new ChestSequence(ctx, assets))
        .orElseThrow(() -> new IllegalStateException("Chest assets failed to load"))),
    /** The chest as a real 3D object simulated in the world. Falls back to GUI chest if there is no valid spawn. */
    WORLD_CHEST(ctx -> ChestAssets.get().<DropSequence>map(assets -> WorldChestSequence.tryCreate(ctx, assets)
        .orElseGet(() -> new ChestSequence(ctx, assets)))
        .orElseThrow(() -> new IllegalStateException("Chest assets failed to load")));

    private final Function<SequenceContext, DropSequence> factory;

    SequenceStyle(Function<SequenceContext, DropSequence> factory)
    {
        this.factory = factory;
    }

    public DropSequence create(SequenceContext context)
    {
        return factory.apply(context);
    }

    public String commandName()
    {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<SequenceStyle> byCommandName(String name)
    {
        for (SequenceStyle style : values())
        {
            if (style.commandName().equalsIgnoreCase(name)) return Optional.of(style);
        }
        return Optional.empty();
    }
}
