package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Only screensaver/settings workers enumerate videos. Home reads the cached count, never the folder. */
final class ScreensaverStore {
    static final String FOLDER_KEY = "folder_uri";
    static final String VOLUME_KEY = "volume_percent";

    static final class Library {
        final List<ScreensaverVideo> videos;
        final String folder;
        final boolean limited;

        Library(List<ScreensaverVideo> videos, String folder, boolean limited) {
            videos.sort(Comparator.comparing((ScreensaverVideo video) -> video.name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(video -> video.key));
            this.limited = limited || videos.size() > ScreensaverRules.MAX_VIDEOS;
            this.videos = Collections.unmodifiableList(new ArrayList<>(videos.subList(0,
                    Math.min(videos.size(), ScreensaverRules.MAX_VIDEOS))));
            this.folder = folder;
        }
    }

    private ScreensaverStore() { }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("video_screensaver", Context.MODE_PRIVATE);
    }

    static File localFolder(Context context) throws IOException {
        File base = context.getExternalFilesDir(null);
        if (base == null) throw new IOException("Local storage unavailable");
        return new File(base, "screensaver");
    }

    static Library scan(Context context) throws IOException {
        SharedPreferences prefs = preferences(context);
        String folder = prefs.getString(FOLDER_KEY, "");
        if (!folder.isEmpty()) return readFolder(context, Uri.parse(folder));
        if (prefs.getBoolean("folder_disabled", false)) return new Library(new ArrayList<>(), "", false);
        // Compatibility only: preserve the original imported set and app-local directory until a folder is bound.
        List<ScreensaverVideo> videos = new ArrayList<>();
        String old = prefs.getString("directory", "");
        if (old.matches("screensaver-[a-f0-9-]{36}")) addLocal(videos, new File(context.getFilesDir(), old));
        File local = localFolder(context);
        if (local.isDirectory()) addLocal(videos, local);
        return new Library(videos, local.getAbsolutePath(), false);
    }

    static Library readFolder(Context context, Uri folder) throws IOException {
        if ("mediafolder".equals(folder.getScheme())) return ScreensaverFolders.read(context, folder);
        if ("file".equals(folder.getScheme())) {
            File local = localFolder(context).getCanonicalFile();
            if (folder.getPath() == null || !new File(folder.getPath()).getCanonicalFile().equals(local))
                throw new IOException("Only the app's own folder accepts a file URI");
            if (!local.isDirectory()) throw new IOException("Folder unavailable");
            List<ScreensaverVideo> videos = new ArrayList<>();
            addLocal(videos, local);
            return new Library(videos, local.getAbsolutePath(), false);
        }
        if (!"content".equals(folder.getScheme()) || !DocumentsContract.isTreeUri(folder))
            throw new IOException("Expected a folder grant");
        String rootId = DocumentsContract.getTreeDocumentId(folder);
        Uri document = DocumentsContract.buildDocumentUriUsingTree(folder, rootId);
        String name;
        try (Cursor cursor = context.getContentResolver().query(document,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE},
                null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()
                    || !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1)))
                throw new IOException("Folder unavailable");
            name = cursor.getString(0);
        }
        List<ScreensaverVideo> videos = new ArrayList<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(folder, rootId);
        boolean limited = false;
        try (Cursor cursor = context.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS},
                null, null, null)) {
            if (cursor == null) throw new IOException("Cannot list folder");
            int scanned = 0;
            while (cursor.moveToNext()) {
                checkCancelled();
                if (++scanned > ScreensaverRules.MAX_FOLDER_ENTRIES) { limited = true; break; }
                String fileName = cursor.getString(1);
                if (ScreensaverRules.videoName(fileName)
                        && !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))
                        && (cursor.getInt(5) & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) == 0
                        && (cursor.isNull(3) || cursor.getLong(3) > 0)) {
                    videos.add(new ScreensaverVideo(DocumentsContract.buildDocumentUriUsingTree(folder, cursor.getString(0)),
                            fileName, cursor.getLong(3), cursor.getLong(4)));
                }
            }
        }
        return new Library(videos, name == null ? folder.toString() : name, limited);
    }

    private static void addLocal(List<ScreensaverVideo> videos, File directory) throws IOException {
        if (!directory.isDirectory()) return;
        try (DirectoryStream<Path> paths = Files.newDirectoryStream(directory.toPath())) {
            int scanned = 0;
            for (Path path : paths) {
                checkCancelled();
                if (++scanned > ScreensaverRules.MAX_FOLDER_ENTRIES) throw new IOException("Folder contains too many entries");
                File file = path.toFile();
                if (file.isFile() && !Files.isSymbolicLink(path) && file.length() > 0 && ScreensaverRules.videoName(file.getName()))
                    videos.add(new ScreensaverVideo(Uri.fromFile(file), file.getName(), file.length(), file.lastModified()));
            }
        }
    }

    static synchronized void bind(Context context, Uri folder, int flags, BooleanSupplier cancelled) throws IOException {
        SharedPreferences prefs = preferences(context);
        String old = prefs.getString(FOLDER_KEY, "");
        boolean tree = "content".equals(folder.getScheme());
        boolean alreadyGranted = tree && context.getContentResolver().getPersistedUriPermissions().stream()
                .anyMatch(permission -> permission.isReadPermission() && permission.getUri().equals(folder));
        boolean granted = false, saved = false;
        try {
            if (tree) {
                if ((flags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new IOException("Missing read grant");
                context.getContentResolver().takePersistableUriPermission(folder, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                granted = true;
            }
            Library library = readFolder(context, folder);
            if (library.videos.isEmpty()) throw new EmptyFolderException();
            checkCancelled();
            if (cancelled.getAsBoolean()) throw new IOException("Cancelled");
            saveSelection(prefs, folder.toString(), library.folder, library.videos.size(), false);
            saved = true;
            if (!old.equals(folder.toString())) releaseGrant(context, old);
        } finally {
            if (!saved && granted && !alreadyGranted) releaseGrant(context, folder.toString());
        }
    }

    static final class EmptyFolderException extends IOException { }

    static synchronized void unbind(Context context) throws IOException {
        SharedPreferences prefs = preferences(context);
        String old = prefs.getString(FOLDER_KEY, "");
        saveSelection(prefs, "", "", 0, true);
        releaseGrant(context, old);
        // Never delete selected originals or the previous version's imported files.
    }

    @android.annotation.SuppressLint("ApplySharedPref") // Worker-only; persist before releasing the old read grant.
    private static void saveSelection(SharedPreferences prefs, String folder, String label, int count, boolean disabled) throws IOException {
        String old = prefs.getString(FOLDER_KEY, ""), selected = prefs.getString("selected", "");
        String oldLabel = prefs.getString("folder_label", "");
        int oldCount = prefs.getInt("video_count", 0);
        boolean wasDisabled = prefs.getBoolean("folder_disabled", false);
        if (prefs.edit().putString(FOLDER_KEY, folder).putString("folder_label", label).putInt("video_count", count)
                .putBoolean("folder_disabled", disabled).remove("selected").commit()) return;
        if (!prefs.edit().putString(FOLDER_KEY, old).putString("folder_label", oldLabel).putInt("video_count", oldCount)
                .putBoolean("folder_disabled", wasDisabled).putString("selected", selected).commit())
            android.util.Log.w("TvHome.Screensaver", "Could not persist folder rollback");
        throw new IOException("Cannot save folder binding");
    }

    private static void releaseGrant(Context context, String saved) {
        if (saved.isEmpty()) return;
        Uri uri = Uri.parse(saved);
        if (!"content".equals(uri.getScheme())) return;
        try { context.getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (SecurityException failure) { android.util.Log.w("TvHome.Screensaver", "Read grant already unavailable", failure); }
    }

    static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
    }
}
