package dev.archfiend.sequence;

import dev.archfiend.render3d.ChestAssets;
import dev.archfiend.render3d.GuiShapes;
import dev.archfiend.render3d.MeshRenderer;
import dev.archfiend.util.Draw;
import dev.archfiend.util.Easing;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Treasure-chest reveal: the chest drops in with physics, rattles, bursts open on a hyperbolic-tangent
 * curve with an exaggerated overshoot, and a spinning, glowing question mark floats out. The glow
 * cycles through random rarity colours, slowing down, then slowly settles on the drop's rarity colour
 * before the question mark pops into the real item.
 * <p>
 * Everything is a pure function of time (seconds), so frames interpolate smoothly between ticks and
 * skipping simply jumps the clock.
 */
final class ChestSequence extends DropSequence
{
    // Layout, in the sequence's virtual GUI units.
    private static final float CHEST_Y = 58f;
    private static final float BASE_Y = CHEST_Y + 26f;
    private static final float CHEST_MOUTH_Y = CHEST_Y - 19f;
    private static final float HOVER_Y = -18f;
    private static final float ITEM_NAME_Y = -106f;
    private static final float FOCAL = 54f;
    private static final float CAMERA_DISTANCE = 6f;
    private static final float PITCH = (float) Math.toRadians(24);
    private static final float YAW = (float) Math.toRadians(-28);
    private static final float QUESTION_SIZE = 0.75f;

    // Timeline, in seconds.
    private static final float RATTLE = 0.6f;
    private static final float RISE_DELAY = 0.1f;
    private static final float RISE = 0.7f;
    private static final float FIRST_SWITCH = 0.07f;
    private static final float SWITCH_SLOWDOWN = 1.12f;
    private static final float COLOR_BLEND = 0.05f;
    private static final float POP = 0.19f;

    // Lid: angle = REST·tanh(8τ) + KICK·tanh(8τ)·e^(−4.5τ)·cos(9τ) — slams open, overshoots, springs back.
    private static final float LID_REST = (float) Math.toRadians(105);
    private static final float LID_KICK = (float) Math.toRadians(60);
    private static final float LID_RATTLE = (float) Math.toRadians(7);

    /** SkyBlock rarity colours: common, uncommon, rare, epic, legendary, mythic, divine, special. */
    private static final int[] RARITY_COLORS = {0xFFFFFF, 0x55FF55, 0x5555FF, 0xAA00AA, 0xFFAA00, 0xFF55FF, 0x55FFFF, 0xFF5555};
    private static final int DUST_COLOR = 0xC9B48A;

    private final ChestAssets assets;
    private final Plan plan;
    private final int finalColor;
    private final int cycleEndColor;

    /** Precomputed timeline and random choices. */
    private record Plan(ChestDrop drop, float land, float open, float riseStart, float riseEnd, float cycleEnd, float reveal,
                        float[] switchTimes, int[] colors, long dustSeed, int totalTicks)
    {
        static Plan create(SequenceContext ctx)
        {
            ChestDrop drop = new ChestDrop(ctx.random());
            List<ChestDrop.Impact> impacts = drop.impacts();
            float lastImpact = impacts.isEmpty() ? 0f : impacts.getLast().time();
            float land = Math.min(drop.settleTime(), lastImpact + 0.35f);
            float open = land + RATTLE;
            float riseStart = open + RISE_DELAY;
            float riseEnd = riseStart + RISE;
            float spin = ctx.spinTicks() / 20f;
            float cycleEnd = riseEnd + Math.max(1.2f, spin * 0.65f);
            float reveal = cycleEnd + Math.max(0.8f, spin * 0.35f);

            List<Float> times = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            float interval = FIRST_SWITCH;
            int previous = -1;
            for (float at = riseStart; at < cycleEnd; at += interval, interval *= SWITCH_SLOWDOWN)
            {
                int pick;
                do pick = ctx.random().nextInt(RARITY_COLORS.length); while (pick == previous);
                previous = pick;
                times.add(at);
                colors.add(RARITY_COLORS[pick]);
            }
            float[] switchTimes = new float[times.size()];
            int[] switchColors = new int[colors.size()];
            for (int i = 0; i < switchTimes.length; i++)
            {
                switchTimes[i] = times.get(i);
                switchColors[i] = colors.get(i);
            }
            return new Plan(drop, land, open, riseStart, riseEnd, cycleEnd, reveal, switchTimes, switchColors,
                ctx.random().nextLong(), Mth.ceil(reveal * 20f));
        }
    }

    ChestSequence(SequenceContext ctx, ChestAssets assets)
    {
        this(ctx, assets, Plan.create(ctx));
    }

    private ChestSequence(SequenceContext ctx, ChestAssets assets, Plan plan)
    {
        super(ctx, plan.totalTicks());
        this.assets = assets;
        this.plan = plan;
        this.finalColor = drop.itemColor() & 0xFFFFFF;
        this.cycleEndColor = cyclingColor(plan.cycleEnd());
    }

    // ---------------------------------------------------------------- sounds

    @Override
    protected void onSpinTick(int age)
    {
        float from = (age - 1) / 20f, to = age / 20f;
        SequenceSounds sounds = ctx.sounds();
        for (ChestDrop.Impact impact : plan.drop().impacts())
        {
            if (within(impact.time(), from, to)) sounds.chestThud(impact.speed());
        }
        for (float rattle : new float[] {0.08f, 0.26f, 0.44f})
        {
            if (within(plan.land() + rattle, from, to)) sounds.chestRattle();
        }
        if (within(plan.open(), from, to)) sounds.chestOpen();
        if (within(plan.riseStart(), from, to)) sounds.questionRise();
        float[] switches = plan.switchTimes();
        for (int i = 0; i < switches.length; i++)
        {
            if (within(switches[i], from, to)) sounds.colorTick(0.85f + 0.09f * (i % 9));
        }
        if (within(plan.cycleEnd(), from, to)) sounds.colorSettle();
    }

    private static boolean within(float time, float from, float to)
    {
        return time > from && time <= to;
    }

    // ---------------------------------------------------------------- layout hooks

    @Override
    protected float titleY()
    {
        return -124f;
    }

    @Override
    protected float badgeY()
    {
        return BASE_Y + 22f;
    }

    // ---------------------------------------------------------------- rendering

    @Override
    protected void renderMachine(GuiGraphicsExtractor g, Font font, float spinTime, float time, float alpha)
    {
        float t = time / 20f;

        drawShadowAndDust(g, t, alpha);
        g.nextStratum();
        drawChest(g, t, alpha);

        if (t >= plan.riseStart())
        {
            float hoverY = questionY(t);
            int color = glowColor(t);
            g.nextStratum();
            drawGlow(g, t, hoverY, color, alpha);
            g.nextStratum();
            float pop = t - plan.reveal();
            if (pop < POP) drawQuestion(g, t, hoverY, color, alpha);
            if (isRevealed()) drawItem(g, pop, hoverY, alpha);
            drawSparkles(g, t, hoverY, color, alpha);
        }

        if (isRevealed())
        {
            g.nextStratum();
            float nameAlpha = Easing.clamp01((t - plan.reveal() - 0.15f) / 0.3f);
            Draw.centeredText(g, font, drop.itemComponent(), 0f, ITEM_NAME_Y, 1.4f, alpha * nameAlpha);
        }
    }

    private void drawChest(GuiGraphicsExtractor g, float t, float alpha)
    {
        ChestDrop physics = plan.drop();
        float height = physics.height(t) + rattleHop(t);
        float squash = physics.squash(t) + burstSquash(t);
        // The chest tumbles in with some spin that unwinds by the time it lands.
        float yawOffset = 0.9f * (1f - Easing.outCubic(t / (physics.firstImpactTime() * 1.15f)));

        Matrix4f body = new Matrix4f()
            .rotateX(PITCH)
            .rotateY(YAW + yawOffset)
            .translate(0f, height - 0.5f, 0f)
            .rotateZ(physics.tilt(t))
            .scale(1f - squash * 0.5f, 1f + squash, 1f - squash * 0.5f);
        Matrix4f lid = new Matrix4f(body)
            .translate(0f, assets.hingeY, assets.hingeZ)
            .rotateX(-lidAngle(t))
            .translate(0f, -assets.hingeY, -assets.hingeZ);
        int lidPart = assets.lidPart;

        g.pose().pushMatrix();
        g.pose().translate(0f, CHEST_Y);
        MeshRenderer.draw(g, assets.chest, assets.chestTexture, part -> part == lidPart ? lid : (Matrix4fc) body,
            CAMERA_DISTANCE, FOCAL, 0xFFFFFF, alpha, 0f, true);
        g.pose().popMatrix();
    }

    /** Lid opening angle (radians). Before opening it strains against the latch during the rattle. */
    private float lidAngle(float t)
    {
        if (t < plan.open())
        {
            if (t < plan.land()) return 0f;
            float into = t - plan.land();
            return LID_RATTLE * (into / RATTLE) * Math.max(0f, Mth.sin(Mth.TWO_PI * 11f * into));
        }
        float tau = t - plan.open();
        float tanh = (float) Math.tanh(8f * tau);
        return LID_REST * tanh + LID_KICK * tanh * (float) Math.exp(-4.5f * tau) * Mth.cos(9f * tau);
    }

    private float rattleHop(float t)
    {
        if (t < plan.land() || t >= plan.open()) return 0f;
        float into = t - plan.land();
        return 0.025f * (into / RATTLE) * Math.max(0f, Mth.sin(Mth.TWO_PI * 5.5f * into));
    }

    /** The lid bursting open jolts the chest: a squash that rings out. */
    private float burstSquash(float t)
    {
        if (t < plan.open()) return 0f;
        float tau = t - plan.open();
        return -0.1f * (float) Math.exp(-9f * tau) * Mth.cos(16f * tau);
    }

    private void drawShadowAndDust(GuiGraphicsExtractor g, float t, float alpha)
    {
        float lift = Math.min(1f, plan.drop().height(t) / 3f);
        float width = 100f * (1f - 0.35f * lift);
        float height = 20f * (1f - 0.35f * lift);
        blitGlow(g, 0f, BASE_Y, width, height, Draw.argb(alpha * 0.55f * (1f - 0.6f * lift), 0x000000));

        List<ChestDrop.Impact> impacts = plan.drop().impacts();
        for (int i = 0; i < impacts.size(); i++)
        {
            ChestDrop.Impact impact = impacts.get(i);
            float since = t - impact.time();
            if (since < 0f || since > 0.9f || impact.speed() < 3f) continue;
            SplittableRandom random = new SplittableRandom(plan.dustSeed() + i);
            int particles = 6 + (int) (impact.speed() * 0.5f);
            for (int p = 0; p < particles; p++)
            {
                float side = random.nextBoolean() ? 1f : -1f;
                float life = 0.5f + random.nextFloat() * 0.3f;
                float x0 = side * (16f + random.nextFloat() * 24f);
                float vx = side * (25f + random.nextFloat() * 55f) * Math.min(1f, impact.speed() / 12f);
                float vy = -(15f + random.nextFloat() * 40f) * Math.min(1f, impact.speed() / 12f);
                int size = 2 + random.nextInt(2);
                if (since > life) continue;
                float drag = (1f - (float) Math.exp(-3f * since)) / 3f; // ∫e^(−3τ)
                float x = x0 + vx * drag;
                float y = BASE_Y + vy * drag + 40f * since * since;
                int color = Draw.argb(alpha * 0.8f * (1f - since / life), DUST_COLOR);
                g.fill(Math.round(x), Math.round(y), Math.round(x) + size, Math.round(y) + size, color);
            }
        }
    }

    // ---------------------------------------------------------------- question mark, glow, item

    private float questionY(float t)
    {
        float rise = Easing.clamp01((t - plan.riseStart()) / RISE);
        float y = Mth.lerp(Easing.outBack(rise, 1.6f), CHEST_MOUTH_Y, HOVER_Y);
        if (t > plan.riseEnd()) y += Mth.sin(Mth.TWO_PI * 0.7f * (t - plan.riseEnd())) * 3f;
        return y;
    }

    private void drawQuestion(GuiGraphicsExtractor g, float t, float y, int color, float alpha)
    {
        float rise = Easing.clamp01((t - plan.riseStart()) / RISE);
        float scale = 0.25f + 0.75f * Easing.outCubic(rise);
        float pop = t - plan.reveal();
        if (pop > 0f) scale *= pop < 0.07f ? 1f + 0.35f * (pop / 0.07f) : Math.max(0f, 1.35f * (1f - (pop - 0.07f) / (POP - 0.07f)));
        if (scale <= 0.01f) return;

        Matrix4f transform = new Matrix4f()
            .rotateX(0.15f)
            .rotateY(spinAngle(t))
            .scale(QUESTION_SIZE * scale);
        int tint = ARGB.srgbLerp(0.8f, 0xFFFFFFFF, 0xFF000000 | color) & 0xFFFFFF;

        g.pose().pushMatrix();
        g.pose().translate(0f, y);
        MeshRenderer.draw(g, assets.question, assets.questionTexture, part -> transform,
            CAMERA_DISTANCE, FOCAL, tint, alpha, 0.65f, false);
        g.pose().popMatrix();
    }

    /** Spin slows from frantic to lazy: ω(u) = ω_end + (ω0 − ω_end)·e^(−ku), integrated. */
    private float spinAngle(float t)
    {
        float u = Math.max(0f, t - plan.riseStart());
        float omegaStart = 11f, omegaEnd = 2.2f, k = 0.9f;
        return omegaEnd * u + (omegaStart - omegaEnd) / k * (1f - (float) Math.exp(-k * u));
    }

    private void drawItem(GuiGraphicsExtractor g, float pop, float y, float alpha)
    {
        if (pop < 0.07f || alpha < 0.55f) return; // items can't fade; hide them while the screen fades
        float grow = Easing.outBack(Easing.clamp01((pop - 0.07f) / 0.25f), 2f);
        float scale = 2.6f * grow;
        // A slow coin-flip spin faked by squeezing the 2D icon horizontally.
        float flip = Math.abs(Mth.cos(pop * 2.2f));
        if (flip < 0.08f || scale <= 0.01f) return;

        g.pose().pushMatrix();
        g.pose().translate(0f, y);
        g.pose().scale(scale * flip, scale);
        g.item(targetSymbol().icon(), -8, -8);
        g.pose().popMatrix();
    }

    private void drawGlow(GuiGraphicsExtractor g, float t, float y, int color, float alpha)
    {
        float strength = glowStrength(t);
        if (strength <= 0.01f) return;
        GuiShapes.rays(g, 0f, y, 12, 84f * strength, 0.1f, t * 0.6f, Draw.argb(alpha * 0.5f * Math.min(1f, strength), color));
        blitGlow(g, 0f, CHEST_MOUTH_Y, 66f, 30f, Draw.argb(alpha * 0.7f * Math.min(1f, strength), color));
        blitGlow(g, 0f, y, 96f * strength, 96f * strength, Draw.argb(alpha * 0.85f, color));
        int core = ARGB.srgbLerp(0.5f, 0xFFFFFFFF, 0xFF000000 | color) & 0xFFFFFF;
        blitGlow(g, 0f, y, 40f * strength, 40f * strength, Draw.argb(alpha * 0.6f, core));
    }

    /** Builds while rising, swells as the colour settles, flares on the reveal. */
    private float glowStrength(float t)
    {
        if (t < plan.riseStart()) return 0f;
        float rise = Easing.outCubic((t - plan.riseStart()) / RISE);
        if (t < plan.cycleEnd()) return rise;
        if (t < plan.reveal())
        {
            return 1f + 0.3f * Easing.outCubic((t - plan.cycleEnd()) / (plan.reveal() - plan.cycleEnd()));
        }
        float since = t - plan.reveal();
        return 1.05f + 0.6f * (float) Math.exp(-4f * since);
    }

    private void drawSparkles(GuiGraphicsExtractor g, float t, float y, int color, float alpha)
    {
        float appear = Easing.clamp01((t - plan.riseStart()) / RISE);
        if (appear <= 0f) return;
        int light = ARGB.srgbLerp(0.4f, 0xFFFFFFFF, 0xFF000000 | color) & 0xFFFFFF;
        for (int k = 0; k < 7; k++)
        {
            float angle = t * 1.8f + k * Mth.TWO_PI / 7f;
            float radius = (30f + 6f * Mth.sin(t * 3f + k)) * appear;
            float twinkle = 0.5f + 0.5f * Mth.sin(t * 9f + k * 1.7f);
            int x = Math.round(Mth.cos(angle) * radius);
            int sy = Math.round(y + Mth.sin(angle) * radius * 0.6f);
            g.fill(x - 1, sy - 1, x + 1, sy + 1, Draw.argb(alpha * twinkle, light));
        }
    }

    private void blitGlow(GuiGraphicsExtractor g, float cx, float cy, float width, float height, int argb)
    {
        if ((argb >>> 24) == 0 || width < 1f || height < 1f) return;
        g.pose().pushMatrix();
        g.pose().translate(cx, cy);
        g.pose().scale(width / 64f, height / 64f);
        g.blit(RenderPipelines.GUI_TEXTURED, assets.glow, -32, -32, 0f, 0f, 64, 64, 64, 64, argb);
        g.pose().popMatrix();
    }

    // ---------------------------------------------------------------- colour

    /** Random rarity colours while cycling, then a slow blend to the drop's real rarity colour. */
    private int glowColor(float t)
    {
        if (t <= plan.cycleEnd()) return cyclingColor(t);
        if (t >= plan.reveal()) return finalColor;
        float settle = (t - plan.cycleEnd()) / (plan.reveal() - plan.cycleEnd());
        float eased = settle * settle * (3f - 2f * settle);
        return ARGB.srgbLerp(eased, 0xFF000000 | cycleEndColor, 0xFF000000 | finalColor) & 0xFFFFFF;
    }

    private int cyclingColor(float t)
    {
        float[] times = plan.switchTimes();
        int[] colors = plan.colors();
        if (times.length == 0) return finalColor;
        int current = 0;
        while (current + 1 < times.length && times[current + 1] <= t) current++;
        if (current == 0) return colors[0];
        float blend = Easing.clamp01((t - times[current]) / COLOR_BLEND);
        return ARGB.srgbLerp(blend, 0xFF000000 | colors[current - 1], 0xFF000000 | colors[current]) & 0xFFFFFF;
    }
}
