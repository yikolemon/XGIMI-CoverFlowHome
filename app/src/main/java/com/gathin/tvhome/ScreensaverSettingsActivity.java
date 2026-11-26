package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bind readable folders without copying videos or decoding any thumbnails in settings. */
public final class ScreensaverSettingsActivity extends Activity {
    private static final int PICK = 1, VIDEO_PERMISSION = 2;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<View> controls = new ArrayList<>();
    private volatile boolean closed;
    private boolean busy;
    private int count;
    private SharedPreferences preferences;
    private TextView status, volume, timeout, folderStatus;
    private LinearLayout panel;
    private View previousFocus;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = ScreensaverStore.preferences(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF10151F);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(48), dp(24), dp(48), dp(24));
        scroll.addView(panel);
        setContentView(scroll);
        TextView title = text(getString(R.string.screensaver_title), 24);
        panel.addView(title, new LinearLayout.LayoutParams(-1, dp(48)));
        folderStatus = text(getString(R.string.screensaver_folder_none), 13);
        folderStatus.setSingleLine(true);
        folderStatus.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        folderStatus.setTextColor(0xB8FFFFFF);
        panel.addView(folderStatus, new LinearLayout.LayoutParams(-1, dp(28)));
        status = text(getString(R.string.screensaver_loading), 14);
        panel.addView(status, new LinearLayout.LayoutParams(-1, dp(40)));
        TextView folderButton = button(R.string.screensaver_folder_choose, this::pick);
        volume = button(R.string.screensaver_volume_title, this::chooseVolume);
        updateVolume();
        timeout = button(R.string.screensaver_timeout_off, this::chooseTimeout);
        updateTimeout();
        button(R.string.screensaver_refresh, this::refresh);
        button(R.string.screensaver_folder_unbind, () -> new AlertDialog.Builder(this)
                .setMessage(R.string.screensaver_folder_unbind_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> perform(() -> ScreensaverStore.unbind(this)))
                .setNegativeButton(android.R.string.cancel, null).show());
        folderButton.requestFocus();
    }

    @Override protected void onResume() { super.onResume(); updateVolume(); refresh(); }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private LinearLayout.LayoutParams itemParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(460), dp(48));
        params.topMargin = dp(8);
        return params;
    }

    private TextView button(int title, Runnable action) {
        TextView button = text(getString(title), 16);
        button.setFocusable(true);
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(0x18FFFFFF);
        surface.setCornerRadius(dp(10));
        button.setBackground(surface);
        button.setOnFocusChangeListener((view, focused) -> surface.setColor(focused ? 0x44FFFFFF : 0x18FFFFFF));
        button.setOnClickListener(view -> { if (!busy) action.run(); });
        panel.addView(button, itemParams());
        controls.add(button);
        return button;
    }

    private void updateVolume() {
        int percent = ScreensaverRules.clampVolumePercent(
                preferences.getInt(ScreensaverStore.VOLUME_KEY, ScreensaverRules.DEFAULT_VOLUME_PERCENT));
        volume.setText(getString(R.string.screensaver_volume, percent));
    }

    private void chooseVolume() {
        int current = ScreensaverRules.clampVolumePercent(
                preferences.getInt(ScreensaverStore.VOLUME_KEY, ScreensaverRules.DEFAULT_VOLUME_PERCENT));
        String[] labels = new String[ScreensaverRules.VOLUME_PERCENTS.length];
        int checked = labels.length - 1;
        for (int i = 0; i < labels.length; i++) {
            int percent = ScreensaverRules.VOLUME_PERCENTS[i];
            labels[i] = getString(R.string.screensaver_volume_option, percent);
            if (percent == current) checked = i;
        }
        new AlertDialog.Builder(this).setTitle(R.string.screensaver_volume_title)
                .setSingleChoiceItems(labels, checked, (dialog, index) -> {
                    preferences.edit().putInt(ScreensaverStore.VOLUME_KEY, ScreensaverRules.VOLUME_PERCENTS[index]).apply();
                    updateVolume();
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void updateTimeout() {
        int minutes = preferences.getInt("timeout_minutes", 10);
        timeout.setText(minutes <= 0 ? getString(R.string.screensaver_timeout_off)
                : getString(R.string.screensaver_timeout_minutes, minutes));
    }

    private void chooseTimeout() {
        String[] labels = new String[ScreensaverRules.TIMEOUT_MINUTES.length];
        int checked = 0;
        for (int i = 0; i < labels.length; i++) {
            int minutes = ScreensaverRules.TIMEOUT_MINUTES[i];
            labels[i] = minutes == 0 ? getString(R.string.screensaver_timeout_off) : getString(R.string.screensaver_timeout_minutes, minutes);
            if (minutes == preferences.getInt("timeout_minutes", 10)) checked = i;
        }
        new AlertDialog.Builder(this).setTitle(R.string.screensaver_timeout_title)
                .setSingleChoiceItems(labels, checked, (dialog, index) -> {
                    preferences.edit().putInt("timeout_minutes", ScreensaverRules.TIMEOUT_MINUTES[index]).apply();
                    updateTimeout();
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                .putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        String saved = preferences.getString(ScreensaverStore.FOLDER_KEY, "");
        if (saved.startsWith("content:")) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(saved));
        ResolveInfo resolver = getPackageManager().resolveActivity(intent, 0);
        if (resolver != null && resolver.activityInfo.packageName.contains("frameworkpackagestubs")) {
            pickLibrary();
            return;
        }
        try { startActivityForResult(intent, PICK); }
        catch (ActivityNotFoundException | SecurityException unavailable) { pickLibrary(); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || data.getData() == null || busy) return;
        bind(data.getData(), data.getFlags());
    }

    private String videoPermission() {
        return Build.VERSION.SDK_INT >= 33 ? android.Manifest.permission.READ_MEDIA_VIDEO : android.Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void pickLibrary() {
        if (checkSelfPermission(videoPermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{videoPermission()}, VIDEO_PERMISSION);
            return;
        }
        if (busy || closed) return;
        setBusy(true);
        worker.execute(() -> {
            List<ScreensaverFolders.Folder> folders = new ArrayList<>();
            boolean available = true;
            try { folders = ScreensaverFolders.list(getApplicationContext()); }
            catch (IOException | RuntimeException failure) {
                available = false;
                android.util.Log.w("TvHome.Screensaver", "Cannot list video folders", failure);
            }
            List<ScreensaverFolders.Folder> result = folders;
            boolean loaded = available;
            runOnUiThread(() -> {
                if (closed) return;
                setBusy(false);
                List<String> labels = new ArrayList<>();
                for (ScreensaverFolders.Folder folder : result)
                    labels.add(getString(R.string.screensaver_folder_option, folder.name, folder.count));
                labels.add(getString(R.string.screensaver_local_folder));
                new AlertDialog.Builder(this).setTitle(loaded && !result.isEmpty()
                                ? R.string.screensaver_folder_choose : R.string.screensaver_folder_library_empty)
                        .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                            if (index < result.size()) bind(result.get(index).uri, 0);
                            else perform(() -> {
                                File local = ScreensaverStore.localFolder(this);
                                if (!local.isDirectory() && !local.mkdirs()) throw new IOException("Cannot create local folder");
                                ScreensaverStore.bind(this, Uri.fromFile(local), 0, () -> closed);
                            });
                        }).setNegativeButton(android.R.string.cancel, null).show();
            });
        });
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != VIDEO_PERMISSION) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) pickLibrary();
        else Toast.makeText(this, R.string.screensaver_folder_permission, Toast.LENGTH_LONG).show();
    }

    private void bind(Uri folder, int flags) {
        perform(() -> ScreensaverStore.bind(getApplicationContext(), folder, flags, () -> closed));
    }

    private void setBusy(boolean value) {
        if (value) previousFocus = getCurrentFocus();
        busy = value;
        for (View control : controls) control.setEnabled(!value);
        if (value) status.setText(R.string.screensaver_loading);
        else if (previousFocus != null && controls.contains(previousFocus)) previousFocus.requestFocus();
        else if (!controls.isEmpty()) controls.get(0).requestFocus();
    }

    private void refresh() {
        if (busy || closed) return;
        setBusy(true);
        worker.execute(() -> {
            ScreensaverStore.Library library = null;
            try { library = ScreensaverStore.scan(getApplicationContext()); }
            catch (IOException | RuntimeException failure) { android.util.Log.w("TvHome.Screensaver", "Cannot refresh videos", failure); }
            ScreensaverStore.Library loaded = library;
            runOnUiThread(() -> {
                if (closed) return;
                count = loaded == null ? 0 : loaded.videos.size();
                preferences.edit().putInt("video_count", count).apply();
                setBusy(false);
                String folder = loaded == null ? preferences.getString("folder_label", "") : loaded.folder;
                folderStatus.setText(folder.isEmpty() ? getString(R.string.screensaver_folder_none) : folder);
                folderStatus.setContentDescription(folderStatus.getText());
                status.setText(loaded == null ? getString(R.string.screensaver_folder_unavailable)
                        : getString(loaded.limited ? R.string.screensaver_folder_limited : R.string.screensaver_video_count, count));
            });
        });
    }

    private interface IoAction { void run() throws IOException; }

    private void perform(IoAction action) {
        if (busy || closed) return;
        setBusy(true);
        worker.execute(() -> {
            int error = 0;
            try { action.run(); }
            catch (ScreensaverStore.EmptyFolderException failure) { error = R.string.screensaver_folder_empty; }
            catch (SecurityException failure) { error = R.string.screensaver_folder_permission; }
            catch (IOException | RuntimeException failure) {
                error = R.string.screensaver_folder_unavailable;
                android.util.Log.w("TvHome.Screensaver", "Folder update failed", failure);
            }
            int failure = error;
            runOnUiThread(() -> {
                if (closed) return;
                setBusy(false);
                Toast.makeText(this, failure == 0 ? R.string.screensaver_folder_updated : failure, Toast.LENGTH_LONG).show();
                if (failure == 0) refresh();
                else status.setText(failure);
            });
        });
    }

    @Override protected void onDestroy() { closed = true; worker.shutdownNow(); super.onDestroy(); }
}
