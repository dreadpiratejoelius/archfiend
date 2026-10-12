package dev.archfiend.sequence;

import dev.archfiend.util.Draw;
import dev.archfiend.util.Easing;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * Horizontal case-opening reel (Scatha-Pro style): a long strip of symbols scrolls past a fixed
 * marker and decelerates onto the drop. The name of whatever is under the marker is shown above it.
 */
final class ReelSequence extends DropSequence
{
    private static final int CELL = 30;
    private static final int PITCH = 34;
    private static final int HALF_WIDTH = PITCH * 9 / 2;
    private static final int HALF_HEIGHT = 21;
    private static final int STRIP_LENGTH = 48;
    /** Starting a few cells in keeps the left half of the panel populated before the reel moves. */
    private static final int START_INDEX = 5;
    /** Leaves a few symbols after the target so the strip never visibly runs out. */
    private static final int TARGET_INDEX = STRIP_LENGTH - 5;
    private static final int LABEL_POP_TICKS = 8;
    private static final int MARKER_COLOR = 0xFFD54F;
    private static final int PANEL_COLOR = 0x0E0E12;

    private final Symbol[] strip = new Symbol[STRIP_LENGTH];
    /** Pixels travelled; includes a random offset so the marker doesn't land dead-centre every time. */
    private final float scrollDistance;
    private int markerIndex = START_INDEX;
    private int markerChangedAge = 0;

    ReelSequence(SequenceContext ctx)
    {
        super(ctx, ctx.spinTicks());
        for (int i = 0; i < STRIP_LENGTH; i++) strip[i] = ctx.pool().next(ctx.random());
        if (ctx.nearMiss()) ctx.pool().bestTease().ifPresent(tease -> strip[TARGET_INDEX + 1] = tease);

        float landingOffset = (ctx.random().nextFloat() - 0.5f) * CELL * 0.8f;
        scrollDistance = (TARGET_INDEX - START_INDEX) * PITCH + landingOffset;
    }

    private float scroll(float spinTime)
    {
        return START_INDEX * PITCH + Easing.outQuart(spinTime / spinTicks()) * scrollDistance;
    }

    private static int indexUnderMarker(float scroll)
    {
        return Mth.clamp(Math.round(scroll / PITCH), 0, STRIP_LENGTH - 1);
    }

    private Symbol symbolAt(int index)
    {
        return index == TARGET_INDEX ? targetSymbol() : strip[index];
    }

    @Override
    protected void onSpinTick(int age)
    {
        int index = indexUnderMarker(scroll(age));
        if (index == markerIndex) return;
        markerIndex = index;
        markerChangedAge = age;

        float progress = (float) age / spinTicks();
        float jitter = (ctx.random().nextFloat() - 0.5f) * 0.2f;
        ctx.sounds().tick(0.7f + Mth.lerp(progress, 0.6f, 0f) + jitter);
    }

    @Override
    protected float titleY()
    {
        return -HALF_HEIGHT - 40;
    }

    @Override
    protected float badgeY()
    {
        return HALF_HEIGHT + 14; // where "Rolling..." sat during the spin
    }

    @Override
    protected void renderMachine(GuiGraphicsExtractor g, Font font, float spinTime, float time, float alpha)
    {
        float scroll = scroll(spinTime);
        int centerIndex = indexUnderMarker(scroll);
        boolean landed = isRevealed();

        g.fill(-HALF_WIDTH - 4, -HALF_HEIGHT - 4, HALF_WIDTH + 4, HALF_HEIGHT + 4, Draw.argb(alpha * 0.85f, PANEL_COLOR));
        Draw.border(g, -HALF_WIDTH - 4, -HALF_HEIGHT - 4, HALF_WIDTH + 4, HALF_HEIGHT + 4, 1, Draw.argb(alpha * 0.6f, 0x3A3A48));

        g.enableScissor(-HALF_WIDTH, -HALF_HEIGHT, HALF_WIDTH, HALF_HEIGHT);
        int visibleRadius = HALF_WIDTH / PITCH + 2;
        for (int i = Math.max(0, centerIndex - visibleRadius); i <= Math.min(STRIP_LENGTH - 1, centerIndex + visibleRadius); i++)
        {
            float x = i * PITCH - scroll;
            float edgeFade = Mth.clampedMap(Math.abs(x), HALF_WIDTH - PITCH * 1.5f, HALF_WIDTH, 1f, 0f);
            boolean isTarget = landed && i == TARGET_INDEX;
            float cellScale = i == centerIndex ? markerPop(time) : 1f;
            float glow = isTarget ? 0.5f + 0.5f * pulse(time, 0.5f) : 0f;
            drawCell(g, symbolAt(i), x, 0f, CELL, alpha * edgeFade, cellScale, glow);
        }
        g.disableScissor();

        // Marker and labels go on a higher stratum so they always draw above item icons.
        g.nextStratum();
        int marker = Draw.argb(alpha, MARKER_COLOR);
        g.fill(-1, -HALF_HEIGHT - 6, 1, HALF_HEIGHT + 6, marker);
        for (int row = 0; row < 4; row++)
        {
            g.fill(-4 + row, -HALF_HEIGHT - 9 + row, 4 - row, -HALF_HEIGHT - 8 + row, marker);
            g.fill(-4 + row, HALF_HEIGHT + 8 - row, 4 - row, HALF_HEIGHT + 9 - row, marker);
        }

        // Fade cells into the panel at both ends instead of hard-clipping them (items can't fade on their own).
        int fadeWidth = PITCH;
        Draw.horizontalGradient(g, -HALF_WIDTH, -HALF_HEIGHT, -HALF_WIDTH + fadeWidth, HALF_HEIGHT,
            Draw.argb(alpha * 0.85f, PANEL_COLOR), Draw.argb(0f, PANEL_COLOR));
        Draw.horizontalGradient(g, HALF_WIDTH - fadeWidth, -HALF_HEIGHT, HALF_WIDTH, HALF_HEIGHT,
            Draw.argb(0f, PANEL_COLOR), Draw.argb(alpha * 0.85f, PANEL_COLOR));

        Component label = landed ? drop.itemComponent() : symbolAt(centerIndex).label();
        float sinceChange = landed ? time - spinTicks() : time - markerChangedAge;
        float labelT = Easing.outCubic(sinceChange / LABEL_POP_TICKS);
        Draw.centeredText(g, font, label, 0f, -HALF_HEIGHT - 17f, Mth.lerp(labelT, 1.9f, 1.4f), alpha * Mth.lerp(labelT, 0.5f, 1f));

        if (!landed)
        {
            Draw.centeredText(g, font, Component.literal(rollingText(time)).withColor(0xAAAAAA), 0f, HALF_HEIGHT + 14f, 1f, alpha * 0.8f);
        }
    }

    /** The cell under the marker swells briefly each time a new one arrives. */
    private float markerPop(float time)
    {
        float t = Easing.clamp01((time - markerChangedAge) / 6f);
        return Mth.lerp(t * t, 1.22f, 1.12f);
    }

    private static String rollingText(float time)
    {
        int dots = ((int) (time / 5f)) % 4;
        return "Rolling" + ".".repeat(dots);
    }
}
