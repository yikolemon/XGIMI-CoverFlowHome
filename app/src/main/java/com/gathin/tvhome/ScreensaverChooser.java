package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.IntConsumer;

/** Floating covers over a black fade, no translucent rectangular panel or video backdrop blur. */
final class ScreensaverChooser {
    final FrameLayout view;
    private final Activity activity;
    private final List<ScreensaverVideo> videos;
    private final List<ImageView> images = new ArrayList<>();
    private final List<LinearLayout> tiles = new ArrayList<>();
    private final List<FrameLayout> frames = new ArrayList<>();
    private final List<GradientDrawable> frameBackgrounds = new ArrayList<>();
    private final List<TextView> labels = new ArrayList<>();
    private final LruCache<String, Bitmap> covers = new LruCache<>(6);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor loader;
    private final Runnable interaction;
    private final IntConsumer choose;
    private final IntConsumer volumeChanged;
    private final HorizontalScrollView scroll;
    private final TextView title, volumeValue;
    private final LinearLayout volumeRow;
    private final GradientDrawable volumeRowBg, volumeFillBg;
    private final View volumeFill;
    private final int playing;
    private volatile int generation;
    private boolean closed, focusVolume;
    private int selection, volumePercent;

    ScreensaverChooser(Activity activity, List<ScreensaverVideo> videos, int playing, int volumePercent,
                       ThreadPoolExecutor loader, Runnable interaction, IntConsumer choose,
                       IntConsumer volumeChanged) {
        this.activity = activity;
        this.videos = videos;
        this.interaction = interaction;
        this.choose = choose;
        this.volumeChanged = volumeChanged;
        this.loader = loader;
        this.playing = playing;
        this.volumePercent = ScreensaverRules.clampVolumePercent(volumePercent);
        view = new FrameLayout(activity);
        view.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00080E1C, 0x88080E1C, 0xEE080E1C}));
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.BOTTOM);
        content.setPadding(0, dp(18), 0, dp(18));
        view.addView(content, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout heading = new LinearLayout(activity);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        title = text(18, 0xF2FFFFFF);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView keys = text(11, 0xCCFFFFFF);
        keys.setText(R.string.screensaver_chooser_keys);
        keys.setPadding(dp(12), dp(5), dp(12), dp(5));
        keys.setBackground(rounded(0x66181C26, 0x28FFFFFF, 1, 12));
        heading.addView(keys, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.setMargins(dp(44), 0, dp(44), dp(8));
        content.addView(heading, headingParams);

        scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setOnTouchListener((v, event) -> { interaction.run(); return false; });
        LinearLayout row = new LinearLayout(activity);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(dp(36), dp(4), dp(36), dp(6));
        scroll.addView(row);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, dp(128)));

        for (int i = 0; i < videos.size(); i++) {
            final int position = i;
            LinearLayout tile = new LinearLayout(activity);
            tile.setOrientation(LinearLayout.VERTICAL);
            FrameLayout frame = new FrameLayout(activity);
            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackground(rounded(0xFF10151B, 0, 0, 6));
            image.setClipToOutline(true);
            image.setImageResource(R.drawable.ic_apps);
            FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(-1, -1);
            imageParams.setMargins(dp(3), dp(3), dp(3), dp(3));
            frame.addView(image, imageParams);
            if (i == playing) {
                TextView badge = text(9, Color.WHITE);
                badge.setText(R.string.screensaver_playing);
                badge.setPadding(dp(7), dp(3), dp(7), dp(3));
                badge.setBackground(rounded(0xE51C342D, 0, 0, 4));
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
                badgeParams.setMargins(dp(9), dp(9), 0, 0);
                frame.addView(badge, badgeParams);
            }
            tile.addView(frame, new LinearLayout.LayoutParams(dp(160), dp(94)));
            TextView label = text(11, 0xB8FFFFFF);
            label.setText(videos.get(i).title());
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            tile.addView(label, new LinearLayout.LayoutParams(dp(160), dp(22)));
            tile.setContentDescription(videos.get(i).title());
            tile.setOnClickListener(v -> { select(position); choose.accept(position); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMargins(dp(8), 0, dp(8), 0);
            row.addView(tile, params);
            GradientDrawable frameBg = rounded(0x0CFFFFFF, 0x28FFFFFF, 1, 10);
            frame.setBackground(frameBg);
            tiles.add(tile);
            frames.add(frame);
            frameBackgrounds.add(frameBg);
            labels.add(label);
            images.add(image);
        }

        volumeRow = new LinearLayout(activity);
        volumeRow.setOrientation(LinearLayout.HORIZONTAL);
        volumeRow.setGravity(Gravity.CENTER_VERTICAL);
        volumeRow.setPadding(dp(16), 0, dp(16), 0);
        volumeRowBg = rounded(0xCC181C26, 0x25FFFFFF, 1, 12);
        volumeRow.setBackground(volumeRowBg);
        TextView volumeLabel = text(13, 0xE6FFFFFF);
        volumeLabel.setText(R.string.screensaver_volume_title);
        volumeRow.addView(volumeLabel, new LinearLayout.LayoutParams(-2, -2));

        TextView minus = text(15, 0xF2FFFFFF);
        minus.setText("−");
        minus.setGravity(Gravity.CENTER);
        minus.setBackground(rounded(0x22FFFFFF, 0x30FFFFFF, 1, 8));
        minus.setOnClickListener(v -> adjustVolume(-10));
        LinearLayout.LayoutParams minusParams = new LinearLayout.LayoutParams(dp(34), dp(28));
        minusParams.setMargins(dp(16), 0, dp(10), 0);
        volumeRow.addView(minus, minusParams);

        FrameLayout track = new FrameLayout(activity);
        track.setBackground(rounded(0x28FFFFFF, 0, 0, 3));
        volumeFill = new View(activity);
        volumeFillBg = rounded(0xCCFFFFFF, 0, 0, 3);
        volumeFill.setBackground(volumeFillBg);
        track.addView(volumeFill, new FrameLayout.LayoutParams(0, -1));
        volumeRow.addView(track, new LinearLayout.LayoutParams(0, dp(6), 1));

        TextView plus = text(15, 0xF2FFFFFF);
        plus.setText("+");
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(rounded(0x22FFFFFF, 0x30FFFFFF, 1, 8));
        plus.setOnClickListener(v -> adjustVolume(10));
        LinearLayout.LayoutParams plusParams = new LinearLayout.LayoutParams(dp(34), dp(28));
        plusParams.setMargins(dp(10), 0, dp(12), 0);
        volumeRow.addView(plus, plusParams);

        volumeValue = text(13, 0xF2FFFFFF);
        volumeValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        volumeRow.addView(volumeValue, new LinearLayout.LayoutParams(dp(44), -2));

        LinearLayout.LayoutParams volumeParams = new LinearLayout.LayoutParams(-1, dp(44));
        volumeParams.setMargins(dp(44), dp(6), dp(44), 0);
        content.addView(volumeRow, volumeParams);
        track.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateVolumeUi());

        select(playing);
        updateVolumeUi();
        view.setAlpha(0f);
        view.setTranslationY(dp(8));
        view.animate().alpha(1f).translationY(0f).setDuration(160).start();
    }

    private TextView text(int size, int color) {
        TextView label = new TextView(activity);
        label.setTextSize(size);
        label.setTextColor(color);
        return label;
    }

    private GradientDrawable rounded(int color, int stroke, int width, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (width > 0) drawable.setStroke(dp(width), stroke);
        return drawable;
    }

    void move(int delta) {
        if (focusVolume) {
            adjustVolume(delta * 10);
            return;
        }
        int next = ScreensaverRules.chooserIndex(selection, delta, videos.size());
        if (next != selection) select(next);
        else interaction.run();
    }

    void down() {
        interaction.run();
        if (!focusVolume) {
            focusVolume = true;
            updateFocusUi();
        }
    }

    boolean up() {
        if (!focusVolume) return false;
        focusVolume = false;
        interaction.run();
        updateFocusUi();
        return true;
    }

    void confirm() {
        if (closed || videos.isEmpty()) return;
        if (focusVolume) {
            interaction.run();
            return;
        }
        choose.accept(selection);
    }

    private void adjustVolume(int delta) {
        if (closed) return;
        interaction.run();
        int next = ScreensaverRules.adjustVolumePercent(volumePercent, delta);
        if (next != volumePercent) {
            volumePercent = next;
            updateVolumeUi();
            volumeChanged.accept(volumePercent);
        }
    }

    private void updateVolumeUi() {
        if (closed) return;
        volumeValue.setText(activity.getString(R.string.screensaver_volume_option, volumePercent));
        int width = ((View) volumeFill.getParent()).getWidth();
        if (width > 0) {
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) volumeFill.getLayoutParams();
            int targetWidth = Math.round(width * (volumePercent / 100f));
            if (params.width != targetWidth) {
                params.width = targetWidth;
                volumeFill.setLayoutParams(params);
            }
        }
        updateFocusUi();
    }

    private void updateFocusUi() {
        if (closed) return;
        for (int i = 0; i < tiles.size(); i++) {
            boolean focused = !focusVolume && i == selection;
            GradientDrawable bg = frameBackgrounds.get(i);
            bg.setColor(focused ? 0x25FFFFFF : 0x0CFFFFFF);
            bg.setStroke(dp(focused ? 2 : 1), focused ? 0xEEFFFFFF : 0x28FFFFFF);
            tiles.get(i).animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                    .alpha(focused || i == playing ? 1f : 0.72f).setDuration(130).start();
            labels.get(i).setTextColor(focused ? 0xF2FFFFFF : 0x99FFFFFF);
        }
        volumeRowBg.setColor(focusVolume ? 0xE6222836 : 0xCC181C26);
        volumeRowBg.setStroke(dp(focusVolume ? 2 : 1), focusVolume ? 0xEEFFFFFF : 0x25FFFFFF);
        volumeFillBg.setColor(focusVolume ? 0xFF8DE5EA : 0xCCFFFFFF);
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private void select(int position) {
        if (closed || videos.isEmpty()) return;
        selection = Math.max(0, Math.min(videos.size() - 1, position));
        interaction.run();
        title.setText(videos.get(selection).title());
        int request = ++generation;
        loader.getQueue().clear();
        for (int i = 0; i < tiles.size(); i++) {
            if (Math.abs(i - selection) > 2) images.get(i).setImageResource(R.drawable.ic_apps);
        }
        updateFocusUi();
        int selected = selection;
        scroll.post(() -> {
            if (!closed) scroll.smoothScrollTo(Math.max(0, tiles.get(selected).getLeft()
                    - (scroll.getWidth() - tiles.get(selected).getWidth()) / 2), 0);
        });
        List<Integer> missing = new ArrayList<>();
        for (int offset : new int[]{0, -1, 1, -2, 2}) {
            int index = selection + offset;
            if (index < 0 || index >= videos.size()) continue;
            Bitmap cached = covers.get(videos.get(index).artworkKey);
            if (cached != null) images.get(index).setImageBitmap(cached);
            else missing.add(index);
        }
        if (Build.VERSION.SDK_INT < 27 || missing.isEmpty()) return;
        Context context = activity.getApplicationContext();
        loader.execute(() -> {
            for (int index : missing) {
                if (request != generation || Thread.currentThread().isInterrupted()) return;
                Bitmap bitmap = frame(context, videos.get(index));
                if (bitmap == null) continue;
                if (!handler.post(() -> {
                    if (closed || request != generation) { bitmap.recycle(); return; }
                    covers.put(videos.get(index).artworkKey, bitmap);
                    images.get(index).setImageBitmap(bitmap);
                })) bitmap.recycle();
            }
        });
    }

    private static Bitmap frame(Context context, ScreensaverVideo video) {
        if (Build.VERSION.SDK_INT < 27) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, video.uri);
            return retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 135);
        } catch (RuntimeException | OutOfMemoryError failure) {
            Log.w("TvHome.Screensaver", "Cover unavailable: " + video.name, failure);
            return null;
        } finally {
            try { retriever.release(); }
            catch (java.io.IOException | RuntimeException failure) { Log.w("TvHome.Screensaver", "Cover release failed", failure); }
        }
    }

    void close() {
        closed = true;
        generation++;
        loader.getQueue().clear();
        view.animate().cancel();
        for (LinearLayout tile : tiles) tile.animate().cancel();
        for (ImageView image : images) image.setImageDrawable(null);
        covers.evictAll();
        view.removeAllViews();
        images.clear();
        tiles.clear();
        frames.clear();
        frameBackgrounds.clear();
        labels.clear();
    }
}
