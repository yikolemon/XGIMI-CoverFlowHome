package com.gathin.tvhome;

import org.junit.Test;
import static org.junit.Assert.*;

public class WallpaperImportPlanTest {
    @Test public void fullHdFitsWithoutExtraDecode() {
        assertEquals(1, WallpaperImportPlan.sampleSize(1920, 1080));
        assertArrayEquals(new int[]{1920, 1080}, WallpaperImportPlan.outputSize(1920, 1080, false));
    }
    @Test public void largeCameraPhotoIsSampledBeforeDecoding() {
        assertEquals(4, WallpaperImportPlan.sampleSize(6000, 4500));
        assertArrayEquals(new int[]{1500, 843}, WallpaperImportPlan.outputSize(1500, 1125, false));
    }
    @Test public void decodedPixelBudgetHoldsForOddAndPortraitSizes() {
        for (int[] size : new int[][]{{6001,4501},{3840,2160},{12000,8000},{300,10000},{10000,300}}) {
            int sample = WallpaperImportPlan.sampleSize(size[0], size[1]);
            assertTrue((long) ((size[0] + sample - 1) / sample) * ((size[1] + sample - 1) / sample)
                    <= WallpaperImportPlan.MAX_DECODE_PIXELS);
        }
    }
    @Test public void smallImagesAreNeverUpscaledAndOrientationIsRespected() {
        assertArrayEquals(new int[]{640, 360}, WallpaperImportPlan.outputSize(640, 480, false));
        assertArrayEquals(new int[]{640, 360}, WallpaperImportPlan.outputSize(480, 640, true));
        int[] portrait = WallpaperImportPlan.outputSize(1080, 1920, false);
        assertTrue(portrait[0] <= 1080 && portrait[1] <= 1080);
    }
    @Test(expected = IllegalArgumentException.class) public void invalidHeaderIsRejected() {
        WallpaperImportPlan.sampleSize(-1, 0);
    }
    @Test(expected = IllegalArgumentException.class) public void unreasonableSourcesAreRejected() {
        WallpaperImportPlan.sampleSize(20000, 20000);
    }
}
