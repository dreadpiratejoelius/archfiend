package dev.archfiend.sequence;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises {@link WorldChestBody} against a simple block grid. */
class WorldChestBodyTest
{
    /** Collisions for a world that is solid at or below {@code floorY} and air above it. */
    private static Function<BlockPos, VoxelShape> floorAt(int floorY)
    {
        return pos -> pos.getY() <= floorY ? Shapes.block() : Shapes.empty();
    }

    @Test
    void chestComesToRestOnFloor()
    {
        // The floor block occupies [-1, 0], so its top surface is at y = 0.
        Function<BlockPos, VoxelShape> world = floorAt(-1);
        WorldChestBody body = new WorldChestBody(0.4375, 0.4375, 0.3);
        body.set(0.0, 3.0, 0.0, 0.0, 0.0, 0.0);

        double dt = 1.0 / 160.0;
        for (int i = 0; i < 160 * 4; i++) body.step(world, dt); // four simulated seconds

        assertTrue(body.sleeping, "chest should sleep after landing");
        // Centre is half-height above the floor top (y = 0).
        assertTrue(body.py >= 0.43 && body.py <= 0.46, "chest should rest on the floor, but py=" + body.py);
    }
}
