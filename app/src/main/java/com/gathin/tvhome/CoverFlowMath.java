package com.gathin.tvhome;

/** Pure carousel geometry, independent of Android and screen resolution. */
final class CoverFlowMath {
    private CoverFlowMath() { }

    static int clamp(int selection, int count) {
        return count <= 0 ? 0 : Math.max(0, Math.min(selection, count - 1));
    }

    static int index(int position, int count) {
        return count <= 0 ? 0 : Math.floorMod(position, count);
    }

    /** Visible artwork is prepared centre-first, with no duplicate work for small carousels. */
    static int[] visibleIndices(int selection, int count) {
        int[] result = new int[Math.min(5, Math.max(0, count))];
        int size = 0;
        for (int offset : new int[]{0, -1, 1, -2, 2}) {
            if (size == result.length) break;
            int candidate = index(selection + offset, count);
            boolean duplicate = false;
            for (int i = 0; i < size; i++) if (result[i] == candidate) duplicate = true;
            if (!duplicate) result[size++] = candidate;
        }
        return result;
    }

    static float distance(int index, float position, int count) {
        if (count <= 1) return 0f;
        float value = (index - position) % count;
        if (value > count / 2f) value -= count;
        if (value < -count / 2f) value += count;
        return value;
    }

    static float scale(float distance) {
        float d = Math.abs(distance);
        return 0.66f + 0.34f / (1f + 0.72f * d * d);
    }

    static float rotation(float distance) {
        float u = Math.max(-1f, Math.min(1f, distance / 1.25f));
        float smooth = u * (1.5f - 0.5f * u * u);
        return -smooth * 16f;
    }

    static float offset(float distance, float width) {
        float d = Math.abs(distance);
        return distance * width * (0.82f + 0.44f / (1f + 0.42f * d * d));
    }

    /** Velocity-preserving cubic Hermite step; settles at target with zero velocity in {@code duration}. */
    static long stepHermite(float startPos, float startVel, float target, float elapsed, float duration, float[] out) {
        if (elapsed >= duration || duration <= 0f) {
            out[0] = target;
            out[1] = 0f;
            return 0L;
        }
        float u = Math.max(0f, elapsed / duration);
        float delta = target - startPos;
        float m0 = startVel * duration;
        // Clamp entry tangent so rapid same-direction taps keep momentum without overshooting.
        if (delta > 0f && m0 > 2.5f * delta) m0 = 2.5f * delta;
        else if (delta < 0f && m0 < 2.5f * delta) m0 = 2.5f * delta;
        else if (Math.abs(m0) > 1.8f) m0 = Math.copySign(1.8f, m0);
        float u2 = u * u;
        float u3 = u2 * u;
        out[0] = startPos + delta * (3f * u2 - 2f * u3) + m0 * (u3 - 2f * u2 + u);
        out[1] = (6f * delta * u * (1f - u) + m0 * (3f * u2 - 4f * u + 1f)) / duration;
        return 1L;
    }
}
