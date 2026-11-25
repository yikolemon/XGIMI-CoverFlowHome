# Cold-start loading

The launch path is intentionally local and bounded:

1. `MainActivity` reads configuration on a serial background executor. Activity recreation cannot start competing wallpaper extraction tasks.
2. Wallpaper selection checks the private collection, then the private single image, then the external/bundled theme. A private choice skips all lower-priority wallpaper scans and extraction.
3. The collection fingerprint is calculated with configuration, off the UI thread. A private collection's immutable UUID directory identifies its revision without rescanning its images on every resume.
4. Once the scene exists, wallpaper decoding and app metadata loading start immediately on separate workers, without a preliminary animation-frame delay.
5. Visible application posters are rasterised on a worker, centre-first. The UI owns the six-entry poster cache. There is only one queued visible batch; rapid navigation supersedes old work. Stale, unpublished bitmaps are discarded, and destruction shuts the worker down.

The launcher reports `Activity.reportFullyDrawn()` after the wallpaper has settled, app metadata has loaded and the visible artwork batch has completed. Failed artwork is logged and does not permanently block readiness reporting.

## Measurement

Use a release APK on the target TV/projector. Keep wallpaper choice, installed applications and device power/thermal state the same between comparisons.

```powershell
adb shell am force-stop com.gathin.tvhome
adb shell am start -W -n com.gathin.tvhome/.MainActivity
adb logcat -d -s ActivityTaskManager ActivityManager
```

Compare both the first displayed frame and the `Fully drawn` time, not only the first empty background. Repeat process-cold launches several times and compare medians. Do not clear application data for an ordinary cold-start test: that also removes the private wallpaper and changes the scenario. First-install/extraction startup is a separate test.

Automated JVM tests cover lazy source selection, source identity, visible artwork ordering/bounds and the persistence locking contract. They do not measure hardware startup time or substitute for device tests of rendering, focus and Activity lifecycle transitions.

## Build environment

The project recommends JDK 17 and SDK 34. This workspace was also verified using the locally installed standard JDK 21 and SDK 34, passed as command-scoped environment variables, without committing machine-specific paths. The default GraalVM JDK failed the Android `jlink` image transform and should not be used for this build here.

Full Lint currently reports the pre-existing missing TV banner error and other warnings. Release assembly and JVM tests can be run independently; this is not a claim that full Lint passes.
