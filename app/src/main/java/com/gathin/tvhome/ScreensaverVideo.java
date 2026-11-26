package com.gathin.tvhome;

import android.net.Uri;

/** One readable video reference. A folder binding never copies the underlying video. */
final class ScreensaverVideo {
    final Uri uri;
    final String name, key, artworkKey;

    ScreensaverVideo(Uri uri, String name, long size, long modified) {
        this.uri = uri;
        this.name = name;
        key = uri.toString();
        artworkKey = key + ':' + size + ':' + modified;
    }

    String title() {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
