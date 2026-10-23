package com.synseria.synema;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The electricity, on a page beside the dashboard (SidePage), from OK on the panel's electricity: the
 * power now, the month's cost and Tempo's colours, then what was used, from the meters Home Assistant's
 * Energy dashboard counts from the grid: today so far, yesterday, this month and the last, and a graph
 * per hour today, per day over 30 days or per month over a year, the meters (a Tempo colour, peak or
 * off-peak) stacked in their colours.
 */
final class EnergyPage {
    static final String KEY = "energy";
    private static final Stats.Range[] RANGES = Stats.Range.values();
    /** The range last chosen. */
    private static Stats.Range range = Stats.Range.DAYS;

    private final MainActivity activity;
    private final SidePage side;
    private final LinearLayout content, totals;
    private final TextView power, cost, tempo, legend;
    private final Chart chart;
    private final LinearLayout chips;
    private HomeConfig home;
    /** The dashboard's connection of the moment (a new one on each return to Home). */
    private final java.util.function.Supplier<HaLive> live;
    /** The month's cost in words, as the panel shows it; empty when unknown. */
    private final java.util.function.Supplier<String> monthCost;
    /** The Energy dashboard's grid meters, once read; in drawing order. */
    private List<String> meters;

    EnergyPage(MainActivity activity, SidePage side, java.util.function.Supplier<HaLive> live,
               java.util.function.Supplier<String> monthCost) {
        this.activity = activity;
        this.side = side;
        this.live = live;
        this.monthCost = monthCost;
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);

        LinearLayout readings = new LinearLayout(activity);
        readings.setGravity(Gravity.CENTER_VERTICAL);
        power = new TextView(activity, null, 0, R.style.BigReading);
        power.setTextSize(40);
        readings.addView(power);
        LinearLayout facts = new LinearLayout(activity);
        facts.setOrientation(LinearLayout.VERTICAL);
        cost = activity.text("", 11, 0xE0F2F2F5);
        tempo = activity.text("", 11, 0xE0F2F2F5);
        facts.addView(cost);
        facts.addView(tempo);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMarginStart(activity.dp(16));
        readings.addView(facts, flp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = activity.dp(4);
        content.addView(readings, rlp);

        // Today, yesterday, this month, the last: a small label over each figure.
        totals = new LinearLayout(activity);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = activity.dp(16);
        content.addView(totals, tlp);
        for (int name : new int[]{R.string.energy_today, R.string.energy_yesterday, R.string.energy_this_month, R.string.energy_last_month}) {
            LinearLayout cell = new LinearLayout(activity);
            cell.setOrientation(LinearLayout.VERTICAL);
            TextView label = new TextView(activity, null, 0, R.style.DashCaps);
            label.setText(name);
            cell.addView(label);
            TextView value = activity.text("–", 20, 0xFFF2F2F5);
            value.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            value.setFontFeatureSettings("tnum");
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.topMargin = activity.dp(4);
            cell.addView(value, vlp);
            totals.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }

        FrameLayout head = new FrameLayout(activity);
        head.setClipChildren(false);
        TextView label = new TextView(activity, null, 0, R.style.DashCaps);
        label.setText(R.string.energy_consumption);
        head.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));
        chips = Chips.row(activity, new String[]{activity.getString(R.string.energy_range_today),
                activity.getString(R.string.energy_range_days), activity.getString(R.string.energy_range_months)}, range.ordinal(), 22, i -> {
            range = RANGES[i];
            draw();
        });
        head.addView(chips, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = activity.dp(18);
        content.addView(head, hlp);

        chart = new Chart(activity);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.topMargin = activity.dp(8);
        content.addView(chart, clp);
        legend = activity.text("", 9.5f, 0xA6F2F2F5);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = activity.dp(6);
        content.addView(legend, llp);
    }

    boolean isOpen() {
        return KEY.equals(side.shown());
    }

    void open(HomeConfig home) {
        this.home = home;
        update();
        side.open(KEY, activity.getString(R.string.energy_title), content, chips.getChildAt(range.ordinal()),
                () -> activity.findViewById(R.id.tech));
        chart.reading();
        if (meters != null) {
            read();
            return;
        }
        Stats.grid(live.get(), grid -> {
            List<String> sorted = new ArrayList<>(grid.meters);
            // Off-peak under peak, blue under white under red: the day's colour on top of its bar.
            sorted.sort((a, b) -> Integer.compare(order(a), order(b)));
            meters = sorted;
            if (isOpen()) read();
        });
    }

    /** Back on the dashboard with the page still open: what was used read again on the new connection. */
    void resumed() {
        if (isOpen()) read();
    }

    /** The power, the cost and Tempo from the states as they are. */
    void update() {
        Map<String, HomeAssistant.State> states = HaLive.STATES;
        HomeAssistant.State p = states.get(home.energyPower);
        Double va = HouseText.number(p);
        power.setText(va == null ? "–" : HouseText.whole(va) + " " + p.attrs.optString("unit_of_measurement"));
        cost.setText(monthCost.get());
        SpannableStringBuilder sb = new SpannableStringBuilder();
        tempoDay(sb, R.string.tech_today, states.get(home.energyTempo));
        tempoDay(sb, R.string.tech_tomorrow, states.get(home.energyTempoTomorrow));
        tempo.setText(sb);
    }

    private void tempoDay(SpannableStringBuilder sb, int day, HomeAssistant.State s) {
        if (s == null || !s.available()) return;
        if (sb.length() > 0) sb.append("   ");
        sb.append(activity.getString(R.string.tech_tempo)).append(' ').append(activity.getString(day)).append("  ");
        int at = sb.length();
        sb.append("●");
        sb.setSpan(new ForegroundColorSpan(HouseText.tempoColour(s)), at, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.append(' ').append(s.state.substring(0, 1).toUpperCase(Locale.getDefault())).append(s.state.substring(1).toLowerCase(Locale.getDefault()));
    }

    // ---- what was used ------------------------------------------------------------------------------

    /**
     * Every day of the last twelve months, and every hour of today, as they count (usable): the three
     * views and the four totals are all made of them.
     */
    private Map<String, List<Stats.Point>> days, hours;

    private void read() {
        if (meters == null) return;
        if (meters.isEmpty()) {
            legend.setText(R.string.energy_none);
            return;
        }
        chart.reading();
        Stats.consumption(live.get(), meters, "day", Stats.Range.MONTHS.start(), d -> {
            Map<String, List<Stats.Point>> byDay = usable(d, false);
            Stats.consumption(live.get(), meters, "hour", Stats.Range.TODAY.start(), h -> {
                days = byDay;
                hours = usable(h, true);
                if (isOpen()) side.whenStill(this::show);
            });
        });
    }

    private void show() {
        LocalDate today = LocalDate.now();
        LocalDate month = today.withDayOfMonth(1);
        total(0, sum(days, startOf(today), startOf(today.plusDays(1))));
        total(1, sum(days, startOf(today.minusDays(1)), startOf(today)));
        total(2, sum(days, startOf(month), startOf(today.plusDays(1))));
        total(3, sum(days, startOf(month.minusMonths(1)), startOf(month)));
        draw();
    }

    /**
     * What a meter cannot have used is left out: more than the house can draw over the period (a meter
     * that came back from zero or doubled for a moment, which Home Assistant's statistics count as use).
     */
    private Map<String, List<Stats.Point>> usable(Map<String, List<Stats.Point>> read, boolean hourly) {
        double kw = mostKw();
        Map<String, List<Stats.Point>> out = new HashMap<>();
        for (Map.Entry<String, List<Stats.Point>> e : read.entrySet()) {
            double most = kw * (hourly ? 1 : hoursADay(e.getKey()));
            List<Stats.Point> kept = new ArrayList<>();
            for (Stats.Point p : e.getValue()) kept.add(p.change > most ? new Stats.Point(p.start, Double.NaN) : p);
            out.put(e.getKey(), kept);
        }
        return out;
    }

    /** The most the house draws (kW): its subscription and the meter's tolerance; 36 kVA, a home's most, when unknown. */
    private double mostKw() {
        HomeAssistant.State s = HaLive.STATES.get(home.energySubscribed);
        Double v = HouseText.number(s);
        if (v == null) return 36;
        boolean kilo = s.attrs.optString("unit_of_measurement").toLowerCase(Locale.ROOT).startsWith("k");
        return (kilo ? v : v / 1000) * 1.25;
    }

    /** A day's hours on a meter: Tempo's off-peak runs from 22 h to 6 h. */
    private static int hoursADay(String id) {
        return offPeak(id) ? 8 : tempoOf(id) < 3 ? 16 : 24;
    }

    private static long startOf(LocalDate day) {
        return day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /** What every meter used from {@code from} to {@code to}; NaN when nothing is known. */
    private static double sum(Map<String, List<Stats.Point>> read, long from, long to) {
        double out = Double.NaN;
        for (List<Stats.Point> ps : read.values()) {
            for (Stats.Point p : ps) {
                if (p.start < from || p.start >= to || Double.isNaN(p.change)) continue;
                out = (Double.isNaN(out) ? 0 : out) + p.change;
            }
        }
        return out;
    }

    private void total(int cell, double kwh) {
        TextView value = (TextView) ((LinearLayout) totals.getChildAt(cell)).getChildAt(1);
        value.setText(Double.isNaN(kwh) ? "–" : Chart.Unit.KWH.value(kwh));
    }

    /** The range chosen, from what was read: per hour today, per day over 30 days, per month over a year. */
    private void draw() {
        if (days == null) return;
        chart.title(activity.getString(range == Stats.Range.TODAY ? R.string.energy_per_hour
                : range == Stats.Range.DAYS ? R.string.energy_per_day : R.string.energy_per_month));
        long from = range.start();
        List<Chart.Series> series = new ArrayList<>();
        SpannableStringBuilder keys = new SpannableStringBuilder();
        double all = 0;
        for (String m : meters) {
            List<Stats.Point> points = range == Stats.Range.TODAY ? hours.get(m) : days.get(m);
            if (points == null) points = Collections.emptyList();
            if (range == Stats.Range.MONTHS) points = byMonth(points);
            double used = 0;
            for (Stats.Point p : points) if (p.start >= from && !Double.isNaN(p.change) && p.change > 0) used += p.change;
            all += used;
            series.add(new Chart.Series(name(m), colour(m), Chart.Unit.KWH, points));
            if (used <= 0) continue;
            if (keys.length() > 0) keys.append("     ");
            int at = keys.length();
            keys.append("●");
            keys.setSpan(new ForegroundColorSpan(colour(m)), at, keys.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            keys.append(' ').append(name(m));
        }
        chart.bars(range, series, activity.getString(R.string.energy_total, Chart.Unit.KWH.value(all)));
        legend.setText(keys);
    }

    /** Days summed into their months. */
    private static List<Stats.Point> byMonth(List<Stats.Point> days) {
        Map<Long, Double> months = new java.util.TreeMap<>();
        ZoneId zone = ZoneId.systemDefault();
        for (Stats.Point p : days) {
            if (Double.isNaN(p.change)) continue;
            LocalDate first = Instant.ofEpochMilli(p.start).atZone(zone).toLocalDate().withDayOfMonth(1);
            months.merge(startOf(first), p.change, Double::sum);
        }
        List<Stats.Point> out = new ArrayList<>();
        for (Map.Entry<Long, Double> e : months.entrySet()) out.add(new Stats.Point(e.getKey(), e.getValue()));
        return out;
    }

    // ---- the meters' colours: Tempo's, from their names ---------------------------------------------

    private static boolean offPeak(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        return s.contains("creuse") || s.contains("offpeak") || s.contains("off_peak") || s.endsWith("_hc") || s.contains("_hc_");
    }

    /** 0 blue, 1 white, 2 red, 3 anything else. */
    private static int tempoOf(String id) {
        String s = id.toLowerCase(Locale.ROOT);
        return s.contains("bleu") || s.contains("blue") ? 0 : s.contains("blanc") || s.contains("white") ? 1
                : s.contains("rouge") || s.contains("red") ? 2 : 3;
    }

    private static int order(String id) {
        return tempoOf(id) * 2 + (offPeak(id) ? 0 : 1);
    }

    private static final int[][] COLOURS = {
            {0xFF0A84FF, 0xFF1C5BB3}, {0xFFE5E5EA, 0xFF8E8E93}, {0xFFFF453A, 0xFFA8322B}, {0xFF64D2FF, 0xFF2F7C99}};

    private static int colour(String id) {
        return COLOURS[tempoOf(id)][offPeak(id) ? 1 : 0];
    }

    private String name(String id) {
        int t = tempoOf(id);
        if (t == 3) {
            HomeAssistant.State s = HaLive.STATES.get(id);
            return s != null ? s.name() : id;
        }
        int colour = t == 0 ? R.string.energy_blue : t == 1 ? R.string.energy_white : R.string.energy_red;
        return activity.getString(colour) + " · " + activity.getString(offPeak(id) ? R.string.energy_offpeak : R.string.energy_peak);
    }
}
