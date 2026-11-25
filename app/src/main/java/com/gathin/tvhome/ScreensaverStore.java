package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Media is only listed/imported by screensaver screens, never scanned by the desktop timer. */
final class ScreensaverStore {
    private ScreensaverStore() { }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("video_screensaver", Context.MODE_PRIVATE);
    }

    static File privateDirectory(Context context, String name) {
        return name != null && name.matches("screensaver-[a-f0-9-]{36}") ? new File(context.getFilesDir(), name) : null;
    }

    static List<File> videos(Context context) {
        List<File> result = new ArrayList<>();
        add(result, privateDirectory(context, preferences(context).getString("directory", "")));
        File external = context.getExternalFilesDir(null);
        if (external != null) add(result, new File(external, "screensaver"));
        return result;
    }

    private static void add(List<File> result, File directory) {
        File[] entries = directory == null ? null : directory.listFiles();
        if (entries == null) return;
        java.util.Arrays.sort(entries, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File file : entries) {
            if (result.size() == ScreensaverRules.MAX_VIDEOS) return;
            if (file.isFile() && !Files.isSymbolicLink(file.toPath()) && file.length() > 0
                    && file.length() <= ScreensaverRules.MAX_VIDEO_BYTES && ScreensaverRules.videoName(file.getName())) result.add(file);
        }
    }

    static String title(File file) {
        String name = file.getName().replaceFirst("^\\d{2}-", "");
        int extension = name.lastIndexOf('.');
        return extension > 0 ? name.substring(0, extension) : name;
    }

    // Runs on the import worker. Durable preference replacement is required before deleting the old set;
    // the free-space check is deliberately conservative and does not evict other applications' caches.
    @android.annotation.SuppressLint("UsableSpace")
    static synchronized void importVideos(Context context, List<Uri> sources, BooleanSupplier cancelled) throws IOException {
        if (sources.isEmpty() || sources.size() > ScreensaverRules.MAX_VIDEOS) throw new IOException("Choose 1 to 20 videos");
        cleanAbandonedImports(context);
        File directory = new File(context.getFilesDir(), "screensaver-" + UUID.randomUUID());
        if (!directory.mkdir()) throw new IOException("Cannot create video directory");
        boolean installed = false;
        try {
            long total = 0;
            byte[] buffer = new byte[64 * 1024];
            for (int i = 0; i < sources.size(); i++) {
                checkCancelled(cancelled);
                Uri source = sources.get(i);
                if (!"content".equals(source.getScheme())) throw new IOException("Expected a document URI");
                String name = displayName(context, source);
                if (!ScreensaverRules.videoName(name)) throw new IOException("Unsupported video extension");
                File target = new File(directory, String.format(Locale.ROOT, "%02d-%s", i + 1, name));
                long size = 0;
                try (InputStream input = context.getContentResolver().openInputStream(source);
                     FileOutputStream output = new FileOutputStream(target)) {
                    if (input == null) throw new IOException("Cannot open video");
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        checkCancelled(cancelled);
                        if (count == 0) continue;
                        size += count;
                        total += count;
                        if (!ScreensaverRules.withinBudget(size, total, directory.getUsableSpace() - count))
                            throw new IOException("Video/storage budget exceeded");
                        output.write(buffer, 0, count);
                    }
                    if (size == 0) throw new IOException("Empty video");
                    output.getFD().sync();
                }
            }
            checkCancelled(cancelled);
            SharedPreferences prefs = preferences(context);
            String old = prefs.getString("directory", "");
            saveCollection(prefs, directory.getName(), sources.size());
            installed = true;
            clear(privateDirectory(context, old));
        } finally {
            if (!installed) clear(directory);
        }
    }

    static synchronized void removeImported(Context context) throws IOException {
        SharedPreferences prefs = preferences(context);
        String old = prefs.getString("directory", "");
        saveCollection(prefs, "", 0);
        clear(privateDirectory(context, old));
    }

    @android.annotation.SuppressLint("ApplySharedPref") // Worker-only transaction: durability precedes deletion.
    private static void saveCollection(SharedPreferences prefs, String directory, int count) throws IOException {
        String old = prefs.getString("directory", ""), selected = prefs.getString("selected", "");
        int oldCount = prefs.getInt("video_count", 0);
        if (prefs.edit().putString("directory", directory).putInt("video_count", count).remove("selected").commit()) return;
        // commit() may update in-memory preferences even when the disk write fails.
        if (!prefs.edit().putString("directory", old).putInt("video_count", oldCount).putString("selected", selected).commit())
            android.util.Log.w("TvHome.Screensaver", "Could not persist preference rollback");
        throw new IOException("Cannot save video selection");
    }

    private static String displayName(Context context, Uri uri) throws IOException {
        String name = null;
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
        try { return ScreensaverRules.importName(name); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid video name", invalid); }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
    }

    /** Recover only this feature's UUID staging directories after a process-killed import. */
    static synchronized void cleanAbandonedImports(Context context) {
        String selected = preferences(context).getString("directory", "");
        File[] entries = context.getFilesDir().listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (!entry.getName().equals(selected) && privateDirectory(context, entry.getName()) != null
                    && entry.isDirectory() && !Files.isSymbolicLink(entry.toPath())) clear(entry);
        }
    }

    private static void clear(File directory) {
        if (directory == null) return;
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            if (file.isFile() && !file.delete()) android.util.Log.w("TvHome.Screensaver", "Cannot delete " + file.getName());
        }
        if (!directory.delete() && directory.exists()) android.util.Log.w("TvHome.Screensaver", "Cannot remove " + directory.getName());
    }
}
