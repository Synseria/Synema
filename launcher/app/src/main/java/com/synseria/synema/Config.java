package com.synseria.synema;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The launcher's settings: the values in res/values/config.xml, with an optional overlay read from
 * {@code config.json} in the app's external files directory
 * ({@code /sdcard/Android/data/com.synseria.synema/files/}). Every key in the JSON is optional and an
 * unknown or badly typed key is ignored, so a theme can change the favourites and the look without a rebuild:
 * {@code tools/theme.sh themes/<name>} pushes a theme folder there and restarts Home.
 *
 * Read on each resume of the launcher's screens; nothing here touches the network.
 */
final class Config {
    private static final String TAG = "TvHome.Config";
    static final String FILE_NAME = "config.json";
    static final String WALLPAPER_NAME = "wallpaper.jpg";

    final List<String> dockApps;
    final List<String> hiddenApps;
    /** Titles hidden from the dashboard's rows, as TvListings.Item.id(). */
    final List<String> hiddenTitles;
    /** Dashboard sections switched off from its edit menu: "resume", "picks", "house". */
    final List<String> hiddenBlocks;
    /** The dashboard's background: the focused title's artwork, a picture, or a theme's id (Backdrop.THEMES). */
    static final String BACKDROP_ARTWORK = "artwork", BACKDROP_PICTURE = "picture";
    final String backdrop;
    /** The background drifts slowly, whichever it is (MovingBackdrop: its own GL surface under the window). */
    final boolean backdropMotion;
    /** The picture for BACKDROP_PICTURE (wallpaper.jpg next to config.json), or null when there is none. */
    final File wallpaper;

    private Config(List<String> dockApps, List<String> hiddenApps, List<String> hiddenTitles, List<String> hiddenBlocks, String backdrop, boolean backdropMotion, File wallpaper) {
        this.dockApps = Collections.unmodifiableList(dockApps);
        this.hiddenApps = Collections.unmodifiableList(hiddenApps);
        this.hiddenTitles = Collections.unmodifiableList(hiddenTitles);
        this.hiddenBlocks = Collections.unmodifiableList(hiddenBlocks);
        this.backdrop = backdrop;
        this.backdropMotion = backdropMotion;
        this.wallpaper = wallpaper;
    }

    /** The same settings with another favourites order, while a move is in progress on the dashboard. */
    Config withDockApps(List<String> dock) {
        return new Config(dock, hiddenApps, hiddenTitles, hiddenBlocks, backdrop, backdropMotion, wallpaper);
    }

    /** The folder the theme tools push into; null when external storage is unavailable. */
    static File dir(Context ctx) {
        return ctx.getExternalFilesDir(null);
    }

    static Config load(Context ctx) {
        Resources res = ctx.getResources();
        List<String> dock = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.dock_apps)));
        List<String> hidden = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.hidden_apps)));
        List<String> hiddenBlocks = new ArrayList<>(Arrays.asList(res.getStringArray(R.array.hidden_blocks)));
        List<String> hiddenTitles = new ArrayList<>();
        String backdrop = BACKDROP_ARTWORK;
        boolean motion = false;

        File dir = dir(ctx);
        File file = dir != null ? new File(dir, FILE_NAME) : null;
        JSONObject json = read(file);
        if (json != null) {
            dock = stringList(json, "dock_apps", dock);
            hidden = stringList(json, "hidden_apps", hidden);
            hiddenTitles = stringList(json, "hidden_titles", hiddenTitles);
            hiddenBlocks = stringList(json, "hidden_blocks", hiddenBlocks);
            String b = json.optString("backdrop");
            if (b.equals(BACKDROP_PICTURE) || Backdrop.theme(b) != null) backdrop = b;
            motion = json.optBoolean("backdrop_motion");
        }
        File wallpaper = dir != null ? new File(dir, WALLPAPER_NAME) : null;
        if (wallpaper != null && !(wallpaper.isFile() && wallpaper.length() > 0)) wallpaper = null;
        if (backdrop.equals(BACKDROP_PICTURE) && wallpaper == null) backdrop = BACKDROP_ARTWORK;

        return new Config(dock, hidden, hiddenTitles, hiddenBlocks, backdrop, motion, wallpaper);
    }

    /**
     * Writes one key (a list or a string) into the overlay, keeping the rest of it: how the dashboard's
     * edit menu saves its choices. False when the file could not be written.
     */
    static boolean save(Context ctx, String key, Object value) {
        File dir = dir(ctx);
        if (dir == null) return false;
        File file = new File(dir, FILE_NAME);
        JSONObject json = read(file);
        if (json == null) json = new JSONObject();
        File tmp = new File(dir, FILE_NAME + ".tmp");
        try {
            json.put(key, value instanceof List ? new JSONArray((List<?>) value) : value);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
                out.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return tmp.renameTo(file);
        } catch (Exception e) {
            Log.w(TAG, "cannot save " + key + ": " + e);
            return false;
        }
    }

    /** Reads and parses the overlay; any problem means "no overlay", never a crash on the home screen. */
    private static JSONObject read(File file) {
        if (file == null || !file.isFile()) return null;
        if (file.length() > 64 * 1024) {
            Log.w(TAG, "ignoring " + file + ": larger than 64 KB");
            return null;
        }
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (text.startsWith("\uFEFF")) text = text.substring(1);   // Windows editors add a BOM
            return new JSONObject(text);
        } catch (Exception e) {
            Log.w(TAG, "ignoring " + file + ": " + e.getMessage());
            return null;
        }
    }


    private static List<String> stringList(JSONObject o, String key, List<String> fallback) {
        Object v = o.opt(key);
        if (!(v instanceof JSONArray)) return fallback;
        JSONArray a = (JSONArray) v;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object item = a.opt(i);
            if (item instanceof String && !((String) item).trim().isEmpty()) out.add(((String) item).trim());
        }
        return out;
    }
}
