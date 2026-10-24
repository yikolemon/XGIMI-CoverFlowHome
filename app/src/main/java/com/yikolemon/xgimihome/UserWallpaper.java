package com.yikolemon.xgimihome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

/** One private, normalised JPEG. No image library, URI grant retention or original-file copy. */
final class UserWallpaper {
    private static final String PREFS = "user_wallpaper";
    private static final long MAX_INPUT_BYTES = 64L * 1024 * 1024;
    private UserWallpaper() { }

    static File file(Context context) { return new File(context.getFilesDir(), "user_wallpaper.jpg"); }

    static boolean enabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
                && file(context).isFile();
    }

    static long revision(Context context) {
        File image = file(context);
        return enabled(context) ? image.lastModified() ^ image.length() : 0;
    }

    static void useTheme(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", false).apply();
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .remove(Config.WALLPAPER_CHOICE_KEY).apply();
    }

    private static InputStream open(Context context, Uri uri) throws IOException {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IOException("Cannot open image");
        return new FilterInputStream(input) {
            long remaining = MAX_INPUT_BYTES;
            @Override public int read() throws IOException {
                if (remaining <= 0) throw new IOException("Image too large");
                int value = super.read();
                if (value >= 0) remaining--;
                return value;
            }
            @Override public int read(byte[] bytes, int offset, int count) throws IOException {
                if (remaining <= 0) throw new IOException("Image too large");
                int read = in.read(bytes, offset, (int) Math.min(count, remaining));
                if (read > 0) remaining -= read;
                return read;
            }
            @Override public long skip(long count) throws IOException {
                long skipped = in.skip(Math.min(count, remaining));
                remaining -= skipped;
                return skipped;
            }
        };
    }

    static void importImage(Context context, Uri uri, BooleanSupplier cancelled) throws IOException {
        Bitmap decoded = null, output = null;
        File temporary = File.createTempFile("wallpaper-import-", ".jpg", context.getCacheDir());
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream input = open(context, uri)) { BitmapFactory.decodeStream(input, null, options); }
            options.inSampleSize = WallpaperImportPlan.sampleSize(options.outWidth, options.outHeight);
            int orientation = ExifInterface.ORIENTATION_NORMAL;
            try (InputStream input = open(context, uri)) {
                orientation = new ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, orientation);
            } catch (IOException unsupportedMetadata) { /* Decodable images need not have EXIF. */ }
            checkCancelled(cancelled);
            options.inJustDecodeBounds = false;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            try (InputStream input = open(context, uri)) { decoded = BitmapFactory.decodeStream(input, null, options); }
            if (decoded == null || (long) decoded.getWidth() * decoded.getHeight() > WallpaperImportPlan.MAX_DECODE_PIXELS)
                throw new IOException("Cannot decode within budget");
            boolean quarterTurn = orientation >= 5 && orientation <= 8;
            int[] size = WallpaperImportPlan.outputSize(decoded.getWidth(), decoded.getHeight(), quarterTurn);
            output = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(output);
            canvas.drawColor(Color.BLACK);
            Matrix matrix = new Matrix();
            matrix.setTranslate(-decoded.getWidth() / 2f, -decoded.getHeight() / 2f);
            if (orientation == 2 || orientation == 5 || orientation == 7) matrix.postScale(-1, 1);
            else if (orientation == 4) matrix.postScale(1, -1);
            int rotation = orientation == 3 ? 180 : orientation == 6 || orientation == 7 ? 90
                    : orientation == 5 || orientation == 8 ? 270 : 0;
            matrix.postRotate(rotation);
            float sourceWidth = quarterTurn ? decoded.getHeight() : decoded.getWidth();
            float sourceHeight = quarterTurn ? decoded.getWidth() : decoded.getHeight();
            float scale = Math.max(size[0] / sourceWidth, size[1] / sourceHeight);
            matrix.postScale(scale, scale);
            matrix.postTranslate(size[0] / 2f, size[1] / 2f);
            canvas.drawBitmap(decoded, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
            // The decoder bitmap is worker-local and has never been handed to the render thread.
            decoded.recycle();
            decoded = null;
            for (int quality = 82; quality >= 32; quality -= 10) {
                checkCancelled(cancelled);
                try (FileOutputStream stream = new FileOutputStream(temporary)) {
                    if (!output.compress(Bitmap.CompressFormat.JPEG, quality, stream)) throw new IOException("JPEG encoding");
                }
                if (temporary.length() <= WallpaperImportPlan.MAX_FILE_BYTES) break;
            }
            if (temporary.length() <= 0 || temporary.length() > WallpaperImportPlan.MAX_FILE_BYTES)
                throw new IOException("Cannot compress within budget");
            checkCancelled(cancelled);
            AtomicFile target = new AtomicFile(file(context));
            FileOutputStream stream = null;
            try {
                stream = target.startWrite();
                try (InputStream input = new FileInputStream(temporary)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) stream.write(buffer, 0, count);
                }
                checkCancelled(cancelled);
                target.finishWrite(stream);
            } catch (IOException failure) {
                if (stream != null) target.failWrite(stream);
                throw failure;
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", true).apply();
        } catch (IllegalArgumentException | OutOfMemoryError failure) {
            throw new IOException("Image cannot be imported safely", failure);
        } finally {
            if (decoded != null) decoded.recycle();
            if (output != null) output.recycle();
            temporary.delete();
        }
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
    }
}
