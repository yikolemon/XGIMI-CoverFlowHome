package com.gathin.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;

/** Standalone HOME entry point for the XGIMI Cover Flow desktop. */
public final class MainActivity extends Activity {
    private CoverFlowHome home;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        home = new CoverFlowHome(this, Config.load(this));
    }

    @Override protected void onStart() { super.onStart(); if (home != null) home.start(); }
    @Override protected void onResume() { super.onResume(); if (home != null) home.resume(); }
    @Override protected void onPause() { if (home != null) home.pause(); super.onPause(); }
    @Override protected void onStop() { if (home != null) home.stop(); super.onStop(); }
    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (home != null) home.home();
    }
    @Override public void onBackPressed() { if (home != null) home.back(); else super.onBackPressed(); }
    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request == CoverFlowHome.WALLPAPER_REQUEST && result == RESULT_OK) recreate();
    }
    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); if (home != null) home.trim(level); }
    @Override protected void onDestroy() { if (home != null) home.close(); super.onDestroy(); }
}
