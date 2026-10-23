package com.synseria.synema;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.http.HttpResponseCache;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Artwork for the dashboard's cards, fetched once and kept in memory: coming back to Home redraws
 * from the cache with no flash. content:// from the app's provider or http(s).
 */
final class Posters {
    private static final String TAG = "TvHome.Posters";
    /** About 24 MB of decoded 565 bitmaps: a few dozen cards at the dashboard's size. */
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Posters() {
    }

    /**
     * Behind an app: the most recent third kept (the band's, the background's), the rest let go for the
     * app in front. The cards keep their own pictures, so Home comes back without a flash.
     */
    static void trim() {
        CACHE.trimToSize(CACHE.maxSize() / 3);
    }

    /**
     * Pictures kept on disk as long as their servers allow (TMDB's for a year): the background of a title
     * seen before shows at once, without downloading its 1 to 4 MB again. Once per process.
     */
    static void keepOnDisk(Context ctx) {
        if (HttpResponseCache.getInstalled() != null) return;
        try {
            HttpResponseCache.install(new File(ctx.getCacheDir(), "http"), 64L * 1024 * 1024);
        } catch (java.io.IOException e) {
            Log.w(TAG, "no disk cache: " + e);
        }
    }

    static Bitmap cached(String uri, int width) {
        return uri == null ? null : CACHE.get(width + "|" + uri);
    }

    /**
     * Puts the artwork in the view, now if cached, else once loaded and if the view still wants it;
     * the view stays an empty placeholder meanwhile, and shows {@code fallback} (the app's banner) when
     * there is no artwork: a banner flashing before every poster looked like a glitch.
     */
    static void into(Context ctx, String uri, int width, ImageView view, Drawable fallback) {
        view.setTag(R.id.poster_uri, uri);
        Bitmap hit = cached(uri, width);
        if (hit != null) {
            view.setImageBitmap(hit);
            return;
        }
        if (uri == null || uri.isEmpty() || MISSING.contains(uri)) {
            view.setImageDrawable(fallback);
            return;
        }
        view.setImageDrawable(null);
        load(ctx.getApplicationContext(), uri, width, bmp -> {
            if (!uri.equals(view.getTag(R.id.poster_uri))) return;
            if (bmp != null) view.setImageBitmap(bmp);
            else view.setImageDrawable(fallback);
        });
    }

    /** Artwork that could not be read (an app's stale rows): the banner at once, no new attempt. */
    private static final Set<String> MISSING = Collections.synchronizedSet(new HashSet<>());

    static void load(Context ctx, String uri, int width, HomeAssistant.Callback<Bitmap> cb) {
        Bitmap hit = cached(uri, width);
        if (hit != null) {
            cb.done(hit);
            return;
        }
        IO.execute(() -> {
            Bitmap bmp = fetch(ctx, uri, width);
            if (bmp != null) CACHE.put(width + "|" + uri, bmp);
            else MISSING.add(uri);
            MAIN.post(() -> cb.done(bmp));
        });
    }

    private static Bitmap fetch(Context ctx, String uri, int width) {
        try {
            Uri u = Uri.parse(uri);
            if ("http".equals(u.getScheme()) || "https".equals(u.getScheme())) {
                HttpURLConnection c = (HttpURLConnection) new URL(uri).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                try (InputStream in = c.getInputStream()) {
                    return HomeAssistant.decode(HomeAssistant.readAll(in), width);
                } finally {
                    c.disconnect();
                }
            }
            try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
                return in == null ? null : HomeAssistant.decode(HomeAssistant.readAll(in), width);
            }
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, uri + ": " + e);
            return null;
        }
    }
}
