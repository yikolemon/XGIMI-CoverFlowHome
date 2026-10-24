package com.gathin.tvhome;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Copies a selected collection privately; a failed import never replaces the current collection. */
final class UserWallpaperCollection {
    private static final String PREFS = "wallpaper_collection";

    static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false);
    }

    static void disable(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit();
    }

    static List<File> images(Context context) {
        String name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("directory", "");
        return WallpaperRotation.images(name.startsWith("collection-") && !name.contains("/")
                ? new File(context.getFilesDir(), name) : null);
    }

    static void importImages(Context context, List<Uri> sources, BooleanSupplier cancelled) throws IOException {
        if (sources.isEmpty() || sources.size() > 100) throw new IOException("Choose 1 to 100 images");
        File directory = new File(context.getFilesDir(), "collection-" + UUID.randomUUID());
        if (!directory.mkdir()) throw new IOException("Cannot create collection");
        boolean installed = false;
        try {
            long total = 0;
            for (int i = 0; i < sources.size(); i++) {
                File output = new File(directory, String.format(java.util.Locale.ROOT, "wallpaper-%03d.jpg", i + 1));
                try (InputStream input = context.getContentResolver().openInputStream(sources.get(i));
                     FileOutputStream stream = new FileOutputStream(output)) {
                    if (input == null) throw new IOException("Cannot open image");
                    byte[] buffer = new byte[8192];
                    long size = 0;
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                            throw new IOException("Cancelled");
                        size += count;
                        total += count;
                        if (size > 10L * 1024 * 1024 || total > 128L * 1024 * 1024)
                            throw new IOException("Collection too large");
                        stream.write(buffer, 0, count);
                    }
                }
                android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(output.getPath(), bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Invalid image");
            }
            if (cancelled.getAsBoolean()) throw new IOException("Cancelled");
            String old = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("directory", "");
            installed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("directory", directory.getName()).putBoolean("enabled", true).commit();
            if (!installed) throw new IOException("Cannot save collection");
            if (old.startsWith("collection-") && !old.contains("/")) clear(new File(context.getFilesDir(), old));
        } finally {
            if (!installed) clear(directory);
        }
    }

    private static void clear(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (file.isFile()) file.delete();
        directory.delete();
    }
}
