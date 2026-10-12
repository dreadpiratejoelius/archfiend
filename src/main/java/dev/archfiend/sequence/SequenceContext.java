package dev.archfiend.sequence;

import dev.archfiend.drop.RngDrop;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.random.RandomGenerator;

/**
 * Everything a sequence needs, snapshotted at start so config edits mid-roll don't warp the animation.
 *
 * @param originPos optional world position where the drop originated (e.g. enemy death location)
 * @param onReveal  invoked exactly once when the result lands (or the roll is skipped)
 */
public record SequenceContext(
    RngDrop drop,
    Symbol target,
    SymbolPool pool,
    RandomGenerator random,
    SequenceSounds sounds,
    int spinTicks,
    int revealTicks,
    float scale,
    boolean nearMiss,
    @Nullable Vec3 originPos,
    Runnable onReveal
)
{
    public SequenceContext(RngDrop drop, Symbol target, SymbolPool pool, RandomGenerator random,
                           SequenceSounds sounds, int spinTicks, int revealTicks, float scale,
                           boolean nearMiss, Runnable onReveal)
    {
        this(drop, target, pool, random, sounds, spinTicks, revealTicks, scale, nearMiss, null, onReveal);
    }
}
