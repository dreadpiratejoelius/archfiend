package dev.archfiend.render3d;

import com.mojang.blaze3d.platform.NativeImage;
import dev.archfiend.ArchfiendClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/** Uploads model textures (downscaled when huge) and procedurally generated sprites. Render thread only. */
public final class ModelTextures
{
    /**
     * Downloaded models often ship pixel-art textures blown up to 4096², which costs 64 MB of VRAM for
     * a 64² image. Anything larger is reduced by an integer factor, sampling block centres so pixel
     * art stays exact.
     */
    private static final int MAX_SIZE = 256;

    private ModelTextures() {}

    public static DynamicTexture load(Minecraft minecraft, Identifier png) throws IOException
    {
        NativeImage image;
        try (InputStream in = minecraft.getResourceManager().open(png))
        {
            image = NativeImage.read(in);
        }
        return register(minecraft, "model/" + png.getPath(), downscale(image));
    }

    /**
     * White radial falloff with a soft core, tinted at draw time for glows and shadows.
     *
     * @return the texture's id, for {@code GuiGraphicsExtractor.blit}
     */
    public static Identifier radialGlow(Minecraft minecraft, int size)
    {
        NativeImage image = new NativeImage(size, size, false);
        float center = (size - 1) / 2f;
        for (int y = 0; y < size; y++)
        {
            for (int x = 0; x < size; x++)
            {
                float d = (float) Math.hypot(x - center, y - center) / center;
                float falloff = d >= 1f ? 0f : (1f - d) * (1f - d) * (0.6f + 0.4f * (1f - d));
                image.setPixel(x, y, ARGB.color(Math.round(falloff * 255f), 0xFFFFFF));
            }
        }
        register(minecraft, "generated/glow", image);
        return idFor("generated/glow");
    }

    /** A tiny solid white texture, for meshes that should be coloured purely by vertex tint. */
    public static DynamicTexture white(Minecraft minecraft)
    {
        NativeImage image = new NativeImage(2, 2, false);
        for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) image.setPixel(x, y, 0xFFFFFFFF);
        return register(minecraft, "generated/white", image);
    }

    private static DynamicTexture register(Minecraft minecraft, String name, NativeImage image)
    {
        DynamicTexture texture = new DynamicTexture(() -> ArchfiendClient.MOD_ID + " " + name, image);
        minecraft.getTextureManager().register(idFor(name), texture);
        return texture;
    }

    /** Predictable identifier for a name registered through {@link #register}; used by world render types. */
    public static Identifier idFor(String name)
    {
        return ArchfiendClient.id("dynamic/" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]", "_"));
    }

    private static NativeImage downscale(NativeImage source)
    {
        int largest = Math.max(source.getWidth(), source.getHeight());
        if (largest <= MAX_SIZE) return source;

        int factor = (largest + MAX_SIZE - 1) / MAX_SIZE;
        int width = Math.max(1, source.getWidth() / factor);
        int height = Math.max(1, source.getHeight() / factor);
        NativeImage scaled = new NativeImage(width, height, false);
        int half = factor / 2;
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < width; x++)
            {
                scaled.setPixel(x, y, source.getPixel(Math.min(source.getWidth() - 1, x * factor + half),
                    Math.min(source.getHeight() - 1, y * factor + half)));
            }
        }
        source.close();
        return scaled;
    }
}
