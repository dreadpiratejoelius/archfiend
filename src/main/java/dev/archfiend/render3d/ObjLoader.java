package dev.archfiend.render3d;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Wavefront OBJ loader: positions, texture coordinates and polygonal faces (fan-triangulated).
 * Materials are ignored; the texture is supplied by the caller. All vertices are part 0.
 */
public final class ObjLoader
{
    private ObjLoader() {}

    public static Model load(ResourceProvider resources, Identifier obj, Identifier texture) throws IOException
    {
        List<float[]> positions = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        GltfLoader.MeshBuilder builder = new GltfLoader.MeshBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resources.open(obj), StandardCharsets.UTF_8)))
        {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null)
            {
                lineNumber++;
                String[] tokens = line.trim().split("\\s+");
                try
                {
                    switch (tokens[0])
                    {
                        case "v" -> positions.add(new float[] {parse(tokens[1]), parse(tokens[2]), parse(tokens[3])});
                        // OBJ puts the uv origin bottom-left; Minecraft textures are top-left.
                        case "vt" -> uvs.add(new float[] {parse(tokens[1]), 1f - parse(tokens[2])});
                        case "f" -> face(tokens, positions, uvs, builder);
                        default -> { /* normals, groups, materials, comments: not needed */ }
                    }
                }
                catch (RuntimeException e)
                {
                    throw new IOException("Malformed OBJ " + obj + " at line " + lineNumber + ": " + line, e);
                }
            }
        }

        if (builder.indices.isEmpty()) throw new IOException("OBJ " + obj + " contains no faces");
        return new Model(builder.build(), texture);
    }

    private static void face(String[] tokens, List<float[]> positions, List<float[]> uvs, GltfLoader.MeshBuilder builder)
    {
        int first = builder.vertexCount();
        int corners = tokens.length - 1;
        for (int c = 1; c <= corners; c++)
        {
            String[] refs = tokens[c].split("/");
            float[] p = positions.get(index(refs[0], positions.size()));
            float[] t = refs.length > 1 && !refs[1].isEmpty() ? uvs.get(index(refs[1], uvs.size())) : new float[2];
            builder.vertex(p[0], p[1], p[2], t[0], t[1], 0);
        }
        for (int c = 1; c + 1 < corners; c++)
        {
            builder.indices.add(first);
            builder.indices.add(first + c);
            builder.indices.add(first + c + 1);
        }
    }

    /** OBJ indices are 1-based; negative values count back from the end. */
    private static int index(String raw, int size)
    {
        int value = Integer.parseInt(raw);
        return value < 0 ? size + value : value - 1;
    }

    private static float parse(String raw)
    {
        return Float.parseFloat(raw);
    }
}
