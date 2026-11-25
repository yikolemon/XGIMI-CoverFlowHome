package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Standalone HOME entry point for the XGIMI Cover Flow desktop. */
public final class MainActivity extends Activity {
    // Serial across recreation so two startup tasks cannot extract the same bundled files at once.
    private static final ExecutorService startupLoader = Executors.newSingleThreadExecutor();
    private CoverFlowHome home;
    private Future<?> startupTask;
    private boolean started, resumed, destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        startupTask = startupLoader.submit(() -> {
            try {
                Config config = Config.load(getApplicationContext());
                runOnUiThread(() -> {
                    if (destroyed || isFinishing()) return;
                    home = new CoverFlowHome(this, config);
                    if (started) home.start();
                    if (resumed) home.resume();
                });
            } catch (RuntimeException failure) {
                Log.e("TvHome.Startup", "Could not load home configuration", failure);
                runOnUiThread(() -> {
                    if (destroyed || isFinishing()) return;
                    TextView retry = new TextView(this);
                    retry.setText(R.string.home_load_failed);
                    retry.setTextColor(0xFFFFFFFF);
                    retry.setGravity(Gravity.CENTER);
                    retry.setFocusable(true);
                    retry.setOnClickListener(view -> recreate());
                    setContentView(retry);
                    retry.requestFocus();
                });
            }
        });
    }

    @Override protected void onStart() { super.onStart(); started = true; if (home != null) home.start(); }
    @Override protected void onResume() { super.onResume(); resumed = true; if (home != null) home.resume(); }
    @Override protected void onPause() { resumed = false; if (home != null) home.pause(); super.onPause(); }
    @Override protected void onStop() { started = false; if (home != null) home.stop(); super.onStop(); }
    @Override public void onUserInteraction() { super.onUserInteraction(); if (home != null) home.userInput(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (home != null) home.windowFocus(focused); }
    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (home != null) home.home();
    }
    @Override public void onBackPressed() { if (home != null) home.back(); }
    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CoverFlowHome.WALLPAPER_REQUEST && result == RESULT_OK) recreate();
    }
    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); if (home != null) home.trim(level); }
    @Override protected void onDestroy() {
        destroyed = true;
        // Let an in-flight extraction finish, but do not start a queued task for a dead Activity.
        if (startupTask != null) startupTask.cancel(false);
        if (home != null) home.close();
        super.onDestroy();
    }
}
