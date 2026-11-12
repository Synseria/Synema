package com.synseria.synema;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The room the focus rests on in the House panel, shown in the featured title's place and veil: its
 * name, temperature, other readings and, under them, the last 24 h of each as thin curves. Display
 * only: nothing in it takes focus. The curves are read once the focus has rested, and kept per room.
 */
final class PlaceBlock {
    /** The focus must rest this long on a room before Home Assistant is asked for its curves. */
    private static final long REST_MS = 300L;
    /** A room's curves are read again after this long. */
    private static final long FRESH_MS = 10 * 60_000L;
    private static final Stats.Span SPAN = Stats.Span.DAY;

    private final MainActivity activity;
    private final View words;
    private final LinearLayout block, facts, charts;
    private final TextView name, temperature;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Object[]> kept = new HashMap<>();
    /** The room now shown, and what its curves were asked for. */
    private String room = "";
    private Runnable pending;

    PlaceBlock(MainActivity activity, View words, LinearLayout block) {
        this.activity = activity;
        this.words = words;
        this.block = block;
        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        name = activity.text("", R.style.Role_Titre);
        name.setIncludeFontPadding(false);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // A reading, not a title: the thin figures of the room tiles.
        temperature = activity.text("", R.style.Role_Titre);
        temperature.setFontVariationSettings("'wght' 200");
        temperature.setFontFeatureSettings("tnum");
        temperature.setIncludeFontPadding(false);
        head.addView(temperature);
        block.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.px(R.dimen.syn_espace_6)));
        facts = new LinearLayout(activity);
        facts.setDividerDrawable(activity.getDrawable(R.drawable.dash_facts_gap));
        facts.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        block.addView(facts, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        charts = new LinearLayout(activity);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.topMargin = activity.px(R.dimen.syn_espace_1);
        block.addView(charts, clp);
    }

    boolean shown() {
        return block.getVisibility() == View.VISIBLE;
    }

    /** The room in the title's place; its curves when the focus rests, at once when kept. */
    void show(Rooms.Room r, Place p, HaLive live) {
        cancel();
        // Exactly the featured title's height, which stays laid out under it: nothing below moves, and the
        // curves take what the name and the readings leave (in a frame, "match parent" would grow the band).
        int h = words.getHeight();
        if (h > 0 && block.getLayoutParams().height != h) {
            block.getLayoutParams().height = h;
            block.requestLayout();
        }
        words.setVisibility(View.INVISIBLE);
        block.setVisibility(View.VISIBLE);
        name.setText(r.name);
        temperature.setText(p.temperature.value == null ? "" : HouseText.units(activity, HouseText.degrees(p.temperature.value), true));
        HouseHeader.facts(activity, facts, p, Integer.MAX_VALUE, R.style.Role_Chiffre);
        if (!r.name.equals(room)) charts.removeAllViews();
        room = r.name;
        List<String> sensors = new ArrayList<>();
        p.addSensors(sensors);
        sensors.remove(p.pressure.sensor);
        Object[] have = kept.get(r.name);
        if (have != null && System.currentTimeMillis() - (Long) have[0] < FRESH_MS) {
            draw(p, cast(have[1]));
            return;
        }
        charts.removeAllViews();
        if (sensors.isEmpty()) return;
        final String asked = r.name;
        pending = () -> Stats.readings(live, sensors, SPAN, read -> {
            if (!asked.equals(room) || !shown() || read.isEmpty()) return;
            kept.put(asked, new Object[]{System.currentTimeMillis(), read});
            activity.whenStill(() -> {
                if (asked.equals(room) && shown()) draw(p, read);
            });
        });
        handler.postDelayed(pending, REST_MS);
    }

    /** The title's words again. */
    void hide() {
        cancel();
        room = "";
        block.setVisibility(View.GONE);
        words.setVisibility(View.VISIBLE);
    }

    private void cancel() {
        if (pending != null) handler.removeCallbacks(pending);
        pending = null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Stats.Point>> cast(Object o) {
        return (Map<String, List<Stats.Point>>) o;
    }

    /** One curve block per kind the room reads, side by side: climate (two curves), CO₂, noise. */
    private void draw(Place p, Map<String, List<Stats.Point>> read) {
        charts.removeAllViews();
        List<Chart.Series> climate = new ArrayList<>();
        series(climate, read, p.temperature.sensor, Chart.Unit.DEGREES);
        series(climate, read, p.humidity.sensor, Chart.Unit.PERCENT);
        add(climate);
        List<Chart.Series> co2 = new ArrayList<>();
        series(co2, read, p.co2.sensor, Chart.Unit.PPM);
        add(co2);
        List<Chart.Series> noise = new ArrayList<>();
        series(noise, read, p.noise.sensor, Chart.Unit.DECIBELS);
        add(noise);
    }

    private void add(List<Chart.Series> s) {
        if (s.isEmpty()) return;
        Chart c = new Chart(activity);
        c.bare();
        c.lines(SPAN, s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (charts.getChildCount() > 0) lp.setMarginStart(activity.px(R.dimen.syn_espace_2));
        charts.addView(c, lp);
    }

    /** As the room page: the first curve the primary accent, the second the secondary ink. */
    private void series(List<Chart.Series> out, Map<String, List<Stats.Point>> read, String sensor, Chart.Unit unit) {
        List<Stats.Point> points = read.get(sensor);
        int color = activity.getColor(out.isEmpty() ? R.color.syn_primaire : R.color.syn_encre_secondaire);
        if (points != null && !points.isEmpty()) out.add(new Chart.Series("", color, unit, points));
    }
}
