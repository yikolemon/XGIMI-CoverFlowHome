package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Configuration/import has no player or thumbnails; importing a new set is transactional. */
public final class ScreensaverSettingsActivity extends Activity {
    private static final int PICK = 1;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<View> controls = new ArrayList<>();
    private volatile boolean closed;
    private boolean busy;
    private int count;
    private SharedPreferences preferences;
    private TextView status, timeout;
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
        status = text(getString(R.string.screensaver_loading), 14);
        panel.addView(status, new LinearLayout.LayoutParams(-1, dp(40)));
        TextView importButton = button(R.string.screensaver_import, this::pick);
        timeout = button(R.string.screensaver_timeout_off, this::chooseTimeout);
        updateTimeout();
        Switch mute = new Switch(this);
        mute.setText(R.string.screensaver_mute);
        mute.setTextSize(16);
        mute.setTextColor(Color.WHITE);
        mute.setChecked(preferences.getBoolean("muted", true));
        mute.setPadding(dp(20), 0, dp(20), 0);
        mute.setOnCheckedChangeListener((view, checked) -> preferences.edit().putBoolean("muted", checked).apply());
        panel.addView(mute, itemParams());
        controls.add(mute);
        button(R.string.screensaver_preview, () -> {
            if (count == 0) Toast.makeText(this, R.string.screensaver_no_videos, Toast.LENGTH_LONG).show();
            else startActivity(new Intent(this, ScreensaverActivity.class));
        });
        button(R.string.screensaver_refresh, this::refresh);
        button(R.string.screensaver_remove, () -> new AlertDialog.Builder(this)
                .setMessage(R.string.screensaver_remove_confirm)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> perform(() -> ScreensaverStore.removeImported(this)))
                .setNegativeButton(android.R.string.cancel, null).show());
        File external = getExternalFilesDir(null);
        TextView help = text(getString(R.string.screensaver_settings_hint,
                external == null ? "—" : new File(external, "screensaver").getAbsolutePath()), 12);
        help.setPadding(0, dp(18), 0, 0);
        help.setTextColor(0xAAFFFFFF);
        panel.addView(help, new LinearLayout.LayoutParams(-1, -2));
        importButton.requestFocus();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }

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
        for (String action : new String[]{Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_GET_CONTENT}) {
            try {
                startActivityForResult(new Intent(action).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), PICK);
                return;
            } catch (ActivityNotFoundException | SecurityException unavailable) { /* Manual local-directory fallback. */ }
        }
        Toast.makeText(this, R.string.screensaver_no_picker, Toast.LENGTH_LONG).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK || result != RESULT_OK || data == null || busy) return;
        LinkedHashSet<Uri> sources = new LinkedHashSet<>();
        if (data.getClipData() != null) {
            if (data.getClipData().getItemCount() > ScreensaverRules.MAX_VIDEOS) {
                Toast.makeText(this, R.string.screensaver_import_failed, Toast.LENGTH_LONG).show();
                return;
            }
            for (int i = 0; i < data.getClipData().getItemCount(); i++) sources.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) sources.add(data.getData());
        perform(() -> ScreensaverStore.importVideos(getApplicationContext(), new ArrayList<>(sources), () -> closed));
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
            int size = 0;
            try {
                ScreensaverStore.cleanAbandonedImports(getApplicationContext());
                File external = getExternalFilesDir(null);
                if (external != null) new File(external, "screensaver").mkdirs();
                size = ScreensaverStore.videos(this).size();
            } catch (RuntimeException failure) { android.util.Log.w("TvHome.Screensaver", "Cannot refresh videos", failure); }
            int loaded = size;
            runOnUiThread(() -> {
                if (closed) return;
                count = loaded;
                preferences.edit().putInt("video_count", count).apply();
                setBusy(false);
                status.setText(getString(R.string.screensaver_video_count, count));
            });
        });
    }

    private interface IoAction { void run() throws IOException; }

    private void perform(IoAction action) {
        if (busy || closed) return;
        setBusy(true);
        worker.execute(() -> {
            boolean success = false;
            try { action.run(); success = true; }
            catch (IOException | RuntimeException failure) { android.util.Log.w("TvHome.Screensaver", "Video update failed", failure); }
            boolean applied = success;
            runOnUiThread(() -> {
                if (closed) return;
                setBusy(false);
                Toast.makeText(this, applied ? R.string.screensaver_imported : R.string.screensaver_import_failed, Toast.LENGTH_LONG).show();
                refresh();
            });
        });
    }

    @Override protected void onDestroy() { closed = true; worker.shutdownNow(); super.onDestroy(); }
}
