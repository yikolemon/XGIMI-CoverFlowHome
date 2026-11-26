package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.io.File;
import java.lang.ref.WeakReference;

/** Opt-in home scene. Keeps the classic grid's lifecycle and focus rules out of the carousel. */
final class CoverFlowHome {
    static final int WALLPAPER_REQUEST = 43;
    private final Activity activity;
    private final Config config;
    private final long wallpaperRevision;
    private final FrameLayout root;
    private final CoverFlowView carousel;
    private final TextView libraryButton;
    private final WallpaperPlayer wallpaper;
    private final float density;
    private HomeLibraryDialog library;
    private boolean registered;
    private boolean resumed;
    private boolean appsDirty;
    private final java.util.concurrent.ExecutorService appLoader = java.util.concurrent.Executors.newSingleThreadExecutor();
    private int loadGeneration;
    private boolean closed;
    private boolean appsLoaded, fullyDrawnReported, fullyDrawnPosted;
    private final ScreensaverRules.UpSequence screensaverUp = new ScreensaverRules.UpSequence();
    private long lastInput;
    private boolean windowFocused, openingScreensaver;
    private final Runnable idleScreensaver = () -> updateScreensaverTimer(true);
    private WeakReference<Bitmap> sampledWallpaper = new WeakReference<>(null);
    private final List<String> packages = new ArrayList<>();
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            appsDirty = true;
            if (resumed) {
                reload(true);
                appsDirty = false;
            }
        }
    };

    CoverFlowHome(Activity activity, Config config) {
        this.activity = activity;
        this.config = config;
        wallpaperRevision = UserWallpaper.revision(activity);
        density = activity.getResources().getDisplayMetrics().density;
        root = new FrameLayout(activity);
        root.setBackgroundColor(config.backgroundColor);
        activity.setContentView(root);
        activity.getWindow().setBackgroundDrawable(null);
        wallpaper = new WallpaperPlayer(activity, root, config, true);

        // A small clock leaves the wallpaper and posters as the primary content.
        LinearLayout clock = new LinearLayout(activity);
        clock.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(activity);
        time.setFormat12Hour("HH:mm");
        time.setFormat24Hour("HH:mm");
        time.setTextSize(29);
        time.setTextColor(0xF2FFFFFF);
        time.setFontFeatureSettings("tnum");
        clock.addView(time);
        TextClock date = new TextClock(activity);
        date.setFormat12Hour(activity.getString(R.string.date_format_home));
        date.setFormat24Hour(activity.getString(R.string.date_format_home));
        date.setTextSize(11);
        date.setTextColor(0x99FFFFFF);
        clock.addView(date);
        FrameLayout.LayoutParams clockParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        clockParams.leftMargin = dp(48);
        clockParams.topMargin = dp(27);
        root.addView(clock, clockParams);

        carousel = new CoverFlowView(activity, config.animations, this::launch, () -> libraryButtonFocus());
        carousel.setLoading(true);
        carousel.setId(View.generateViewId());
        FrameLayout.LayoutParams flowParams = new FrameLayout.LayoutParams(-1, -1);
        flowParams.topMargin = dp(100);
        flowParams.bottomMargin = dp(25);
        root.addView(carousel, flowParams);

        libraryButton = new TextView(activity);
        libraryButton.setId(View.generateViewId());
        // Icon-only glass circle, matching the classic icon-only top buttons: the label is for screen readers.
        libraryButton.setContentDescription(activity.getString(R.string.cover_flow_all_apps));
        libraryButton.setGravity(Gravity.CENTER);
        libraryButton.setPadding(dp(10), 0, dp(10), 0);
        Drawable icon = activity.getDrawable(R.drawable.ic_apps);
        if (icon != null) icon.setBounds(0, 0, dp(22), dp(22));
        libraryButton.setCompoundDrawables(icon, null, null, null);
        libraryButton.setFocusable(true);
        libraryButton.setVisibility(View.INVISIBLE);
        libraryButton.setOnFocusChangeListener((view, focused) -> {
            screensaverUp.reset();
            libraryButton.setBackground(buttonSurface(focused));
            libraryButton.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f)
                    .setDuration(config.animations ? 140 : 0).start();
        });
        libraryButton.setBackground(buttonSurface(false));
        libraryButton.setOnClickListener(v -> showLibrary());
        libraryButton.setOnLongClickListener(v -> {
            activity.startActivityForResult(new Intent(activity, WallpaperSettingsActivity.class), WALLPAPER_REQUEST);
            return true;
        });
        libraryButton.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.getRepeatCount() == 0 && screensaverUp.press(android.os.SystemClock.uptimeMillis())) openScreensaver(false);
                return true;
            }
            screensaverUp.reset();
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK) {
                hideLibraryButton();
                return true;
            }
            return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                    || code == KeyEvent.KEYCODE_DPAD_UP;
        });
        FrameLayout.LayoutParams libraryParams = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP | Gravity.END);
        libraryParams.rightMargin = dp(48);
        libraryParams.topMargin = dp(34);
        root.addView(libraryButton, libraryParams);
        carousel.setNextFocusUpId(libraryButton.getId());
        libraryButton.setNextFocusDownId(carousel.getId());
        wallpaper.setOnImageChanged(() -> {
            updateFrost();
            root.setBackgroundColor(wallpaper.hasImage() ? android.graphics.Color.TRANSPARENT : config.backgroundColor);
            reportReady();
        });
        carousel.setOnArtworkReady(this::reportReady);
        appsDirty = true;
        root.post(() -> {
            updateFrost();
            carousel.requestFocus();
        });
    }

    private int dp(float value) { return Math.round(value * density); }

    private Drawable buttonSurface(boolean focused) {
        GradientDrawable surface = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{focused ? 0x55FFFFFF : 0x22FFFFFF, focused ? 0x22FFFFFF : 0x0CFFFFFF});
        surface.setCornerRadius(dp(22));
        surface.setStroke(dp(1), focused ? 0xDDFFFFFF : 0x44FFFFFF);
        return surface;
    }

    private void libraryButtonFocus() {
        libraryButton.setVisibility(View.VISIBLE);
        libraryButton.requestFocus();
    }

    private void hideLibraryButton() {
        carousel.requestFocus();
        libraryButton.animate().cancel();
        libraryButton.setScaleX(1f);
        libraryButton.setScaleY(1f);
        libraryButton.setVisibility(View.INVISIBLE);
    }

    private void reload() {
        reload(false);
    }

    private void reload(boolean forceReload) {
        if (closed) return;
        String previous = carousel.selectedPackage();
        int generation = ++loadGeneration;
        appLoader.execute(() -> {
        Set<String> wanted = new LinkedHashSet<>(config.dockApps);
        wanted.addAll(LibraryApps.load(activity));
        wanted.removeAll(LibraryApps.loadRemoved(activity));
        wanted.removeAll(config.hiddenApps);
        Order saved = Order.load(new File(activity.getFilesDir(), "cover_flow_order.txt"));
        List<String> ordered = CoverFlowOrder.arrange(new ArrayList<>(wanted),
                saved == null ? Collections.emptyList() : saved.keys);
        List<CoverFlowView.App> apps = new ArrayList<>();
        List<String> loadedPackages = new ArrayList<>();
        PackageManager pm = activity.getPackageManager();
        for (String pkg : ordered) {
            if (pkg.equals(activity.getPackageName())) continue;
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue;
            try {
                android.content.pm.PackageInfo info = pm.getPackageInfo(pkg, 0);
                String label = config.appLabels.get(pkg);
                if (label == null) label = info.applicationInfo != null
                        ? pm.getApplicationLabel(info.applicationInfo).toString() : pkg;
                apps.add(new CoverFlowView.App(pkg, label, info.lastUpdateTime));
                loadedPackages.add(pkg);
            } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
                // A concurrent uninstall or broken resource must not take the desktop down.
            }
        }
        root.post(() -> {
            if (closed || generation != loadGeneration) return;
            boolean libraryChanged = forceReload || !packages.equals(loadedPackages);
            packages.clear();
            packages.addAll(loadedPackages);
            appsLoaded = true;
            carousel.setLoading(false);
            carousel.setApps(apps, carousel.selectedPackage() != null ? carousel.selectedPackage() : previous, forceReload);
            // Refresh only after the supplier exposes the newly committed membership/order.
            if (library != null && libraryChanged) library.refresh();
        });
        });
    }

    /** Measure usable content, not just an early empty background (ActivityManager fully-drawn time). */
    private void reportReady() {
        if (fullyDrawnReported || fullyDrawnPosted || closed || !resumed || !appsLoaded
                || !wallpaper.isReady() || !carousel.isArtworkReady()) return;
        fullyDrawnPosted = true;
        root.postOnAnimation(() -> {
            fullyDrawnPosted = false;
            if (closed || !resumed || fullyDrawnReported || !wallpaper.isReady() || !carousel.isArtworkReady()) return;
            activity.reportFullyDrawn();
            fullyDrawnReported = true;
        });
    }

    private void moveHome(String pkg, int direction) {
        List<String> reordered = CoverFlowOrder.move(packages, pkg, direction);
        if (reordered.equals(packages)) {
            Toast.makeText(activity, R.string.cover_flow_order_edge, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Order.save(new File(activity.getFilesDir(), "cover_flow_order.txt"), new Order("", reordered))) {
            Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        reload();
        Toast.makeText(activity, R.string.cover_flow_order_saved, Toast.LENGTH_SHORT).show();
    }

    private void launch(String pkg) {
        PackageManager pm = activity.getPackageManager();
        Intent intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        try {
            if (intent == null) throw new ActivityNotFoundException();
            activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(activity, R.string.cover_flow_unavailable, Toast.LENGTH_SHORT).show();
            reload();
        }
    }

    private void showLibrary() {
        if (library != null || closed) return;
        root.removeCallbacks(idleScreensaver);
        library = new HomeLibraryDialog(activity, () -> new ArrayList<>(packages),
                this::saveHomeOrder, () -> reload(true), () -> {
                    library = null;
                    hideLibraryButton();
                    userInput();
                });
        library.show();
    }

    private boolean saveHomeOrder(List<String> updated) {
        // Package changes can invalidate an editing draft. Never overwrite a newer membership list.
        if (updated.size() != packages.size() || new java.util.HashSet<>(updated).size() != packages.size()
                || !new java.util.HashSet<>(updated).equals(new java.util.HashSet<>(packages))) return false;
        if (!Order.save(new File(activity.getFilesDir(), "cover_flow_order.txt"), new Order("", updated))) return false;
        // The library refreshes synchronously when this returns; reload() publishes its result later.
        // Commit the saved order now so that refresh cannot restore the previous order.
        packages.clear();
        packages.addAll(updated);
        reload();
        return true;
    }

    /** Android 11 has no RenderEffect: downsample the wallpaper once, not the screen on every frame. */
    private void updateFrost() {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (!(child instanceof ImageView)) continue;
            Drawable drawable = ((ImageView) child).getDrawable();
            if (!(drawable instanceof BitmapDrawable)) continue;
            Bitmap original = ((BitmapDrawable) drawable).getBitmap();
            if (sampledWallpaper.get() == original) return;
            Bitmap small = Bitmap.createScaledBitmap(original, 32, 18, true);
            Bitmap softened = Bitmap.createScaledBitmap(small, 192, 108, true);
            if (small != original && small != softened) small.recycle();
            carousel.setFrost(softened);
            sampledWallpaper = new WeakReference<>(original);
            break;
        }
    }

    void start() {
        if (registered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) activity.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else activity.registerReceiver(packageReceiver, filter);
        registered = true;
    }

    void resume() {
        if (UserWallpaper.revision(activity) != wallpaperRevision) {
            activity.recreate();
            return;
        }
        resumed = true;
        windowFocused = activity.hasWindowFocus();
        openingScreensaver = false;
        userInput();
        hideLibraryButton();
        // Both loaders are asynchronous; start them now instead of waiting for another frame.
        wallpaper.start();
        if (appsDirty) {
            reload();
            appsDirty = false;
        }
        carousel.restoreArtwork();
        updateFrost();
    }

    void pause() {
        resumed = false;
        root.removeCallbacks(idleScreensaver);
        screensaverUp.reset();
        wallpaper.stop();
        carousel.stopAnimation();
        if (library != null) library.close();
    }

    void stop() {
        // Keep observing package changes while another app is foreground, but defer work to resume.
        carousel.clearArtwork();
        sampledWallpaper.clear();
        wallpaper.trim();
        root.setBackgroundColor(config.backgroundColor);
    }

    void home() {
        if (library != null) library.close();
        hideLibraryButton();
    }

    void back() { home(); }

    void trim(int level) {
        if (resumed || level < android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                && level != android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                && level != android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) return;
        carousel.clearArtwork();
        sampledWallpaper.clear();
        wallpaper.trim();
        root.setBackgroundColor(config.backgroundColor);
    }

    void close() {
        closed = true;
        root.removeCallbacks(idleScreensaver);
        loadGeneration++;
        appLoader.shutdownNow();
        if (registered) activity.unregisterReceiver(packageReceiver);
        registered = false;
        wallpaper.close();
        carousel.release();
    }

    void userInput() {
        lastInput = android.os.SystemClock.uptimeMillis();
        updateScreensaverTimer(false);
    }

    void windowFocus(boolean focused) {
        windowFocused = focused;
        screensaverUp.reset();
        userInput();
    }

    private void updateScreensaverTimer(boolean fire) {
        root.removeCallbacks(idleScreensaver);
        android.content.SharedPreferences prefs = ScreensaverStore.preferences(activity);
        long delay = ScreensaverRules.idleDelay(lastInput, android.os.SystemClock.uptimeMillis(),
                prefs.getInt("timeout_minutes", 10), resumed && windowFocused && library == null && !closed && !openingScreensaver,
                prefs.getInt("video_count", 0) > 0);
        if (delay < 0) return;
        if (fire && delay == 0) openScreensaver(true);
        else root.postDelayed(idleScreensaver, delay);
    }

    private void openScreensaver(boolean automatic) {
        if (!resumed || closed || openingScreensaver) return;
        root.removeCallbacks(idleScreensaver);
        if (ScreensaverStore.preferences(activity).getInt("video_count", 0) <= 0) {
            if (!automatic) activity.startActivity(new Intent(activity, ScreensaverSettingsActivity.class));
            return;
        }
        try {
            openingScreensaver = true;
            activity.startActivity(new Intent(activity, ScreensaverActivity.class).putExtra("automatic", automatic));
        } catch (ActivityNotFoundException | SecurityException failure) {
            openingScreensaver = false;
            android.util.Log.w("TvHome.Screensaver", "Cannot start screensaver", failure);
            userInput();
        }
    }

}
