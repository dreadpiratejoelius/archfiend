package dev.archfiend.sequence;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.archfiend.render3d.ChestAssets;
import dev.archfiend.render3d.Mesh;
import dev.archfiend.util.Draw;
import dev.archfiend.util.Easing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.random.RandomGenerator;

/**
 * World-space treasure chest: the chest drops into the world in front of the player, bounces
 * with physics and dust, settles upright on the ground, rattles, bursts open and reveals the glowing
 * question mark and item. The title, item name and destination badge stay on the HUD; there is no scrim.
 * <p>
 * Falls back to the GUI {@link ChestSequence} when no valid spawn position is found.
 */
final class WorldChestSequence extends DropSequence
{
    private static final float RATTLE = 0.6f;
    private static final float RISE_DELAY = 0.1f;
    private static final float RISE = 0.7f;
    private static final float FIRST_SWITCH = 0.07f;
    private static final float SWITCH_SLOWDOWN = 1.12f;
    private static final float COLOR_BLEND = 0.05f;
    private static final float POP = 0.19f;
    private static final float[] RATTLES = {0.08f, 0.26f, 0.44f};

    /** World height of the chest in blocks; the mesh is normalised to height 1. */
    private static final float WORLD_HEIGHT = 0.875f;
    private static final float QUESTION_SIZE = 0.55f;

    // Lid: angle = REST·tanh(8τ) + KICK·tanh(8τ)·e^(−4.5τ)·cos(9τ) — slams open, overshoots, springs back.
    private static final float LID_REST = (float) Math.toRadians(105);
    private static final float LID_KICK = (float) Math.toRadians(60);
    private static final float LID_RATTLE = (float) Math.toRadians(7);

    private static final int[] RARITY_COLORS = {0xFFFFFF, 0x55FF55, 0x5555FF, 0xAA00AA, 0xFFAA00, 0xFF55FF, 0x55FFFF, 0xFF5555};

    private record Plan(ChestDrop drop, float land, float open, float riseStart, float riseEnd, float cycleEnd, float reveal,
                        float[] switchTimes, int[] switchColors, int totalTicks)
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

            RandomGenerator random = ctx.random();
            List<Float> times = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            float interval = FIRST_SWITCH;
            int previous = -1;
            for (float at = riseStart; at < cycleEnd; at += interval, interval *= SWITCH_SLOWDOWN)
            {
                int pick;
                do pick = random.nextInt(RARITY_COLORS.length); while (pick == previous);
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
                Mth.ceil(reveal * 20f));
        }
    }

    private final ChestAssets assets;
    private final Vec3 ground;
    private final float baseYaw;
    private final boolean yawSign;
    private final Plan plan;
    private final int finalColor;
    private final int cycleEndColor;

    private boolean poofed;
    private final ItemStackRenderState itemState = new ItemStackRenderState();
    private @Nullable ItemStack resolvedIcon;

    private WorldChestSequence(SequenceContext ctx, ChestAssets assets, Vec3 ground, float targetYaw)
    {
        this(ctx, assets, ground, targetYaw, Plan.create(ctx));
    }

    private WorldChestSequence(SequenceContext ctx, ChestAssets assets, Vec3 ground, float targetYaw, Plan plan)
    {
        super(ctx, plan.totalTicks());
        this.assets = assets;
        this.ground = ground;
        this.baseYaw = targetYaw;
        this.yawSign = ctx.random().nextBoolean();
        this.plan = plan;
        this.finalColor = drop.itemColor() & 0xFFFFFF;
        this.cycleEndColor = cyclingColor(plan.cycleEnd());
    }

    /** Creates a world chest, or empty if no valid spawn position is available. */
    static Optional<DropSequence> tryCreate(SequenceContext ctx, ChestAssets assets)
    {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (player == null || level == null) return Optional.empty();

        Vec3 ground = null;
        if (ctx.originPos() != null)
        {
            Vec3 origin = ctx.originPos();
            if (player.distanceToSqr(origin.x, origin.y, origin.z) <= 48.0 * 48.0)
            {
                ground = findGroundAt(player, level, origin);
            }
        }
        if (ground == null)
        {
            ground = findGround(player, level);
        }
        if (ground == null) return Optional.empty();

        float yaw = (float) Math.atan2(player.getX() - ground.x, player.getZ() - ground.z);
        return Optional.of(new WorldChestSequence(ctx, assets, ground, yaw));
    }

    // ---------------------------------------------------------------- spawn

    /** Finds the ground block surface directly beneath the given position (e.g. enemy death spot). */
    private static @Nullable Vec3 findGroundAt(LocalPlayer player, ClientLevel level, Vec3 pos)
    {
        Vec3 downFrom = new Vec3(pos.x, pos.y + 1.2, pos.z);
        Vec3 downTo = new Vec3(pos.x, pos.y - 4.5, pos.z);
        BlockHitResult hit = level.clip(new ClipContext(downFrom, downTo, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) return null;

        double groundY = hit.getLocation().y;
        BlockPos basePos = BlockPos.containing(pos.x, groundY + 0.1, pos.z);
        BlockPos abovePos = basePos.above();

        if (isSolid(level, basePos) || isSolid(level, abovePos)) return null;

        return new Vec3(pos.x, groundY, pos.z);
    }

    /** Finds a valid ground position in front of the player; returns null if none is found. */
    private static @Nullable Vec3 findGround(LocalPlayer player, ClientLevel level)
    {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double horizontal = Math.hypot(look.x, look.z);
        if (horizontal < 1e-3) return null;
        double dx = look.x / horizontal, dz = look.z / horizontal;

        for (double dist : new double[] {2.8, 2.3, 1.8, 3.4})
        {
            Vec3 eyeTarget = new Vec3(eye.x + dx * dist, eye.y, eye.z + dz * dist);
            BlockHitResult wallHit = level.clip(new ClipContext(eye, eyeTarget, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            Vec3 target = wallHit.getType() == HitResult.Type.MISS
                ? eyeTarget
                : wallHit.getLocation().subtract(dx * 0.4, 0.0, dz * 0.4);

            Vec3 downFrom = new Vec3(target.x, eye.y + 1.2, target.z);
            Vec3 downTo = new Vec3(target.x, eye.y - 5.5, target.z);
            BlockHitResult groundHit = level.clip(new ClipContext(downFrom, downTo, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            if (groundHit.getType() != HitResult.Type.BLOCK) continue;

            double groundY = groundHit.getLocation().y;
            BlockPos basePos = BlockPos.containing(target.x, groundY + 0.1, target.z);
            BlockPos abovePos = basePos.above();
            BlockPos above2Pos = abovePos.above();

            if (isSolid(level, basePos) || isSolid(level, abovePos) || isSolid(level, above2Pos)) continue;

            return new Vec3(target.x, groundY, target.z);
        }
        return null;
    }

    private static boolean isSolid(ClientLevel level, BlockPos pos)
    {
        return !level.getBlockState(pos).isAir() && level.getBlockState(pos).isSolidRender();
    }

    // ---------------------------------------------------------------- sounds & particles

    @Override
    protected void onSpinTick(int age)
    {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;

        float from = (age - 1) / 20f, to = age / 20f;
        SequenceSounds sounds = ctx.sounds();

        for (ChestDrop.Impact impact : plan.drop().impacts())
        {
            if (within(impact.time(), from, to))
            {
                sounds.chestThud(level, ground.x, ground.y, ground.z, impact.speed());
                for (int p = 0; p < 8; p++)
                {
                    double angle = p * Math.PI * 2.0 / 8.0;
                    level.addParticle(ParticleTypes.POOF,
                        ground.x + Math.cos(angle) * 0.35, ground.y + 0.05, ground.z + Math.sin(angle) * 0.35,
                        Math.cos(angle) * 0.12, 0.04, Math.sin(angle) * 0.12);
                }
            }
        }

        for (float rattle : RATTLES)
        {
            if (within(plan.land() + rattle, from, to)) sounds.chestRattle(level, ground.x, ground.y, ground.z);
        }
        if (within(plan.open(), from, to)) sounds.chestOpen(level, ground.x, ground.y, ground.z);

        double mouthY = ground.y + WORLD_HEIGHT * 0.82;
        if (within(plan.riseStart(), from, to)) sounds.questionRise(level, ground.x, mouthY, ground.z);

        float[] switches = plan.switchTimes();
        for (int i = 0; i < switches.length; i++)
        {
            if (within(switches[i], from, to)) sounds.colorTick(level, ground.x, mouthY, ground.z, 0.85f + 0.09f * (i % 9));
        }
        if (within(plan.cycleEnd(), from, to)) sounds.colorSettle(level, ground.x, mouthY, ground.z);

        float t = age / 20f;
        if (t >= plan.riseStart() && t < plan.reveal() + POP)
        {
            float qY = hoverY(t);
            level.addParticle(ParticleTypes.ENCHANT,
                ground.x + (Math.random() - 0.5) * 0.4,
                qY + (Math.random() - 0.5) * 0.4,
                ground.z + (Math.random() - 0.5) * 0.4,
                (Math.random() - 0.5) * 0.2, 0.1, (Math.random() - 0.5) * 0.2);
        }
    }

    private static boolean within(float time, float from, float to)
    {
        return time > from && time <= to;
    }

    // ---------------------------------------------------------------- HUD

    @Override
    protected boolean rendersScrim()
    {
        return false;
    }

    @Override
    protected boolean worldChest()
    {
        return true;
    }

    @Override
    public boolean hidesHud()
    {
        return false; // the player is still playing; only chat is hidden
    }

    @Override
    protected boolean hidesChat()
    {
        return spinning();
    }

    @Override
    protected float titleY()
    {
        return -112f;
    }

    @Override
    protected float badgeY()
    {
        return 118f;
    }

    @Override
    protected void renderMachine(GuiGraphicsExtractor g, Font font, float spinTime, float time, float alpha)
    {
        float t = time / 20f;
        if (isRevealed())
        {
            float nameAlpha = Easing.clamp01((t - plan.reveal() - 0.15f) / 0.3f);
            Draw.centeredText(g, font, drop.itemComponent(), 0f, 88f, 1.4f, alpha * nameAlpha);
        }
    }

    // ---------------------------------------------------------------- world rendering

    @Override
    public void renderWorld(PoseStack poseStack, SubmitNodeCollector collector, Vec3 cameraPos, float partialTick)
    {
        Level level = Minecraft.getInstance().level;
        if (level == null) return;

        float t = timeSeconds(partialTick);
        float pop = popScale(t);
        if (pop <= 0.01f) return;

        ChestDrop physics = plan.drop();
        float height = (physics.height(t) + rattleHop(t)) * WORLD_HEIGHT;
        float squash = physics.squash(t) + burstSquash(t);
        float tilt = physics.tilt(t);
        float yawOffset = 0.5f * (1f - Easing.outCubic(Math.min(1f, t / (physics.firstImpactTime() * 1.15f))));
        float yaw = baseYaw + (yawSign ? yawOffset : -yawOffset);

        int light = sampleLight(level, ground.x, ground.y + Math.max(0f, height) + WORLD_HEIGHT * 0.5, ground.z);
        renderChest(poseStack, collector, cameraPos, ground.x, ground.y + height, ground.z, yaw, tilt, squash, pop, light, t);

        if (t >= plan.riseStart())
        {
            float hoverY = hoverY(t);
            int color = glowColor(t);
            if (t < plan.reveal() + POP) renderQuestion(poseStack, collector, cameraPos, ground.x, hoverY, ground.z, t, color, light);
            if (t >= plan.reveal()) renderItem(poseStack, collector, cameraPos, ground.x, hoverY, ground.z, t, light);
        }

        float fadeStart = (spinTicks() + ctx.revealTicks()) / 20f;
        if (!poofed && t >= fadeStart)
        {
            poofed = true;
            for (int i = 0; i < 12; i++)
            {
                level.addParticle(ParticleTypes.POOF, ground.x, ground.y + WORLD_HEIGHT * 0.5, ground.z,
                    (Math.random() - 0.5) * 0.4, Math.random() * 0.3, (Math.random() - 0.5) * 0.4);
            }
        }
    }

    private float popScale(float t)
    {
        float fadeStart = (spinTicks() + ctx.revealTicks()) / 20f;
        float since = t - fadeStart;
        if (since <= 0f) return 1f;
        return Math.max(0f, 1f - since / 0.25f);
    }

    private static int sampleLight(Level level, double x, double y, double z)
    {
        BlockPos pos = BlockPos.containing(x, y, z);
        int block = level.getBrightness(LightLayer.BLOCK, pos);
        int sky = level.getBrightness(LightLayer.SKY, pos);
        return LightCoordsUtil.pack(block, sky);
    }

    private void renderChest(PoseStack poseStack, SubmitNodeCollector collector, Vec3 cameraPos,
                             double x, double y, double z, float yaw, float tilt, float squash, float pop, int light, float t)
    {
        poseStack.pushPose();
        translate(poseStack, cameraPos, x, y, z);
        poseStack.mulPose(new Quaternionf().rotationY(yaw));
        poseStack.mulPose(new Quaternionf().rotationZ(tilt));
        float scaleY = WORLD_HEIGHT * (1f + squash) * pop;
        float scaleXZ = WORLD_HEIGHT * (1f - squash * 0.5f) * pop;
        poseStack.scale(scaleXZ, scaleY, scaleXZ);

        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(assets.chestTextureId),
            (pose, consumer) -> emitMesh(consumer, pose, assets.chest, part -> part != assets.lidPart, light, 0xFFFFFFFF));

        poseStack.pushPose();
        poseStack.translate(0f, assets.hingeY, assets.hingeZ);
        poseStack.mulPose(new Quaternionf().rotationX(-lidAngle(t)));
        poseStack.translate(0f, -assets.hingeY, -assets.hingeZ);
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(assets.chestTextureId),
            (pose, consumer) -> emitMesh(consumer, pose, assets.chest, part -> part == assets.lidPart, light, 0xFFFFFFFF));
        poseStack.popPose();

        poseStack.popPose();
    }

    private void renderQuestion(PoseStack poseStack, SubmitNodeCollector collector, Vec3 cameraPos,
                                double x, double y, double z, float t, int color, int light)
    {
        float rise = Easing.clamp01((t - plan.riseStart()) / RISE);
        float scale = 0.25f + 0.75f * Easing.outCubic(rise);
        float pop = t - plan.reveal();
        if (pop > 0f)
        {
            scale *= pop < 0.07f ? 1f + 0.35f * (pop / 0.07f) : Math.max(0f, 1.35f * (1f - (pop - 0.07f) / (POP - 0.07f)));
        }
        if (scale <= 0.01f) return;

        int tint = 0xFF000000 | (ARGB.srgbLerp(0.8f, 0xFFFFFFFF, 0xFF000000 | color) & 0xFFFFFF);
        poseStack.pushPose();
        translate(poseStack, cameraPos, x, y, z);
        poseStack.mulPose(new Quaternionf().rotationY(spinAngle(t)));
        poseStack.scale(QUESTION_SIZE * scale, QUESTION_SIZE * scale, QUESTION_SIZE * scale);
        collector.submitCustomGeometry(poseStack, RenderTypes.entityTranslucentEmissive(assets.whiteTextureId),
            (pose, consumer) -> emitMesh(consumer, pose, assets.question, null, light, tint));
        poseStack.popPose();
    }

    private void renderItem(PoseStack poseStack, SubmitNodeCollector collector, Vec3 cameraPos,
                            double x, double y, double z, float t, int light)
    {
        float pop = t - plan.reveal();
        if (pop < 0.07f) return;
        float grow = Easing.outBack(Easing.clamp01((pop - 0.07f) / 0.25f), 2f);
        float scale = 0.7f * grow;
        if (scale <= 0.01f) return;

        poseStack.pushPose();
        translate(poseStack, cameraPos, x, y, z);
        poseStack.mulPose(new Quaternionf().rotationY(pop * 1.6f));
        poseStack.scale(scale, scale, scale);
        submitItem(collector, poseStack, light);
        poseStack.popPose();
    }

    private void submitItem(SubmitNodeCollector collector, PoseStack poseStack, int light)
    {
        ItemStack icon = targetSymbol().icon();
        if (icon.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null || minecraft.player == null) return;
        if (resolvedIcon != icon)
        {
            resolvedIcon = icon;
            itemState.clear();
            minecraft.getItemModelResolver().updateForTopItem(itemState, icon, ItemDisplayContext.FIXED, level, minecraft.player, 0);
        }
        if (itemState.isEmpty()) return;
        itemState.submit(poseStack, collector, light, OverlayTexture.NO_OVERLAY, 0);
    }

    // ---------------------------------------------------------------- mesh emission

    /** Emits a mesh's triangles as lit quads with degenerate 4th vertex for entity render types. */
    private static void emitMesh(VertexConsumer consumer, PoseStack.Pose pose, Mesh mesh,
                                 @Nullable IntPredicate partFilter, int light, int argb)
    {
        int[] indices = mesh.indices();
        float[] p = mesh.positions();
        float[] uv = mesh.uvs();
        int[] parts = mesh.parts();
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF, a = (argb >>> 24) & 0xFF;

        float minX = Float.POSITIVE_INFINITY, minY = minX, minZ = minX;
        float maxX = Float.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (int i = 0; i < mesh.vertexCount(); i++)
        {
            minX = Math.min(minX, p[i * 3]);
            maxX = Math.max(maxX, p[i * 3]);
            minY = Math.min(minY, p[i * 3 + 1]);
            maxY = Math.max(maxY, p[i * 3 + 1]);
            minZ = Math.min(minZ, p[i * 3 + 2]);
            maxZ = Math.max(maxZ, p[i * 3 + 2]);
        }
        float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f, cz = (minZ + maxZ) * 0.5f;

        for (int t = 0; t < mesh.triangleCount(); t++)
        {
            int i0 = indices[t * 3], i1 = indices[t * 3 + 1], i2 = indices[t * 3 + 2];
            if (partFilter != null && (!partFilter.test(parts[i0]) || !partFilter.test(parts[i1]) || !partFilter.test(parts[i2]))) continue;

            float e1x = p[i1 * 3] - p[i0 * 3], e1y = p[i1 * 3 + 1] - p[i0 * 3 + 1], e1z = p[i1 * 3 + 2] - p[i0 * 3 + 2];
            float e2x = p[i2 * 3] - p[i0 * 3], e2y = p[i2 * 3 + 1] - p[i0 * 3 + 1], e2z = p[i2 * 3 + 2] - p[i0 * 3 + 2];
            float nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length > 1e-6f)
            {
                nx /= length;
                ny /= length;
                nz /= length;
            }

            float tx = (p[i0 * 3] + p[i1 * 3] + p[i2 * 3]) / 3f;
            float ty = (p[i0 * 3 + 1] + p[i1 * 3 + 1] + p[i2 * 3 + 1]) / 3f;
            float tz = (p[i0 * 3 + 2] + p[i1 * 3 + 2] + p[i2 * 3 + 2]) / 3f;
            if (nx * (tx - cx) + ny * (ty - cy) + nz * (tz - cz) < 0f)
            {
                nx = -nx;
                ny = -ny;
                nz = -nz;
            }

            int[] corners = {i0, i1, i2, i2};
            for (int index : corners)
            {
                consumer.addVertex(pose, p[index * 3], p[index * 3 + 1], p[index * 3 + 2])
                    .setColor(r, g, b, a)
                    .setUv(uv[index * 2], uv[index * 2 + 1])
                    .setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(light)
                    .setNormal(pose, nx, ny, nz);
            }
        }
    }

    private static void translate(PoseStack poseStack, Vec3 camera, double x, double y, double z)
    {
        poseStack.translate(x - camera.x, y - camera.y, z - camera.z);
    }

    // ---------------------------------------------------------------- motion

    private float hoverY(float t)
    {
        float mouthY = (float) ground.y + WORLD_HEIGHT * 0.82f;
        float targetY = (float) ground.y + WORLD_HEIGHT + 0.65f;
        float rise = Easing.clamp01((t - plan.riseStart()) / RISE);
        float y = mouthY + Easing.outBack(rise, 1.6f) * (targetY - mouthY);
        if (t > plan.riseEnd()) y += Mth.sin(Mth.TWO_PI * 0.7f * (t - plan.riseEnd())) * 0.04f;
        return y;
    }

    private float spinAngle(float t)
    {
        float u = Math.max(0f, t - plan.riseStart());
        float omegaStart = 11f, omegaEnd = 2.2f, k = 0.9f;
        return omegaEnd * u + (omegaStart - omegaEnd) / k * (1f - (float) Math.exp(-k * u));
    }

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

    private float burstSquash(float t)
    {
        if (t < plan.open()) return 0f;
        float tau = t - plan.open();
        return -0.1f * (float) Math.exp(-9f * tau) * Mth.cos(16f * tau);
    }

    // ---------------------------------------------------------------- colour

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
        float[] switches = plan.switchTimes();
        if (switches.length == 0) return finalColor;
        int[] colors = plan.switchColors();
        int current = 0;
        while (current + 1 < switches.length && switches[current + 1] <= t) current++;
        if (current == 0) return colors[0];
        float blend = Easing.clamp01((t - switches[current]) / COLOR_BLEND);
        return ARGB.srgbLerp(blend, 0xFF000000 | colors[current - 1], 0xFF000000 | colors[current]) & 0xFFFFFF;
    }
}
