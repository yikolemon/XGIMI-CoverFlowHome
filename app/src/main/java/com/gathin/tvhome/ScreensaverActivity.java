package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Foreground-only video screen. No player/service/thumbnail infrastructure exists on the desktop. */
public final class ScreensaverActivity extends Activity implements SurfaceHolder.Callback {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final ScreensaverRules.DoubleBack back = new ScreensaverRules.DoubleBack();
    private final Set<String> failed = new HashSet<>();
    private List<ScreensaverVideo> videos = new ArrayList<>();
    private FrameLayout root;
    private SurfaceView surface;
    private TextView status, hint;
    private SharedPreferences preferences;
    private ScreensaverChooser chooser;
    private ThreadPoolExecutor coverLoader;
    private MediaPlayer player;
    private AudioManager audio;
    private AudioFocusRequest audioRequest;
    private boolean active, closed, surfaceReady, prepared, focusGranted;
    private boolean audioPaused;
    private int selected, videoWidth, videoHeight, playbackGeneration, audioGeneration;
    private Runnable prepareTimeout;
    private final Runnable hideChooser = this::hideChooser;
    private final Runnable hideHint = () -> hint.setVisibility(View.GONE);

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        preferences = ScreensaverStore.preferences(this);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitVideo());
        status = text(R.string.screensaver_loading);
        root.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        hint = text(R.string.screensaver_play_hint);
        hint.setBackgroundColor(0x99000000);
        root.addView(hint, new FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM));
        loader.execute(() -> {
            List<ScreensaverVideo> loaded;
            boolean unavailable = false;
            try { loaded = ScreensaverStore.scan(getApplicationContext()).videos; }
            catch (IOException | RuntimeException failure) {
                Log.w("TvHome.Screensaver", "Cannot list videos", failure);
                loaded = new ArrayList<>();
                unavailable = true;
            }
            List<ScreensaverVideo> result = loaded;
            boolean missing = unavailable;
            handler.post(() -> {
                if (closed || isFinishing()) return;
                videos = result;
                preferences.edit().putInt("video_count", videos.size()).apply();
                String previous = preferences.getString("selected", "");
                for (int i = 0; i < videos.size(); i++) {
                    ScreensaverVideo video = videos.get(i);
                    if (video.key.equals(previous) || "file".equals(video.uri.getScheme()) && previous.equals(video.uri.getPath())) selected = i;
                }
                if (videos.isEmpty()) {
                    if (getIntent().getBooleanExtra("automatic", false)) { finish(); return; }
                    status.setText(missing ? R.string.screensaver_folder_unavailable : R.string.screensaver_no_videos);
                }
                else maybePlay();
            });
        });
        loader.shutdown();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(int resource) {
        TextView view = new TextView(this);
        view.setText(resource);
        view.setTextColor(Color.WHITE);
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    @Override protected void onResume() {
        super.onResume();
        active = true;
        back.reset();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.postDelayed(hideHint, 4000);
        maybePlay();
    }

    private void maybePlay() {
        if (active && surfaceReady && !closed && player == null && !videos.isEmpty() && failed.size() < videos.size()) play(selected, false);
    }

    private void play(int index, boolean manual) {
        if (!active || !surfaceReady || closed || videos.isEmpty()) return;
        releasePlayer();
        selected = Math.max(0, Math.min(videos.size() - 1, index));
        ScreensaverVideo video = videos.get(selected);
        if (manual) failed.remove(video.key);
        status.setText(R.string.screensaver_loading);
        status.setVisibility(View.VISIBLE);
        try {
            MediaPlayer current = new MediaPlayer();
            player = current;
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
            current.setAudioAttributes(attributes);
            float volume = configuredVolume();
            boolean audible = volume > 0f && requestAudio(attributes);
            float applied = audible ? volume : 0f;
            current.setVolume(applied, applied);
            current.setDisplay(surface.getHolder());
            current.setLooping(true);
            current.setOnVideoSizeChangedListener((p, width, height) -> {
                if (p != player) return;
                videoWidth = width;
                videoHeight = height;
                fitVideo();
            });
            current.setOnPreparedListener(p -> {
                if (p != player || !active || !surfaceReady || closed) return;
                handler.removeCallbacks(prepareTimeout);
                prepared = true;
                try {
                    if (!audioPaused) p.start();
                    status.setVisibility(View.GONE);
                    preferences.edit().putString("selected", video.key).apply();
                } catch (IllegalStateException failure) { failVideo(p); }
            });
            current.setOnErrorListener((p, what, extra) -> { failVideo(p); return true; });
            current.setDataSource(this, video.uri);
            current.prepareAsync();
            prepareTimeout = () -> failVideo(current);
            handler.postDelayed(prepareTimeout, 15_000);
        } catch (IOException | RuntimeException failure) {
            Log.w("TvHome.Screensaver", "Cannot play " + video.name, failure);
            failVideo(player);
        }
    }

    private float configuredVolume() {
        return ScreensaverRules.playerVolume(
                preferences.getInt(ScreensaverStore.VOLUME_KEY, ScreensaverRules.DEFAULT_VOLUME_PERCENT));
    }

    private boolean requestAudio(AudioAttributes attributes) {
        if (focusGranted) return true;
        int generation = ++audioGeneration;
        audioRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (!active || player == null || generation != audioGeneration) return;
                    try {
                        if (change == AudioManager.AUDIOFOCUS_LOSS) { finish(); }
                        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            audioPaused = true;
                            if (prepared) player.pause();
                        }
                        else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                            float ducked = configuredVolume() * 0.15f;
                            player.setVolume(ducked, ducked);
                        }
                        else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                            audioPaused = false;
                            float volume = configuredVolume();
                            player.setVolume(volume, volume);
                            if (prepared) player.start();
                        }
                    } catch (IllegalStateException failure) { finish(); }
                }, handler).build();
        focusGranted = audio.requestAudioFocus(audioRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focusGranted;
    }

    private void failVideo(MediaPlayer current) {
        if (current != player || !active || closed || videos.isEmpty()) return;
        hideChooser();
        failed.add(videos.get(selected).key);
        releasePlayer();
        int generation = playbackGeneration;
        for (int step = 1; step <= videos.size(); step++) {
            int next = (selected + step) % videos.size();
            if (!failed.contains(videos.get(next).key)) {
                Toast.makeText(this, R.string.screensaver_skipping, Toast.LENGTH_SHORT).show();
                handler.post(() -> { if (generation == playbackGeneration && active) play(next, false); });
                return;
            }
        }
        status.setText(R.string.screensaver_play_failed);
        status.setVisibility(View.VISIBLE);
        abandonAudio();
    }

    private void fitVideo() {
        if (videoWidth <= 0 || videoHeight <= 0 || root.getWidth() <= 0 || root.getHeight() <= 0) return;
        float scale = Math.min(root.getWidth() / (float) videoWidth, root.getHeight() / (float) videoHeight);
        int width = Math.max(1, Math.round(videoWidth * scale)), height = Math.max(1, Math.round(videoHeight * scale));
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (params.width != width || params.height != height) {
            params.width = width;
            params.height = height;
            surface.setLayoutParams(params);
        }
    }

    private void showChooser() {
        if (videos.isEmpty() || chooser != null) { deferChooserHide(); return; }
        hint.setVisibility(View.GONE);
        if (coverLoader == null) coverLoader = new ThreadPoolExecutor(1, 1, 0L,
                TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        int currentVolume = ScreensaverRules.clampVolumePercent(
                preferences.getInt(ScreensaverStore.VOLUME_KEY, ScreensaverRules.DEFAULT_VOLUME_PERCENT));
        chooser = new ScreensaverChooser(this, videos, selected, currentVolume, coverLoader, this::deferChooserHide, index -> {
            if (ScreensaverRules.shouldRestartVideo(index, selected, player != null && prepared)) play(index, true);
            hideChooser();
        }, this::setVolumePercent);
        root.addView(chooser.view, new FrameLayout.LayoutParams(-1, dp(264), Gravity.BOTTOM));
        deferChooserHide();
    }

    private void setVolumePercent(int percent) {
        int clamped = ScreensaverRules.clampVolumePercent(percent);
        preferences.edit().putInt(ScreensaverStore.VOLUME_KEY, clamped).apply();
        if (player == null) return;
        float volume = configuredVolume();
        if (volume > 0f && !focusGranted) {
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
            if (!requestAudio(attributes)) volume = 0f;
        }
        try { player.setVolume(volume, volume); }
        catch (IllegalStateException ignored) { }
    }

    private void deferChooserHide() { handler.removeCallbacks(hideChooser); handler.postDelayed(hideChooser, 8000); }

    private void hideChooser() {
        handler.removeCallbacks(hideChooser);
        if (chooser == null) return;
        root.removeView(chooser.view);
        chooser.close();
        chooser = null;
    }

    private void back() {
        if (chooser != null) {
            hideChooser();
            back.reset();
            return;
        }
        if (back.press(SystemClock.uptimeMillis())) { finish(); return; }
        Toast.makeText(this, R.string.screensaver_back_again, Toast.LENGTH_SHORT).show();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) back();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            back.reset();
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (chooser != null) chooser.down();
                else if (event.getRepeatCount() == 0) showChooser();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (chooser == null || !chooser.up()) hideChooser();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (chooser != null) chooser.move(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (chooser != null && event.getRepeatCount() == 0) chooser.confirm();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() { back(); }
    @Override public void surfaceCreated(SurfaceHolder holder) { surfaceReady = true; maybePlay(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surfaceReady = false; releasePlayer(); }

    private void releasePlayer() {
        playbackGeneration++;
        if (prepareTimeout != null) handler.removeCallbacks(prepareTimeout);
        prepared = false;
        MediaPlayer current = player;
        player = null;
        if (current != null) {
            current.setOnPreparedListener(null);
            current.setOnErrorListener(null);
            current.setOnVideoSizeChangedListener(null);
            current.release();
        }
    }

    @Override protected void onPause() {
        active = false;
        back.reset();
        releasePlayer();
        hideChooser();
        handler.removeCallbacks(hideHint);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        abandonAudio();
        super.onPause();
    }

    private void abandonAudio() {
        audioGeneration++;
        if (audioRequest != null) audio.abandonAudioFocusRequest(audioRequest);
        audioRequest = null;
        focusGranted = false;
        audioPaused = false;
    }

    @Override protected void onStop() { super.onStop(); if (!isChangingConfigurations()) finish(); }

    @Override protected void onDestroy() {
        closed = true;
        releasePlayer();
        hideChooser();
        handler.removeCallbacksAndMessages(null);
        loader.shutdownNow();
        if (coverLoader != null) coverLoader.shutdownNow();
        surface.getHolder().removeCallback(this);
        super.onDestroy();
    }
}
