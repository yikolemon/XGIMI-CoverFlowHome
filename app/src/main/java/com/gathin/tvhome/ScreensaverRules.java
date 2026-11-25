package com.gathin.tvhome;

import java.util.Locale;

/** Remote/timing/budget rules independent of Android, for deterministic regression tests. */
final class ScreensaverRules {
    static final long UP_WINDOW_MS = 2000, BACK_WINDOW_MS = 600;
    static final int MAX_VIDEOS = 20;
    static final long MAX_VIDEO_BYTES = 512L * 1024 * 1024;
    static final long MAX_BATCH_BYTES = 2L * 1024 * 1024 * 1024;
    static final long RESERVED_BYTES = 64L * 1024 * 1024;
    static final int[] TIMEOUT_MINUTES = {0, 1, 5, 10, 15, 30, 60};

    private ScreensaverRules() { }

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

    static boolean withinBudget(long videoBytes, long batchBytes, long freeBytes) {
        return videoBytes > 0 && videoBytes <= MAX_VIDEO_BYTES && batchBytes >= videoBytes
                && batchBytes <= MAX_BATCH_BYTES && freeBytes >= RESERVED_BYTES;
    }

    static String importName(String name) {
        if (name == null) throw new IllegalArgumentException("Missing video name");
        String safe = name.replaceAll("[\\p{Cntrl}/\\\\]", "_");
        if (!videoName(safe)) throw new IllegalArgumentException("Unsupported video extension");
        // Bound UTF-8 filename bytes too: e.g. 100 Chinese characters exceed Android's 255-byte limit.
        int end = safe.length() > 70 && Character.isHighSurrogate(safe.charAt(59)) ? 59 : 60;
        return safe.length() <= 70 ? safe : safe.substring(0, end) + safe.substring(safe.lastIndexOf('.'));
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
