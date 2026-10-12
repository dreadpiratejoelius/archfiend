package dev.archfiend.sequence;

import dev.archfiend.drop.RngDrop;

import java.util.random.RandomGenerator;

/**
 * Everything a sequence needs, snapshotted at start so config edits mid-roll don't warp the animation.
 *
 * @param onReveal invoked exactly once when the result lands (or the roll is skipped)
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
    Runnable onReveal
) {}
