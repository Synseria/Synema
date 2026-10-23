package com.synseria.synema;

import android.content.Context;
import android.content.res.TypedArray;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** How the house reads in words, shared by the dashboard and the screensaver. */
final class HouseText {
    /** CO₂ indoors (ppm): fresh air below the first, stale below the second, time to air the room beyond. */
    static final double CO2_FRESH = 800, CO2_STALE = 1200;
    /** EDF's Tempo integrations name the colours in French; red is the dear day. */
    private static final String TEMPO_BLUE = "bleu", TEMPO_WHITE = "blanc", TEMPO_RED = "rouge";
    private static final int BLUE = 0xFF3E8EF7, WHITE = 0xFFF4F6FA, RED = 0xFFE5484D, UNKNOWN = 0xFF6E6E73;

    private HouseText() {
    }

    /** Something out of the ordinary: a capsule on the dashboard's top line, a line in the screensaver. */
    static final class Notice {
        final String text;
        /** A problem (red) rather than an activity (violet). */
        final boolean problem;
        /** The entity's domain ("person", "vacuum"…), for the capsule's sign; empty for a reading. */
        final String domain;
        /** Who it is about, for a person's initial. */
        final String name;

        Notice(String text, boolean problem, String domain, String name) {
            this.text = text;
            this.problem = problem;
            this.domain = domain;
            this.name = name;
        }
    }

    /** "Salon 23° / 57 %", or null when the temperature has no number; humidity only when it has one. */
    static String temperature(HomeConfig.Climate t, Map<String, HomeAssistant.State> states) {
        Double temp = number(states.get(t.entity));
        if (temp == null) return null;
        Double hum = t.humidity.isEmpty() ? null : number(states.get(t.humidity));
        return t.label + " " + degrees(temp) + (hum != null ? String.format(Locale.getDefault(), " / %.0f %%", hum) : "");
    }

    /**
     * What is out of the ordinary now, problems first: the status entries that show, a place whose CO₂
     * is past CO2_STALE, the power above energy's limit, a red Tempo day today or tomorrow.
     */
    static List<Notice> notices(Context ctx, HomeConfig home, Map<String, HomeAssistant.State> states, long nowMs) {
        List<Notice> out = new ArrayList<>();
        for (HomeConfig.Status st : home.status) {
            HomeAssistant.State s = states.get(st.entity);
            if (s == null || !s.available() || !st.shows(s.state, s.changed, nowMs)) continue;
            String domain = st.entity.substring(0, Math.max(0, st.entity.indexOf('.')));
            out.add(new Notice(status(st, s), st.alert(s.state), domain, who(st, s)));
        }
        List<HomeConfig.Climate> places = new ArrayList<>(home.temperatures);
        if (home.outside != null) places.add(0, home.outside);
        for (HomeConfig.Climate c : places) {
            Double ppm = c.co2.isEmpty() ? null : number(states.get(c.co2));
            if (ppm != null && ppm >= CO2_STALE) {
                out.add(problem(ctx.getString(R.string.co2_high, whole(ppm), c.label)));
            }
        }
        HomeAssistant.State power = home.energyPower.isEmpty() ? null : states.get(home.energyPower);
        Double va = number(power);
        if (va != null && va > home.energyLimit) {
            String unit = power.attrs.optString("unit_of_measurement");
            out.add(problem(ctx.getString(R.string.power_high, whole(va) + (unit.isEmpty() ? "" : " " + unit))));
        }
        if (tempoRed(home.energyTempo, states)) out.add(problem(ctx.getString(R.string.tempo_red_today)));
        if (tempoRed(home.energyTempoTomorrow, states)) out.add(problem(ctx.getString(R.string.tempo_red_tomorrow)));
        out.sort((a, b) -> Boolean.compare(b.problem, a.problem));   // stable: home.json's order otherwise
        return out;
    }

    private static Notice problem(String text) {
        return new Notice(text, true, "", "");
    }

    private static boolean tempoRed(String entity, Map<String, HomeAssistant.State> states) {
        HomeAssistant.State s = entity.isEmpty() ? null : states.get(entity);
        return s != null && s.state.toLowerCase(Locale.ROOT).contains(TEMPO_RED);
    }

    /** A Tempo day's colour for its dot; grey until EDF says (tomorrow's comes late in the morning). */
    static int tempoColour(HomeAssistant.State s) {
        String c = s.state.toLowerCase(Locale.ROOT);
        return c.contains(TEMPO_RED) ? RED : c.contains(TEMPO_WHITE) ? WHITE : c.contains(TEMPO_BLUE) ? BLUE : UNKNOWN;
    }

    /** Whom or what a status entry is about: its label, else the entity's name in Home Assistant. */
    private static String who(HomeConfig.Status st, HomeAssistant.State s) {
        return st.label.isEmpty() ? s.name() : st.label;
    }

    /** "Roborock en ménage", "Porte d'entrée ouverte": who or what, then the state in words. */
    private static String status(HomeConfig.Status st, HomeAssistant.State s) {
        String words = st.states.get(s.state);
        return who(st, s) + " " + (words != null ? words : s.state);
    }

    /** The entity's state as a number, or null when it has none. */
    static Double number(HomeAssistant.State s) {
        if (s == null || !s.available()) return null;
        try {
            return Double.parseDouble(s.state);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Home Assistant's weather condition ("rainy") in words, from the weather_condition arrays. */
    static String condition(Context ctx, String state) {
        String[] keys = ctx.getResources().getStringArray(R.array.weather_condition_keys);
        String[] names = ctx.getResources().getStringArray(R.array.weather_condition_names);
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(state)) return names[i];
        return state;
    }

    /** The condition's icon, from the same arrays; 0 for a condition they do not list. */
    static int conditionIcon(Context ctx, String state) {
        String[] keys = ctx.getResources().getStringArray(R.array.weather_condition_keys);
        TypedArray icons = ctx.getResources().obtainTypedArray(R.array.weather_condition_icons);
        try {
            for (int i = 0; i < keys.length; i++) if (keys[i].equals(state)) return icons.getResourceId(i, 0);
            return 0;
        } finally {
            icons.recycle();
        }
    }

    static String whole(double v) {
        return String.format(Locale.getDefault(), "%.0f", v);
    }

    static String degrees(double v) {
        return String.format(Locale.getDefault(), "%.0f°", v);
    }
}
