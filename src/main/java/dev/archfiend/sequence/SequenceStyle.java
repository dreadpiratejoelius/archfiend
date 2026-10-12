package dev.archfiend.sequence;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

public enum SequenceStyle
{
    /** Horizontal case-opening reel (Scatha-Pro style). */
    REEL(ReelSequence::new),
    /** Three-reel slot machine with a lever (SkyOcean style). */
    SLOTS(SlotMachineSequence::new);

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
