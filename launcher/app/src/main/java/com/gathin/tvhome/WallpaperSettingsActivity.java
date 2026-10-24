package com.gathin.tvhome;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Text-only remote-friendly picker; no thumbnails, catalogue decoder or storage-management permission. */
public final class WallpaperSettingsActivity extends Activity {
    private static final int PICK = 1, STORAGE = 2, PICK_FOLDER = 3, FOLDER_STORAGE = 4;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private boolean busy;
    private TextView status, choose, folderChoice, theme;
    private Switch rotation;
    private LinearLayout rotationRow;
    private AlertDialog browser;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(64), dp(32), dp(64), dp(32));
        panel.setBackgroundColor(0xFF10151F);
        setContentView(panel);
        TextView title = text(getString(R.string.local_wallpaper_title), 26);
        panel.addView(title, new LinearLayout.LayoutParams(-1, dp(56)));
        String current = getString(R.string.local_wallpaper_default);
        if (UserWallpaperCollection.enabled(this)) {
            current = getString(R.string.local_wallpaper_collection, UserWallpaperCollection.images(this).size());
        } else if (UserWallpaper.enabled(this)) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            File image = UserWallpaper.file(this);
            BitmapFactory.decodeFile(image.getPath(), bounds);
            current = getString(R.string.local_wallpaper_custom_details, bounds.outWidth, bounds.outHeight,
                    (image.length() + 1023) / 1024);
        }
        status = text(current, 15);
        status.setTextColor(0xBBFFFFFF);
        panel.addView(status, new LinearLayout.LayoutParams(-1, dp(50)));
        choose = button(panel, R.string.local_wallpaper_choose, this::pick);
        folderChoice = button(panel, R.string.local_wallpaper_folder, this::pickFolder);
        rotation = new Switch(this);
        rotationRow = new LinearLayout(this);
        rotationRow.setGravity(Gravity.CENTER_VERTICAL);
        rotationRow.setPadding(dp(20), 0, dp(20), 0);
        TextView rotationLabel = text(getString(R.string.local_wallpaper_rotation), 17);
        rotationLabel.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        rotationRow.addView(rotationLabel, new LinearLayout.LayoutParams(0, -1, 1));
        rotation.setFocusable(false);
        rotationRow.addView(rotation, new LinearLayout.LayoutParams(-2, -2));
        rotationRow.setFocusable(true);
        styleChoice(rotationRow, rotationLabel, false);
        rotationRow.setOnFocusChangeListener((view, focused) -> styleChoice(rotationRow, rotationLabel, focused));
        rotationRow.setOnClickListener(view -> { if (!busy) rotation.toggle(); });
        rotation.setChecked(getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean(Config.WALLPAPER_ROTATION_ENABLED_KEY, true));
        rotation.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE).edit()
                    .putBoolean(Config.WALLPAPER_ROTATION_ENABLED_KEY, checked).apply();
            setResult(RESULT_OK);
        });
        LinearLayout.LayoutParams rotationParams = new LinearLayout.LayoutParams(dp(440), dp(54));
        rotationParams.gravity = Gravity.CENTER_HORIZONTAL;
        rotationParams.topMargin = dp(12);
        panel.addView(rotationRow, rotationParams);
        theme = button(panel, R.string.local_wallpaper_restore, () -> {
            UserWallpaper.useTheme(this);
            setResult(RESULT_OK);
            finish();
        });
        TextView hint = text(getString(R.string.local_wallpaper_hint), 13);
        hint.setTextColor(0x99FFFFFF);
        hint.setPadding(0, dp(20), 0, 0);
        panel.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        choose.requestFocus();
        if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
            Uri shared = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
            if (shared != null && "content".equals(shared.getScheme())) {
                new AlertDialog.Builder(this).setTitle(R.string.local_wallpaper_import_confirm)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> importImage(shared))
                        .setNegativeButton(android.R.string.cancel, null).show();
            }
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private TextView button(LinearLayout panel, int label, Runnable click) {
        TextView view = text(getString(label), 17);
        view.setFocusable(true);
        styleChoice(view, false);
        view.setOnFocusChangeListener((v, focus) -> styleChoice(view, focus));
        view.setOnClickListener(v -> { if (!busy) click.run(); });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(440), dp(54));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(12);
        panel.addView(view, params);
        return view;
    }

    private void styleChoice(TextView view, boolean focused) {
        styleChoice(view, view, focused);
    }

    private void styleChoice(android.view.View view, TextView label, boolean focused) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(14));
        background.setColor(focused ? 0x3AFFFFFF : 0x14FFFFFF);
        background.setStroke(dp(focused ? 2 : 1), focused ? 0xEEFFFFFF : 0x35FFFFFF);
        view.setBackground(background);
        label.setTextColor(focused ? Color.WHITE : 0xAAFFFFFF);
    }

    private void pick() {
        for (String action : new String[]{Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT}) {
            Intent intent = new Intent(action).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
            try { startActivityForResult(intent, PICK); return; }
            catch (ActivityNotFoundException | SecurityException noSystemPicker) { /* TV fallback below. */ }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Toast.makeText(this, R.string.local_wallpaper_no_picker, Toast.LENGTH_LONG).show();
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, STORAGE);
        } else browse(Environment.getExternalStorageDirectory());
    }

    private void pickFolder() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PICK_FOLDER);
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            if (Build.VERSION.SDK_INT >= 33) {
                Toast.makeText(this, R.string.local_wallpaper_no_picker, Toast.LENGTH_LONG).show();
            } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, FOLDER_STORAGE);
            } else browse(Environment.getExternalStorageDirectory(), true);
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != STORAGE && request != FOLDER_STORAGE) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
            browse(Environment.getExternalStorageDirectory(), request == FOLDER_STORAGE);
        else Toast.makeText(this, R.string.local_wallpaper_permission, Toast.LENGTH_LONG).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK && result == RESULT_OK && data != null && data.getData() != null)
            importImage(data.getData());
        if (request == PICK_FOLDER && result == RESULT_OK && data != null && data.getData() != null)
            importFolder(data.getData());
    }

    private void busy(boolean value) {
        busy = value;
        choose.setEnabled(!value);
        folderChoice.setEnabled(!value);
        rotation.setEnabled(!value);
        rotationRow.setEnabled(!value);
        theme.setEnabled(!value);
        status.setText(value ? R.string.local_wallpaper_working : R.string.local_wallpaper_failed);
    }

    private void importImage(Uri uri) {
        if (busy) return;
        busy(true);
        worker.execute(() -> {
            boolean success;
            try { UserWallpaper.importImage(getApplicationContext(), uri, () -> closed); success = true; }
            catch (IOException | RuntimeException failure) { success = false; }
            final boolean applied = success;
            runOnUiThread(() -> {
                if (closed) return;
                if (applied) {
                    Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                    finish();
                } else busy(false);
            });
        });
    }

    private void browse(File folder) {
        browse(folder, false);
    }

    private void browse(File folder, boolean collectionMode) {
        busy(true);
        worker.execute(() -> {
            List<File> entries = new ArrayList<>();
            boolean truncated = false;
            try {
                File root = Environment.getExternalStorageDirectory().getCanonicalFile();
                File current = folder.getCanonicalFile();
                if (!current.equals(root) && !current.getPath().startsWith(root.getPath() + File.separator))
                    throw new IOException("Outside shared storage");
                try (DirectoryStream<Path> paths = Files.newDirectoryStream(current.toPath())) {
                    int scanned = 0;
                    for (Path path : paths) {
                        if (closed) return;
                        if (++scanned > 1000 || entries.size() >= 200) { truncated = true; break; }
                        File item = path.toFile();
                        if (item.getName().startsWith(".") || Files.isSymbolicLink(path)) continue;
                        if (item.isDirectory() && !"Android".equals(item.getName()) || imageName(item.getName()))
                            entries.add(item);
                    }
                }
                entries.sort(Comparator.comparing((File f) -> !f.isDirectory())
                        .thenComparing(File::getName, String.CASE_INSENSITIVE_ORDER));
                boolean parent = !current.equals(root);
                List<String> labels = new ArrayList<>();
                if (collectionMode) labels.add(getString(R.string.local_wallpaper_use_folder));
                if (parent) labels.add(getString(R.string.local_wallpaper_parent));
                for (File item : entries) labels.add((item.isDirectory() ? "▸ " : "") + item.getName());
                final boolean limited = truncated;
                runOnUiThread(() -> {
                    if (closed) return;
                    busy = false;
                    choose.setEnabled(true);
                    folderChoice.setEnabled(true);
                    rotation.setEnabled(true);
                    rotationRow.setEnabled(true);
                    theme.setEnabled(true);
                    status.setText(R.string.local_wallpaper_browse_hint);
                    browser = new AlertDialog.Builder(this).setTitle(current.getPath())
                            .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                                if (collectionMode && index == 0) {
                                    if (limited) {
                                        Toast.makeText(this, R.string.local_wallpaper_limited, Toast.LENGTH_LONG).show();
                                        return;
                                    }
                                    List<Uri> images = new ArrayList<>();
                                    for (File item : entries) if (item.isFile() && imageName(item.getName())) images.add(Uri.fromFile(item));
                                    importCollection(images);
                                }
                                else if (parent && index == (collectionMode ? 1 : 0)) browse(current.getParentFile(), collectionMode);
                                else {
                                    File selected = entries.get(index - (parent ? 1 : 0) - (collectionMode ? 1 : 0));
                                    if (selected.isDirectory()) browse(selected, collectionMode);
                                    else importImage(Uri.fromFile(selected));
                                }
                            }).setNegativeButton(android.R.string.cancel, null).create();
                    browser.show();
                    if (limited) Toast.makeText(this, R.string.local_wallpaper_limited, Toast.LENGTH_LONG).show();
                });
            } catch (IOException | RuntimeException unavailable) {
                runOnUiThread(() -> { if (!closed) busy(false); });
            }
        });
    }

    private void importFolder(Uri tree) {
        if (busy) return;
        busy(true);
        worker.execute(() -> {
            List<Uri> images = new ArrayList<>();
            boolean success = false;
            try {
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                        DocumentsContract.getTreeDocumentId(tree));
                String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME};
                try (Cursor cursor = getContentResolver().query(children, projection, null, null, null)) {
                    if (cursor == null) throw new IOException("Cannot list folder");
                    java.util.Map<String, Uri> sorted = new java.util.TreeMap<>();
                    int scanned = 0;
                    while (cursor.moveToNext()) {
                        if (++scanned > 1000 || closed) throw new IOException("Folder too large or cancelled");
                        String name = cursor.getString(1);
                        if (name != null && imageName(name)) sorted.put(name,
                                DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)));
                    }
                    images.addAll(sorted.values());
                }
                UserWallpaperCollection.importImages(this, images, () -> closed);
                success = true;
            } catch (IOException | RuntimeException failure) { /* Existing selection remains intact. */ }
            final boolean imported = success;
            runOnUiThread(() -> finishCollection(imported));
        });
    }

    private void importCollection(List<Uri> images) {
        if (busy) return;
        busy(true);
        worker.execute(() -> {
            boolean success = false;
            try {
                UserWallpaperCollection.importImages(this, images, () -> closed);
                success = true;
            } catch (IOException | RuntimeException failure) { /* Existing selection remains intact. */ }
            final boolean imported = success;
            runOnUiThread(() -> finishCollection(imported));
        });
    }

    private void finishCollection(boolean success) {
        if (closed) return;
        if (!success) { busy(false); return; }
        Toast.makeText(this, R.string.wallpaper_applied, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    private static boolean imageName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    @Override protected void onDestroy() {
        closed = true;
        worker.shutdownNow();
        if (browser != null) browser.dismiss();
        super.onDestroy();
    }
}
