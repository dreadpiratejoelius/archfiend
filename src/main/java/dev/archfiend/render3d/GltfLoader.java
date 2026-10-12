package dev.archfiend.render3d;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Minimal glTF 2.0 loader for small, static props: triangle primitives, node hierarchies and
 * skinned meshes in their bind pose. Each vertex is tagged with a part id (the dominant skin joint,
 * or the mesh node's ordinal for unskinned models) so rigid sub-parts can be animated by hand.
 * Only the base colour texture of the first material is used.
 */
public final class GltfLoader
{
    private static final int TRIANGLES = 4;
    private static final int FLOAT = 5126, UNSIGNED_BYTE = 5121, UNSIGNED_SHORT = 5123, UNSIGNED_INT = 5125;

    private GltfLoader() {}

    public static Model load(ResourceProvider resources, Identifier gltf) throws IOException
    {
        JsonObject root;
        try (Reader reader = new InputStreamReader(resources.open(gltf), StandardCharsets.UTF_8))
        {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        }
        Context ctx = new Context(root, readBuffers(resources, gltf, root));

        MeshBuilder builder = new MeshBuilder();
        JsonArray nodes = root.getAsJsonArray("nodes");
        JsonArray sceneNodes = root.getAsJsonArray("scenes").get(root.has("scene") ? root.get("scene").getAsInt() : 0)
            .getAsJsonObject().getAsJsonArray("nodes");
        int[] meshOrdinal = {0};
        for (JsonElement node : sceneNodes) visit(ctx, nodes, node.getAsInt(), new Matrix4f(), builder, meshOrdinal);

        if (builder.indices.isEmpty()) throw new IOException("glTF " + gltf + " contains no triangles");
        return new Model(builder.build(), resolve(gltf, baseColorImage(root)));
    }

    private static void visit(Context ctx, JsonArray nodes, int index, Matrix4f parent, MeshBuilder builder, int[] meshOrdinal) throws IOException
    {
        JsonObject node = nodes.get(index).getAsJsonObject();
        Matrix4f global = new Matrix4f(parent).mul(localTransform(node));

        if (node.has("mesh"))
        {
            // Skinned vertices are already in bind-pose model space; glTF says to ignore the node transform.
            boolean skinned = node.has("skin");
            JsonObject mesh = ctx.root.getAsJsonArray("meshes").get(node.get("mesh").getAsInt()).getAsJsonObject();
            for (JsonElement primitive : mesh.getAsJsonArray("primitives"))
            {
                addPrimitive(ctx, primitive.getAsJsonObject(), skinned ? null : global, meshOrdinal[0], builder);
            }
            meshOrdinal[0]++;
        }
        if (node.has("children"))
        {
            for (JsonElement child : node.getAsJsonArray("children")) visit(ctx, nodes, child.getAsInt(), global, builder, meshOrdinal);
        }
    }

    private static void addPrimitive(Context ctx, JsonObject primitive, Matrix4f transform, int ordinal, MeshBuilder builder) throws IOException
    {
        if (primitive.has("mode") && primitive.get("mode").getAsInt() != TRIANGLES) return;
        JsonObject attributes = primitive.getAsJsonObject("attributes");
        if (!attributes.has("POSITION")) return;

        float[] positions = ctx.floats(attributes.get("POSITION").getAsInt());
        int count = positions.length / 3;
        float[] uvs = attributes.has("TEXCOORD_0") ? ctx.floats(attributes.get("TEXCOORD_0").getAsInt()) : new float[count * 2];
        float[] joints = attributes.has("JOINTS_0") ? ctx.floats(attributes.get("JOINTS_0").getAsInt()) : null;
        float[] weights = attributes.has("WEIGHTS_0") ? ctx.floats(attributes.get("WEIGHTS_0").getAsInt()) : null;

        int base = builder.vertexCount();
        Vector3f v = new Vector3f();
        for (int i = 0; i < count; i++)
        {
            v.set(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
            if (transform != null) transform.transformPosition(v);
            int part = joints != null && weights != null ? dominantJoint(joints, weights, i) : ordinal;
            builder.vertex(v.x, v.y, v.z, uvs[i * 2], uvs[i * 2 + 1], part);
        }

        if (primitive.has("indices"))
        {
            for (float index : ctx.floats(primitive.get("indices").getAsInt())) builder.indices.add(base + (int) index);
        }
        else
        {
            for (int i = 0; i < count; i++) builder.indices.add(base + i);
        }
    }

    private static int dominantJoint(float[] joints, float[] weights, int vertex)
    {
        int best = 0;
        for (int k = 1; k < 4; k++)
        {
            if (weights[vertex * 4 + k] > weights[vertex * 4 + best]) best = k;
        }
        return (int) joints[vertex * 4 + best];
    }

    private static Matrix4f localTransform(JsonObject node)
    {
        if (node.has("matrix"))
        {
            JsonArray m = node.getAsJsonArray("matrix");
            float[] values = new float[16];
            for (int i = 0; i < 16; i++) values[i] = m.get(i).getAsFloat();
            return new Matrix4f().set(values); // glTF and JOML are both column-major
        }
        Matrix4f local = new Matrix4f();
        if (node.has("translation"))
        {
            JsonArray t = node.getAsJsonArray("translation");
            local.translate(t.get(0).getAsFloat(), t.get(1).getAsFloat(), t.get(2).getAsFloat());
        }
        if (node.has("rotation"))
        {
            JsonArray r = node.getAsJsonArray("rotation");
            local.rotate(new Quaternionf(r.get(0).getAsFloat(), r.get(1).getAsFloat(), r.get(2).getAsFloat(), r.get(3).getAsFloat()));
        }
        if (node.has("scale"))
        {
            JsonArray s = node.getAsJsonArray("scale");
            local.scale(s.get(0).getAsFloat(), s.get(1).getAsFloat(), s.get(2).getAsFloat());
        }
        return local;
    }

    private static String baseColorImage(JsonObject root) throws IOException
    {
        try
        {
            JsonObject material = root.getAsJsonArray("materials").get(0).getAsJsonObject();
            int texture = material.getAsJsonObject("pbrMetallicRoughness").getAsJsonObject("baseColorTexture").get("index").getAsInt();
            int image = root.getAsJsonArray("textures").get(texture).getAsJsonObject().get("source").getAsInt();
            return root.getAsJsonArray("images").get(image).getAsJsonObject().get("uri").getAsString();
        }
        catch (RuntimeException e)
        {
            throw new IOException("glTF has no base colour texture", e);
        }
    }

    private static ByteBuffer[] readBuffers(ResourceProvider resources, Identifier gltf, JsonObject root) throws IOException
    {
        JsonArray buffers = root.getAsJsonArray("buffers");
        ByteBuffer[] result = new ByteBuffer[buffers.size()];
        for (int i = 0; i < result.length; i++)
        {
            String uri = buffers.get(i).getAsJsonObject().get("uri").getAsString();
            byte[] bytes;
            if (uri.startsWith("data:"))
            {
                bytes = Base64.getDecoder().decode(uri.substring(uri.indexOf(',') + 1));
            }
            else
            {
                try (InputStream in = resources.open(resolve(gltf, uri)))
                {
                    bytes = in.readAllBytes();
                }
            }
            result[i] = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        }
        return result;
    }

    /** Resolves a URI relative to the glTF file's folder. */
    static Identifier resolve(Identifier relativeTo, String uri) throws IOException
    {
        String path = relativeTo.getPath();
        String folder = path.contains("/") ? path.substring(0, path.lastIndexOf('/') + 1) : "";
        // Resource paths must be lowercase; exporters often write mixed-case file names into the glTF.
        Identifier resolved = Identifier.tryBuild(relativeTo.getNamespace(), folder + uri.toLowerCase(Locale.ROOT));
        if (resolved == null) throw new IOException("Invalid resource path '" + uri + "' in " + relativeTo);
        return resolved;
    }

    private record Context(JsonObject root, ByteBuffer[] buffers)
    {
        /** Reads any accessor as a flat float array (integers are converted). */
        float[] floats(int accessorIndex) throws IOException
        {
            JsonObject accessor = root.getAsJsonArray("accessors").get(accessorIndex).getAsJsonObject();
            JsonObject view = root.getAsJsonArray("bufferViews").get(accessor.get("bufferView").getAsInt()).getAsJsonObject();
            ByteBuffer buffer = buffers[view.get("buffer").getAsInt()];

            int components = switch (accessor.get("type").getAsString())
            {
                case "SCALAR" -> 1;
                case "VEC2" -> 2;
                case "VEC3" -> 3;
                case "VEC4" -> 4;
                case "MAT4" -> 16;
                default -> throw new IOException("Unsupported accessor type " + accessor.get("type"));
            };
            int componentType = accessor.get("componentType").getAsInt();
            int size = switch (componentType)
            {
                case FLOAT, UNSIGNED_INT -> 4;
                case UNSIGNED_SHORT -> 2;
                case UNSIGNED_BYTE -> 1;
                default -> throw new IOException("Unsupported component type " + componentType);
            };
            int count = accessor.get("count").getAsInt();
            int start = (view.has("byteOffset") ? view.get("byteOffset").getAsInt() : 0)
                + (accessor.has("byteOffset") ? accessor.get("byteOffset").getAsInt() : 0);
            int stride = view.has("byteStride") ? view.get("byteStride").getAsInt() : components * size;

            float[] out = new float[count * components];
            for (int i = 0; i < count; i++)
            {
                for (int c = 0; c < components; c++)
                {
                    int at = start + i * stride + c * size;
                    out[i * components + c] = switch (componentType)
                    {
                        case FLOAT -> buffer.getFloat(at);
                        case UNSIGNED_INT -> Integer.toUnsignedLong(buffer.getInt(at));
                        case UNSIGNED_SHORT -> Short.toUnsignedInt(buffer.getShort(at));
                        default -> Byte.toUnsignedInt(buffer.get(at));
                    };
                }
            }
            return out;
        }
    }

    static final class MeshBuilder
    {
        final List<Float> positions = new ArrayList<>();
        final List<Float> uvs = new ArrayList<>();
        final List<Integer> parts = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();

        int vertexCount()
        {
            return parts.size();
        }

        void vertex(float x, float y, float z, float u, float v, int part)
        {
            positions.add(x);
            positions.add(y);
            positions.add(z);
            uvs.add(u);
            uvs.add(v);
            parts.add(part);
        }

        Mesh build()
        {
            float[] p = new float[positions.size()];
            for (int i = 0; i < p.length; i++) p[i] = positions.get(i);
            float[] t = new float[uvs.size()];
            for (int i = 0; i < t.length; i++) t[i] = uvs.get(i);
            return new Mesh(p, t, parts.stream().mapToInt(Integer::intValue).toArray(), indices.stream().mapToInt(Integer::intValue).toArray());
        }
    }
}
