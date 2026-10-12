package dev.archfiend.render3d;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

/** Untextured triangles with per-vertex colour (gradients), which vanilla's GUI helpers cannot draw. */
public final class GuiShapes
{
    private GuiShapes() {}

    /**
     * Light rays fanning out from (cx, cy).
     *
     * @param rotation radians
     * @param inner    ARGB at the centre; rays fade to transparent at the tips
     */
    public static void rays(GuiGraphicsExtractor graphics, float cx, float cy, int count, float length, float halfWidthRadians,
                            float rotation, int inner)
    {
        if ((inner >>> 24) == 0 || count <= 0) return;
        int outer = inner & 0x00FFFFFF;
        float[] xy = new float[count * 8];
        int[] colors = new int[count * 4];
        for (int r = 0; r < count; r++)
        {
            double angle = rotation + r * Math.PI * 2 / count;
            float lx = (float) Math.cos(angle - halfWidthRadians) * length, ly = (float) Math.sin(angle - halfWidthRadians) * length;
            float rx = (float) Math.cos(angle + halfWidthRadians) * length, ry = (float) Math.sin(angle + halfWidthRadians) * length;
            float[] quad = {cx, cy, cx + lx, cy + ly, cx + rx, cy + ry, cx, cy};
            System.arraycopy(quad, 0, xy, r * 8, 8);
            colors[r * 4] = inner;
            colors[r * 4 + 1] = outer;
            colors[r * 4 + 2] = outer;
            colors[r * 4 + 3] = inner;
        }
        submit(graphics, xy, colors, cx - length, cy - length, cx + length, cy + length);
    }

    private static void submit(GuiGraphicsExtractor graphics, float[] xy, int[] colors, float minX, float minY, float maxX, float maxY)
    {
        Matrix3x2f pose = new Matrix3x2f(graphics.pose());
        ScreenRectangle scissor = graphics.scissorStack.peek();
        graphics.guiRenderState.addGuiElement(new ColoredQuads(pose, xy, colors, scissor,
            MeshRenderer.bounds(pose, minX, minY, maxX, maxY, scissor)));
    }

    private record ColoredQuads(Matrix3x2f pose, float[] xy, int[] colors,
                                @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements GuiElementRenderState
    {
        @Override
        public void buildVertices(VertexConsumer consumer)
        {
            for (int i = 0; i < colors.length; i++)
            {
                consumer.addVertexWith2DPose(pose, xy[i * 2], xy[i * 2 + 1]).setColor(colors[i]);
            }
        }

        @Override
        public RenderPipeline pipeline()
        {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup()
        {
            return TextureSetup.noTexture();
        }
    }
}
