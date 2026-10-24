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

    static float distance(int index, float position, int count) {
        if (count <= 1) return 0f;
        float value = (index - position) % count;
        if (value > count / 2f) value -= count;
        if (value < -count / 2f) value += count;
        return value;
    }

    static float scale(float distance) {
        return Math.max(0.66f, 1f - Math.min(Math.abs(distance), 2f) * 0.16f);
    }

    static float rotation(float distance) {
        return -Math.max(-1f, Math.min(1f, distance)) * 16f;
    }

    static float offset(float distance, float width) {
        float d = Math.abs(distance);
        return Math.signum(distance) * width * (Math.min(d, 1f) * 1.13f + Math.max(0f, d - 1f) * 0.82f);
    }
}
