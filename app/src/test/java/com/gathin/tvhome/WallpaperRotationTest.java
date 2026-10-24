package com.gathin.tvhome;

import org.junit.Test;
import java.io.File;
import java.util.Arrays;
import java.util.Random;
import static org.junit.Assert.*;

public class WallpaperRotationTest {
    @Test public void defaultRotationIntervalIsThirtyMinutes() {
        assertEquals(30, Config.DEFAULT_WALLPAPER_ROTATION_MINUTES);
        assertEquals(30 * 60_000L, Config.DEFAULT_WALLPAPER_ROTATION_MINUTES * 60_000L);
    }

    @Test public void rotationBecomesDueAtThirtyMinuteBoundary() {
        long start = 10_000L;
        long interval = 30 * 60_000L;
        assertFalse(WallpaperRotation.due(start, start + interval - 1, interval));
        assertTrue(WallpaperRotation.due(start, start + interval, interval));
    }

    @Test public void sequentialRotationAdvancesAndWraps() {
        File first = new File("01.jpg"), second = new File("02.jpg");
        assertSame(second, WallpaperRotation.pick(Arrays.asList(first, second), first.getName(),
                WallpaperRotation.ORDER_SEQUENTIAL, new Random(1)));
        assertSame(first, WallpaperRotation.pick(Arrays.asList(first, second), second.getName(),
                WallpaperRotation.ORDER_SEQUENTIAL, new Random(1)));
    }
}
