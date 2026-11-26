package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Camera;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.util.LruCache;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One focus target, one horizontal carousel. No RecyclerView focus search can escape into another row. */
final class CoverFlowView extends View {
    static final class App {
        final String pkg;
        final String label;
        final String artworkKey;

        App(String pkg, String label, long updated) {
            this.pkg = pkg;
            this.label = label;
            this.artworkKey = pkg + ":" + updated + ":" + label;
        }
    }

    private static final float GLIDE_DURATION_SEC = 0.34f;
    private static final long REPEAT_INTERVAL_MS = 350L;
    private static final Typeface POSTER_LABEL_TYPEFACE = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private final List<App> apps = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint frostPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Camera camera = new Camera();
    private final Matrix matrix = new Matrix();
    private final Matrix frostMatrix = new Matrix();
    private final RectF rect = new RectF();
    private final RectF frostBounds = new RectF();
    private final RectF backdrop = new RectF();
    private final Typeface textTypeface = Typeface.create("sans-serif", Typeface.NORMAL);
    // Cache is UI-thread-only: 16 posters (~3.8 MB) keeps all 7 visible slots and neighbours hot.
    private final LruCache<String, Bitmap> posters = new LruCache<>(16);
    private final Context artworkContext;
    private final Handler artworkHandler = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor artworkLoader = new ThreadPoolExecutor(1, 1, 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private volatile int artworkGeneration;
    private boolean released;
    private boolean artworkPaused;
    private boolean artworkReady = true;
    private Runnable onArtworkReady;
    private final Consumer<String> open;
    private final Runnable up;
    private final boolean animations;
    private final float density;
    private final String instructions;
    private int selection;
    private float position, velocity, startPosition, startVelocity;
    private boolean animating;
    private long segmentStartNanos, lastFrameNanos;
    private final float[] glideOut = new float[2];
    private Bitmap frost;
    private BitmapShader frostShader;
    private float cardWidth, cardHeight;
    private final int[] drawingOrder = new int[7];
    private final float[] distances = new float[7];
    private String counter = "";
    private long lastMoveTime;
    private boolean loading;
    private final Choreographer.FrameCallback frameCallback = this::stepGlide;

    void setLoading(boolean value) {
        loading = value;
        invalidate();
    }

    CoverFlowView(Context context, boolean animations, Consumer<String> open, Runnable up) {
        super(context);
        artworkContext = context.getApplicationContext();
        this.open = open;
        this.up = up;
        this.animations = animations;
        density = getResources().getDisplayMetrics().density;
        instructions = context.getString(R.string.cover_flow_instructions);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    String selectedPackage() {
        return apps.isEmpty() ? null : apps.get(CoverFlowMath.index(selection, apps.size())).pkg;
    }

    void setApps(List<App> next, String previous) {
        setApps(next, previous, false);
    }

    void setApps(List<App> next, String previous, boolean forceReload) {
        if (released) return;
        boolean same = apps.size() == next.size();
        for (int i = 0; same && i < next.size(); i++) {
            same = apps.get(i).artworkKey.equals(next.get(i).artworkKey);
        }
        if (same && !forceReload) return;
        stopAnimation();
        if (forceReload) {
            // Membership changes must not reuse a stale carousel transition or poster set.
            posters.evictAll();
            lastMoveTime = 0;
        }
        apps.clear();
        apps.addAll(next);
        selection = 0;
        for (int i = 0; i < apps.size(); i++) {
            App app = apps.get(i);
            if (app.pkg.equals(previous)) selection = i;
        }
        position = selection;
        updateDescription();
        warmPosters();
        if (forceReload) requestLayout();
        invalidate();
    }

    void setFrost(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            frost = null;
            frostShader = null;
            frostPaint.setShader(null);
        } else {
            // Pre-bake the translucent glass tint onto the 192x108 frost bitmap to eliminate a fill pass per card.
            Bitmap tinted = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(tinted);
            canvas.drawBitmap(bitmap, 0, 0, null);
            canvas.drawColor(0x30FFFFFF);
            frost = tinted;
            frostBounds.set(0, 0, tinted.getWidth(), tinted.getHeight());
            frostShader = new BitmapShader(tinted, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            frostPaint.setShader(frostShader);
        }
        invalidate();
    }

    private float dp(float value) { return value * density; }

    private void updateDescription() {
        counter = (CoverFlowMath.index(selection, apps.size()) + 1) + " / " + apps.size();
        setContentDescription(apps.isEmpty() ? getContext().getString(R.string.cover_flow_empty)
                : apps.get(CoverFlowMath.index(selection, apps.size())).label + ". " + instructions);
    }

    private void move(int step) {
        if (apps.size() <= 1) return;
        if (animating && segmentStartNanos != 0L && lastFrameNanos >= segmentStartNanos) {
            float elapsed = (lastFrameNanos - segmentStartNanos) / 1_000_000_000f;
            if (CoverFlowMath.stepHermite(startPosition, startVelocity, selection, elapsed, GLIDE_DURATION_SEC, glideOut) != 0L) {
                position = glideOut[0];
                velocity = glideOut[1];
            } else {
                position = selection;
                velocity = 0f;
            }
        }
        selection += step;
        updateDescription();
        warmPosters();
        if (!animations) {
            position = selection;
            velocity = 0f;
            animating = false;
            invalidate();
            return;
        }
        startPosition = position;
        startVelocity = velocity;
        segmentStartNanos = animating && lastFrameNanos != 0L ? lastFrameNanos : 0L;
        if (!animating) {
            animating = true;
            lastFrameNanos = 0L;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
        playSoundEffect(step < 0 ? SoundEffectConstants.NAVIGATION_LEFT : SoundEffectConstants.NAVIGATION_RIGHT);
    }

    private void stepGlide(long frameTimeNanos) {
        if (!animating || released) return;
        if (segmentStartNanos == 0L) segmentStartNanos = frameTimeNanos - 16_666_667L;
        lastFrameNanos = frameTimeNanos;
        float elapsed = Math.max(0f, (frameTimeNanos - segmentStartNanos) / 1_000_000_000f);
        boolean running = CoverFlowMath.stepHermite(
                startPosition, startVelocity, selection, elapsed, GLIDE_DURATION_SEC, glideOut) != 0L;
        position = glideOut[0];
        velocity = glideOut[1];
        if (running) {
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else {
            animating = false;
        }
        invalidate();
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            // Pace held repeats (~3 cards/sec) so each passing card stays readable.
            if (event.getRepeatCount() == 0 || event.getEventTime() - lastMoveTime >= REPEAT_INTERVAL_MS) {
                move(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                lastMoveTime = event.getEventTime();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) {
            if (event.getRepeatCount() == 0) up.run();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.getRepeatCount() == 0) performClick();
            return true;
        }
        return super.onKeyDown(code, event);
    }

    @Override public boolean performClick() {
        super.performClick();
        if (!apps.isEmpty()) open.accept(apps.get(CoverFlowMath.index(selection, apps.size())).pkg);
        else up.run();
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            requestFocus();
            return true;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float x = event.getX() - getWidth() / 2f;
            if (Math.abs(x) <= cardWidth / 2f) performClick();
            else move(x < 0 ? -1 : 1);
            return true;
        }
        return true;
    }

    @Override protected void onFocusChanged(boolean focused, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(focused, direction, previous);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        cardHeight = Math.min(dp(286), h * 0.68f);
        cardWidth = cardHeight * 2f / 3f;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float centerX = getWidth() / 2f;
        float centerY = getHeight() * 0.43f;
        if (apps.isEmpty()) {
            if (!loading) text(canvas, getContext().getString(R.string.cover_flow_empty), centerX, centerY, 18, 0xCCFFFFFF);
            return;
        }
        View root = getRootView();
        int rootHeight = root != null ? root.getHeight() : getHeight();
        int slots = apps.size() == 1 ? 1 : 7;
        int first = apps.size() == 1 ? selection : (int) Math.floor(position) - 3;
        for (int i = 0; i < slots; i++) {
            distances[i] = first + i - position;
            drawingOrder[i] = i;
        }
        // Back-to-front so the central poster overlaps its neighbours naturally.
        for (int i = 1; i < slots; i++) {
            int value = drawingOrder[i], j = i - 1;
            while (j >= 0 && Math.abs(distances[drawingOrder[j]]) < Math.abs(distances[value])) {
                drawingOrder[j + 1] = drawingOrder[j--];
            }
            drawingOrder[j + 1] = value;
        }
        for (int draw = 0; draw < slots; draw++) {
            int slot = drawingOrder[draw];
            float distance = distances[slot];
            if (Math.abs(distance) >= 2.85f) continue;
            int index = CoverFlowMath.index(first + slot, apps.size());
            float scale = CoverFlowMath.scale(distance);
            float x = centerX + CoverFlowMath.offset(distance, cardWidth);
            drawCard(canvas, apps.get(index), distance, x, centerY, scale, rootHeight);
        }

        float baseline = centerY + cardHeight / 2 + dp(44);
        int selected = CoverFlowMath.index(selection, apps.size());
        for (int i = 0; i < apps.size(); i++) {
            // For unusually large favourite lists, the position counter below stays readable instead.
            if (apps.size() > 12) break;
            paint.setColor(i == selected ? 0xEEFFFFFF : 0x44FFFFFF);
            float x = centerX + (i - (apps.size() - 1) / 2f) * dp(15);
            canvas.drawCircle(x, baseline, dp(i == selected ? 3f : 2f), paint);
        }
        if (apps.size() > 12) text(canvas, counter, centerX, baseline, 12, 0xAAFFFFFF);
    }

    private void drawCard(Canvas canvas, App app, float distance, float x, float y, float scale, int rootHeight) {
        float proximity = 1f - Math.min(Math.abs(distance), 2f) / 2f;
        float w = cardWidth, h = cardHeight;
        float radius = dp(15);
        float edgeFade = Math.min(1f, (2.85f - Math.abs(distance)) / 0.85f);
        paint.setShader(null);
        paint.setAlpha(255);

        camera.save();
        camera.setLocation(0, 0, -10 * density);
        camera.rotateY(CoverFlowMath.rotation(distance));
        camera.getMatrix(matrix);
        camera.restore();
        matrix.preTranslate(-w / 2, -h / 2);
        matrix.postScale(scale, scale);
        matrix.postTranslate(x, y);
        canvas.save();
        canvas.concat(matrix);
        int alpha = Math.round(255 * (0.58f + proximity * 0.42f) * edgeFade);
        rect.set(0, 0, w, h);
        if (frost != null && frostShader != null && !frost.isRecycled()) {
            backdrop.set(w / 2 - x, h / 2 - y - getTop(),
                    w / 2 - x + getWidth(), h / 2 - y - getTop() + rootHeight);
            frostMatrix.setRectToRect(frostBounds, backdrop, Matrix.ScaleToFit.FILL);
            frostShader.setLocalMatrix(frostMatrix);
            frostPaint.setAlpha(alpha);
            canvas.drawRoundRect(rect, radius, radius, frostPaint);
        } else {
            paint.setColor(0x30FFFFFF);
            paint.setAlpha(0x30 * alpha / 255);
            canvas.drawRoundRect(rect, radius, radius, paint);
        }
        Bitmap artwork = posters.get(app.artworkKey);
        if (artwork != null) {
            paint.setColor(Color.WHITE);
            paint.setAlpha(alpha);
            canvas.drawBitmap(artwork, null, rect, paint);
        }
        float focusBlend = hasFocus() ? Math.max(0f, Math.min(1f, (proximity - 0.6f) / 0.4f)) : 0f;
        focusBlend = focusBlend * focusBlend * (3f - 2f * focusBlend);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(0.8f + 0.9f * focusBlend));
        paint.setColor(Color.WHITE);
        paint.setAlpha(Math.round((0x66 + (0xE6 - 0x66) * focusBlend) * alpha / 255f));
        rect.inset(dp(0.8f), dp(0.8f));
        canvas.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        canvas.restore();
    }

    private void text(Canvas canvas, String value, float x, float y, float size, int color) {
        paint.setShader(null);
        paint.setColor(color);
        paint.setTextSize(dp(size));
        paint.setTypeface(textTypeface);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(value, x, y, paint);
        paint.setTextAlign(Paint.Align.LEFT);
    }

    /** Original portrait artwork: app identity, not a stretched landscape banner or an unrelated movie poster. */
    private static Bitmap poster(Context context, App app) {
        int w = 300, h = 450;
        Bitmap bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Path cornerClip = new Path();
        cornerClip.addRoundRect(new RectF(0, 0, 200, 300), 15.7f, 15.7f, Path.Direction.CW);
        canvas.clipPath(cornerClip);
        canvas.scale(2f / 3f, 2f / 3f);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        int[] colors = {0x99526DAD, 0x99D47749, 0x99BD9469, 0x997E54B3};
        int color = colors[Math.floorMod(app.pkg.hashCode(), colors.length)];
        String category = "APPLICATION";
        if (app.pkg.equals("io.github.grahamzen.anime.tv")) { color = colors[0]; category = "ANIME"; }
        else if (app.pkg.equals("com.xyoye.dandanplay")) { color = colors[1]; category = "DANMAKU"; }
        else if (app.pkg.equals("com.netease.filmlytv")) { color = colors[2]; category = "CINEMA"; }
        else if (app.pkg.equals("com.V2.blb5")) { color = colors[3]; category = "BILIBILI"; }
        p.setShader(new LinearGradient(0, 0, w, h, color, 0xA00B1020, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        p.setShader(null);
        p.setColor(0xBBFFFFFF);
        p.setTextSize(12);
        canvas.drawText(category, 26, 38, p);
        p.setColor(0x66FFFFFF);
        canvas.drawRect(26, 53, 49, 55, p);

        // Draw icons directly onto the frosted card without an extra halo tray, rounding square/adaptive edges.
        Drawable icon;
        try { icon = context.getPackageManager().getApplicationIcon(app.pkg); }
        catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException unavailable) {
            icon = context.getDrawable(R.drawable.ic_apps);
        }
        if (icon != null) {
            icon = icon.mutate();
            android.graphics.Rect oldBounds = icon.copyBounds();
            Path iconClip = new Path();
            iconClip.addRoundRect(new RectF(76, 138, 224, 286), 28, 28, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(iconClip);
            icon.setBounds(76, 138, 224, 286);
            icon.draw(canvas);
            canvas.restore();
            icon.setBounds(oldBounds);
        }
        p.setColor(Color.WHITE);
        p.setTypeface(POSTER_LABEL_TYPEFACE);
        p.setTextSize(29);
        p.setTextAlign(Paint.Align.CENTER);
        String label = app.label;
        while (label.length() > 1 && p.measureText(label) > w - 52) {
            p.setTextSize(p.getTextSize() - 1);
            if (p.getTextSize() <= 16) break;
        }
        canvas.drawText(label, w / 2f, 365, p);
        p.setShader(new LinearGradient(0, 0, w, h,
                new int[]{0x28FFFFFF, 0x04FFFFFF, 0x14000000}, new float[]{0, 0.48f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, p);
        return bitmap;
    }

    void stopAnimation() {
        animating = false;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        position = selection;
        startPosition = selection;
        velocity = 0f;
        startVelocity = 0f;
        segmentStartNanos = 0L;
        lastFrameNanos = 0L;
        invalidate();
    }

    /** Only the newest visible batch is queued. No PackageManager or raster work on the UI thread. */
    private void warmPosters() {
        if (released || artworkPaused) return;
        int generation = ++artworkGeneration;
        artworkLoader.getQueue().clear();
        List<App> missing = new ArrayList<>();
        for (int index : CoverFlowMath.visibleIndices(selection, apps.size())) {
            App app = apps.get(index);
            if (posters.get(app.artworkKey) == null) missing.add(app);
        }
        artworkReady = missing.isEmpty();
        if (artworkReady) {
            if (onArtworkReady != null) onArtworkReady.run();
            return;
        }
        artworkLoader.execute(() -> {
            for (App app : missing) {
                if (generation != artworkGeneration || Thread.currentThread().isInterrupted()) return;
                Bitmap bitmap;
                try { bitmap = poster(artworkContext, app); }
                catch (RuntimeException | OutOfMemoryError failure) {
                    Log.w("TvHome.Artwork", "Could not prepare poster for " + app.pkg, failure);
                    continue;
                }
                if (generation != artworkGeneration || Thread.currentThread().isInterrupted()) {
                    bitmap.recycle();
                    return;
                }
                if (!artworkHandler.post(() -> {
                    if (released || generation != artworkGeneration) {
                        bitmap.recycle(); // This bitmap was never published to the render thread.
                        return;
                    }
                    posters.put(app.artworkKey, bitmap);
                    invalidate();
                })) bitmap.recycle();
            }
            artworkHandler.post(() -> {
                if (released || generation != artworkGeneration) return;
                artworkReady = true;
                if (onArtworkReady != null) onArtworkReady.run();
            });
        });
    }

    boolean isArtworkReady() { return artworkReady; }

    void setOnArtworkReady(Runnable listener) { onArtworkReady = listener; }

    void clearArtwork() {
        artworkPaused = true;
        artworkGeneration++;
        artworkLoader.getQueue().clear();
        artworkReady = false;
        posters.evictAll();
        frost = null;
        frostShader = null;
        frostPaint.setShader(null);
        // Do not recycle bitmaps still referenced by Android's render thread.
        invalidate();
    }

    void restoreArtwork() { artworkPaused = false; warmPosters(); invalidate(); }

    void release() {
        released = true;
        onArtworkReady = null;
        stopAnimation();
        clearArtwork();
        artworkLoader.shutdownNow();
        apps.clear();
    }
}
