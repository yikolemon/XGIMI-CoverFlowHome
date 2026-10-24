# Built-in XGIMI theme

The standalone app ships with the configuration at `app/src/main/assets/config.json` and its matching `wallpaper.jpg`.

To customize without rebuilding, copy `config.json` and/or a `wallpaper.jpg` to:

```text
/sdcard/Android/data/com.yikolemon.xgimihome/files/
```

An optional `wallpapers/` subdirectory can hold a wallpaper rotation collection. `dock_apps` controls the initial Home carousel; `app_labels` optionally overrides labels. App additions, removals, and the user's saved order are stored separately in the app's private data, so editing the theme does not erase them.

The app never changes the device's default Home selection. Apply any new files and reopen the launcher to load them.
