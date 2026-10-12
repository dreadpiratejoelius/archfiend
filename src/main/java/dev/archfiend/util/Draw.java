package dev.archfiend.util;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;

/** Small drawing helpers on top of {@link GuiGraphicsExtractor}. All colours are RGB unless named ARGB. */
public final class Draw
{
    /** Below this alpha text is skipped entirely; the font renderer discards near-transparent glyphs anyway. */
    private static final float MIN_TEXT_ALPHA = 0.02f;

    private Draw() {}

    public static int argb(float alpha, int rgb)
    {
        return ARGB.color(Math.round(Easing.clamp01(alpha) * 255f), rgb & 0xFFFFFF);
    }

    public static void border(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int thickness, int argb)
    {
        g.fill(x0, y0, x1, y0 + thickness, argb);
        g.fill(x0, y1 - thickness, x1, y1, argb);
        g.fill(x0, y0 + thickness, x0 + thickness, y1 - thickness, argb);
        g.fill(x1 - thickness, y0 + thickness, x1, y1 - thickness, argb);
    }

    /** Horizontal gradient approximated with vertical bands; vanilla only ships a vertical gradient fill. */
    public static void horizontalGradient(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int argbLeft, int argbRight)
    {
        int width = x1 - x0;
        if (width <= 0) return;
        int bands = Math.min(width, 16);
        for (int i = 0; i < bands; i++)
        {
            int bx0 = x0 + width * i / bands;
            int bx1 = x0 + width * (i + 1) / bands;
            g.fill(bx0, y0, bx1, y1, ARGB.srgbLerp((i + 0.5f) / bands, argbLeft, argbRight));
        }
    }

    /** Draws text centred on (x, y) at the given scale, skipping it when effectively invisible. */
    public static void centeredText(GuiGraphicsExtractor g, Font font, Component text, float x, float y, float scale, float alpha)
    {
        if (alpha < MIN_TEXT_ALPHA || scale <= 0f) return;
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale);
        g.centeredText(font, text, 0, -font.lineHeight / 2, argb(alpha, 0xFFFFFF));
        g.pose().popMatrix();
    }
}
