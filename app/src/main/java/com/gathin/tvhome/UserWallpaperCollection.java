package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Copies a selected collection privately; a failed import never replaces the current collection. */
final class UserWallpaperCollection {
    private static final String PREFS = "wallpaper_collection";

    static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false);
    }

    static void disable(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", false).commit();
    }

    static List<File> images(Context context) {
        return WallpaperRotation.images(directory(context));
    }

    static File directory(Context context) {
        String name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("directory", "");
        return name.startsWith("collection-") && !name.contains("/")
                ? new File(context.getFilesDir(), name) : null;
    }

    static void importImages(Context context, List<Uri> sources, BooleanSupplier cancelled) throws IOException {
        if (sources.isEmpty() || sources.size() > 100) throw new IOException("Choose 1 to 100 images");
        File directory = new File(context.getFilesDir(), "collection-" + UUID.randomUUID());
        if (!directory.mkdir()) throw new IOException("Cannot create collection");
        boolean installed = false;
        try {
            for (int i = 0; i < sources.size(); i++) {
                if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                    throw new IOException("Cancelled");
                File output = new File(directory, String.format(java.util.Locale.ROOT, "wallpaper-%03d.jpg", i + 1));
                normalizeAndSave(context, sources.get(i), output, cancelled);
            }
            if (cancelled.getAsBoolean()) throw new IOException("Cancelled");
            String old = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("directory", "");
            installed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("directory", directory.getName()).putBoolean("enabled", true).commit();
            if (!installed) throw new IOException("Cannot save collection");
            if (old.startsWith("collection-") && !old.contains("/")) clear(new File(context.getFilesDir(), old));
        } finally {
            if (!installed) clear(directory);
        }
    }

    /**
     * Reads one image URI, applies EXIF orientation correction, re-encodes as JPEG and writes
     * to {@code target}. Mirrors the normalization done by {@link UserWallpaper#importImage}.
     */
    private static void normalizeAndSave(Context context, Uri source, File target,
            BooleanSupplier cancelled) throws IOException {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) throw new IOException("Cannot open image");
            BitmapFactory.decodeStream(in, null, opts);
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw new IOException("Invalid image");
        opts.inSampleSize = WallpaperImportPlan.sampleSize(opts.outWidth, opts.outHeight);
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in != null) orientation = new ExifInterface(in)
                    .getAttributeInt(ExifInterface.TAG_ORIENTATION, orientation);
        } catch (IOException ignored) { /* images without EXIF are still decodable */ }
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new IOException("Cancelled");
        opts.inJustDecodeBounds = false;
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap decoded;
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) throw new IOException("Cannot open image");
            decoded = BitmapFactory.decodeStream(in, null, opts);
        }
        if (decoded == null
                || (long) decoded.getWidth() * decoded.getHeight() > WallpaperImportPlan.MAX_DECODE_PIXELS)
            throw new IOException("Cannot decode within budget");
        try {
            boolean quarterTurn = orientation >= 5 && orientation <= 8;
            int[] size = WallpaperImportPlan.outputSize(decoded.getWidth(), decoded.getHeight(), quarterTurn);
            Bitmap out = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.RGB_565);
            try {
                Canvas canvas = new Canvas(out);
                canvas.drawColor(Color.BLACK);
                Matrix matrix = new Matrix();
                matrix.setTranslate(-decoded.getWidth() / 2f, -decoded.getHeight() / 2f);
                if (orientation == 2 || orientation == 5 || orientation == 7) matrix.postScale(-1, 1);
                else if (orientation == 4) matrix.postScale(1, -1);
                int rot = orientation == 3 ? 180 : orientation == 6 || orientation == 7 ? 90
                        : orientation == 5 || orientation == 8 ? 270 : 0;
                matrix.postRotate(rot);
                float sw = quarterTurn ? decoded.getHeight() : decoded.getWidth();
                float sh = quarterTurn ? decoded.getWidth() : decoded.getHeight();
                float scale = Math.max(size[0] / sw, size[1] / sh);
                matrix.postScale(scale, scale);
                matrix.postTranslate(size[0] / 2f, size[1] / 2f);
                canvas.drawBitmap(decoded, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
                for (int quality = 82; quality >= 32; quality -= 10) {
                    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
                        throw new IOException("Cancelled");
                    try (FileOutputStream fos = new FileOutputStream(target)) {
                        if (!out.compress(Bitmap.CompressFormat.JPEG, quality, fos))
                            throw new IOException("JPEG encoding failed");
                    }
                    if (target.length() <= WallpaperImportPlan.MAX_FILE_BYTES) break;
                }
                if (target.length() <= 0 || target.length() > WallpaperImportPlan.MAX_FILE_BYTES)
                    throw new IOException("Cannot compress within budget");
            } finally {
                out.recycle();
            }
        } finally {
            decoded.recycle();
        }
    }

    private static void clear(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (file.isFile()) file.delete();
        directory.delete();
    }
}
