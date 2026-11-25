package com.gathin.tvhome;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

/** Lazy source priority: never prepare a theme that a private selection will replace. */
final class WallpaperSelection {
    final List<File> files;
    final boolean collection;

    private WallpaperSelection(List<File> files, boolean collection) {
        this.files = files;
        this.collection = collection;
    }

    static WallpaperSelection select(boolean themeEnabled, Supplier<List<File>> collection,
                                     Supplier<List<File>> single, Supplier<List<File>> theme) {
        List<File> files = collection.get();
        if (!files.isEmpty()) return new WallpaperSelection(files, true);
        files = single.get();
        if (!files.isEmpty()) return new WallpaperSelection(files, false);
        return new WallpaperSelection(themeEnabled ? theme.get() : Collections.emptyList(), false);
    }

    /** Compute on the loader thread, not once per file on the UI thread. */
    static String collectionKey(List<File> files) {
        StringBuilder key = new StringBuilder();
        for (File file : files) key.append(file.getAbsolutePath()).append(':').append(file.length()).append(';');
        return key.toString();
    }
}
