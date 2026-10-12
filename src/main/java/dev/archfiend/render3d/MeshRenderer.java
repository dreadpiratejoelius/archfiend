package dev.archfiend.render3d;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.joml.Matrix3x2f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.function.IntFunction;

/**
 * Draws a textured {@link Mesh} into the GUI by projecting it on the CPU.
 * <p>
 * The GUI pipeline has no depth buffer, so triangles are back-face culled and painted far-to-near
 * (painter's algorithm). That is exact for convex, non-intersecting props such as a chest. Lighting
 * is a single directional light plus ambient, baked into vertex colours.
 * <p>
 * View space: x right, y up, z towards the viewer; the camera sits at {@code (0, 0, distance)}.
 * Projected points are in the current GUI pose's units, centred on the pose origin.
 */
public final class MeshRenderer
{
    private static final Vector3f LIGHT = new Vector3f(-0.45f, 0.8f, 0.55f).normalize();
    private static final float AMBIENT = 0.5f;
    private static final float DIFFUSE = 0.55f;
    private static final float NEAR = 0.1f;

    private MeshRenderer() {}

    /**
     * @param partTransforms model-to-view transform for each part id
     * @param distance       camera distance; smaller values exaggerate perspective
     * @param focal          GUI units per world unit at the origin's depth
     * @param tint           RGB multiplied into every vertex
     * @param emissive       0 = fully lit, 1 = ignores lighting (for glowing objects)
     * @param cullBackFaces  disable for meshes with inconsistent winding
     */
    public static void draw(GuiGraphicsExtractor graphics, Mesh mesh, AbstractTexture texture, IntFunction<Matrix4fc> partTransforms,
                            float distance, float focal, int tint, float alpha, float emissive, boolean cullBackFaces)
    {
        if (alpha <= 0.01f) return;
        int vertices = mesh.vertexCount();
        float[] view = new float[vertices * 3];
        float[] screen = new float[vertices * 2];
        Vector3f v = new Vector3f();
        for (int i = 0; i < vertices; i++)
        {
            partTransforms.apply(mesh.parts()[i]).transformPosition(mesh.positions()[i * 3], mesh.positions()[i * 3 + 1], mesh.positions()[i * 3 + 2], v);
            view[i * 3] = v.x;
            view[i * 3 + 1] = v.y;
            view[i * 3 + 2] = v.z;
            float depth = Math.max(NEAR, distance - v.z);
            float scale = focal * distance / depth;
            screen[i * 2] = v.x * scale;
            screen[i * 2 + 1] = -v.y * scale;
        }

        int[] indices = mesh.indices();
        int triangles = mesh.triangleCount();
        long[] order = new long[triangles]; // depth bits in the high word, triangle index in the low word
        float[] shade = new float[triangles];
        int visible = 0;
        Vector3f edge1 = new Vector3f(), edge2 = new Vector3f(), normal = new Vector3f(), toCamera = new Vector3f();
        for (int t = 0; t < triangles; t++)
        {
            int a = indices[t * 3], b = indices[t * 3 + 1], c = indices[t * 3 + 2];
            if (distance - view[a * 3 + 2] < NEAR || distance - view[b * 3 + 2] < NEAR || distance - view[c * 3 + 2] < NEAR) continue;

            edge1.set(view[b * 3] - view[a * 3], view[b * 3 + 1] - view[a * 3 + 1], view[b * 3 + 2] - view[a * 3 + 2]);
            edge2.set(view[c * 3] - view[a * 3], view[c * 3 + 1] - view[a * 3 + 1], view[c * 3 + 2] - view[a * 3 + 2]);
            edge1.cross(edge2, normal);
            if (normal.lengthSquared() < 1e-12f) continue;
            normal.normalize();
            toCamera.set(-view[a * 3], -view[a * 3 + 1], distance - view[a * 3 + 2]);
            boolean facing = normal.dot(toCamera) > 0f;
            if (cullBackFaces && !facing) continue;
            if (!facing) normal.negate(); // light double-sided faces from the visible side

            float lit = Math.min(1f, AMBIENT + DIFFUSE * Math.max(0f, normal.dot(LIGHT)));
            shade[t] = lit + (1f - lit) * emissive;
            float depth = (view[a * 3 + 2] + view[b * 3 + 2] + view[c * 3 + 2]) / 3f;
            // Sort key: increasing depth draws farthest first. Float bits are order-preserving for
            // our range once shifted positive.
            order[visible++] = ((long) Float.floatToIntBits(depth + 1000f) << 32) | t;
        }
        if (visible == 0) return;
        Arrays.sort(order, 0, visible);

        float[] xy = new float[visible * 8];
        float[] uv = new float[visible * 8];
        int[] colors = new int[visible];
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        int alphaByte = Math.round(Math.min(1f, alpha) * 255f);
        for (int k = 0; k < visible; k++)
        {
            int t = (int) order[k];
            // Triangles are emitted as quads with the last corner repeated: the GUI pipeline draws quads.
            int[] corners = {indices[t * 3], indices[t * 3 + 1], indices[t * 3 + 2], indices[t * 3 + 2]};
            for (int q = 0; q < 4; q++)
            {
                int vtx = corners[q];
                float x = screen[vtx * 2], y = screen[vtx * 2 + 1];
                xy[k * 8 + q * 2] = x;
                xy[k * 8 + q * 2 + 1] = y;
                uv[k * 8 + q * 2] = mesh.uvs()[vtx * 2];
                uv[k * 8 + q * 2 + 1] = mesh.uvs()[vtx * 2 + 1];
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
            float s = shade[t];
            colors[k] = (alphaByte << 24)
                | (Math.round(((tint >> 16) & 0xFF) * s) << 16)
                | (Math.round(((tint >> 8) & 0xFF) * s) << 8)
                | Math.round((tint & 0xFF) * s);
        }

        Matrix3x2f pose = new Matrix3x2f(graphics.pose());
        ScreenRectangle scissor = graphics.scissorStack.peek();
        graphics.guiRenderState.addGuiElement(new MeshElement(
            pose, xy, uv, colors, visible, texture.getTextureView(), texture.getSampler(), scissor,
            bounds(pose, minX, minY, maxX, maxY, scissor)
        ));
    }

    static @Nullable ScreenRectangle bounds(Matrix3x2f pose, float minX, float minY, float maxX, float maxY, @Nullable ScreenRectangle scissor)
    {
        int x0 = (int) Math.floor(minX), y0 = (int) Math.floor(minY);
        ScreenRectangle box = new ScreenRectangle(x0, y0, Math.max(1, (int) Math.ceil(maxX) - x0), Math.max(1, (int) Math.ceil(maxY) - y0))
            .transformMaxBounds(pose);
        return scissor != null ? box.intersection(scissor) : box;
    }

    /** One draw of a projected mesh: {@code count} quads with per-quad colour. */
    private record MeshElement(Matrix3x2f pose, float[] xy, float[] uv, int[] colors, int count,
                               GpuTextureView view, GpuSampler sampler,
                               @Nullable ScreenRectangle scissorArea, @Nullable ScreenRectangle bounds) implements GuiElementRenderState
    {
        @Override
        public void buildVertices(VertexConsumer consumer)
        {
            for (int k = 0; k < count; k++)
            {
                for (int q = 0; q < 4; q++)
                {
                    int i = k * 8 + q * 2;
                    consumer.addVertexWith2DPose(pose, xy[i], xy[i + 1]).setUv(uv[i], uv[i + 1]).setColor(colors[k]);
                }
            }
        }

        @Override
        public RenderPipeline pipeline()
        {
            return RenderPipelines.GUI_TEXTURED;
        }

        @Override
        public TextureSetup textureSetup()
        {
            return TextureSetup.singleTexture(view, sampler);
        }
    }
}
