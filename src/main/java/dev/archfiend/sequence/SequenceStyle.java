package dev.archfiend.sequence;

import dev.archfiend.render3d.ChestAssets;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

public enum SequenceStyle
{
    /** Horizontal case-opening reel (Scatha-Pro style). */
    REEL(ReelSequence::new),
    /** Three-reel slot machine with a lever (SkyOcean style). */
    SLOTS(SlotMachineSequence::new),
    /** 3D treasure chest that drops in, bursts open and reveals the item. Falls back to the reel if its models fail to load. */
    CHEST(ctx -> ChestAssets.get().<DropSequence>map(assets -> new ChestSequence(ctx, assets)).orElseGet(() -> new ReelSequence(ctx))),
    /** The chest as a real 3D object simulated in the world. Falls back to the GUI chest if there is no valid spawn. */
    WORLD_CHEST(ctx -> ChestAssets.get().<DropSequence>map(assets -> WorldChestSequence.tryCreate(ctx, assets)
        .orElseGet(() -> new ChestSequence(ctx, assets))).orElseGet(() -> new ReelSequence(ctx)));

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
