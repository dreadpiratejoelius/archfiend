package dev.archfiend.sequence;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Pre-simulated physics for the chest falling in: gravity, energy-losing bounces, a spring-damped
 * wobble kicked by each impact, and squash-and-stretch. Simulated once at a fine fixed step and
 * sampled by time, so playback is smooth at any frame rate and identical however ticks land.
 * <p>
 * Units: heights in chest heights; squash is the fractional vertical stretch (negative = squashed).
 */
final class ChestDrop
{
    private static final float STEP = 0.004f;
    private static final float MAX_SECONDS = 3f;
    private static final float START_HEIGHT = 3.6f;
    private static final float GRAVITY = 30f;
    private static final float RESTITUTION = 0.42f;
    private static final float REST_SPEED = 0.9f;

    private static final float TILT_STIFFNESS = 110f;
    private static final float TILT_DAMPING = 7f;
    private static final float SQUASH_STIFFNESS = 320f;
    private static final float SQUASH_DAMPING = 15f;
    /** Falling objects stretch along their motion; this caps it. */
    private static final float MAX_FALL_STRETCH = 0.12f;

    /** A bounce, for sounds and dust. */
    record Impact(float time, float speed) {}

    private final float[] height;
    private final float[] tilt;
    private final float[] squash;
    private final List<Impact> impacts = new ArrayList<>();
    private final float settleTime;

    ChestDrop(RandomGenerator random)
    {
        int samples = (int) (MAX_SECONDS / STEP) + 1;
        height = new float[samples];
        tilt = new float[samples];
        squash = new float[samples];

        float y = START_HEIGHT, vy = 0f;
        float angle = 0f, angularVelocity = 0f;
        float sq = 0f, squashVelocity = 0f;
        boolean resting = false;
        float settled = MAX_SECONDS;

        for (int i = 0; i < samples; i++)
        {
            float time = i * STEP;
            if (!resting)
            {
                vy -= GRAVITY * STEP;
                y += vy * STEP;
                if (y <= 0f && vy < 0f)
                {
                    float speed = -vy;
                    y = 0f;
                    impacts.add(new Impact(time, speed));
                    // Each impact squashes the chest and knocks it off balance in a random direction.
                    squashVelocity -= speed * 0.085f;
                    angularVelocity += (random.nextBoolean() ? 1 : -1) * speed * (0.035f + random.nextFloat() * 0.03f);
                    if (speed * RESTITUTION < REST_SPEED)
                    {
                        resting = true;
                        vy = 0f;
                    }
                    else
                    {
                        vy = speed * RESTITUTION;
                    }
                }
            }

            angularVelocity += (-TILT_STIFFNESS * angle - TILT_DAMPING * angularVelocity) * STEP;
            angle += angularVelocity * STEP;
            squashVelocity += (-SQUASH_STIFFNESS * sq - SQUASH_DAMPING * squashVelocity) * STEP;
            sq = Math.clamp(sq + squashVelocity * STEP, -0.35f, 0.35f);

            height[i] = y;
            tilt[i] = angle;
            squash[i] = sq + (resting ? 0f : Math.min(MAX_FALL_STRETCH, Math.abs(vy) * 0.006f));

            if (resting && settled == MAX_SECONDS && Math.abs(angle) < 0.004f && Math.abs(sq) < 0.004f) settled = time;
        }
        settleTime = Math.min(settled, MAX_SECONDS);
    }

    /** When the chest has stopped moving, in seconds. */
    float settleTime()
    {
        return settleTime;
    }

    float firstImpactTime()
    {
        return impacts.isEmpty() ? settleTime : impacts.getFirst().time();
    }

    List<Impact> impacts()
    {
        return impacts;
    }

    float height(float seconds)
    {
        return sample(height, seconds);
    }

    float tilt(float seconds)
    {
        return sample(tilt, seconds);
    }

    float squash(float seconds)
    {
        return sample(squash, seconds);
    }

    private static float sample(float[] values, float seconds)
    {
        float index = Math.clamp(seconds / STEP, 0f, values.length - 1f);
        int i = (int) index;
        if (i >= values.length - 1) return values[values.length - 1];
        float f = index - i;
        return values[i] + (values[i + 1] - values[i]) * f;
    }
}
