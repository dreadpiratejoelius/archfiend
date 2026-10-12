package dev.archfiend.render3d;

import net.minecraft.resources.Identifier;

/**
 * A loaded mesh together with its base colour texture.
 *
 * @param texture resource location of the texture image (PNG)
 */
public record Model(Mesh mesh, Identifier texture) {}
