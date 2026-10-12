package dev.archfiend.sequence;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Client-side rigid-body simulation of the falling chest: a solid box with a quaternion orientation,
 * gravity and impulse-based contact against the block grid's collision shapes. The eight corners are
 * used as contact probes, so the box can come to rest at any tilt rather than snapped to the grid.
 * <p>
 * Angular velocity is kept in the body frame; linear velocity and position are world-space.
 */
final class WorldChestBody
{
    private static final double GRAVITY = 28.0;
    private static final double RESTITUTION = 0.35;
    private static final double FRICTION = 0.6;
    private static final double LINEAR_DAMP = 0.05;
    private static final double ANGULAR_DAMP = 1.0;
    /** Fraction of angular velocity retained on a contact; dissipates the tumble so the box settles. */
    private static final double IMPACT_ANGULAR_DAMP = 0.6;
    /** Anti-tunnelling speed caps, blocks/s and rad/s. */
    private static final double MAX_SPEED = 40.0;
    private static final double MAX_ANGULAR = 12.0;
    private static final double SLEEP_LINEAR = 0.3;
    private static final double SLEEP_ANGULAR = 0.5;
    private static final double SLEEP_SECONDS = 0.3;
    private static final double POSITION_CORRECTION = 0.85;
    private static final double SLOP = 0.002;

    private final double hx;
    private final double hy;
    private final double hz;
    private final double invIxx;
    private final double invIyy;
    private final double invIzz;

    /** Box centre, world space. */
    double px, py, pz;
    /** Linear velocity, blocks/s. */
    double vx, vy, vz;
    /** Angular velocity, body frame, rad/s. */
    double wx, wy, wz;
    final Quaternionf orient = new Quaternionf();

    boolean sleeping;
    private double sleepClock;
    /** Approaching speed (blocks/s) of the hardest contact since it was last reset; -1 when none. */
    double lastImpact = -1.0;

    private final Vector3d a = new Vector3d();
    private final Vector3d b = new Vector3d();
    private final Vector3d c = new Vector3d();

    WorldChestBody(double hx, double hy, double hz)
    {
        this.hx = hx;
        this.hy = hy;
        this.hz = hz;
        // Inertia of a solid box about its centre (mass 1): I = (h_j² + h_k²) / 3 per axis.
        this.invIxx = 3.0 / (hy * hy + hz * hz);
        this.invIyy = 3.0 / (hx * hx + hz * hz);
        this.invIzz = 3.0 / (hx * hx + hy * hy);
    }

    void set(double px, double py, double pz, double wx, double wy, double wz)
    {
        this.px = px;
        this.py = py;
        this.pz = pz;
        this.wx = wx;
        this.wy = wy;
        this.wz = wz;
    }

    /**
     * One fixed substep of {@code dt} seconds.
     *
     * @param collisions maps a block position to its collision shape
     */
    void step(Function<BlockPos, VoxelShape> collisions, double dt)
    {
        if (sleeping) return;

        vy -= GRAVITY * dt;
        vx *= 1.0 - LINEAR_DAMP * dt;
        vz *= 1.0 - LINEAR_DAMP * dt;
        wx *= 1.0 - ANGULAR_DAMP * dt;
        wy *= 1.0 - ANGULAR_DAMP * dt;
        wz *= 1.0 - ANGULAR_DAMP * dt;
        clampSpeed();

        px += vx * dt;
        py += vy * dt;
        pz += vz * dt;
        orient.integrate((float) dt, (float) wx, (float) wy, (float) wz).normalize();

        List<Contact> contacts = collide(collisions);
        resolvePosition(contacts);
        for (Contact contact : contacts) resolveVelocity(contact);

        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        double ang = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (speed < SLEEP_LINEAR && ang < SLEEP_ANGULAR)
        {
            sleepClock += dt;
            if (sleepClock >= SLEEP_SECONDS) sleep();
        }
        else
        {
            sleepClock = 0.0;
        }
    }

    private void sleep()
    {
        sleeping = true;
        vx = vy = vz = 0.0;
        wx = wy = wz = 0.0;
    }

    private void clampSpeed()
    {
        double speed = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (speed > MAX_SPEED)
        {
            double s = MAX_SPEED / speed;
            vx *= s;
            vy *= s;
            vz *= s;
        }
        double ang = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (ang > MAX_ANGULAR)
        {
            double s = MAX_ANGULAR / ang;
            wx *= s;
            wy *= s;
            wz *= s;
        }
    }

    /** The eight corners in world space. */
    private void corners(double[][] out)
    {
        for (int i = 0; i < 8; i++)
        {
            double lx = (i & 1) == 0 ? -hx : hx;
            double ly = (i & 2) == 0 ? -hy : hy;
            double lz = (i & 4) == 0 ? -hz : hz;
            orient.transform(a.set(lx, ly, lz));
            out[i][0] = px + a.x;
            out[i][1] = py + a.y;
            out[i][2] = pz + a.z;
        }
    }

    /** Finds penetrating corners against the block collision shapes. */
    private List<Contact> collide(Function<BlockPos, VoxelShape> collisions)
    {
        double[][] corners = new double[8][3];
        corners(corners);

        double minX = Double.POSITIVE_INFINITY, minY = minX, minZ = minX;
        double maxX = Double.NEGATIVE_INFINITY, maxY = maxX, maxZ = maxX;
        for (double[] corner : corners)
        {
            minX = Math.min(minX, corner[0]);
            minY = Math.min(minY, corner[1]);
            minZ = Math.min(minZ, corner[2]);
            maxX = Math.max(maxX, corner[0]);
            maxY = Math.max(maxY, corner[1]);
            maxZ = Math.max(maxZ, corner[2]);
        }

        List<Contact> contacts = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int bx = floor(minX); bx <= floor(maxX); bx++)
        {
            for (int by = floor(minY); by <= floor(maxY); by++)
            {
                for (int bz = floor(minZ); bz <= floor(maxZ); bz++)
                {
                    pos.set(bx, by, bz);
                    VoxelShape shape = collisions.apply(pos);
                    if (shape.isEmpty()) continue;
                    for (AABB box : shape.toAabbs())
                    {
                        AABB world = box.move(bx, by, bz);
                        for (double[] corner : corners)
                        {
                            if (!world.contains(corner[0], corner[1], corner[2])) continue;
                            contacts.add(contact(world, corner[0], corner[1], corner[2]));
                            break; // one containing box per corner is enough
                        }
                    }
                }
            }
        }
        return contacts;
    }

    /** Contact normal and depth for a corner inside a block AABB; the normal is the closest face. */
    private Contact contact(AABB box, double x, double y, double z)
    {
        double dxMin = x - box.minX, dxMax = box.maxX - x;
        double dyMin = y - box.minY, dyMax = box.maxY - y;
        double dzMin = z - box.minZ, dzMax = box.maxZ - z;

        double depth = dxMin, nx = -1.0, ny = 0.0, nz = 0.0;
        if (dxMax < depth) { depth = dxMax; nx = 1.0; ny = 0.0; nz = 0.0; }
        if (dyMin < depth) { depth = dyMin; nx = 0.0; ny = -1.0; nz = 0.0; }
        if (dyMax < depth) { depth = dyMax; nx = 0.0; ny = 1.0; nz = 0.0; }
        if (dzMin < depth) { depth = dzMin; nx = 0.0; ny = 0.0; nz = -1.0; }
        if (dzMax < depth) { depth = dzMax; nx = 0.0; ny = 0.0; nz = 1.0; }

        return new Contact(nx, ny, nz, depth, x - px, y - py, z - pz);
    }

    /** Pushes the body out of the deepest contact along its normal. */
    private void resolvePosition(List<Contact> contacts)
    {
        if (contacts.isEmpty()) return;
        Contact deepest = contacts.get(0);
        for (int i = 1; i < contacts.size(); i++)
        {
            if (contacts.get(i).depth > deepest.depth) deepest = contacts.get(i);
        }
        double push = (deepest.depth + SLOP) * POSITION_CORRECTION;
        px += deepest.nx * push;
        py += deepest.ny * push;
        pz += deepest.nz * push;
    }

    /** Impulse response (restitution + Coulomb friction) at a contact. */
    private void resolveVelocity(Contact contact)
    {
        b.set(contact.nx, contact.ny, contact.nz); // normal
        c.set(contact.rx, contact.ry, contact.rz); // contact point from centre

        orient.transform(a.set(wx, wy, wz));       // world angular velocity
        a.cross(c, a);                             // w × r
        a.add(vx, vy, vz);                         // contact velocity
        double vn = a.dot(b);
        if (vn >= 0.0) return;                     // separating or resting
        if (vn < -1.0) lastImpact = Math.max(lastImpact, -vn);

        double denom = 1.0 + angularInvMass(b, c);
        double j = -(1.0 + RESTITUTION) * vn / denom;
        b.mul(j, a);                               // normal impulse jn
        vx += a.x;
        vy += a.y;
        vz += a.z;
        applyAngularImpulse(c, a);
        // Dissipate the tumble on contact; the sequential solver otherwise slowly injects angular energy.
        wx *= IMPACT_ANGULAR_DAMP;
        wy *= IMPACT_ANGULAR_DAMP;
        wz *= IMPACT_ANGULAR_DAMP;

        // Coulomb friction against the tangential contact velocity.
        orient.transform(a.set(wx, wy, wz));
        a.cross(c, a);
        a.add(vx, vy, vz);
        double normalSpeed = a.dot(b);
        double tangentialX = a.x - b.x * normalSpeed;
        double tangentialY = a.y - b.y * normalSpeed;
        double tangentialZ = a.z - b.z * normalSpeed;
        double length = Math.sqrt(tangentialX * tangentialX + tangentialY * tangentialY + tangentialZ * tangentialZ);
        if (length < 1e-6) return;
        double jt = Math.min(length, FRICTION * j);
        a.set(-tangentialX / length * jt, -tangentialY / length * jt, -tangentialZ / length * jt);
        vx += a.x;
        vy += a.y;
        vz += a.z;
        applyAngularImpulse(c, a);
    }

    /** {@code n · ((I_world⁻¹ (r × n)) × r)}, the angular term of the effective inverse mass. */
    private double angularInvMass(Vector3d n, Vector3d r)
    {
        r.cross(n, a);                              // r × n (world)
        orient.transformInverse(a);                 // Rᵀ(r × n)
        a.mul(invIxx, invIyy, invIzz);              // I_body⁻¹
        orient.transform(a);                        // I_world⁻¹(r × n)
        a.cross(r, a);                              // × r
        return n.dot(a);
    }

    private void applyAngularImpulse(Vector3d r, Vector3d impulse)
    {
        r.cross(impulse, a);                        // r × j (world)
        orient.transformInverse(a);                 // Rᵀ
        a.mul(invIxx, invIyy, invIzz);              // I_body⁻¹
        wx += a.x;
        wy += a.y;
        wz += a.z;
    }

    private static int floor(double value)
    {
        return (int) Math.floor(value);
    }

    /** A penetrating corner: outward normal, penetration depth and contact point from the centre. */
    private record Contact(double nx, double ny, double nz, double depth, double rx, double ry, double rz) {}
}
