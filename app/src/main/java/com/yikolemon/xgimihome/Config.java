package com.yikolemon.xgimihome;

import android.content.Context;
import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Small configuration model for the standalone Cover Flow launcher. */
final class Config {
    static final String SETTINGS_NAME = "launcher_settings";
    static final String WALLPAPER_CHOICE_KEY = "wallpaper_choice";

    final List<String> dockApps;
    final List<String> hiddenApps;
    final Map<String, String> appLabels;
    final boolean animations, wallpaperTrueColor, wallpaperEnabled;
    final int backgroundColor, wallpaperDim, wallpaperRotationMinutes;
    final String wallpaperOrder;
    final List<File> wallpapers;

    private Config(List<String> dockApps, List<String> hiddenApps, Map<String, String> appLabels,
                   boolean animations, int backgroundColor, boolean wallpaperTrueColor,
                   boolean wallpaperEnabled, int wallpaperDim, int wallpaperRotationMinutes,
                   String wallpaperOrder, List<File> wallpapers) {
        this.dockApps = Collections.unmodifiableList(dockApps);
        this.hiddenApps = Collections.unmodifiableList(hiddenApps);
        this.appLabels = Collections.unmodifiableMap(appLabels);
        this.animations = animations;
        this.backgroundColor = backgroundColor;
        this.wallpaperTrueColor = wallpaperTrueColor;
        this.wallpaperEnabled = wallpaperEnabled;
        this.wallpaperDim = wallpaperDim;
        this.wallpaperRotationMinutes = wallpaperRotationMinutes;
        this.wallpaperOrder = wallpaperOrder;
        this.wallpapers = Collections.unmodifiableList(wallpapers);
    }

    static Config load(Context context) {
        JSONObject json = readJson(context);
        List<String> dock = strings(json.optJSONArray("dock_apps"));
        List<String> hidden = strings(json.optJSONArray("hidden_apps"));
        Map<String, String> labels = new HashMap<>();
        JSONArray mappings = json.optJSONArray("app_labels");
        if (mappings != null) for (int i = 0; i < mappings.length(); i++) {
            String item = mappings.optString(i, "");
            int split = item.indexOf('=');
            if (split > 0 && split < item.length() - 1)
                labels.put(item.substring(0, split), item.substring(split + 1));
        }
        int background;
        try { background = Color.parseColor(json.optString("background_color", "#080E1C")); }
        catch (IllegalArgumentException invalid) { background = Color.rgb(8, 14, 28); }
        boolean enabled = json.optBoolean("wallpaper", true);
        File themeDir = context.getExternalFilesDir(null);
        List<File> wallpapers = new ArrayList<>();
        if (enabled && themeDir != null) {
            wallpapers.addAll(WallpaperRotation.images(new File(themeDir, "wallpapers")));
            File wallpaper = new File(themeDir, "wallpaper.jpg");
            if (wallpapers.isEmpty() && wallpaper.isFile() && wallpaper.length() > 0) wallpapers.add(wallpaper);
            if (wallpapers.isEmpty()) {
                wallpaper = new File(context.getFilesDir(), "wallpaper.jpg");
                if (copyDefaultWallpaper(context, wallpaper)) wallpapers.add(wallpaper);
            }
        }
        if (UserWallpaper.enabled(context)) {
            wallpapers.clear();
            wallpapers.add(UserWallpaper.file(context));
        }
        return new Config(dock, hidden, labels, json.optBoolean("animations", true), background,
                json.optBoolean("wallpaper_true_color", false), !wallpapers.isEmpty(),
                json.optInt("wallpaper_dim", 25), json.optInt("wallpaper_rotation_minutes", 0),
                json.optString("wallpaper_order", WallpaperRotation.ORDER_SHUFFLE), wallpapers);
    }

    private static JSONObject readJson(Context context) {
        File external = context.getExternalFilesDir(null);
        File override = external == null ? null : new File(external, "config.json");
        try {
            byte[] bytes;
            if (override != null && override.isFile())
                try (InputStream input = new java.io.FileInputStream(override)) { bytes = readAll(input); }
            else try (InputStream input = context.getAssets().open("config.json")) { bytes = readAll(input); }
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception failure) {
            Log.w("XgimiHome.Config", "Using built-in defaults", failure);
            return new JSONObject();
        }
    }

    private static byte[] readAll(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static List<String> strings(JSONArray array) {
        if (array == null) return new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) unique.add(value);
        }
        return new ArrayList<>(unique);
    }

    private static boolean copyDefaultWallpaper(Context context, File target) {
        if (target.isFile() && target.length() > 0) return true;
        try (InputStream input = context.getAssets().open("wallpaper.jpg");
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return true;
        } catch (Exception failure) {
            Log.w("XgimiHome.Config", "Bundled wallpaper unavailable", failure);
            return false;
        }
    }
}
