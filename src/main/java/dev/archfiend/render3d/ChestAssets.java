package dev.archfiend.render3d;

import dev.archfiend.ArchfiendClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The chest and question-mark models, normalised for the chest sequence. Loaded lazily on the render
 * thread; a failed load is logged once and the chest style then falls back to the reel.
 * <p>
 * Chest normalisation: the lid is the highest part (by skin joint). The front is whichever side the
 * lid protrudes past the body (the latch). The model is turned so the front faces +z, centred on x/z,
 * stood on y = 0 and scaled to a height of 1. The hinge is the back edge of the lid at the top of the body.
 */
public final class ChestAssets
{
    private static final Identifier CHEST_MODEL = ArchfiendClient.id("models/minecraft_chest/scene.gltf");
    private static final Identifier QUESTION_MODEL = ArchfiendClient.id("models/question_mark/question_mark.obj");
    private static final Identifier QUESTION_TEXTURE = ArchfiendClient.id("models/question_mark/1028817.png");
    private static final int GLOW_SIZE = 128;

    private static Optional<ChestAssets> cached;

    public final Mesh chest;
    public final int lidPart;
    public final float hingeY;
    public final float hingeZ;
    public final DynamicTexture chestTexture;
    /** Texture-manager identifier of {@link #chestTexture}, for world render types. */
    public final Identifier chestTextureId;
    public final Mesh question;
    public final DynamicTexture questionTexture;
    /** Texture-manager identifier of the white tint texture, for world render types. */
    public final Identifier whiteTextureId;
    public final Identifier glow;

    private ChestAssets(Mesh chest, int lidPart, float hingeY, float hingeZ, DynamicTexture chestTexture, Identifier chestTextureId,
                        Mesh question, DynamicTexture questionTexture, Identifier whiteTextureId, Identifier glow)
    {
        this.chest = chest;
        this.lidPart = lidPart;
        this.hingeY = hingeY;
        this.hingeZ = hingeZ;
        this.chestTexture = chestTexture;
        this.chestTextureId = chestTextureId;
        this.question = question;
        this.questionTexture = questionTexture;
        this.whiteTextureId = whiteTextureId;
        this.glow = glow;
    }

    /** Render thread only. */
    public static Optional<ChestAssets> get()
    {
        if (cached == null)
        {
            try
            {
                cached = Optional.of(load(Minecraft.getInstance()));
            }
            catch (IOException | RuntimeException e)
            {
                ArchfiendClient.LOGGER.error("Could not load the chest sequence models; using the reel instead", e);
                cached = Optional.empty();
            }
        }
        return cached;
    }

    private static ChestAssets load(Minecraft minecraft) throws IOException
    {
        Model chestModel = GltfLoader.load(minecraft.getResourceManager(), CHEST_MODEL);
        Mesh raw = chestModel.mesh();

        int lid = highestPart(raw);
        Mesh.Bounds lidBounds = raw.bounds(lid);
        Mesh.Bounds bodyBounds = boundsExcluding(raw, lid);

        // Turn the latch side (where the lid sticks out past the body) towards the camera (+z).
        float[] protrusion = {
            lidBounds.maxX - bodyBounds.maxX,  // front is +x
            bodyBounds.minX - lidBounds.minX,  // front is -x
            lidBounds.maxZ - bodyBounds.maxZ,  // front is +z
            bodyBounds.minZ - lidBounds.minZ   // front is -z
        };
        float[] turn = {(float) -Math.PI / 2, (float) Math.PI / 2, 0f, (float) Math.PI};
        int front = 2;
        for (int i = 0; i < 4; i++) if (protrusion[i] > protrusion[front] + 1e-4f) front = i;
        Mesh turned = raw.transformed(new Matrix4f().rotateY(turn[front]));

        Mesh.Bounds all = turned.bounds(-1);
        float scale = 1f / all.height();
        Mesh chest = turned.transformed(new Matrix4f()
            .scale(scale)
            .translate(-all.centerX(), -all.minY, -all.centerZ()));

        float hingeY = boundsExcluding(chest, lid).maxY;
        float hingeZ = chest.bounds(lid).minZ;

        Model questionModel = ObjLoader.load(minecraft.getResourceManager(), QUESTION_MODEL, QUESTION_TEXTURE);
        Mesh.Bounds q = questionModel.mesh().bounds(-1);
        Mesh question = questionModel.mesh().transformed(new Matrix4f()
            .scale(1f / q.height())
            .translate(-q.centerX(), -q.centerY(), -q.centerZ()));

        return new ChestAssets(
            chest, lid, hingeY, hingeZ,
            ModelTextures.load(minecraft, chestModel.texture()),
            ModelTextures.idFor("model/" + chestModel.texture().getPath()),
            question,
            // Drawn white and tinted with the glow, so it can cycle through every rarity colour.
            ModelTextures.white(minecraft),
            ModelTextures.idFor("generated/white"),
            ModelTextures.radialGlow(minecraft, GLOW_SIZE)
        );
    }

    private static int highestPart(Mesh mesh)
    {
        TreeSet<Integer> parts = new TreeSet<>();
        for (int part : mesh.parts()) parts.add(part);
        if (parts.size() < 2) throw new IllegalStateException("Chest model needs a separate lid part (skin joint)");
        int best = parts.first();
        for (int part : parts)
        {
            if (mesh.bounds(part).centerY() > mesh.bounds(best).centerY()) best = part;
        }
        return best;
    }

    private static Mesh.Bounds boundsExcluding(Mesh mesh, int excludedPart)
    {
        Mesh.Bounds bounds = new Mesh.Bounds();
        float[] p = mesh.positions();
        for (int i = 0; i < mesh.vertexCount(); i++)
        {
            if (mesh.parts()[i] != excludedPart) bounds.include(p[i * 3], p[i * 3 + 1], p[i * 3 + 2]);
        }
        return bounds;
    }
}
