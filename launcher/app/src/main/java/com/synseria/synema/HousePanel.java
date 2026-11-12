package com.synseria.synema;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The dashboard's Home Assistant parts: the top line's readings and capsules (HouseHeader), and the
 * House panel on the right: the rooms lit, home.json's tiles, the rooms (Rooms; OK opens one's page,
 * RoomPage) and the electricity (OK opens its page, EnergyPage). The rooms, then the tiles, scroll a
 * whole line at a time between the heading and the electricity, which stays put (TileScroll).
 *
 * While the dashboard shows, Home Assistant pushes every change of what it shows (HaLive): a light
 * switched anywhere is drawn within a moment. The rooms themselves (which areas, which things) are
 * read on each return to Home and every ROOMS_MS. The states and rooms are kept for the life of the
 * process, so coming back to Home draws them at once and an update only changes what changed: no flash
 * of empty tiles, and the focus stays on its tile.
 */
final class HousePanel {
    private static final int PER_LINE = 2;
    /**
     * A room's height (dp): a name over its temperature; the rooms beyond the height scroll. A tile is a touch
     * target (syn_cible_min), the space between lines syn_espace_1.
     */
    // jeton manquant: syn_tuile_piece_hauteur (68)
    private static final int ROOM_DP = 68;
    /** A remote key as drawn on its tile (dp): its size; its corner is syn_rayon_pastille, its distance from the edges syn_espace_2. */
    // jeton manquant: syn_touche_tuile_largeur (14), syn_touche_tuile_hauteur (8)
    private static final int KEY_W_DP = 14, KEY_H_DP = 8;
    /** The rooms are read again this often while the dashboard stays in front. */
    private static final long ROOMS_MS = 10 * 60_000L;
    /** The month's cost is read again this often: Home Assistant values the hours as they end. */
    private static final long COST_MS = 15 * 60_000L;
    /** After a press, the tiles are drawn again from Home Assistant's states this late: a refused call shows. */
    private static final long CHECK_PRESS_MS = 3_000L;
    private static final Map<String, HomeAssistant.State> STATES = HaLive.STATES;
    private static List<Rooms.Room> lastRooms = new ArrayList<>();

    private final MainActivity activity;
    private final HouseHeader header;
    private final SidePage side;
    private final RoomPage roomPage;
    private final EnergyPage energyPage;
    private final LinearLayout tiles, rooms;
    private final View panel, rule;
    private final TextView litRooms;
    private HomeConfig home;
    private HomeAssistant ha;
    /** Home Assistant's pushed states, while the dashboard is in front. */
    private HaLive live;
    private long roomsReadAt, costReadAt;
    /** What the grid's electricity cost this month (€, Stats.monthCost), the subscription aside; NaN when unknown. */
    private double usedCost = Double.NaN;
    /** The tiles and rooms built for this configuration; rebuilt only when it changes. */
    private String builtFor = "", roomsFor = "";

    HousePanel(MainActivity activity, SidePage side) {
        this.activity = activity;
        header = new HouseHeader(activity);
        this.side = side;
        roomPage = new RoomPage(activity, side, () -> live);
        energyPage = new EnergyPage(activity, side, () -> live, this::monthCost);
        View tech = activity.findViewById(R.id.tech);
        Focus.block(tech);
        onFocus(tech, () -> activity.houseFocused(null, null, live));
        tech.setOnClickListener(v -> energyPage.open(home));
        tiles = activity.findViewById(R.id.tiles);
        rooms = activity.findViewById(R.id.rooms);
        // Nothing cut under the heading: the rooms and the tiles scroll a whole line at a time.
        ((TileScroll) activity.findViewById(R.id.houseScroll)).byLines(rooms, tiles);
        rule = activity.findViewById(R.id.houseRule);
        litRooms = activity.findViewById(R.id.litRooms);
        panel = activity.findViewById(R.id.haPanel);
    }

    /** Takes the current configuration; draws from the cached states at once. */
    void bind(HomeConfig home, HomeAssistant ha, boolean shown) {
        this.home = home;
        this.ha = ha;
        boolean on = ha != null;
        panel.setVisibility(on && shown ? View.VISIBLE : View.GONE);
        activity.findViewById(R.id.btnAssist).setVisibility(on && !home.assistantPresets.isEmpty() ? View.VISIBLE : View.GONE);
        header.bind(home, on);
        if (!on) return;
        String signature = home.tiles.size() + "|" + tileNames(home.tiles);
        if (!signature.equals(builtFor)) {
            buildTiles();
            builtFor = signature;
        }
        render();
    }

    /** The dashboard comes to the front: Home Assistant's states pushed from now on, the rooms read. */
    void start() {
        stop();
        if (ha == null) return;
        live = new HaLive(ha, new HaLive.Listener() {
            @Override
            public void onStates() {
                whenStill(HousePanel.this::render);
            }

            @Override
            public void onReachable(boolean reachable) {
                whenStill(() -> header.reachable(reachable));
            }
        });
        live.start();
        watch();
        readRooms();
        readHistory();
        // Also when unknown: the last connection may have gone before answering.
        if (Double.isNaN(usedCost) || System.currentTimeMillis() - costReadAt > COST_MS) readCost();
        roomPage.resumed();
        energyPage.resumed();
    }

    /** The dashboard leaves the front: nothing is pushed any more. */
    void stop() {
        if (live != null) live.stop();
        live = null;
    }

    /** While in front, now and then: the rooms when due, the trends when due (History). */
    void refresh() {
        if (ha == null) return;
        if (System.currentTimeMillis() - roomsReadAt > ROOMS_MS) readRooms();
        if (System.currentTimeMillis() - costReadAt > COST_MS) readCost();
        readHistory();
    }

    /** A press was answered: the tiles are drawn from the states again a little later, so a refused one goes back. */
    void pressed() {
        tiles.postDelayed(() -> whenStill(this::render), CHECK_PRESS_MS);
    }

    /** Watches what the dashboard shows: home.json's entities and the rooms' things and sensors. */
    private void watch() {
        if (live == null) return;
        java.util.Set<String> ids = new java.util.HashSet<>(home.entities());
        for (Rooms.Room r : lastRooms) r.addEntities(ids);
        live.watch(ids);
    }

    private void readRooms() {
        final HomeAssistant client = ha;
        roomsReadAt = System.currentTimeMillis();
        Rooms.load(client, live, home.houseLabel, false, found -> {
            if (client != ha || activity.isFinishing() || found.isEmpty()) return;
            whenStill(() -> {
                lastRooms = found;
                watch();
                showRooms(found);
            });
        });
    }

    private void readCost() {
        final HaLive from = live;
        costReadAt = System.currentTimeMillis();
        Stats.monthCost(from, eur -> {
            if (from != live || activity.isFinishing()) return;
            usedCost = eur;
            whenStill(this::render);
        });
    }

    /** The trends of what the top line and the rooms show (History). */
    private void readHistory() {
        final HomeAssistant client = ha;
        java.util.Set<String> sensors = new java.util.HashSet<>();
        header.addSensors(sensors);
        for (Rooms.Room r : lastRooms) place(r).addSensors(sensors);
        History.need(client, sensors, Place.Kind.PRESSURE.spanMs + 30 * 60_000L, () -> {
            if (client == ha && !activity.isFinishing()) whenStill(this::render);
        });
    }

    /** Everything from the states as they are: the top line, the electricity, the tiles, the rooms. */
    private void render() {
        header.render(STATES);
        showElectricity(STATES);
        showRooms(lastRooms);
        for (int r = 0; r < tiles.getChildCount(); r++) {
            LinearLayout row = (LinearLayout) tiles.getChildAt(r);
            for (int c = 0; c < row.getChildCount(); c++) {
                LinearLayout t = (LinearLayout) row.getChildAt(c);
                HomeConfig.Item tile = (HomeConfig.Item) t.getTag();
                HomeAssistant.State s = STATES.get(tile.action.entity);
                ((TextView) t.getChildAt(2)).setText(s == null ? "" : HouseText.units(activity, describe(s), false));
                t.setActivated(s != null && s.on());
            }
        }
    }

    private void buildTiles() {
        tiles.removeAllViews();
        LinearLayout row = null;
        for (int i = 0; i < home.tiles.size(); i++) {
            HomeConfig.Item tile = home.tiles.get(i);
            if (i % PER_LINE == 0) {
                row = new LinearLayout(activity);
                row.setClipChildren(false);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.px(R.dimen.syn_cible_min));
                rlp.topMargin = i == 0 ? 0 : activity.px(R.dimen.syn_espace_1);
                tiles.addView(row, rlp);
            }
            LinearLayout t = new LinearLayout(activity);
            t.setOrientation(LinearLayout.VERTICAL);
            Focus.tile(t);
            t.setPadding(activity.px(R.dimen.syn_espace_2), activity.px(R.dimen.syn_espace_1), activity.px(R.dimen.syn_espace_2), activity.px(R.dimen.syn_espace_1));
            t.setTag(tile);
            onFocus(t, () -> {
                Rooms.Room r = roomOf(tile.action.entity);
                activity.houseFocused(r, r == null ? null : place(r), live);
            });
            TextView state = Tokens.line(activity, "", R.style.Role_Note);
            state.setTextColor(activity.getColorStateList(R.color.dash_tile_state));
            state.setDuplicateParentStateEnabled(true);
            TextView name = Tokens.line(activity, tile.name, R.style.Role_Corps);
            // The remote's colour button that toggles it: its key drawn in the tile's top right corner.
            int key = colourKey(tile);
            if (key != 0) {
                t.setForeground(keyDrawable(key));
                name.setPadding(0, 0, activity.dp(KEY_W_DP) + activity.px(R.dimen.syn_espace_2), 0);
            }
            t.addView(name);
            t.addView(new View(activity), new LinearLayout.LayoutParams(1, 0, 1f));
            t.addView(state);
            t.setOnClickListener(v -> {
                // A switch answers at once; Home Assistant's state corrects it a moment later if need be.
                if (tile.action.service.endsWith("/toggle")) {
                    t.setActivated(!t.isActivated());
                    state.setText(t.isActivated() ? R.string.state_on : R.string.state_off);
                }
                activity.runAction(tile.action);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i % PER_LINE == 0 && i + 1 < home.tiles.size()) lp.setMarginEnd(activity.px(R.dimen.syn_espace_1));   // a lone last tile spans the row
            row.addView(t, lp);
        }
    }

    // ---- the rooms --------------------------------------------------------------------------------

    /** The rooms lit in the house, and a tile per room: built once per set of rooms, then updated in place. */
    private void showRooms(List<Rooms.Room> found) {
        int lit = Rooms.lit(found, STATES);
        litRooms.setText(found.isEmpty() ? "" : lit == 0 ? activity.getString(R.string.house_all_off)
                : activity.getResources().getQuantityString(R.plurals.rooms_lit, lit, lit));
        StringBuilder names = new StringBuilder();
        for (Rooms.Room r : found) names.append(r.name).append(';');
        if (!names.toString().equals(roomsFor)) {
            roomsFor = names.toString();
            buildRooms(found);
        }
        rule.setVisibility(found.isEmpty() || home.tiles.isEmpty() ? View.GONE : View.VISIBLE);
        fillRooms(found);
        Rooms.Room open = room(roomPage.shown());
        if (open != null) roomPage.update(open, place(open));
        if (energyPage.isOpen()) energyPage.update();
    }

    /** Adds to what the view does on gaining focus (its lift, Focus). */
    private static void onFocus(View v, Runnable gained) {
        View.OnFocusChangeListener lift = v.getOnFocusChangeListener();
        v.setOnFocusChangeListener((view, has) -> {
            lift.onFocusChange(view, has);
            if (has) gained.run();
        });
    }

    /** The room that holds this thing, or null when no room is known to. */
    private Rooms.Room roomOf(String entity) {
        for (Rooms.Room r : lastRooms) for (Rooms.Entity e : r.entities) if (e.id.equals(entity)) return r;
        return null;
    }

    private Rooms.Room room(String name) {
        for (Rooms.Room r : lastRooms) if (r.name.equals(name)) return r;
        return null;
    }

    void whenStill(Runnable r) {
        side.whenStill(r);
    }

    private void fillRooms(List<Rooms.Room> found) {
        for (int i = 0; i < found.size() && i / PER_LINE < rooms.getChildCount(); i++) {
            LinearLayout line = (LinearLayout) rooms.getChildAt(i / PER_LINE);
            fillRoom((LinearLayout) line.getChildAt(i % PER_LINE), found.get(i));
        }
    }

    private void buildRooms(List<Rooms.Room> found) {
        rooms.removeAllViews();
        LinearLayout line = null;
        for (int i = 0; i < found.size(); i++) {
            if (i % PER_LINE == 0) {
                line = new LinearLayout(activity);
                line.setClipChildren(false);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(ROOM_DP));
                llp.topMargin = i == 0 ? 0 : activity.px(R.dimen.syn_espace_1);
                rooms.addView(line, llp);
            }
            String name = found.get(i).name;
            LinearLayout t = new LinearLayout(activity);
            t.setOrientation(LinearLayout.VERTICAL);
            Focus.tile(t);
            t.setPadding(activity.px(R.dimen.syn_espace_2), activity.px(R.dimen.syn_espace_1), activity.px(R.dimen.syn_espace_2), activity.px(R.dimen.syn_espace_1));
            // The name across the tile (never cut), then the temperature with its other readings stacked beside.
            TextView title = Tokens.line(activity, name, R.style.Role_CorpsFort);
            title.setIncludeFontPadding(false);
            TextView lit = Tokens.line(activity, activity.getString(R.string.room_lit), R.style.Role_Petit);
            lit.setIncludeFontPadding(false);
            t.addView(new NameLine(activity, title, lit));
            LinearLayout body = new LinearLayout(activity);
            body.setGravity(Gravity.CENTER_VERTICAL);
            // A reading, not a title: the thin figures of the top line's temperatures, at a tile's size.
            TextView temp = Tokens.line(activity, "", R.style.Role_Releve);
            temp.setIncludeFontPadding(false);
            body.addView(temp);
            LinearLayout facts = new LinearLayout(activity);
            facts.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.setMarginStart(activity.px(R.dimen.syn_espace_2));
            body.addView(facts, flp);
            t.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            t.setTag(name);
            onFocus(t, () -> {
                Rooms.Room r = room(name);
                activity.houseFocused(r, r == null ? null : place(r), live);
            });
            t.setOnClickListener(v -> {
                Rooms.Room r = room(name);
                if (r != null) roomPage.open(r, place(r), home, () -> rooms.findViewWithTag(name));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i % PER_LINE == 0) lp.setMarginEnd(activity.px(R.dimen.syn_espace_1));
            line.addView(t, lp);
        }
        // A lone last room keeps the width of the others.
        if (found.size() % PER_LINE != 0) line.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1f));
    }

    /**
     * "Salon" over its temperature and, stacked beside, its other readings as the top line shows them; the tile lighter while a light is on. Rebuilt only when something changed.
     */
    private void fillRoom(LinearLayout t, Rooms.Room r) {
        Place place = place(r);
        boolean lit = r.lit(STATES);
        String signature = place.signature() + lit;
        if (signature.equals(t.getTag(R.id.room_shown))) return;
        t.setTag(R.id.room_shown, signature);
        t.setActivated(lit);
        ((NameLine) t.getChildAt(0)).setLit(lit);
        LinearLayout body = (LinearLayout) t.getChildAt(1);
        ((TextView) body.getChildAt(0)).setText(place.temperature.value == null ? "" : HouseText.units(activity, HouseText.degrees(place.temperature.value), true));
        HouseHeader.facts(activity, (LinearLayout) body.getChildAt(1), place, Integer.MAX_VALUE, R.style.Role_Petit);
    }

    /**
     * A room's readings: for each, the sensor its entry in home.json's temperatures (by label) gives,
     * read from the states, else the room's own first sensor of that kind.
     */
    private Place place(Rooms.Room r) {
        HomeConfig.Climate c = null;
        for (HomeConfig.Climate t : home.temperatures) {
            if (t.label.equals(r.name)) {
                c = t;
                break;
            }
        }
        return new Place(reading(c == null ? "" : c.entity, r.temperature), reading(c == null ? "" : c.humidity, r.humidity),
                Place.Reading.NONE, reading(c == null ? "" : c.co2, r.co2), reading(c == null ? "" : c.noise, r.noise));
    }

    /** A room's name and, at the right, the word "lit" while it is: dropped when the name leaves it no room, never the name cut. */
    private static final class NameLine extends LinearLayout {
        private final TextView name, word;
        private boolean lit;

        NameLine(MainActivity activity, TextView name, TextView word) {
            super(activity);
            this.name = name;
            this.word = word;
            setGravity(Gravity.CENTER_VERTICAL);
            addView(name);
            addView(new View(activity), new LayoutParams(0, 1, 1f));
            addView(word);
            word.setPadding(activity.px(R.dimen.syn_espace_1), 0, 0, 0);
        }

        void setLit(boolean lit) {
            this.lit = lit;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            float need = name.getPaint().measureText(name.getText().toString()) + word.getPaint().measureText(word.getText().toString())
                    + word.getPaddingStart();
            word.setVisibility(lit && need <= MeasureSpec.getSize(widthSpec) ? VISIBLE : GONE);
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private static Place.Reading reading(String configured, String discovered) {
        return Place.Reading.of(configured.isEmpty() ? discovered : configured, STATES);
    }

    // ---- the electricity --------------------------------------------------------------------------

    /**
     * The power against the subscription (and a bar) beside the month's cost, then Tempo today and
     * tomorrow; what is known.
     */
    private void showElectricity(Map<String, HomeAssistant.State> states) {
        HomeAssistant.State power = states.get(home.energyPower), subscribed = states.get(home.energySubscribed);
        Double va = HouseText.number(power), max = HouseText.number(subscribed);
        TextView powerValue = activity.findViewById(R.id.powerValue);
        FrameLayout gauge = activity.findViewById(R.id.powerGauge);
        powerValue.setVisibility(va == null ? View.INVISIBLE : View.VISIBLE);
        gauge.setVisibility(va == null || max == null ? View.GONE : View.VISIBLE);
        if (va != null) {
            if (max != null) {
                gauge.removeAllViews();
                gauge.addView(activity.progress((float) Math.min(1, va / (max * kilo(subscribed) / kilo(power)))),
                        new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            powerValue.setText(HouseText.power(activity, va, power, max, subscribed));
        }
        String month = monthCost();
        TextView cost = activity.findViewById(R.id.costValue);
        cost.setVisibility(month.isEmpty() ? View.GONE : View.VISIBLE);
        cost.setText(HouseText.units(activity, month, true));
        HomeAssistant.State today = states.get(home.energyTempo), tomorrow = states.get(home.energyTempoTomorrow);
        activity.findViewById(R.id.tempoLine).setVisibility(today == null && tomorrow == null ? View.GONE : View.VISIBLE);
        tempoDay(R.id.tempoToday, R.id.tempoTodayLabel, R.string.tech_today, today);
        tempoDay(R.id.tempoTomorrow, R.id.tempoTomorrowLabel, R.string.tech_tomorrow, tomorrow);
        boolean any = va != null || !month.isEmpty() || today != null || tomorrow != null;
        activity.findViewById(R.id.tech).setVisibility(any ? View.VISIBLE : View.GONE);
    }

    /**
     * "42,10 € ce mois": what the hours cost as the Energy dashboard values them, and the month of
     * subscription (home.json's energy.subscription, when known); empty when the hours are unknown.
     */
    private String monthCost() {
        if (Double.isNaN(usedCost)) return "";
        Double subscription = HouseText.number(STATES.get(home.energySubscription));
        double eur = usedCost + (subscription == null ? 0 : subscription);
        return activity.getString(R.string.tech_month, HouseText.measure(String.format(Locale.getDefault(), "%.2f", eur), "€"));
    }

    private static String unit(HomeAssistant.State s) {
        return s.attrs.optString("unit_of_measurement");
    }

    /** 1000 for a reading in kVA or kW, else 1: the bar compares the power and the subscription in one unit. */
    private static double kilo(HomeAssistant.State s) {
        return unit(s).startsWith("k") ? 1000 : 1;
    }

    /** "Tempo · today" over the day's colour in words, a dot of that colour before it; the line is gone while the day is unknown. */
    private void tempoDay(int valueId, int labelId, int day, HomeAssistant.State s) {
        TextView value = activity.findViewById(valueId);
        TextView label = activity.findViewById(labelId);
        ((View) value.getParent()).setVisibility(s == null ? View.GONE : View.VISIBLE);
        if (s == null) return;
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(HouseText.tempoColour(activity, s));
        int size = activity.px(R.dimen.syn_espace_2);
        dot.setSize(size, size);
        label.setText(activity.getString(R.string.tech_tempo) + " · " + activity.getString(day));
        value.setText(HouseText.tempoName(s));
        value.setCompoundDrawablesRelativeWithIntrinsicBounds(dot, null, null, null);
    }

    /** A tile's second line: brightness for a light that is on, otherwise the state in words. */
    private String describe(HomeAssistant.State s) {
        switch (s.state) {
            case "on":
                int b = s.attrs.optInt("brightness", -1);
                return b >= 0 ? activity.getString(R.string.state_brightness, Math.round(b * 100f / 255f)) : activity.getString(R.string.state_on);
            case "off":
                return activity.getString(R.string.state_off);
            case "open":
                return activity.getString(R.string.state_open);
            case "closed":
                return activity.getString(R.string.state_closed);
            default:
                for (HomeConfig.Status st : home.status) {
                    if (st.entity.equals(s.id) && st.states.containsKey(s.state)) return st.states.get(s.state);
                }
                return s.state;
        }
    }

    /** The remote's colour buttons (red, green, yellow, blue), as their keys. */
    private static final int[] COLOUR_CODES = {183, 184, 185, 186};
    private static final int[] COLOURS = {R.color.syn_touche_rouge, R.color.syn_touche_verte, R.color.syn_touche_jaune,
            R.color.syn_touche_bleue};

    /** The colour of the remote button set to this tile's entity, or 0: no key on the tile. */
    private int colourKey(HomeConfig.Item tile) {
        for (int i = 0; i < COLOUR_CODES.length; i++) {
            HomeConfig.Action a = home.keys.get(COLOUR_CODES[i]);
            if (a != null && !a.entity.isEmpty() && a.entity.equals(tile.action.entity)) return activity.getColor(COLOURS[i]);
        }
        return 0;
    }

    /** A small remote key of this colour, pinned to a tile's top right corner. */
    private Drawable keyDrawable(int colour) {
        GradientDrawable key = new GradientDrawable();
        key.setColor(colour);
        key.setCornerRadius(activity.px(R.dimen.syn_rayon_pastille));
        key.setStroke(activity.px(R.dimen.syn_trait_filet), activity.getColor(R.color.syn_filet));
        LayerDrawable layer = new LayerDrawable(new Drawable[]{key});
        layer.setLayerSize(0, activity.dp(KEY_W_DP), activity.dp(KEY_H_DP));
        layer.setLayerGravity(0, Gravity.TOP | Gravity.END);
        layer.setLayerInsetTop(0, activity.px(R.dimen.syn_espace_2));
        layer.setLayerInsetEnd(0, activity.px(R.dimen.syn_espace_2));
        return layer;
    }

    private static String tileNames(List<HomeConfig.Item> items) {
        StringBuilder sb = new StringBuilder();
        for (HomeConfig.Item i : items) sb.append(i.name).append('/').append(i.action.entity).append(';');
        return sb.toString();
    }
}
