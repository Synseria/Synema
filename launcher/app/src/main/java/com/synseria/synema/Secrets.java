package com.synseria.synema;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * The keys of the services the launcher talks to (Home Assistant's address and token, TMDB's token), in
 * secrets.json in the app's external files directory, pushed by tools/secrets.sh from secrets.local.json
 * on the Mac: never in the APK, the repo or a log.
 */
final class Secrets {
    private static final String TAG = "TvHome.Secrets";
    static final String FILE_NAME = "secrets.json";

    private Secrets() {
    }

    /** The keys; empty when the file is missing or unreadable. */
    static JSONObject read(Context ctx) {
        File dir = Config.dir(ctx);
        File f = dir != null ? new File(dir, FILE_NAME) : null;
        if (f == null || !f.isFile()) return new JSONObject();
        try (InputStream in = new FileInputStream(f)) {
            return new JSONObject(new String(HomeAssistant.readAll(in), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "unreadable " + FILE_NAME);
            return new JSONObject();
        }
    }
}
