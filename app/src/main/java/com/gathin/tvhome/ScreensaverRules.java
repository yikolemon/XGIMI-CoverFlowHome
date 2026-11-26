package com.gathin.tvhome;

import java.util.Locale;

/** Remote/timing/selection rules independent of Android, for deterministic regression tests. */
final class ScreensaverRules {
    static final long UP_WINDOW_MS = 2000, BACK_WINDOW_MS = 600;
    static final int MAX_VIDEOS = 20;
    static final int MAX_FOLDER_ENTRIES = 1000;
    static final int DEFAULT_VOLUME_PERCENT = 100;
    static final int[] TIMEOUT_MINUTES = {0, 1, 5, 10, 15, 30, 60};
    static final int[] VOLUME_PERCENTS = {0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100};

    private ScreensaverRules() { }

    static int clampVolumePercent(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    static int adjustVolumePercent(int current, int delta) {
        return clampVolumePercent(current + delta);
    }

    static float playerVolume(int percent) {
        return clampVolumePercent(percent) / 100f;
    }

    static long idleDelay(long lastInput, long now, int minutes, boolean active, boolean hasVideos) {
        if (!active || !hasVideos || minutes <= 0) return -1;
        long timeout = Math.min(60, minutes) * 60_000L;
        long elapsed = now >= lastInput ? now - lastInput : 0;
        return Math.max(0, timeout - elapsed);
    }

    static boolean videoName(String name) {
        if (name == null || name.contains("/") || name.contains("\\") || name.startsWith(".")) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".webm")
                || lower.endsWith(".mkv") || lower.endsWith(".3gp") || lower.endsWith(".mov");
    }

    static int chooserIndex(int current, int delta, int count) {
        if (count <= 0) return 0;
        return (int) Math.max(0L, Math.min(count - 1L, (long) current + delta));
    }

    static boolean shouldRestartVideo(int next, int playing, boolean ready) {
        return next != playing || !ready;
    }

    static final class UpSequence {
        private long first = -1;
        private int count;
        boolean press(long now) {
            if (first < 0 || now < first || now - first > UP_WINDOW_MS) { first = now; count = 0; }
            if (++count < 3) return false;
            reset();
            return true;
        }
        void reset() { first = -1; count = 0; }
    }

    static final class DoubleBack {
        private long first = -1;
        boolean press(long now) {
            if (first >= 0 && now >= first && now - first <= BACK_WINDOW_MS) { reset(); return true; }
            first = now;
            return false;
        }
        void reset() { first = -1; }
    }
}
