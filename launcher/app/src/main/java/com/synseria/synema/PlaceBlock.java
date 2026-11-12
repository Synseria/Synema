package com.synseria.synema;

import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The room the focus rests on in the House panel, shown in the featured title's place and veil: its
 * name, temperature, other readings and, under them, the last 24 h of each as thin curves. Display
 * only: nothing in it takes focus. The curves are read once the focus has rested (Stats keeps them).
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
    /** The room now shown, and what is drawn of it (its readings): the next tile of the same room changes nothing. */
    private String room = "", drawn = "";
    private Runnable pending;

    PlaceBlock(MainActivity activity, View words, LinearLayout block) {
        this.activity = activity;
        this.words = words;
        this.block = block;
        LinearLayout head = new LinearLayout(activity);
        head.setGravity(Gravity.CENTER_VERTICAL);
        name = Tokens.line(activity, "", R.style.Role_Titre);
        name.setIncludeFontPadding(false);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // A reading, not a title: the thin figures of the room tiles.
        temperature = Tokens.line(activity, "", R.style.Role_Releve);
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

    /** The room in the title's place; its curves when the focus rests, at once when Stats has them. */
    void show(Rooms.Room r, Place p, HaLive live) {
        String signature = r.name + '|' + p.signature();
        if (shown() && signature.equals(drawn)) return;
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
        drawn = signature;
        List<String> sensors = new ArrayList<>();
        p.addSensors(sensors);
        sensors.remove(p.pressure.sensor);
        charts.removeAllViews();
        if (sensors.isEmpty()) return;
        pending = () -> Stats.readings(live, sensors, SPAN, read -> {
            if (!r.name.equals(room) || !shown() || read.isEmpty()) return;
            activity.whenStill(() -> {
                if (r.name.equals(room) && shown()) draw(p, read);
            });
        });
        handler.postDelayed(pending, REST_MS);
    }

    /** The title's words again. */
    void hide() {
        cancel();
        room = "";
        drawn = "";
        block.setVisibility(View.GONE);
        words.setVisibility(View.VISIBLE);
    }

    /** The activity goes: no curves are asked for after it. */
    void release() {
        cancel();
    }

    private void cancel() {
        if (pending != null) handler.removeCallbacks(pending);
        pending = null;
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
        int color = out.isEmpty() ? Tokens.primary(activity) : activity.getColor(R.color.syn_encre_secondaire);
        if (points != null && !points.isEmpty()) out.add(new Chart.Series("", color, unit, points));
    }
}
