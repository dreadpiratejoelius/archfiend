package dev.archfiend.sequence;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.archfiend.drop.RngDrop;
import dev.archfiend.util.Draw;
import dev.archfiend.util.Easing;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Timeline shared by every sequence style: fade in → spin → reveal → fade out.
 * <p>
 * Logic advances on client ticks; rendering interpolates with the partial tick so motion stays smooth
 * at any frame rate. Subclasses lay out their machine in a virtual coordinate space centred on the
 * screen origin, which is scaled to fit the current GUI size.
 */
public abstract class DropSequence
{
    protected static final int FADE_IN_TICKS = 6;
    protected static final int FADE_OUT_TICKS = 10;
    private static final int TITLE_POP_TICKS = 8;
    private static final int FLASH_TICKS = 6;
    private static final float REFERENCE_GUI_WIDTH = 480f;
    private static final float REFERENCE_GUI_HEIGHT = 270f;
    private static final int BADGE_DELAY_TICKS = 6;
    private static final int BADGE_FADE_TICKS = 6;

    protected final SequenceContext ctx;
    protected final RngDrop drop;
    private final int spinTicks;
    private final int totalTicks;
    private Symbol target;
    private int age;
    private boolean revealed;
    private @Nullable Component destination;

    protected DropSequence(SequenceContext ctx, int spinTicks)
    {
        this.ctx = ctx;
        this.drop = ctx.drop();
        this.target = ctx.target();
        this.spinTicks = spinTicks;
        this.totalTicks = spinTicks + ctx.revealTicks() + FADE_OUT_TICKS;
    }

    /** Called every tick while spinning, with the already-incremented age. Use for sounds. */
    protected abstract void onSpinTick(int age);

    /**
     * @param spinTime elapsed spin time in ticks, frozen at the spin length once landed
     * @param time     elapsed time including the reveal phase
     * @param alpha    global fade multiplier
     */
    protected abstract void renderMachine(GuiGraphicsExtractor g, Font font, float spinTime, float time, float alpha);

    /** Virtual Y coordinate (centre of the line) for the tier banner shown on reveal. */
    protected abstract float titleY();

    /** Virtual Y coordinate (centre of the line) for the "where it landed" badge shown on reveal. */
    protected abstract float badgeY();

    /** Whether this style draws a dark full-screen scrim behind the machine. World styles override to false. */
    protected boolean rendersScrim()
    {
        return true;
    }

    /** Whether this is a world-space style (a 3D object in the world, HUD text only). */
    protected boolean worldChest()
    {
        return false;
    }

    /** Whether only the chat overlay should be hidden (world styles hide chat but keep the rest of the HUD). */
    protected boolean hidesChat()
    {
        return hidesHud();
    }

    /**
     * Renders this sequence's world-space content into the level submit pass. GUI styles do nothing.
     *
     * @param cameraPos the camera position, for billboard orientation and distance
     */
    public void renderWorld(PoseStack poseStack, SubmitNodeCollector collector, Vec3 cameraPos, float partialTick) {}

    public final void tick()
    {
        if (isFinished()) return;
        age++;
        if (!revealed)
        {
            onSpinTick(age);
            if (age >= spinTicks) reveal();
        }
    }

    /** First call jumps to the result; a second call (or one during the reveal) ends the sequence. */
    public final void skip()
    {
        if (!revealed)
        {
            age = spinTicks;
            reveal();
        }
        else
        {
            age = Math.max(age, spinTicks + ctx.revealTicks());
        }
    }

    public final boolean isFinished()
    {
        return age >= totalTicks;
    }

    public final boolean isRevealed()
    {
        return revealed;
    }

    public final void setTargetIcon(ItemStack icon)
    {
        target = target.withIcon(icon);
    }

    /** Where the drop actually arrived, e.g. "✔ Inventory"; shown under the machine once revealed. */
    public final void setDestination(Component destination)
    {
        this.destination = destination;
    }

    /** Whether the vanilla HUD should stay hidden; it returns halfway through the final fade-out. */
    public boolean hidesHud()
    {
        return spinning();
    }

    /** True while the sequence is still running (before the final fade-out). */
    protected final boolean spinning()
    {
        return age < totalTicks - FADE_OUT_TICKS / 2;
    }

    /** The winning symbol, including any icon resolved from the inventory after the sequence started. */
    public final Symbol targetSymbol()
    {
        return target;
    }

    protected final int spinTicks()
    {
        return spinTicks;
    }

    /** Elapsed sequence time in seconds, clamped to the total length; for interpolation on render. */
    protected final float timeSeconds(float partialTick)
    {
        return Math.min(age + partialTick, totalTicks) / 20f;
    }

    private void reveal()
    {
        revealed = true;
        ctx.onReveal().run();
        ctx.sounds().reveal(drop.tier().isJackpot());
    }

    public final void render(GuiGraphicsExtractor g, float partialTick)
    {
        float time = Math.min(age + partialTick, totalTicks);
        float alpha = Math.min(Easing.clamp01(time / FADE_IN_TICKS), Easing.clamp01((totalTicks - time) / FADE_OUT_TICKS));
        if (alpha <= 0f) return;

        Font font = Minecraft.getInstance().font;
        int width = g.guiWidth();
        int height = g.guiHeight();

        g.nextStratum();
        if (rendersScrim()) g.fill(0, 0, width, height, Draw.argb(alpha * 0.6f, 0x000000));
        g.nextStratum();

        float layoutScale = ctx.scale() * Mth.clamp(
            Math.min(width / REFERENCE_GUI_WIDTH, height / REFERENCE_GUI_HEIGHT), 0.6f, 3f
        );

        g.pose().pushMatrix();
        g.pose().translate(width / 2f, height / 2f);
        g.pose().scale(layoutScale);

        renderMachine(g, font, Math.min(time, spinTicks), time, alpha);
        if (time >= spinTicks)
        {
            renderTitle(g, font, time - spinTicks, alpha);
            if (destination != null)
            {
                float badgeAlpha = Easing.clamp01((time - spinTicks - BADGE_DELAY_TICKS) / BADGE_FADE_TICKS);
                Draw.centeredText(g, font, destination, 0f, badgeY(), 1f, alpha * badgeAlpha);
            }
        }

        g.pose().popMatrix();

        if (drop.tier().isJackpot() && rendersScrim() && time >= spinTicks && time < spinTicks + FLASH_TICKS)
        {
            g.nextStratum();
            g.fill(0, 0, width, height, Draw.argb(0.35f * (1f - (time - spinTicks) / FLASH_TICKS), 0xFFFFFF));
        }
    }

    private void renderTitle(GuiGraphicsExtractor g, Font font, float revealTime, float alpha)
    {
        float pop = Easing.outBack(revealTime / TITLE_POP_TICKS, 2.2f);
        // Jackpot banners strobe white for a moment to sell the moment.
        boolean strobe = drop.tier().isJackpot() && revealTime < 24f && ((int) (revealTime / 3f) & 1) == 1;
        Component title = strobe
            ? Component.literal(drop.label()).withStyle(Style.EMPTY.withBold(true)).withColor(0xFFFFFF)
            : drop.labelComponent();
        Draw.centeredText(g, font, title, 0f, titleY(), 1.8f * pop, alpha);
    }

    /** 0..1 sine pulse, useful for glows. */
    protected static float pulse(float time, float speed)
    {
        return 0.5f + 0.5f * Mth.sin(time * speed);
    }
}
