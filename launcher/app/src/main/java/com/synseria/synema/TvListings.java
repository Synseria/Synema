package com.synseria.synema;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What apps publish to the system TV provider: their Watch Next rows ("continue watching") and the
 * programs of their preview channels (their picks), with the words apps give about them (synopsis,
 * genre, year, rating, time left). Netflix publishes no Watch Next: its titles in progress sit in its
 * channel, recognisable by their deep link's "continueWatching" list, and count as continue watching
 * here. A third-party launcher sees other apps' rows with READ_TV_LISTINGS (granted over adb by
 * build-and-install.sh), and only those marked searchable. Runs off the main thread.
 */
final class TvListings {
    private static final String TAG = "TvHome.TvListings";

    /**
     * A title as the dashboard shows it: what its app published, completed by TMDB where the app said
     * nothing (with: the one place the two meet, the app's words always winning).
     */
    static final class Item {
        final String pkg;
        final String title;
        /** The episode as the app gives it ("S2 É4 · Le retour", "Épisode 10"), or empty. */
        final String subtitle;
        /** The season and episode numbers the app gave, 0 when it gave none; whether it named the episode. */
        final int season, episode;
        final boolean episodeNamed;
        /** 0..1, or -1 when the app gave no position. */
        final float progress;
        final Intent intent;
        final String posterUri;
        /** The synopsis, or empty. */
        final String description;
        /** "Drame", "2024", "★ 4,2": each may be empty. */
        final String genre, year, rating;
        /** The length the app gave (ms), 0 when it gave none; what is left to watch (ms), -1 when unknown. */
        final long duration, left;
        /** The audience's rating, in percent (TMDB's); -1 when unknown. */
        final int audience;
        /** The kind the app gave (TvContract's TYPE_MOVIE, TYPE_TV_SERIES…), or -1. */
        final int type;
        /** A large picture for the background (TMDB's), or null. */
        final String backdropUri;
        /** Already completed from TMDB. */
        final boolean completed;

        private Item(String pkg, String title, String subtitle, int season, int episode, boolean episodeNamed, float progress,
                     Intent intent, String posterUri, String description, String genre, String year, String rating,
                     long duration, long left, int audience, int type, String backdropUri, boolean completed) {
            this.pkg = pkg;
            this.title = title == null ? "" : title;
            this.subtitle = subtitle;
            this.season = season;
            this.episode = episode;
            this.episodeNamed = episodeNamed;
            this.progress = progress;
            this.intent = intent;
            this.posterUri = posterUri;
            this.description = description;
            this.genre = genre;
            this.year = year;
            this.rating = rating;
            this.duration = duration;
            this.left = left;
            this.audience = audience;
            this.type = type;
            this.backdropUri = backdropUri;
            this.completed = completed;
        }

        /** An app itself, for a favourite that publishes nothing. */
        static Item app(String pkg, String name, Intent launch) {
            return new Item(pkg, name, "", 0, 0, false, -1f, launch, null, "", "", "", "", 0, -1, -1, -1, null, true);
        }

        /**
         * Completed from TMDB where the app gave nothing: the synopsis (the episode's when the app named
         * a season and episode), the episode's title, the genre, the year, a background picture, the
         * audience's rating; and what is left from TMDB's length when the app's is
         * not believable (Left).
         */
        Item with(Tmdb.Info i) {
            boolean ep = season > 0 && episode > 0;
            String sub = ep && !episodeNamed && !i.episodeTitle.isEmpty() ? subtitle + " · " + i.episodeTitle : subtitle;
            String about = !description.isEmpty() ? description : ep && !i.episodeOverview.isEmpty() ? i.episodeOverview : i.overview;
            return new Item(pkg, title, sub, season, episode, episodeNamed || !sub.equals(subtitle), progress, intent, posterUri,
                    about, genre.isEmpty() ? i.genre : genre, year.isEmpty() ? i.year : year, rating, duration,
                    Left.of(duration, progress, type, i.runtime), i.audience, type,
                    backdropUri != null ? backdropUri : i.backdrop.isEmpty() ? null : i.backdrop, true);
        }

        /** "Drame · 2024 · ★ 4,2 · reste 23 min", what is known. */
        String facts(Context ctx) {
            List<String> out = new ArrayList<>();
            for (String f : new String[]{genre, year, rating}) if (!f.isEmpty()) out.add(f);
            if (left >= 0) out.add(ctx.getString(R.string.time_left, minutes(left)));
            return android.text.TextUtils.join(" · ", out);
        }

        /** The title in its app, whatever the progress: what hiding a title remembers. */
        String id() {
            return pkg + '|' + title;
        }

        /** Same app, title and progress: used to skip redrawing a row that did not change. */
        String key() {
            return pkg + '|' + title + '|' + subtitle + '|' + Math.round(progress * 100);
        }
    }

    /**
     * What is left to watch of a title, from its length and how far in it is. The app's length is used
     * when believable; some apps give it at the wrong scale (Canal+, a film of 96 min as 96 s, whose
     * position is off by as much, so the progress bar stays right), and TMDB's length is used instead.
     */
    static final class Left {
        /** No film is this short: an app's length below it is at the wrong scale. */
        static final long FILM_MIN_MS = 5 * 60_000L;
        /** An app's length this many times above or below TMDB's is not the same title's, or not in ms. */
        static final double RUNTIME_RATIO = 2;
        /** A minute or less left: not worth saying. */
        static final long WORTH_MS = 60_000L;

        private Left() {
        }

        /**
         * Ms left, or -1: from the app's {@code duration} when believable, else from TMDB's
         * {@code runtimeMin} (0 when unknown), never from a length that is not.
         */
        static long of(long duration, float progress, int type, int runtimeMin) {
            if (progress < 0) return -1;
            long runtime = runtimeMin * 60_000L;
            boolean film = type == TvContract.PreviewPrograms.TYPE_MOVIE || runtime > 0;
            boolean believable = duration > 0 && !(film && duration < FILM_MIN_MS)
                    && (runtime == 0 || (duration <= runtime * RUNTIME_RATIO && duration * RUNTIME_RATIO >= runtime));
            long whole = believable ? duration : runtime;
            long left = Math.round(whole * (1 - progress));
            return whole > 0 && left > WORTH_MS ? left : -1;
        }
    }

    /** The columns read from both tables (Watch Next and preview programs share their names). */
    private static final String[] COLUMNS = {
            TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
            TvContract.WatchNextPrograms.COLUMN_TITLE,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
            TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
            TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
            TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
            TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
            TvContract.WatchNextPrograms.COLUMN_BROWSABLE,
            TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION,
            TvContract.WatchNextPrograms.COLUMN_LONG_DESCRIPTION,
            TvContract.WatchNextPrograms.COLUMN_CANONICAL_GENRE,
            TvContract.WatchNextPrograms.COLUMN_RELEASE_DATE,
            TvContract.WatchNextPrograms.COLUMN_REVIEW_RATING,
            TvContract.WatchNextPrograms.COLUMN_REVIEW_RATING_STYLE,
            TvContract.WatchNextPrograms.COLUMN_TYPE,
    };
    private static final int PKG = 0, TITLE = 1, EP_TITLE = 2, SEASON = 3, EPISODE = 4, POSITION = 5,
            DURATION = 6, INTENT = 7, POSTER = 8, BROWSABLE = 9, SHORT = 10, LONG = 11, GENRE = 12,
            RELEASED = 13, RATING = 14, RATING_STYLE = 15, TYPE = 16;
    /** Preview programs also say which channel and in what order. */
    private static final int CHANNEL = COLUMNS.length, WEIGHT = COLUMNS.length + 1, ENGAGED = COLUMNS.length;

    /**
     * Continue watching, newest first, at most {@code max}, with every app's newest item ahead of a
     * second one from any app: one app (Canal+ publishes twenty) must not push the others out. Watch
     * Next rows carry the time they were last watched; channel items carry none, so they take the time
     * their app was last used (UsageStats), in the app's own order.
     */
    static List<Item> watchNext(Context ctx, int max) {
        List<long[]> order = new ArrayList<>();
        List<Item> all = new ArrayList<>();
        Map<String, Long> lastUsed = lastUsed(ctx);
        for (Item it : channelContinueWatching(ctx)) {
            Long used = lastUsed.get(it.pkg);
            // Keep the app's order: each next item a millisecond older.
            order.add(new long[]{(used != null ? used : 0L) - all.size(), all.size()});
            all.add(it);
        }
        // A selection or sort order makes the provider throw for callers without full EPG access: filter here.
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI,
                with(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS), null, null, null)) {
            while (c != null && c.moveToNext()) {
                if (c.getInt(BROWSABLE) == 0) continue;   // removed by the user or the app
                Item it = item(ctx, c, c.getString(PKG), false);
                if (it == null) continue;
                order.add(new long[]{c.getLong(ENGAGED), all.size()});
                all.add(it);
            }
        } catch (SecurityException e) {
            Log.w(TAG, "no access to Watch Next (READ_TV_LISTINGS not granted?): " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "Watch Next unreadable: " + e);
        }
        order.sort((a, b) -> Long.compare(b[0], a[0]));
        List<Item> firsts = new ArrayList<>(), rest = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (long[] o : order) {
            Item it = all.get((int) o[1]);
            (seen.add(it.pkg) ? firsts : rest).add(it);
        }
        firsts.addAll(rest);
        return new ArrayList<>(firsts.subList(0, Math.min(max, firsts.size())));
    }

    /** Preview programs whose deep link says they come from the app's continue-watching list (Netflix). */
    private static List<Item> channelContinueWatching(Context ctx) {
        List<Item> out = new ArrayList<>();
        try (Cursor c = previewPrograms(ctx)) {
            while (c != null && c.moveToNext()) {
                String uri = c.getString(INTENT);
                if (c.getInt(BROWSABLE) == 0 || uri == null || !uri.contains(CONTINUE_WATCHING)) continue;
                Item it = item(ctx, c, c.getString(PKG), true);
                if (it == null) continue;
                playFromWhereLeft(it.intent);
                out.add(it);
            }
        } catch (Exception e) {
            Log.w(TAG, "preview programs unreadable: " + e);
        }
        return out;
    }

    /**
     * Netflix links a title in progress to its page (/title/), where the film waits for another OK; the
     * same title under /watch/ plays it from where it was left, which is what continue watching promises.
     * Only over http (nflx:/watch/ opens nothing), and stripped to Netflix's component and its
     * source_type: with the rest of what Netflix published (its launch flag, its tracking extras) the
     * link reached a chooser between Netflix and the browsers.
     */
    private static void playFromWhereLeft(Intent intent) {
        Uri link = intent.getData();
        String path = link == null ? null : link.getPath();
        if (path == null || !path.startsWith("/title/")) return;
        Bundle extras = new Bundle();
        if (intent.hasExtra("source_type")) extras.putInt("source_type", intent.getIntExtra("source_type", 0));
        intent.setData(link.buildUpon().path("/watch/" + path.substring("/title/".length())).build())
                .setFlags(0)
                .replaceExtras(extras);
    }

    /** Netflix's deep-link marker for a title in progress ("listContext=continueWatching", URL-encoded). */
    private static final String CONTINUE_WATCHING = "listContext%3DcontinueWatching";

    /** The programs of the app's first browsable preview channel (its row on the stock home), in order. */
    static List<Item> picks(Context ctx, String pkg, int max) {
        List<Item> out = channelPrograms(ctx, pkg).get(pkg);
        return out == null ? new ArrayList<>() : new ArrayList<>(out.subList(0, Math.min(max, out.size())));
    }

    /**
     * What the apps put forward, every app's picks interleaved: each app's first title comes before any
     * app's second, so one app publishing forty cannot fill the row; the app used last leads each round.
     * At most {@code max}.
     */
    static List<Item> discover(Context ctx, int max) {
        Map<String, List<Item>> byApp = channelPrograms(ctx, null);
        Map<String, Long> lastUsed = lastUsed(ctx);
        List<String> apps = new ArrayList<>(byApp.keySet());
        // Ties (apps never used) by name: the same order every time, so the row is not rebuilt for nothing.
        apps.sort((a, b) -> {
            int byUse = Long.compare(lastUsed.getOrDefault(b, 0L), lastUsed.getOrDefault(a, 0L));
            return byUse != 0 ? byUse : a.compareTo(b);
        });
        List<Item> out = new ArrayList<>();
        for (int round = 0; out.size() < max; round++) {
            boolean any = false;
            for (String app : apps) {
                List<Item> items = byApp.get(app);
                if (round >= items.size() || out.size() == max) continue;
                out.add(items.get(round));
                any = true;
            }
            if (!any) break;
        }
        return out;
    }

    /**
     * Each app's first browsable preview channel, its programs in the app's order (highest weight first,
     * insertion order without weights); only {@code onlyPkg}'s when not null. Titles in progress are
     * left to continue watching.
     */
    private static Map<String, List<Item>> channelPrograms(Context ctx, String onlyPkg) {
        Map<Long, String> channels = firstChannels(ctx, onlyPkg);
        Map<String, List<long[]>> order = new HashMap<>();
        List<Item> all = new ArrayList<>();
        try (Cursor c = previewPrograms(ctx)) {
            while (c != null && c.moveToNext()) {
                String pkg = channels.get(c.getLong(CHANNEL));
                if (pkg == null || c.getInt(BROWSABLE) == 0
                        || String.valueOf(c.getString(INTENT)).contains(CONTINUE_WATCHING)) continue;
                Item it = item(ctx, c, pkg, false);
                if (it == null) continue;
                order.computeIfAbsent(pkg, k -> new ArrayList<>()).add(new long[]{c.getLong(WEIGHT), all.size()});
                all.add(it);
            }
        } catch (Exception e) {
            Log.w(TAG, "preview programs unreadable: " + e);
        }
        Map<String, List<Item>> out = new HashMap<>();
        for (Map.Entry<String, List<long[]>> e : order.entrySet()) {
            e.getValue().sort((a, b) -> Long.compare(b[0], a[0]));
            List<Item> items = new ArrayList<>();
            for (long[] o : e.getValue()) items.add(all.get((int) o[1]));
            out.put(e.getKey(), items);
        }
        return out;
    }

    /**
     * Everything the apps published that can be opened: their continue watching and all their channels'
     * programs. What the assistant searches when asked to play a title.
     */
    static List<Item> catalogue(Context ctx) {
        List<Item> out = new ArrayList<>(watchNext(ctx, Integer.MAX_VALUE));
        try (Cursor c = previewPrograms(ctx)) {
            while (c != null && c.moveToNext()) {
                if (c.getInt(BROWSABLE) == 0) continue;
                Item it = item(ctx, c, c.getString(PKG), false);
                if (it != null) out.add(it);
            }
        } catch (Exception e) {
            Log.w(TAG, "preview programs unreadable: " + e);
        }
        return out;
    }

    /**
     * The catalogue's title best matching what was said, or null: every word of the title, accents and
     * case aside, must be in the request ("mets l'amour est dans le pré sur Canal+" finds "L'amour est
     * dans le pré - Saison 21"), the longest such title wins, and a named app breaks ties.
     */
    static Item find(List<Item> catalogue, String request, String appHint) {
        String said = " " + plain(request) + " ";
        Item best = null;
        int bestLen = 0;
        for (Item it : catalogue) {
            String title = plain(it.title.replaceAll("\\s+[-–:]\\s+.*$", ""));   // "Titre - Saison 21" → "Titre"
            if (title.length() < 3 || !said.contains(" " + title + " ")) continue;
            int len = title.length() + (appHint != null && appHint.equals(it.pkg) ? 1000 : 0);
            if (len > bestLen) {
                best = it;
                bestLen = len;
            }
        }
        return best;
    }

    /** Lower case, no accents or punctuation, single spaces. */
    static String plain(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** Each app's first browsable channel, by channel id; only {@code onlyPkg}'s when not null. */
    private static Map<Long, String> firstChannels(Context ctx, String onlyPkg) {
        Map<Long, String> out = new HashMap<>();
        Set<String> seen = new HashSet<>();
        try (Cursor c = ctx.getContentResolver().query(TvContract.Channels.CONTENT_URI,
                new String[]{TvContract.Channels._ID, TvContract.Channels.COLUMN_PACKAGE_NAME, TvContract.Channels.COLUMN_BROWSABLE},
                null, null, null)) {
            while (c != null && c.moveToNext()) {
                String pkg = c.getString(1);
                if (pkg == null || c.getInt(2) == 0 || (onlyPkg != null && !onlyPkg.equals(pkg))) continue;
                if (seen.add(pkg)) out.put(c.getLong(0), pkg);
            }
        } catch (Exception e) {
            Log.w(TAG, "channels unreadable: " + e);
        }
        return out;
    }

    private static Cursor previewPrograms(Context ctx) {
        return ctx.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,
                with(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, TvContract.PreviewPrograms.COLUMN_WEIGHT), null, null, null);
    }

    private static String[] with(String... extra) {
        String[] out = new String[COLUMNS.length + extra.length];
        System.arraycopy(COLUMNS, 0, out, 0, COLUMNS.length);
        System.arraycopy(extra, 0, out, COLUMNS.length, extra.length);
        return out;
    }

    /** One row as an Item; {@code netflixStyle}: the episode sits in the short description. */
    private static Item item(Context ctx, Cursor c, String pkg, boolean netflixStyle) {
        Intent intent = parseIntent(c.getString(INTENT));
        if (intent == null || stale(ctx, c.getString(POSTER))) return null;
        String season = c.getString(SEASON), episode = c.getString(EPISODE), epTitle = c.getString(EP_TITLE);
        StringBuilder sub = new StringBuilder();
        if (season != null && episode != null) sub.append('S').append(season).append(" É").append(episode);
        else if (episode != null) sub.append("É").append(episode);
        boolean named = epTitle != null && !epTitle.isEmpty();
        if (named) sub.append(sub.length() > 0 ? " · " : "").append(epTitle);
        String shortDesc = nonNull(c.getString(SHORT)), longDesc = nonNull(c.getString(LONG));
        if (netflixStyle && sub.length() == 0) sub.append(episode(shortDesc));
        long pos = c.getLong(POSITION), dur = c.getLong(DURATION);
        float progress = dur > 0 && pos > 0 ? Math.min(1f, pos / (float) dur) : -1f;
        String description = !longDesc.isEmpty() ? longDesc : netflixStyle ? "" : shortDesc;
        String released = nonNull(c.getString(RELEASED));
        int type = c.isNull(TYPE) ? -1 : c.getInt(TYPE);
        return new Item(pkg, c.getString(TITLE), sub.toString(), number(season), number(episode), named, progress, intent,
                c.getString(POSTER), description, genre(c), released.matches("\\d{4}.*") ? released.substring(0, 4) : "",
                rating(c), dur, Left.of(dur, progress, type, 0), -1, type, null, false);
    }

    private static int number(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * A row an app left behind in an older version: its artwork points to a provider the app no longer
     * has (Plex's RecommendationsProvider). Shown, it was a line of logos opening nothing useful.
     */
    private static boolean stale(Context ctx, String poster) {
        if (poster == null || !poster.startsWith("content://")) return false;
        String authority = android.net.Uri.parse(poster).getAuthority();
        return authority != null && ctx.getPackageManager().resolveContentProvider(authority, 0) == null;
    }

    /** The first genre the app gave, in words; empty without. */
    private static String genre(Cursor c) {
        String genres = c.getString(GENRE);
        String[] decoded = genres == null || genres.isEmpty() ? new String[0] : TvContract.Programs.Genres.decode(genres);
        return decoded.length > 0 && !decoded[0].isEmpty() ? genre(decoded[0]) : "";
    }

    /** The rating the app gave, as stars or a percentage; empty without. */
    private static String rating(Cursor c) {
        String rating = c.getString(RATING);
        if (rating == null || rating.isEmpty()) return "";
        try {
            float r = Float.parseFloat(rating);
            int style = c.getInt(RATING_STYLE);
            if (style == TvContract.PreviewPrograms.REVIEW_RATING_STYLE_PERCENTAGE) return Math.round(r) + " %";
            if (style == TvContract.PreviewPrograms.REVIEW_RATING_STYLE_STARS) return String.format(Locale.getDefault(), "★ %.1f", r);
        } catch (NumberFormatException ignored) {
        }
        return "";
    }

    private static String minutes(long ms) {
        long m = ms / 60_000;
        return m >= 60 ? (m / 60) + " h " + String.format(Locale.ROOT, "%02d", m % 60) : m + " min";
    }

    /** The system's genre ids in French (the dashboard's language); others as they are, capitalised. */
    private static String genre(String g) {
        switch (g) {
            case TvContract.Programs.Genres.MOVIES: return "Film";
            case TvContract.Programs.Genres.DRAMA: return "Drame";
            case TvContract.Programs.Genres.COMEDY: return "Comédie";
            case TvContract.Programs.Genres.SPORTS: return "Sport";
            case TvContract.Programs.Genres.FAMILY_KIDS: return "Jeunesse";
            case TvContract.Programs.Genres.ANIMAL_WILDLIFE: return "Animaux";
            case TvContract.Programs.Genres.NEWS: return "Info";
            case TvContract.Programs.Genres.MUSIC: return "Musique";
            case TvContract.Programs.Genres.ENTERTAINMENT: return "Divertissement";
            case TvContract.Programs.Genres.TRAVEL: return "Voyage";
            case TvContract.Programs.Genres.EDUCATION: return "Documentaire";
            default: return g.charAt(0) + g.substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    /** "Reprendre la lecture Épisode 10" → "Épisode 10": the app's own wording, without the verb. */
    private static String episode(String description) {
        int i = description.indexOf("Épisode");
        if (i < 0) i = description.indexOf("Episode");
        return i >= 0 ? description.substring(i) : "";
    }

    /** When each app was last in front, from usage stats; empty without the GET_USAGE_STATS app-op. */
    private static Map<String, Long> lastUsed(Context ctx) {
        Map<String, Long> out = new HashMap<>();
        UsageStatsManager usm = ctx.getSystemService(UsageStatsManager.class);
        if (usm == null) return out;
        long now = System.currentTimeMillis();
        try {
            for (Map.Entry<String, UsageStats> e : usm.queryAndAggregateUsageStats(now - 30L * 24 * 3600_000, now).entrySet()) {
                out.put(e.getKey(), e.getValue().getLastTimeUsed());
            }
        } catch (SecurityException e) {
            Log.w(TAG, "no usage stats (GET_USAGE_STATS not allowed?)");
        }
        return out;
    }

    private static Intent parseIntent(String uri) {
        if (uri == null) return null;
        try {
            return Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
        } catch (Exception e) {
            return null;
        }
    }
}
