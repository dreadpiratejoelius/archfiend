package dev.archfiend.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * An indexed triangle mesh.
 *
 * @param positions xyz per vertex
 * @param uvs       uv per vertex, origin top-left like Minecraft textures
 * @param parts     a part id per vertex (e.g. the dominant skin joint), so sub-meshes like a lid can
 *                  be transformed independently
 * @param indices   three vertex indices per triangle
 */
public record Mesh(float[] positions, float[] uvs, int[] parts, int[] indices)
{
    public int vertexCount()
    {
        return positions.length / 3;
    }

    public int triangleCount()
    {
        return indices.length / 3;
    }

    /** Applies {@code transform} to every vertex position, returning a new mesh. */
    public Mesh transformed(Matrix4f transform)
    {
        float[] moved = new float[positions.length];
        Vector3f v = new Vector3f();
        for (int i = 0; i < vertexCount(); i++)
        {
            transform.transformPosition(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2], v);
            moved[i * 3] = v.x;
            moved[i * 3 + 1] = v.y;
            moved[i * 3 + 2] = v.z;
        }
        return new Mesh(moved, uvs, parts, indices);
    }

    /** Axis-aligned bounds of the vertices belonging to {@code part}, or of all vertices if {@code part < 0}. */
    public Bounds bounds(int part)
    {
        Bounds bounds = new Bounds();
        for (int i = 0; i < vertexCount(); i++)
        {
            if (part >= 0 && parts[i] != part) continue;
            bounds.include(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
        }
        return bounds;
    }

    public static final class Bounds
    {
        public float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        public float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;

        void include(float x, float y, float z)
        {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        public boolean isEmpty()
        {
            return minX > maxX;
        }

        public float centerX()
        {
            return (minX + maxX) / 2f;
        }

        public float centerY()
        {
            return (minY + maxY) / 2f;
        }

        public float centerZ()
        {
            return (minZ + maxZ) / 2f;
        }

        public float height()
        {
            return maxY - minY;
        }
    }
}
