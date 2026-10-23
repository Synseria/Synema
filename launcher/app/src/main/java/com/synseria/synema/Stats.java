package com.synseria.synema;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sensors' long-term statistics from Home Assistant (recorder/statistics_during_period, over HaLive):
 * per 5 minutes, hour, day or month, a reading's mean, min and max, a meter's consumption; and the
 * Energy dashboard's grid, its meters and the month's cost. What the
 * graphs draw: 30 days stay a few hundred points, where the raw history of a busy sensor is thousands.
 * Kept a few minutes, so moving between spans and rooms reads nothing twice.
 */
final class Stats {
    /** One period's values; NaN when Home Assistant gave none. */
    static final class Point {
        final long start;
        final double mean, min, max, change;

        Point(JSONObject p) {
            start = p.optLong("start");
            mean = p.optDouble("mean");
            min = p.optDouble("min");
            max = p.optDouble("max");
            change = p.optDouble("change");
        }

        /** A meter's use over the period starting at {@code start}. */
        Point(long start, double change) {
            this.start = start;
            mean = min = max = Double.NaN;
            this.change = change;
        }

        /** A reading over the period starting at {@code start}. */
        Point(long start, double mean, double min, double max) {
            this.start = start;
            this.mean = mean;
            this.min = min;
            this.max = max;
            change = Double.NaN;
        }
    }

    /** What a graph covers, and the period of its points. */
    enum Span {
        HOURS_6("6 h", 6 * History.HOUR_MS, "5minute", 15 * 60_000L),
        DAY("24 h", 24 * History.HOUR_MS, "5minute", 30 * 60_000L),
        WEEK("7 j", 7 * 24 * History.HOUR_MS, "hour", 3 * History.HOUR_MS),
        // A day a point: a month of hours is a zigzag of nights and days.
        MONTH("30 j", 30 * 24 * History.HOUR_MS, "day", 24 * History.HOUR_MS);

        final String label;
        final long ms;
        /** What Home Assistant is asked for. */
        final String period;
        /** What a drawn point gathers (Chart): two to five dozen points a graph. */
        final long drawnMs;

        Span(String label, long ms, String period, long drawnMs) {
            this.label = label;
            this.ms = ms;
            this.period = period;
            this.drawnMs = drawnMs;
        }
    }

    /** A meter's consumption per hour today, per day over 30 days, per month over a year. */
    enum Range {
        TODAY, DAYS, MONTHS;

        /** Where it starts, at local midnight. */
        long start() {
            LocalDate today = LocalDate.now();
            LocalDate from = this == TODAY ? today : this == DAYS ? today.minusDays(29) : today.withDayOfMonth(1).minusMonths(11);
            return from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
    }

    private static final long FRESH_MS = 5 * 60_000L;
    private static final Map<String, Object[]> CACHE = new HashMap<>();

    private Stats() {
    }

    /** Readings (mean, min, max) of {@code sensors} over {@code span}; empty lists on failure. Main thread. */
    static void readings(HaLive live, List<String> sensors, Span span, HomeAssistant.Callback<Map<String, List<Point>>> cb) {
        read(live, sensors, System.currentTimeMillis() - span.ms, span.period, "[\"mean\",\"min\",\"max\"]", null, cb);
    }

    /** What {@code meters} used (change, kWh) per {@code period} ("hour", "day") since {@code since}. Main thread. */
    static void consumption(HaLive live, List<String> meters, String period, long since, HomeAssistant.Callback<Map<String, List<Point>>> cb) {
        read(live, meters, since, period, "[\"change\"]", "{\"energy\":\"kWh\"}", cb);
    }

    private static void read(HaLive live, List<String> ids, long since, String period, String types, String units,
                             HomeAssistant.Callback<Map<String, List<Point>>> cb) {
        String key = ids + period + types + since / FRESH_MS;
        Object[] have = CACHE.get(key);
        if (have != null && System.currentTimeMillis() - (Long) have[0] < FRESH_MS) {
            @SuppressWarnings("unchecked") Map<String, List<Point>> data = (Map<String, List<Point>>) have[1];
            cb.done(data);
            return;
        }
        if (live == null || ids.isEmpty()) {
            cb.done(Collections.emptyMap());
            return;
        }
        try {
            JSONObject command = new JSONObject().put("type", "recorder/statistics_during_period")
                    .put("start_time", Instant.ofEpochMilli(since).toString())
                    .put("statistic_ids", new JSONArray(ids)).put("period", period).put("types", new JSONArray(types));
            if (units != null) command.put("units", new JSONObject(units));
            live.request(command, result -> {
                Map<String, List<Point>> out = new HashMap<>();
                JSONObject all = result == null ? null : result.optJSONObject("result");
                for (String id : ids) {
                    JSONArray points = all == null ? null : all.optJSONArray(id);
                    List<Point> list = new ArrayList<>();
                    for (int i = 0; points != null && i < points.length(); i++) list.add(new Point(points.optJSONObject(i)));
                    out.put(id, list);
                }
                if (result != null) CACHE.put(key, new Object[]{System.currentTimeMillis(), out});
                cb.done(out);
            });
        } catch (org.json.JSONException e) {
            cb.done(Collections.emptyMap());
        }
    }

    /** The Energy dashboard's electricity from the grid: its meters, and the statistics of what their use cost. */
    static final class Grid {
        final List<String> meters = new ArrayList<>(), costs = new ArrayList<>();
    }

    /**
     * The grid as Home Assistant's energy settings give it (older settings list its meters under
     * flow_from); empty when it has none. A meter valued at a price has no cost statistic in the
     * settings: Home Assistant makes one, named in energy/info. Main thread.
     */
    static void grid(HaLive live, HomeAssistant.Callback<Grid> cb) {
        Grid out = new Grid();
        if (live == null) {
            cb.done(out);
            return;
        }
        live.request(command("energy/get_prefs"), prefs -> live.request(command("energy/info"), info -> {
            JSONObject p = prefs == null ? null : prefs.optJSONObject("result");
            JSONObject i = info == null ? null : info.optJSONObject("result");
            JSONObject made = i == null ? null : i.optJSONObject("cost_sensors");
            JSONArray sources = p == null ? null : p.optJSONArray("energy_sources");
            for (int k = 0; sources != null && k < sources.length(); k++) {
                JSONObject s = sources.optJSONObject(k);
                if (s == null || !"grid".equals(s.optString("type"))) continue;
                flow(s, made, out);
                JSONArray from = s.optJSONArray("flow_from");
                for (int j = 0; from != null && j < from.length(); j++) if (from.optJSONObject(j) != null) flow(from.optJSONObject(j), made, out);
            }
            cb.done(out);
        }));
    }

    private static void flow(JSONObject f, JSONObject made, Grid out) {
        String meter = f.isNull("stat_energy_from") ? "" : f.optString("stat_energy_from");
        if (meter.isEmpty()) return;
        out.meters.add(meter);
        String cost = f.isNull("stat_cost") ? "" : f.optString("stat_cost");
        if (cost.isEmpty() && made != null && !made.isNull(meter)) cost = made.optString(meter);
        if (!cost.isEmpty()) out.costs.add(cost);
    }

    /**
     * What the grid's electricity has cost since the month began (€), each hour at its price as the
     * Energy dashboard counts it; NaN when unknown, or when a statistic could not be read (a part would
     * pass for the whole). Main thread.
     */
    static void monthCost(HaLive live, HomeAssistant.Callback<Double> cb) {
        grid(live, g -> {
            if (g.costs.isEmpty()) {
                cb.done(Double.NaN);
                return;
            }
            double[] sum = {0};
            int[] left = {g.costs.size()};
            for (String id : g.costs) {
                JSONObject command = command("recorder/statistic_during_period");
                try {
                    command.put("statistic_id", id).put("types", new JSONArray().put("change"))
                            .put("calendar", new JSONObject().put("period", "month"));
                } catch (org.json.JSONException e) {
                    sum[0] = Double.NaN;
                }
                live.request(command, r -> {
                    JSONObject result = r == null ? null : r.optJSONObject("result");
                    // No change at all this month (a red day yet to come) is none used.
                    if (result == null) sum[0] = Double.NaN;
                    else if (!result.isNull("change")) sum[0] += result.optDouble("change", 0);
                    if (--left[0] == 0) cb.done(sum[0]);
                });
            }
        });
    }

    private static JSONObject command(String type) {
        try {
            return new JSONObject().put("type", type);
        } catch (org.json.JSONException e) {
            throw new IllegalArgumentException(type, e);
        }
    }
}
