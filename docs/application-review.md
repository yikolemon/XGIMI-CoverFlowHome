# Application performance and memory review

Scope: static review of the launcher, including both home layouts, app libraries,
wallpaper decoding/import, configuration/storage, key services, clock/screensaver,
animations and lifecycle cleanup. This is not a device CPU/heap profile or a claim
that every code path has been exercised.

## Fixed in this review

- Wallpaper rotation row: explicit label and switch layout, matching the other
  rows' width, height and focus surface; one remote focus target for the row.
- ClockScene: cancel pending reveal and glow/content animations on stop, avoiding
  animation work after the screensaver is hidden.

## Remaining optimization candidates

| Priority | Area | Finding / recommended next step |
| --- | --- | --- |
| High | CoverFlowHome.reload, HomeLibraryDialog.refresh, AppAdapter reload | PackageManager queries, metadata and icon loading run on the UI thread. Measure resume/library-open latency, then move discovery to a bounded worker with generation checks to discard stale results. |
| High | WallpaperPlayer.showFirst | First wallpaper decode is synchronous to avoid a blank first frame. Measure cold-start time before changing; asynchronous decoding needs a retained placeholder and lifecycle-safe delivery. |
| Medium | CoverFlowView.warmPosters | Icons and poster rasterization run on the UI thread during navigation. Cache is bounded to six 200x300 ARGB posters (~1.37 MiB pixel storage); asynchronous prefetch could reduce navigation stalls, but must not mutate Drawable instances concurrently. |
| Medium | MainActivity.heroArtCache | Classic-layout art cache is a HashMap, cleared under memory pressure but without a byte budget. Replace with a byte-sized LRU after measuring typical art sizes and revisit patterns. |
| Medium | Config.load | Reads/writes theme files and may synchronously commit preferences during startup. Cache parsed theme against a revision and move disk persistence off the first-frame path without losing theme-change invalidation. |
| Medium | UserWallpaperCollection | Stream copies are bounded (100 images, 10 MiB/image, 128 MiB total); files are not normalized like single-image imports. Consider per-image downsampling for large collections; handle EXIF and cancellation, and preserve atomic installation. |
| Medium | WallpaperPlayer / CoverFlowHome.updateFrost | Wallpaper decode is sampled and later changes use a single worker. Frost generation still runs on the UI thread; measure before offloading, preserving bitmap lifetime and rejecting obsolete results. |
| Low | Classic MainActivity trim | Tiles and hero cache are released under memory pressure; wallpaper has a trim API but is only trimmed by CoverFlowHome. Extend classic-layout recovery tests before wiring this into classic trim. |

## Existing safeguards retained

- Wallpaper decoding uses screen-targeted sampling and RGB_565 by default.
- Poster and Cover Flow library icon caches are bounded (six posters / sixteen
  80x80 icons, respectively); do not manually recycle rendered cached bitmaps.
- Wallpaper timers stop when Home is paused; decoding executor shuts down on close.
- Folder copying is streamed on a worker, with bounded input and failure-safe
  replacement; cancelled imports clean their temporary collection.
- Receivers and stats work have lifecycle cleanup. Grid and carousel have separate
  lifecycles and should be tested separately when changing shared code.

## Validation boundaries

Run release build, release lint and JVM tests. UI alignment, focus navigation,
resume latency, 30-minute rotation and actual PSS/heap savings still require device
testing. No numerical speed or memory improvement is asserted without profiling.
