package dev.archfiend.sequence;

import dev.archfiend.util.Draw;
import dev.archfiend.util.Easing;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;

import java.util.Arrays;

/**
 * Three-reel slot machine (SkyOcean style): the lever is pulled, the reels spin and stop one after
 * another, all landing on the drop for a jackpot line.
 */
final class SlotMachineSequence extends DropSequence
{
    private static final int REELS = 3;
    private static final int REEL_WIDTH = 34;
    private static final int REEL_SPACING = 40;
    private static final int CELL_HEIGHT = 30;
    private static final int CELL_SIZE = 26;
    private static final int WINDOW_HALF_HEIGHT = 40;
    private static final int FRAME_HALF_WIDTH = 64;
    private static final int FRAME_TOP = -68;
    private static final int FRAME_BOTTOM = 60;

    private static final int LEVER_TICKS = 10;
    /** Delay between consecutive reels stopping. */
    private static final int STAGGER_TICKS = 14;
    /** Each reel overshoots its target slightly, then settles back over this many ticks. */
    private static final int SETTLE_TICKS = 5;
    private static final float OVERSHOOT = 0.35f;
    /** Strips start this far in so the cells below the payline are populated before the first spin. */
    private static final int START_INDEX = 2;
    private static final int BASE_STRIP_LENGTH = 26;
    private static final int STRIP_LENGTH_STEP = 6;

    private static final int FRAME_GOLD = 0xD4AF37;
    private static final int PAYLINE_COLOR = 0xFF5555;

    private final Symbol[][] strips = new Symbol[REELS][];
    private final int[] targetIndex = new int[REELS];
    private final int[] lastIndex = new int[REELS];

    SlotMachineSequence(SequenceContext ctx)
    {
        super(ctx, LEVER_TICKS + ctx.spinTicks() + (REELS - 1) * STAGGER_TICKS + SETTLE_TICKS);
        Arrays.fill(lastIndex, START_INDEX);
        for (int reel = 0; reel < REELS; reel++)
        {
            int length = BASE_STRIP_LENGTH + reel * STRIP_LENGTH_STEP;
            Symbol[] strip = new Symbol[length];
            for (int i = 0; i < length; i++) strip[i] = ctx.pool().next(ctx.random());
            // Two symbols after the target keep the overshoot and the cell above the payline populated.
            int target = length - 3;
            if (ctx.nearMiss()) ctx.pool().bestTease().ifPresent(tease -> strip[target + 1] = tease);
            strips[reel] = strip;
            targetIndex[reel] = target;
        }
    }

    private int reelStopTick(int reel)
    {
        return LEVER_TICKS + ctx.spinTicks() + reel * STAGGER_TICKS;
    }

    /** Fractional strip index sitting on the payline at the given time. */
    private float reelPosition(int reel, float time)
    {
        float spinTime = time - LEVER_TICKS;
        if (spinTime <= 0f) return START_INDEX;

        int duration = ctx.spinTicks() + reel * STAGGER_TICKS;
        int target = targetIndex[reel];
        if (spinTime <= duration) return START_INDEX + (target - START_INDEX + OVERSHOOT) * Easing.outCubic(spinTime / duration);
        return target + OVERSHOOT * (1f - Easing.outCubic((spinTime - duration) / SETTLE_TICKS));
    }

    private Symbol symbolAt(int reel, int index)
    {
        return index == targetIndex[reel] ? targetSymbol() : strips[reel][index];
    }

    @Override
    protected void onSpinTick(int age)
    {
        if (age == 2) ctx.sounds().lever();

        boolean crossed = false;
        for (int reel = 0; reel < REELS; reel++)
        {
            int stopTick = reelStopTick(reel);
            if (age == stopTick) ctx.sounds().reelStop(reel);

            int index = Math.round(reelPosition(reel, age));
            if (index != lastIndex[reel])
            {
                lastIndex[reel] = index;
                if (age < stopTick) crossed = true;
            }
        }
        if (crossed)
        {
            float progress = Easing.clamp01((float) (age - LEVER_TICKS) / ctx.spinTicks());
            ctx.sounds().slotTick(Mth.lerp(progress, 1.7f, 1.0f));
        }
    }

    @Override
    protected float titleY()
    {
        return FRAME_TOP - 16;
    }

    @Override
    protected void renderMachine(GuiGraphicsExtractor g, Font font, float spinTime, float time, float alpha)
    {
        boolean landed = isRevealed();

        renderFrame(g, font, time, alpha);
        renderLever(g, spinTime, alpha);

        for (int reel = 0; reel < REELS; reel++)
        {
            int x = (reel - 1) * REEL_SPACING;
            int x0 = x - REEL_WIDTH / 2;
            int x1 = x + REEL_WIDTH / 2;
            g.fill(x0, -WINDOW_HALF_HEIGHT, x1, WINDOW_HALF_HEIGHT, Draw.argb(alpha * 0.95f, 0x0B0B10));
            Draw.border(g, x0 - 1, -WINDOW_HALF_HEIGHT - 1, x1 + 1, WINDOW_HALF_HEIGHT + 1, 1, Draw.argb(alpha * 0.8f, 0x5A4A2A));

            float position = reelPosition(reel, spinTime);
            int base = Mth.floor(position);
            g.enableScissor(x0, -WINDOW_HALF_HEIGHT, x1, WINDOW_HALF_HEIGHT);
            for (int i = Math.max(0, base - 2); i <= Math.min(strips[reel].length - 1, base + 2); i++)
            {
                // Higher indices sit above the payline, so increasing position scrolls the strip downwards.
                float y = (position - i) * CELL_HEIGHT;
                float glow = landed && i == targetIndex[reel] ? 0.5f + 0.5f * pulse(time + reel * 2f, 0.5f) : 0f;
                drawCell(g, symbolAt(reel, i), x, y, CELL_SIZE, alpha, 1f, glow);
            }
            g.disableScissor();
        }

        g.nextStratum();
        for (int reel = 0; reel < REELS; reel++)
        {
            int x = (reel - 1) * REEL_SPACING;
            int x0 = x - REEL_WIDTH / 2;
            int x1 = x + REEL_WIDTH / 2;
            // Cylinder shading: darken towards the top and bottom of each window.
            g.fillGradient(x0, -WINDOW_HALF_HEIGHT, x1, -WINDOW_HALF_HEIGHT + 16, Draw.argb(alpha * 0.85f, 0x000000), Draw.argb(0f, 0x000000));
            g.fillGradient(x0, WINDOW_HALF_HEIGHT - 16, x1, WINDOW_HALF_HEIGHT, Draw.argb(0f, 0x000000), Draw.argb(alpha * 0.85f, 0x000000));
        }
        renderPayline(g, time, alpha, landed);

        Component status = landed ? drop.itemComponent() : Component.literal("Good luck...").withColor(0xAAAAAA);
        float statusScale = landed ? Mth.lerp(Easing.outCubic((time - spinTicks()) / 8f), 1.6f, 1.15f) : 1f;
        Draw.centeredText(g, font, status, 0f, FRAME_BOTTOM - 9f, statusScale, alpha);
    }

    private void renderFrame(GuiGraphicsExtractor g, Font font, float time, float alpha)
    {
        int left = -FRAME_HALF_WIDTH;
        int right = FRAME_HALF_WIDTH;
        g.fillGradient(left, FRAME_TOP, right, FRAME_BOTTOM, Draw.argb(alpha * 0.95f, 0x2A1F3D), Draw.argb(alpha * 0.95f, 0x15151C));
        Draw.border(g, left - 2, FRAME_TOP - 2, right + 2, FRAME_BOTTOM + 2, 2, Draw.argb(alpha, FRAME_GOLD));
        Draw.border(g, left + 2, FRAME_TOP + 2, right - 2, FRAME_BOTTOM - 2, 1, Draw.argb(alpha * 0.4f, FRAME_GOLD));

        int titleColor = ARGB.srgbLerp(pulse(time, 0.25f), 0xFFFFD54F, 0xFFFFFFFF) & 0xFFFFFF;
        Component title = Component.literal("✦ RNG SLOTS ✦").withColor(titleColor);
        Draw.centeredText(g, font, title, 0f, FRAME_TOP + 13f, 1.1f, alpha);
    }

    private void renderPayline(GuiGraphicsExtractor g, float time, float alpha, boolean landed)
    {
        float lineAlpha = landed ? 0.6f + 0.4f * pulse(time, 0.9f) : 0.45f;
        int color = Draw.argb(alpha * lineAlpha, landed ? 0xFFD54F : PAYLINE_COLOR);
        int edge = FRAME_HALF_WIDTH - 4;
        g.fill(-edge, 0, edge, 1, color);
        for (int row = 0; row < 4; row++)
        {
            // Inward-pointing arrows on both sides of the payline.
            g.fill(-edge - 4 + row, -3 + row, -edge - 3 + row, 4 - row, color);
            g.fill(edge + 3 - row, -3 + row, edge + 4 - row, 4 - row, color);
        }
    }

    private void renderLever(GuiGraphicsExtractor g, float time, float alpha)
    {
        float pull;
        if (time < 4f) pull = Easing.outCubic(time / 4f);
        else if (time < LEVER_TICKS) pull = 1f - Easing.outCubic((time - 4f) / (LEVER_TICKS - 4f));
        else pull = 0f;

        int baseX = FRAME_HALF_WIDTH + 2;
        int knobY = Math.round(Mth.lerp(pull, -40f, 12f));
        g.fill(baseX, -7, baseX + 6, 7, Draw.argb(alpha, 0x55555F));
        g.fill(baseX + 8, Math.min(knobY, 0), baseX + 10, Math.max(knobY, 0), Draw.argb(alpha, 0xB0B0B8));
        g.fill(baseX + 5, -2, baseX + 9, 2, Draw.argb(alpha, 0x77777F));
        g.fill(baseX + 5, knobY - 4, baseX + 13, knobY + 4, Draw.argb(alpha, 0xE53935));
        g.fill(baseX + 6, knobY - 3, baseX + 8, knobY - 1, Draw.argb(alpha, 0xFF8A80));
    }
}
