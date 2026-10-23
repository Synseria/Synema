package com.synseria.synema;

import android.content.Context;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What TMDB (themoviedb.org) knows of a title, to complete what its app publishes: the synopsis, an
 * episode's title and synopsis, the genre and year, a large picture for the background, the audience's
 * rating. Only with {@code tmdb_token} in the secrets; without, nothing changes.
 *
 * A title is matched on its exact name (as a film or a series when the app says which), on its year
 * when the app gives one, else the most popular; no sure match gives nothing rather than another
 * title's synopsis. Asked only for the title the band shows, off the main thread, a few requests a
 * second at most, and kept on disk for a week, misses included. The token goes in a header, never in
 * an address or a log.
 */
final class Tmdb {
    private static final String TAG = "TvHome.Tmdb";
    private static final String API = "https://api.themoviedb.org/3/";
    /** The background picture as it was made (1920 or 3840 wide), decoded at the screen's width. */
    private static final String BACKDROP = "https://image.tmdb.org/t/p/original";
    /** Fewer votes than this say little of what the audience thinks: no rating then. */
    static final int AUDIENCE_MIN_VOTES = 50;
    /** Bumped when what is kept per title changes, so older answers are asked again. */
    private static final int VERSION = 3;
    private static final String LANGUAGE = "fr-FR";
    private static final long KEEP_MS = 7 * 24 * 3600_000L;
    /** Between two requests: TMDB asks for restraint, and the TV's network has other work. */
    private static final long SPACING_MS = 300;
    private static final int TIMEOUT_MS = 6000;

    /** What TMDB adds; any field may be empty, runtime 0, a rating -1. */
    static final class Info {
        final String overview, episodeTitle, episodeOverview, genre, year, backdrop;
        /** A film's length in minutes, 0 when unknown. */
        final int runtime;
        /** The audience's rating, in percent; -1 when unknown. */
        final int audience;

        Info(JSONObject j) {
            overview = j.optString("overview");
            episodeTitle = j.optString("episodeTitle");
            episodeOverview = j.optString("episodeOverview");
            genre = j.optString("genre");
            year = j.optString("year");
            String path = j.optString("backdrop_path");
            backdrop = path.startsWith("/") ? BACKDROP + path : "";
            runtime = j.optInt("runtime");
            audience = j.optInt("audience", -1);
        }
    }

    private static Tmdb instance;
    private static String instanceToken;

    private final String token;
    private final File dir;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Answers this session, by title: an Info, or NONE when TMDB has no sure match. */
    private final Map<String, Object> memory = new HashMap<>();
    private final Map<String, List<HomeAssistant.Callback<Info>>> asked = new HashMap<>();
    private static final Object NONE = new Object();
    private long lastRequest;

    private Tmdb(Context ctx, String token) {
        this.token = token;
        dir = new File(ctx.getCacheDir(), "tmdb");
    }

    /** The configured client, or null without a token. */
    static synchronized Tmdb get(Context ctx) {
        String token = Secrets.read(ctx).optString("tmdb_token");
        if (token.isEmpty()) return null;
        if (instance == null || !token.equals(instanceToken)) {
            instance = new Tmdb(ctx.getApplicationContext(), token);
            instanceToken = token;
        }
        return instance;
    }

    /** What is already known of this title, without asking; null when nothing is (yet). */
    Info known(TvListings.Item item) {
        Object o = memory.get(key(item));
        return o instanceof Info ? (Info) o : null;
    }

    /** Looks the title up, then answers on the main thread: null when TMDB has no sure match or did not answer. */
    void lookup(TvListings.Item item, HomeAssistant.Callback<Info> cb) {
        String key = key(item);
        Object o = memory.get(key);
        if (o != null) {
            cb.done(o instanceof Info ? (Info) o : null);
            return;
        }
        List<HomeAssistant.Callback<Info>> waiting = asked.get(key);
        if (waiting != null) {
            waiting.add(cb);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(cb);
        asked.put(key, waiting);
        io.execute(() -> {
            JSONObject found = cached(key);
            boolean fromDisk = found != null;
            if (!fromDisk) found = find(item);
            JSONObject result = found;
            main.post(() -> {
                // A failed request (network, rate limit) is tried again next time; a miss is kept.
                if (result != null) memory.put(key, result.length() == 0 ? NONE : new Info(result));
                Info info = result == null || result.length() == 0 ? null : new Info(result);
                List<HomeAssistant.Callback<Info>> all = asked.remove(key);
                if (all != null) for (HomeAssistant.Callback<Info> c : all) c.done(info);
            });
            if (!fromDisk && result != null) store(key, result);
        });
    }

    /** The title as matched (its kind, plain name, year, season and episode), and what is asked of it. */
    private String key(TvListings.Item item) {
        return VERSION + "|" + kind(item) + "|" + TvListings.plain(name(item)) + "|" + item.year
                + "|" + item.season + "|" + item.episode;
    }

    /** "movie", "tv" or "" (either), from what the app says. */
    private static String kind(TvListings.Item item) {
        if (item.type == TvContract.PreviewPrograms.TYPE_MOVIE) return "movie";
        if (item.season > 0 || item.episode > 0 || item.type == TvContract.PreviewPrograms.TYPE_TV_SERIES
                || item.type == TvContract.PreviewPrograms.TYPE_TV_SEASON || item.type == TvContract.PreviewPrograms.TYPE_TV_EPISODE) return "tv";
        return "";
    }

    /** The title without what apps add to it ("Titre - Saison 21" is "Titre"). */
    private static String name(TvListings.Item item) {
        return item.title.replaceAll("(?i)\\s+[-–:]\\s+(Saison|Season|S)\\s*\\d+.*$", "").trim();
    }

    // ---- on the io thread --------------------------------------------------------------------------

    /** The match and its details, {} for no sure match, null when TMDB could not be asked. */
    private JSONObject find(TvListings.Item item) {
        String kind = kind(item), title = name(item);
        JSONObject search = get("search/" + (kind.isEmpty() ? "multi" : kind), "query", title);
        if (search == null) return null;
        JSONObject match = best(search.optJSONArray("results"), kind, title, item.year);
        if (match == null) return new JSONObject();
        String type = kind.isEmpty() ? match.optString("media_type") : kind;
        JSONObject details = get(type + "/" + match.optLong("id"));
        if (details == null) return null;
        try {
            JSONObject out = new JSONObject();
            out.put("overview", details.optString("overview"));
            JSONArray genres = details.optJSONArray("genres");
            if (genres != null && genres.length() > 0) out.put("genre", genres.optJSONObject(0).optString("name"));
            String date = details.optString("movie".equals(type) ? "release_date" : "first_air_date");
            if (date.length() >= 4) out.put("year", date.substring(0, 4));
            out.put("backdrop_path", details.optString("backdrop_path"));
            out.put("runtime", details.optInt("runtime"));
            if (details.optInt("vote_count") >= AUDIENCE_MIN_VOTES) {
                out.put("audience", (int) Math.round(details.optDouble("vote_average") * 10));
            }
            if ("tv".equals(type) && item.season > 0 && item.episode > 0) {
                JSONObject ep = get("tv/" + match.optLong("id") + "/season/" + item.season + "/episode/" + item.episode);
                if (ep != null) {
                    out.put("episodeTitle", ep.optString("name"));
                    out.put("episodeOverview", ep.optString("overview"));
                }
            }
            return out;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /**
     * The result named exactly as the title (its local or original name), of its year when the app
     * gives one, the most popular; null when none is.
     */
    private static JSONObject best(JSONArray results, String kind, String title, String year) {
        String wanted = TvListings.plain(title);
        JSONObject best = null;
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String type = kind.isEmpty() ? r.optString("media_type") : kind;
            if (!"movie".equals(type) && !"tv".equals(type)) continue;
            boolean film = "movie".equals(type);
            if (!wanted.equals(TvListings.plain(r.optString(film ? "title" : "name")))
                    && !wanted.equals(TvListings.plain(r.optString(film ? "original_title" : "original_name")))) continue;
            String date = r.optString(film ? "release_date" : "first_air_date");
            if (!year.isEmpty() && !date.startsWith(year)) continue;
            if (best == null || r.optDouble("popularity") > best.optDouble("popularity")) best = r;
        }
        return best;
    }

    /** A GET of the API in French, spaced from the last; null on any failure. */
    private JSONObject get(String path, String... query) {
        Uri.Builder u = Uri.parse(API + path).buildUpon().appendQueryParameter("language", LANGUAGE);
        for (int i = 0; i + 1 < query.length; i += 2) u.appendQueryParameter(query[i], query[i + 1]);
        return fetch(u.build().toString(), path);
    }

    /** A JSON GET, spaced from the last request; null on any failure. Logged as {@code name}. */
    private JSONObject fetch(String url, String name) {
        long wait = lastRequest + SPACING_MS - SystemClock.elapsedRealtime();
        if (wait > 0) SystemClock.sleep(wait);
        lastRequest = SystemClock.elapsedRealtime();
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("Authorization", "Bearer " + token);
            c.setRequestProperty("Accept", "application/json");
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                Log.w(TAG, name + ": HTTP " + code);
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                return new JSONObject(new String(HomeAssistant.readAll(in), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, name + ": " + e.getClass().getSimpleName());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The answer kept on disk for this title, if younger than a week. */
    private JSONObject cached(String key) {
        File f = new File(dir, file(key));
        if (!f.isFile() || System.currentTimeMillis() - f.lastModified() > KEEP_MS) return null;
        try (InputStream in = new FileInputStream(f)) {
            return new JSONObject(new String(HomeAssistant.readAll(in), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    private void store(String key, JSONObject value) {
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        try (FileOutputStream out = new FileOutputStream(new File(dir, file(key)))) {
            out.write(value.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "cache: " + e.getClass().getSimpleName());
        }
    }

    private static String file(String key) {
        try {
            StringBuilder sb = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8))) {
                sb.append(String.format("%02x", b));
            }
            return sb.append(".json").toString();
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode()) + ".json";
        }
    }
}
