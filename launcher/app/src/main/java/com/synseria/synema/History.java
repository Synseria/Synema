package com.synseria.synema;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sensors' past readings, from Home Assistant's history: read in one request for every sensor shown, at
 * most every quarter of an hour, and kept for the process. The trends beside the readings (top line,
 * panel, room page) and a room page's graph all come from here. On the main thread.
 */
final class History {
    static final long HOUR_MS = 3600_000L;
    /** A series is read again once this old. */
    private static final long FRESH_MS = 15 * 60_000L;
    /**
     * A change compares the last quarter of an hour with a quarter of an hour a span ago, each averaged:
     * a jumpy reading (sound) does not swing it.
     */
    private static final long WINDOW_MS = 15 * 60_000L;

    /** A sensor's readings, oldest first, each in force until the next; the first is the one at {@code since}. */
    static final class Series {
        final long since, readAt;
        final long[] times;
        final double[] values;

        Series(long since, long readAt, List<HomeAssistant.State> states) {
            this.since = since;
            this.readAt = readAt;
            List<Long> ts = new ArrayList<>();
            List<Double> vs = new ArrayList<>();
            for (HomeAssistant.State s : states) {
                Double v = HouseText.number(s);
                if (v == null) continue;
                ts.add(Math.max(since, s.changed));
                vs.add(v);
            }
            times = new long[ts.size()];
            values = new double[vs.size()];
            for (int i = 0; i < times.length; i++) {
                times[i] = ts.get(i);
                values[i] = vs.get(i);
            }
        }

        /** The value in force at {@code time}, or null before the first. */
        Double at(long time) {
            int lo = 0, hi = times.length - 1, found = -1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                if (times[mid] <= time) {
                    found = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return found < 0 ? null : values[found];
        }

        /** The mean of the values in force at five instants from {@code from} to {@code to}; null when none is known. */
        Double mean(long from, long to) {
            double sum = 0;
            int n = 0;
            for (int i = 0; i < 5; i++) {
                Double v = at(from + (to - from) * i / 4);
                if (v != null) {
                    sum += v;
                    n++;
                }
            }
            return n == 0 ? null : sum / n;
        }
    }

    private static final Map<String, Series> SERIES = new HashMap<>();
    /** Being read: not asked for again meanwhile. */
    private static final Set<String> READING = new HashSet<>();

    private History() {
    }

    /**
     * Reads, in one request, those of {@code sensors} whose series is older than FRESH_MS or covers less
     * than {@code spanMs}; then runs {@code done}, only when something was read.
     */
    static void need(HomeAssistant ha, Collection<String> sensors, long spanMs, Runnable done) {
        long now = System.currentTimeMillis();
        List<String> missing = new ArrayList<>();
        for (String s : sensors) {
            if (s == null || s.isEmpty() || READING.contains(s) || missing.contains(s)) continue;
            Series have = SERIES.get(s);
            if (have == null || now - have.readAt > FRESH_MS || have.since > now - spanMs + FRESH_MS) missing.add(s);
        }
        if (missing.isEmpty()) return;
        READING.addAll(missing);
        // A series read again keeps its length: a room's day stays a day when the trends refresh it.
        long since = now - spanMs;
        for (String s : missing) {
            Series have = SERIES.get(s);
            if (have != null) since = Math.min(since, now - (have.readAt - have.since));
        }
        long from = since;
        ha.histories(missing, from, read -> {
            READING.removeAll(missing);
            for (Map.Entry<String, List<HomeAssistant.State>> e : read.entrySet()) {
                SERIES.put(e.getKey(), new Series(from, now, e.getValue()));
            }
            if (!read.isEmpty()) done.run();
        });
    }

    /** The sensor's series, or null before it is read. */
    static Series of(String sensor) {
        return sensor == null ? null : SERIES.get(sensor);
    }

    /** How much the reading moved over {@code spanMs} (see WINDOW_MS); null before it is read. */
    static Double change(String sensor, long spanMs) {
        Series s = of(sensor);
        if (s == null) return null;
        long now = System.currentTimeMillis();
        Double recent = s.mean(now - WINDOW_MS, now);
        Double before = s.mean(now - spanMs - WINDOW_MS / 2, now - spanMs + WINDOW_MS / 2);
        return recent == null || before == null ? null : recent - before;
    }
}
