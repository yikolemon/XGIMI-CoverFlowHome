package com.gathin.tvhome;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** TV fallback: choose a media-library folder, not individual files and not unrestricted storage. */
final class ScreensaverFolders {
    static final class Folder {
        final Uri uri;
        final String name;
        int count;
        Folder(Uri uri, String name) { this.uri = uri; this.name = name; }
    }

    private ScreensaverFolders() { }

    static List<Folder> list(Context context) throws IOException {
        Map<String, Folder> folders = new LinkedHashMap<>();
        query(context, null, (folder, name, video) -> {
            Folder entry = folders.get(folder.toString());
            if (entry == null) { entry = new Folder(folder, name); folders.put(folder.toString(), entry); }
            entry.count++;
        });
        List<Folder> result = new ArrayList<>(folders.values());
        result.sort(Comparator.comparing(folder -> folder.name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    static ScreensaverStore.Library read(Context context, Uri folder) throws IOException {
        String volume = folder.getQueryParameter("volume"), path = folder.getQueryParameter("path");
        if (!"videos".equals(folder.getAuthority()) || volume == null || path == null) throw new IOException("Invalid folder reference");
        List<ScreensaverVideo> videos = new ArrayList<>();
        query(context, folder, (reference, name, video) -> videos.add(video));
        return new ScreensaverStore.Library(videos, label(volume, path), false);
    }

    private interface Entry { void accept(Uri folder, String name, ScreensaverVideo video); }

    private static void query(Context context, Uri wanted, Entry entry) throws IOException {
        boolean modern = Build.VERSION.SDK_INT >= 29;
        String pathColumn = modern ? MediaStore.Video.Media.RELATIVE_PATH : MediaStore.Video.Media.DATA;
        String[] projection = modern
                ? new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED, pathColumn, MediaStore.Video.Media.VOLUME_NAME}
                : new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED, pathColumn};
        String selection = modern ? MediaStore.Video.Media.IS_PENDING + "=0" : null;
        String[] args = null;
        if (modern && wanted != null) {
            selection += " AND " + pathColumn + "=? AND " + MediaStore.Video.Media.VOLUME_NAME + "=?";
            args = new String[]{wanted.getQueryParameter("path"), wanted.getQueryParameter("volume")};
        }
        try (Cursor cursor = context.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, null)) {
            if (cursor == null) throw new IOException("Media library unavailable");
            int scanned = 0;
            while (cursor.moveToNext()) {
                ScreensaverStore.checkCancelled();
                if (++scanned > 5000) throw new IOException("Media library too large; use the system folder picker");
                String name = cursor.getString(1), location = cursor.getString(4);
                if (!ScreensaverRules.videoName(name) || cursor.getLong(2) <= 0 || location == null) continue;
                String path = modern ? location : new File(location).getParent();
                if (path == null) continue;
                String volume = modern ? cursor.getString(5) : "external";
                if (volume == null) continue;
                Uri folder = new Uri.Builder().scheme("mediafolder").authority("videos")
                        .appendQueryParameter("volume", volume).appendQueryParameter("path", path).build();
                if (wanted != null && !wanted.equals(folder)) continue;
                Uri base = modern ? MediaStore.Video.Media.getContentUri(volume) : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                entry.accept(folder, label(volume, path), new ScreensaverVideo(ContentUris.withAppendedId(base, cursor.getLong(0)),
                        name, cursor.getLong(2), cursor.getLong(3)));
            }
        }
    }

    private static String label(String volume, String path) {
        return path + ("external_primary".equals(volume) || "external".equals(volume) ? "" : " · " + volume);
    }
}
