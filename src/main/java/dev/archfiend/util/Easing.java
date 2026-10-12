package dev.archfiend.util;

/** Easing curves over {@code t ∈ [0, 1]}. Inputs outside the range are clamped. */
public final class Easing
{
    private Easing() {}

    public static float clamp01(float t)
    {
        return t < 0f ? 0f : (t > 1f ? 1f : t);
    }

    public static float outCubic(float t)
    {
        float a = 1f - clamp01(t);
        return 1f - a * a * a;
    }

    public static float outQuart(float t)
    {
        float a = 1f - clamp01(t);
        return 1f - a * a * a * a;
    }

    /** Overshoots past 1 before settling; {@code overshoot} ≈ 1.7 gives the classic ~10% overshoot. */
    public static float outBack(float t, float overshoot)
    {
        float a = clamp01(t) - 1f;
        float c3 = overshoot + 1f;
        return 1f + c3 * a * a * a + overshoot * a * a;
    }
}
