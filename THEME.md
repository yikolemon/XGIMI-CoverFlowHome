# Built-in XGIMI theme

The standalone app ships with the configuration at `app/src/main/assets/config.json`, a default `wallpaper.jpg`, and 14 bundled images in `app/src/main/assets/wallpapers/`. By default, the bundled collection rotates in filename order every 30 minutes while Home is visible. The wallpaper settings page includes a switch to turn rotation off or on.

To customize without rebuilding, copy `config.json` and/or a `wallpaper.jpg` to:

```text
/sdcard/Android/data/com.yikolemon.xgimihome/files/
```

An optional `wallpapers/` subdirectory can replace the bundled rotation collection. Set `wallpaper_rotation_minutes` to change the interval (`0` disables rotation); `wallpaper_order` can be `sequential` or `shuffle`. `dock_apps` controls the initial Home carousel; `app_labels` optionally overrides labels. App additions, removals, and the user's saved order are stored separately in the app's private data, so editing the theme does not erase them.

The app never changes the device's default Home selection. Apply any new files and reopen the launcher to load them.
