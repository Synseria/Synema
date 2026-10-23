package com.synseria.synema;

import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A room of the house, on a page beside the dashboard (SidePage): its readings, its lights and its
 * other things (Rooms, with house_label's rule), its cameras of home.json, and at the bottom its
 * graphs (Chart), the climate (temperature and humidity), the CO₂ and the noise side by side, over 6 h,
 * 24 h, 7 or 30 days. OK on a tile does what suits it.
 */
final class RoomPage {
    private static final int PER_ROW = 4;
    /** The camera column's width (dp), its picture the widest card of the page. */
    private static final int CAMERA_DP = 300;
    /** The graphs' height when the tiles fill the page, and at most when they leave room. */
    private static final int CHART_DP = 128, CHART_MAX_DP = 230;
    static final int TEMPERATURE = 0xFFFF9F0A, HUMIDITY = 0xFF64D2FF, CO2 = 0xFF30D158, NOISE = 0xFFBF5AF2;
    /** The span last chosen, kept for every room. */
    private static Stats.Span span = Stats.Span.DAY;

    private final MainActivity activity;
    private final SidePage side;
    private final LinearLayout content;
    private final TextView temperature;
    private final LinearLayout facts, body, strip, charts;
    private final ScrollView scroll;
    /** The page's first tile, focused on opening. */
    private View first;
    /** The room shown, and what its graphs read from. */
    private Rooms.Room room;
    private Place place;
    /** The dashboard's connection of the moment (a new one on each return to Home). */
    private final java.util.function.Supplier<HaLive> live;

    RoomPage(MainActivity activity, SidePage side, java.util.function.Supplier<HaLive> live) {
        this.activity = activity;
        this.side = side;
        this.live = live;
        content = new LinearLayout(activity) {
            /** The graphs take what the tiles leave, from CHART_DP up to CHART_MAX_DP. */
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) charts.getLayoutParams();
                lp.height = activity.dp(CHART_DP);
                super.onMeasure(widthSpec, heightSpec);
                if (strip.getVisibility() != View.VISIBLE) return;
                int spare = scroll.getMeasuredHeight() - body.getMeasuredHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
                if (spare <= 0) return;
                lp.height = Math.min(activity.dp(CHART_MAX_DP), lp.height + spare);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);

        LinearLayout readings = new LinearLayout(activity);
        readings.setGravity(Gravity.CENTER_VERTICAL);
        temperature = new TextView(activity, null, 0, R.style.BigReading);
        temperature.setTextSize(40);
        readings.addView(temperature);
        facts = new LinearLayout(activity);
        facts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.setMarginStart(activity.dp(16));
        readings.addView(facts, flp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = activity.dp(4);
        content.addView(readings, rlp);

        // The scroll's padding is room for a focused tile's or camera's lift and ring (Focus); its margins
        // take it back on every side but the bottom, where it clips: the graphs' heading sits just under.
        int space = Focus.room(activity, CAMERA_DP);
        scroll = new TileScroll(activity, null);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        scroll.setPadding(space, space, space, space);
        body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setClipChildren(false);
        scroll.addView(body);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = activity.dp(14) - space;
        slp.setMarginStart(-space);
        slp.setMarginEnd(-space);
        content.addView(scroll, slp);

        // The graphs: the spans at the top right, then one card per kind of reading.
        strip = new LinearLayout(activity);
        strip.setOrientation(LinearLayout.VERTICAL);
        strip.setClipChildren(false);
        FrameLayout head = new FrameLayout(activity);
        head.setClipChildren(false);
        TextView label = new TextView(activity, null, 0, R.style.DashCaps);
        label.setText(R.string.graph_history);
        head.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL));
        String[] spans = new String[Stats.Span.values().length];
        for (int i = 0; i < spans.length; i++) spans[i] = Stats.Span.values()[i].label;
        head.addView(Chips.row(activity, spans, span.ordinal(), 22, i -> {
            span = Stats.Span.values()[i];
            readCharts();
        }), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END));
        strip.addView(head);
        charts = new LinearLayout(activity);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(CHART_DP));
        clp.topMargin = activity.dp(8);
        strip.addView(charts, clp);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.topMargin = activity.dp(10);
        content.addView(strip, stlp);
    }

    /** The room shown, or null. */
    String shown() {
        return room == null || !room.name.equals(side.shown()) ? null : room.name;
    }

    /** Shows {@code r}; Back gives the focus to {@code back}, the room's tile on the dashboard. */
    void open(Rooms.Room r, Place p, HomeConfig home, java.util.function.Supplier<View> back) {
        room = r;
        place = p;
        fill(r, p, home);
        buildCharts(p);
        side.open(r.name, r.name, content, first, back);
        readCharts();
    }

    /** Back on the dashboard with the page still open: its graphs read again on the new connection. */
    void resumed() {
        if (shown() != null) readCharts();
    }

    /** The room's new states and readings; the focus stays where it is. */
    void update(Rooms.Room r, Place p) {
        if (shown() == null || !r.name.equals(room.name)) return;
        room = r;
        place = p;
        showReadings(p);
        for (Rooms.Entity e : r.entities) {
            View t = body.findViewWithTag(e.id);
            if (t instanceof LinearLayout) paint((LinearLayout) t, e);
        }
    }

    // ---- the graphs -------------------------------------------------------------------------------

    /** A card per kind the room reads: climate, CO₂, noise; none, no strip. */
    private void buildCharts(Place p) {
        charts.removeAllViews();
        if (!p.temperature.sensor.isEmpty() || !p.humidity.sensor.isEmpty()) chart(R.string.graph_climate);
        if (!p.co2.sensor.isEmpty()) chart(R.string.graph_co2);
        if (!p.noise.sensor.isEmpty()) chart(R.string.graph_noise);
        strip.setVisibility(charts.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private void chart(int title) {
        Chart c = new Chart(activity);
        c.title(activity.getString(title));
        c.setTag(title);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (charts.getChildCount() > 0) lp.setMarginStart(activity.dp(8));
        charts.addView(c, lp);
    }

    private void readCharts() {
        Place p = place;
        String shownRoom = room.name;
        List<String> sensors = new ArrayList<>();
        p.addSensors(sensors);
        if (sensors.isEmpty()) return;
        for (int i = 0; i < charts.getChildCount(); i++) ((Chart) charts.getChildAt(i)).reading();
        Stats.Span asked = span;
        Stats.readings(live.get(), sensors, asked, read -> {
            if (!shownRoom.equals(shown()) || asked != span) return;
            side.whenStill(() -> drawCharts(p, read));
        });
    }

    private void drawCharts(Place p, Map<String, List<Stats.Point>> read) {
        for (int i = 0; i < charts.getChildCount(); i++) {
            Chart c = (Chart) charts.getChildAt(i);
            List<Chart.Series> s = new ArrayList<>();
            int title = (Integer) c.getTag();
            if (title == R.string.graph_climate) {
                series(s, read, p.temperature.sensor, TEMPERATURE, Chart.Unit.DEGREES);
                series(s, read, p.humidity.sensor, HUMIDITY, Chart.Unit.PERCENT);
            } else if (title == R.string.graph_co2) {
                series(s, read, p.co2.sensor, CO2, Chart.Unit.PPM);
            } else {
                series(s, read, p.noise.sensor, NOISE, Chart.Unit.DECIBELS);
            }
            c.lines(span, s);
        }
    }

    private static void series(List<Chart.Series> out, Map<String, List<Stats.Point>> read, String sensor, int color, Chart.Unit unit) {
        List<Stats.Point> points = read.get(sensor);
        if (points != null && !points.isEmpty()) out.add(new Chart.Series("", color, unit, points));
    }

    // ---- the page ---------------------------------------------------------------------------------

    private void fill(Rooms.Room r, Place p, HomeConfig home) {
        showReadings(p);
        body.removeAllViews();
        first = null;
        LinearLayout left = new LinearLayout(activity);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClipChildren(false);
        section(left, R.string.room_lights, r.lights(), r.name);
        section(left, R.string.room_devices, r.devices(), r.name);
        body.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        List<Integer> cams = new ArrayList<>();
        for (int i = 0; i < home.cameras.size(); i++) if (r.cameras.contains(home.cameras.get(i).entity)) cams.add(i);
        if (!cams.isEmpty()) {
            LinearLayout right = new LinearLayout(activity);
            right.setOrientation(LinearLayout.VERTICAL);
            right.setClipChildren(false);
            right.addView(label(R.string.cameras));
            for (int i : cams) right.addView(camera(home.cameras.get(i), i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(activity.dp(CAMERA_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginStart(activity.dp(22));
            body.addView(right, lp);
        }
    }

    private void showReadings(Place p) {
        temperature.setText(HouseHeader.degrees(p, ""));
        HouseHeader.facts(activity, facts, 11, false, p);
    }

    /** A titled grid of tiles, four to a line; nothing when there is nothing. */
    private void section(LinearLayout parent, int name, List<Rooms.Entity> entities, String area) {
        if (entities.isEmpty()) return;
        TextView label = label(name);
        if (parent.getChildCount() > 0) ((LinearLayout.LayoutParams) label.getLayoutParams()).topMargin = activity.dp(14);
        parent.addView(label);
        LinearLayout line = null;
        for (int i = 0; i < entities.size(); i++) {
            if (i % PER_ROW == 0) {
                line = new LinearLayout(activity);
                line.setClipChildren(false);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(46));
                llp.topMargin = i == 0 ? 0 : activity.dp(6);
                parent.addView(line, llp);
            }
            View t = tile(entities.get(i), area);
            if (first == null) first = t;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMarginEnd(activity.dp(6));
            line.addView(t, lp);
        }
        // Equal widths on the last line.
        for (int i = entities.size() % PER_ROW; i > 0 && i < PER_ROW; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 1, 1f);
            lp.setMarginEnd(activity.dp(6));
            line.addView(new View(activity), lp);
        }
    }

    private TextView label(int name) {
        TextView t = new TextView(activity, null, 0, R.style.DashCaps);
        t.setText(name);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = activity.dp(6);
        lp.setMarginStart(activity.dp(2));
        t.setLayoutParams(lp);
        return t;
    }

    /** A tile: the name, the state below, lit when on; OK does what suits the entity. */
    private LinearLayout tile(Rooms.Entity e, String area) {
        LinearLayout t = new LinearLayout(activity);
        t.setOrientation(LinearLayout.VERTICAL);
        Focus.tile(t);
        t.setPadding(activity.dp(10), activity.dp(7), activity.dp(10), activity.dp(7));
        t.setTag(e.id);
        TextView name = activity.text(shortName(e.name, area), 11, Focus.TILE_TEXT);
        TextView state = activity.text("", 9.5f, 0);
        state.setTextColor(activity.getColorStateList(R.color.dash_tile_state));
        state.setDuplicateParentStateEnabled(true);
        t.addView(name);
        t.addView(new View(activity), new LinearLayout.LayoutParams(1, 0, 1f));
        t.addView(state);
        HomeConfig.Item item = HomeConfig.Item.forEntity(e.id, e.name);
        t.setOnClickListener(v -> {
            // A switch answers at once; Home Assistant's state follows a moment later.
            if (item.action.service.endsWith("/toggle")) {
                t.setActivated(!t.isActivated());
                state.setText(t.isActivated() ? R.string.state_on : R.string.state_off);
            }
            activity.runAction(item.action);
        });
        paint(t, e);
        return t;
    }

    private void paint(LinearLayout t, Rooms.Entity e) {
        HomeAssistant.State s = HaLive.STATES.get(e.id);
        t.setActivated(s != null && s.on());
        ((TextView) t.getChildAt(2)).setText(s == null ? "" : stateWords(s.state));
    }

    /** A camera of home.json in the room: its last still and name; OK opens it full screen. */
    private View camera(HomeConfig.Labelled cam, int index) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        Card card = new Card(activity);
        Focus.card(card);
        card.setContentDescription(cam.label);
        ImageView img = new RoundedImageView(activity);
        img.setImageBitmap(CameraRow.lastFrame(cam.entity));
        card.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.setOnClickListener(v -> activity.open(new Intent(activity, CameraActivity.class).putExtra(CameraActivity.EXTRA_INDEX, index), v));
        col.addView(card);
        col.addView(activity.text(cam.label, 9, 0xFFA1A1A6));
        if (first == null) first = card;
        return col;
    }

    /** "Salon - Grande lampe" in the Salon is "Grande lampe". */
    private static String shortName(String name, String area) {
        for (String sep : new String[]{" - ", " ", ": "}) {
            if (name.startsWith(area + sep) && name.length() > area.length() + sep.length()) return name.substring(area.length() + sep.length());
        }
        return name;
    }

    private String stateWords(String state) {
        switch (state) {
            case "on": return activity.getString(R.string.state_on);
            case "off": return activity.getString(R.string.state_off);
            case "open": return activity.getString(R.string.state_open);
            case "closed": return activity.getString(R.string.state_closed);
            case "unavailable": case "unknown": return "—";
            default: return state;
        }
    }
}
