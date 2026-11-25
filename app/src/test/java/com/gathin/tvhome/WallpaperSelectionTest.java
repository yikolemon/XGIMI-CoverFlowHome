package com.gathin.tvhome;

import org.junit.Test;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class WallpaperSelectionTest {
    private static final List<File> COLLECTION = Arrays.asList(new File("private/a.jpg"), new File("private/b.jpg"));
    private static final List<File> SINGLE = Collections.singletonList(new File("private/user_wallpaper.jpg"));
    private static final List<File> THEME = Collections.singletonList(new File("theme/wallpaper.jpg"));

    @Test public void privateCollectionSkipsAllLowerPriorityIo() {
        WallpaperSelection selected = WallpaperSelection.select(true, () -> COLLECTION,
                () -> { throw new AssertionError("Single wallpaper should not be probed"); },
                () -> { throw new AssertionError("Theme must not be scanned or extracted"); });
        assertSame(COLLECTION, selected.files);
        assertTrue(selected.collection);
    }

    @Test public void privateSingleSkipsThemeIo() {
        WallpaperSelection selected = WallpaperSelection.select(true, Collections::emptyList, () -> SINGLE,
                () -> { throw new AssertionError("Theme must not be scanned or extracted"); });
        assertSame(SINGLE, selected.files);
        assertFalse(selected.collection);
    }

    @Test public void emptyPrivateSourcesFallBackToTheme() {
        WallpaperSelection selected = WallpaperSelection.select(true, Collections::emptyList,
                Collections::emptyList, () -> THEME);
        assertSame(THEME, selected.files);
        assertFalse(selected.collection);
    }

    @Test public void disabledThemeDoesNoThemeIo() {
        WallpaperSelection selected = WallpaperSelection.select(false, Collections::emptyList,
                Collections::emptyList, () -> { throw new AssertionError("Theme is disabled"); });
        assertTrue(selected.files.isEmpty());
    }

    @Test public void privateSelectionStillOverridesDisabledTheme() {
        WallpaperSelection selected = WallpaperSelection.select(false, Collections::emptyList, () -> SINGLE,
                () -> { throw new AssertionError("Theme is disabled"); });
        assertSame(SINGLE, selected.files);
    }

    @Test public void differentCollectionsWithSameNamesHaveDifferentKeys() {
        String first = WallpaperSelection.collectionKey(Collections.singletonList(new File("collection-a/wallpaper-001.jpg")));
        String second = WallpaperSelection.collectionKey(Collections.singletonList(new File("collection-b/wallpaper-001.jpg")));
        assertNotEquals(first, second);
        assertEquals(first, WallpaperSelection.collectionKey(Collections.singletonList(new File("collection-a/wallpaper-001.jpg"))));
    }
}
