package dev.archfiend.track;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Tracks enemy attacks, deaths, and despawns on the client so that when a drop announcement
 * arrives, the sequence can spawn the world chest on top of where the enemy died.
 */
public final class EnemyDeathTracker
{
    private static final long MAX_AGE_MS = 5_000;
    private static final double MAX_DISTANCE = 48.0;

    public static final class TrackedEntity
    {
        public final int id;
        public Vec3 position;
        public long timeMillis;
        public boolean attackedByPlayer;
        public boolean confirmedDead;
        public boolean wasDamaged;

        TrackedEntity(int id, Vec3 position, long timeMillis, boolean attackedByPlayer, boolean confirmedDead, boolean wasDamaged)
        {
            this.id = id;
            this.position = position;
            this.timeMillis = timeMillis;
            this.attackedByPlayer = attackedByPlayer;
            this.confirmedDead = confirmedDead;
            this.wasDamaged = wasDamaged;
        }
    }

    private final Map<Integer, TrackedEntity> tracked = new HashMap<>();

    public void registerEvents()
    {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClientSide() && entity != player && isValidEnemy(entity))
            {
                recordAttack(entity);
            }
            return InteractionResult.PASS;
        });

        ClientEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity != null && isValidEnemy(entity))
            {
                recordUnload(entity);
            }
        });
    }

    public void tick(Minecraft minecraft)
    {
        long now = System.currentTimeMillis();
        prune(now);

        ClientLevel level = minecraft.level;
        Player player = minecraft.player;
        if (level == null || player == null) return;

        for (Entity entity : level.entitiesForRendering())
        {
            if (entity == player || !isValidEnemy(entity)) continue;
            if (entity instanceof LivingEntity living)
            {
                if (living.hurtTime > 0)
                {
                    recordDamaged(living, now);
                }
                if (living.isDeadOrDying() || living.deathTime > 0 || living.getHealth() <= 0f)
                {
                    recordDeath(living, now);
                }
            }
        }
    }

    /**
     * Finds the most likely enemy death location for a drop announced right now.
     *
     * @param nowMillis current system timestamp in milliseconds
     * @return the world position where the enemy died, or {@code null} if none was found
     */
    public @Nullable Vec3 findRecentDeath(long nowMillis)
    {
        prune(nowMillis);
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        Vec3 playerPos = player != null ? player.position() : null;
        Vec3 lookVec = player != null ? player.getViewVector(1.0f) : null;

        TrackedEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (TrackedEntity entity : tracked.values())
        {
            double dist = playerPos != null ? entity.position.distanceTo(playerPos) : 0.0;
            if (dist > MAX_DISTANCE) continue;

            double ageSec = (nowMillis - entity.timeMillis) / 1000.0;
            if (ageSec < 0.0 || ageSec > 4.5) continue;

            double score = 0.0;
            if (entity.attackedByPlayer && entity.confirmedDead)
            {
                score += 1000.0;
            }
            else if (entity.attackedByPlayer)
            {
                score += 600.0;
            }
            else if (entity.confirmedDead)
            {
                score += 500.0;
            }
            else if (entity.wasDamaged)
            {
                score += 300.0;
            }
            else
            {
                continue;
            }

            if (entity.wasDamaged)
            {
                score += 150.0;
            }

            // High recency bonus: server drop announcements usually follow within 0.5s of mob death
            if (ageSec < 1.0)
            {
                score += (1.0 - ageSec) * 200.0;
            }

            // Direction bonus: mob in the direction the player is looking
            if (lookVec != null && playerPos != null && dist > 0.1)
            {
                Vec3 toEntity = entity.position.subtract(playerPos).normalize();
                double dot = lookVec.dot(toEntity);
                if (dot > 0.0)
                {
                    score += dot * 150.0;
                }
            }

            score -= ageSec * 50.0;
            score -= dist * 2.0;

            if (score > bestScore)
            {
                bestScore = score;
                best = entity;
            }
        }

        return best != null ? best.position : null;
    }

    public void reset()
    {
        tracked.clear();
    }

    private void recordAttack(Entity entity)
    {
        long now = System.currentTimeMillis();
        TrackedEntity existing = tracked.get(entity.getId());
        if (existing != null)
        {
            existing.position = entity.position();
            existing.timeMillis = now;
            existing.attackedByPlayer = true;
        }
        else
        {
            tracked.put(entity.getId(), new TrackedEntity(entity.getId(), entity.position(), now, true, false, false));
        }
    }

    private void recordDamaged(LivingEntity entity, long now)
    {
        TrackedEntity existing = tracked.get(entity.getId());
        if (existing != null)
        {
            existing.position = entity.position();
            existing.timeMillis = now;
            existing.wasDamaged = true;
        }
        else
        {
            tracked.put(entity.getId(), new TrackedEntity(entity.getId(), entity.position(), now, false, false, true));
        }
    }

    private void recordDeath(LivingEntity entity, long now)
    {
        TrackedEntity existing = tracked.get(entity.getId());
        if (existing != null)
        {
            existing.position = entity.position();
            existing.timeMillis = now;
            existing.confirmedDead = true;
        }
        else
        {
            tracked.put(entity.getId(), new TrackedEntity(entity.getId(), entity.position(), now, false, true, false));
        }
    }

    private void recordUnload(Entity entity)
    {
        long now = System.currentTimeMillis();
        TrackedEntity existing = tracked.get(entity.getId());
        if (existing != null)
        {
            existing.position = entity.position();
            existing.timeMillis = now;
            existing.confirmedDead = true;
        }
        else if (entity instanceof LivingEntity living)
        {
            Minecraft mc = Minecraft.getInstance();
            Player player = mc.player;
            if (player != null && entity.position().distanceTo(player.position()) <= MAX_DISTANCE)
            {
                tracked.put(entity.getId(), new TrackedEntity(entity.getId(), entity.position(), now, false, true, false));
            }
        }
    }

    private void prune(long now)
    {
        Iterator<Map.Entry<Integer, TrackedEntity>> it = tracked.entrySet().iterator();
        while (it.hasNext())
        {
            Map.Entry<Integer, TrackedEntity> entry = it.next();
            if (now - entry.getValue().timeMillis > MAX_AGE_MS)
            {
                it.remove();
            }
        }
    }

    private static boolean isValidEnemy(Entity entity)
    {
        return !(entity instanceof Player) && !(entity instanceof ArmorStand);
    }
}
