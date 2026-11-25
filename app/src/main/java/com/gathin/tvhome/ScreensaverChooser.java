package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.IntConsumer;

/** Created only on Down; centre-first scaled frames, no persistent decoder/cache on Home. */
final class ScreensaverChooser {
    final HorizontalScrollView view;
    private final Activity activity;
    private final List<File> videos;
    private final List<ImageView> images = new ArrayList<>();
    private final List<LinearLayout> tiles = new ArrayList<>();
    private final LruCache<String, Bitmap> covers = new LruCache<>(6);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor loader;
    private final Runnable interaction;
    private final IntConsumer choose;
    private volatile int generation;
    private boolean closed;
    private int selection;

    ScreensaverChooser(Activity activity, List<File> videos, int selected, ThreadPoolExecutor loader,
                       Runnable interaction, IntConsumer choose) {
        this.activity = activity;
        this.videos = videos;
        this.interaction = interaction;
        this.choose = choose;
        this.loader = loader;
        view = new HorizontalScrollView(activity);
        view.setHorizontalScrollBarEnabled(false);
        view.setBackgroundColor(0xE610151F);
        view.setFillViewport(true);
        LinearLayout row = new LinearLayout(activity);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        view.addView(row);
        for (int i = 0; i < videos.size(); i++) {
            final int position = i;
            LinearLayout tile = new LinearLayout(activity);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(5), dp(5), dp(5), dp(4));
            ImageView image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setImageResource(R.drawable.ic_apps);
            tile.addView(image, new LinearLayout.LayoutParams(dp(160), dp(90)));
            TextView label = new TextView(activity);
            label.setText(ScreensaverStore.title(videos.get(i)));
            label.setTextColor(Color.WHITE);
            label.setTextSize(12);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tile.addView(label, new LinearLayout.LayoutParams(dp(160), dp(26)));
            tile.setContentDescription(label.getText());
            tile.setOnClickListener(v -> { select(position); choose.accept(position); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.setMargins(dp(5), 0, dp(5), 0);
            row.addView(tile, params);
            tiles.add(tile);
            images.add(image);
        }
        select(selected);
    }

    void move(int delta) { select(Math.max(0, Math.min(videos.size() - 1, selection + delta))); }

    void confirm() { if (!closed && !videos.isEmpty()) choose.accept(selection); }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    private void select(int position) {
        if (closed || videos.isEmpty()) return;
        selection = Math.max(0, Math.min(videos.size() - 1, position));
        interaction.run();
        int request = ++generation;
        loader.getQueue().clear();
        for (int i = 0; i < tiles.size(); i++) {
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(i == selection ? 0x3349BFC5 : 0x12FFFFFF);
            surface.setCornerRadius(dp(8));
            surface.setStroke(dp(i == selection ? 2 : 1), i == selection ? Color.WHITE : 0x33FFFFFF);
            tiles.get(i).setBackground(surface);
            if (Math.abs(i - selection) > 2) images.get(i).setImageResource(R.drawable.ic_apps);
        }
        int selected = selection;
        view.post(() -> {
            if (!closed) view.smoothScrollTo(Math.max(0, tiles.get(selected).getLeft()
                    - (view.getWidth() - tiles.get(selected).getWidth()) / 2), 0);
        });
        List<Integer> missing = new ArrayList<>();
        for (int offset : new int[]{0, -1, 1, -2, 2}) {
            int index = selection + offset;
            if (index < 0 || index >= videos.size()) continue;
            Bitmap cached = covers.get(videos.get(index).getAbsolutePath());
            if (cached != null) images.get(index).setImageBitmap(cached);
            else missing.add(index);
        }
        if (Build.VERSION.SDK_INT < 27 || missing.isEmpty()) return;
        loader.execute(() -> {
            for (int index : missing) {
                if (request != generation || Thread.currentThread().isInterrupted()) return;
                Bitmap bitmap = frame(videos.get(index));
                if (bitmap == null) continue;
                if (!handler.post(() -> {
                    if (closed || request != generation) { bitmap.recycle(); return; }
                    covers.put(videos.get(index).getAbsolutePath(), bitmap);
                    images.get(index).setImageBitmap(bitmap);
                })) bitmap.recycle();
            }
        });
    }

    private static Bitmap frame(File file) {
        if (Build.VERSION.SDK_INT < 27) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 135);
        } catch (RuntimeException | OutOfMemoryError failure) {
            Log.w("TvHome.Screensaver", "Cover unavailable: " + file.getName(), failure);
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
        for (ImageView image : images) image.setImageDrawable(null);
        covers.evictAll();
        view.removeAllViews();
        images.clear();
        tiles.clear();
    }
}
