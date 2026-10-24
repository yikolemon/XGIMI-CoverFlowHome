package com.yikolemon.xgimihome;

/** Import budgets and centre-crop geometry, independent of Android decoders. */
final class WallpaperImportPlan {
    static final long MAX_SOURCE_PIXELS = 100_000_000L;
    static final long MAX_DECODE_PIXELS = 2_100_000L;
    static final int MAX_FILE_BYTES = 512 * 1024;

    private WallpaperImportPlan() { }

    static int sampleSize(int width, int height) {
        if (width <= 0 || height <= 0 || width > 40000 || height > 40000
                || (long) width * height > MAX_SOURCE_PIXELS) throw new IllegalArgumentException("Image dimensions");
        int sample = 1;
        while ((long) ((width + sample - 1) / sample) * ((height + sample - 1) / sample) > MAX_DECODE_PIXELS)
            sample *= 2;
        return sample;
    }

    /** Never upscale. A large source may be sampled below 1080p to honour the decode budget. */
    static int[] outputSize(int width, int height, boolean quarterTurn) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Image dimensions");
        int w = quarterTurn ? height : width, h = quarterTurn ? width : height;
        double scale = Math.min(1d, Math.min(w / 1920d, h / 1080d));
        return new int[]{Math.max(1, (int) Math.floor(1920 * scale)),
                Math.max(1, (int) Math.floor(1080 * scale))};
    }
}
