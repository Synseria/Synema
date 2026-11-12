package com.synseria.synema;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The top line's Home Assistant parts: outside and the TV's room (the temperature large, the other
 * readings beside it, each with its trend (Place)), then a capsule for each thing out of the
 * ordinary (HouseText.notices): violet for an activity, light red for a problem, nothing when calm.
 */
final class HouseHeader {
    /** The readings beside a temperature on the top line: two lines of text fit the height of the temperature. */
    private static final int HEADER_FACTS = 2;
    /** A reading's icon (dp), a touch under its text. */
    // jeton manquant: syn_icone_petite (14)
    private static final int FACT_ICON_DP = 14;
    private final MainActivity activity;
    private final View outside, room;
    private final FlowRow chips;
    private HomeConfig home;
    private Map<String, HomeAssistant.State> shown = new HashMap<>();
    private String chipsShown = "";
    /** Home Assistant answers; when not, a capsule says so, ahead of the others. */
    private boolean reachable = true;

    HouseHeader(MainActivity activity) {
        this.activity = activity;
        outside = activity.findViewById(R.id.outside);
        room = activity.findViewById(R.id.room);
        chips = activity.findViewById(R.id.statusChips);
    }

    /** Takes the configuration; {@code on}: Home Assistant is set up. */
    void bind(HomeConfig home, boolean on) {
        this.home = home;
        outside.setVisibility(on && home.outside != null ? View.VISIBLE : View.GONE);
        room.setVisibility(on && !home.temperatures.isEmpty() ? View.VISIBLE : View.GONE);
        if (!on) {
            chips.removeAllViews();
            chipsShown = "";
        }
    }

    void render(Map<String, HomeAssistant.State> states) {
        shown = states;
        if (outside.getVisibility() == View.VISIBLE) column(activity, outside, home, home.outside, states, true, HEADER_FACTS);
        if (room.getVisibility() == View.VISIBLE) column(activity, room, home, home.temperatures.get(0), states, false, HEADER_FACTS);
        showChips(states);
    }

    /** The sensors of the top line's places, for their trends (History). */
    void addSensors(java.util.Collection<String> out) {
        if (home.outside != null) Place.of(home.outside, shown).addSensors(out);
        if (!home.temperatures.isEmpty()) Place.of(home.temperatures.get(0), shown).addSensors(out);
    }

    // ---- a column: a place's readings ------------------------------------------------------------

    /**
     * A place's column (dash_climate): its name, the sky with {@code sky}, its temperature large and
     * its first {@code maxFacts} other readings beside it; the top line's, and the screensaver's.
     * Unchanged: no relayout.
     */
    static void column(Context ctx, View column, HomeConfig home, HomeConfig.Climate c, Map<String, HomeAssistant.State> states,
                       boolean sky, int maxFacts) {
        HomeAssistant.State w = sky ? states.get(home.weather) : null;
        String condition = w != null && w.available() ? w.state : null;
        Place place = Place.of(c, states);
        String signature = c.label + "|" + condition + "|" + place.signature() + "|" + maxFacts;
        if (signature.equals(column.getTag())) return;
        column.setTag(signature);

        ((TextView) column.findViewById(R.id.climateLabel)).setText(condition != null ? c.label + " ·" : c.label);
        TextView skyText = column.findViewById(R.id.climateSky);
        skyText.setVisibility(condition != null ? View.VISIBLE : View.GONE);
        if (condition != null) {
            skyText.setText(HouseText.condition(ctx, condition));
            Drawable icon = ctx.getDrawable(HouseText.conditionIcon(ctx, condition)).mutate();
            icon.setBounds(0, 0, dp(ctx, FACT_ICON_DP), dp(ctx, FACT_ICON_DP));
            icon.setTint(ctx.getColor(R.color.syn_encre_secondaire));
            skyText.setCompoundDrawablesRelative(icon, null, null, null);
        }
        ((TextView) column.findViewById(R.id.climateTemp)).setText(degrees(ctx, place, "–"));
        facts(ctx, column.findViewById(R.id.climateFacts), place, maxFacts);
    }

    /** The place's temperature, its trend's arrow after it as tall as the readings beside; {@code none} when unknown. */
    static CharSequence degrees(Context ctx, Place p, String none) {
        if (p.temperature.value == null) return none;
        SpannableStringBuilder sb = HouseText.units(ctx, HouseText.degrees(p.temperature.value), true);
        Place.Trend trend = Place.Kind.TEMPERATURE.trend(p.temperature.sensor);
        if (trend != null) arrow(ctx, sb, trend, ctx.getColor(R.color.syn_encre_secondaire),
                ctx.getResources().getDimensionPixelSize(R.dimen.syn_typo_chiffre_taille));
        return sb;
    }

    /** {@code trend}'s arrow, {@code size} px square, after a space: an icon, for the font's arrow glyphs come out tiny. */
    private static void arrow(Context ctx, SpannableStringBuilder sb, Place.Trend trend, int color, int size) {
        Drawable d = ctx.getDrawable(trend.icon).mutate();
        d.setTint(color);
        d.setBounds(0, 0, size, size);
        sb.append(' ');
        int at = sb.length();
        sb.append('\uFFFC');
        sb.setSpan(new ImageSpan(d, ImageSpan.ALIGN_BASELINE), at, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * A place's readings beside its temperature, one per line of {@code facts}, the first {@code max} of:
     * humidity, pressure, CO₂ (with its dot), noise, those known, each with its unit and its trend.
     */
    static void facts(Context ctx, LinearLayout facts, Place p, int max) {
        facts(ctx, facts, p, max, R.style.Role_Chiffre);
    }

    /** As above, each line in the text role {@code style}: a room's tile has less height than the top line. */
    static void facts(Context ctx, LinearLayout facts, Place p, int max, int style) {
        facts.removeAllViews();
        List<TextView> lines = new ArrayList<>();
        if (p.humidity.value != null) {
            lines.add(fact(ctx, style, R.drawable.ic_drop, 0, HouseText.whole(p.humidity.value), "%",
                    Place.Kind.HUMIDITY.trend(p.humidity.sensor)));
        }
        if (p.pressure.value != null) {
            lines.add(fact(ctx, style, R.drawable.ic_gauge, 0, HouseText.whole(p.pressure.value), ctx.getString(R.string.unit_hpa),
                    Place.Kind.PRESSURE.trend(p.pressure.sensor)));
        }
        if (p.co2.value != null) {
            double co2 = p.co2.value;
            int dot = ctx.getColor(co2 < HouseText.CO2_FRESH ? R.color.syn_succes : co2 < HouseText.CO2_STALE ? R.color.syn_alerte
                    : R.color.syn_danger);
            lines.add(fact(ctx, style, 0, dot, HouseText.whole(co2), ctx.getString(R.string.unit_co2),
                    Place.Kind.CO2.trend(p.co2.sensor)));
        }
        if (p.noise.value != null) {
            lines.add(fact(ctx, style, R.drawable.ic_noise, 0, HouseText.whole(p.noise.value), ctx.getString(R.string.unit_db),
                    Place.Kind.NOISE.trend(p.noise.sensor)));
        }
        for (TextView line : lines.subList(0, Math.min(max, lines.size()))) facts.addView(line);
    }

    /**
     * One reading on its line: an icon (or, with {@code dot}, a dot of that colour), the value, its unit
     * (HouseText.units) and its trend's arrow.
     */
    private static TextView fact(Context ctx, int style, int icon, int dot, String value, String unit, Place.Trend trend) {
        SpannableStringBuilder sb = HouseText.units(ctx, HouseText.measure(value, unit), true);
        TextView line = new TextView(ctx);
        line.setTextAppearance(style);
        if (trend != null) arrow(ctx, sb, trend, ctx.getColor(trend.color), Math.round(line.getTextSize()));
        line.setText(sb);
        line.setSingleLine(true);
        line.setIncludeFontPadding(false);
        // A dot of five ninths of the icon's size, centred in the same slot, so every value starts at the same place.
        int slot = dp(ctx, FACT_ICON_DP);
        Drawable d;
        if (dot != 0) {
            int size = slot * 5 / 9;
            d = new InsetDrawable(dot(dot, size), (slot - size) / 2);
        } else {
            d = ctx.getDrawable(icon).mutate();
            d.setTint(ctx.getColor(R.color.syn_encre_secondaire));
        }
        d.setBounds(0, 0, slot, slot);
        line.setCompoundDrawablesRelative(d, null, null, null);
        line.setCompoundDrawablePadding(ctx.getResources().getDimensionPixelSize(R.dimen.syn_espace_1));
        return line;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    // ---- what is out of the ordinary -------------------------------------------------------------

    /** Home Assistant answers again, or no longer: the capsules say so. */
    void reachable(boolean on) {
        reachable = on;
        showChips(shown);
    }

    private void showChips(Map<String, HomeAssistant.State> states) {
        List<HouseText.Notice> notices = HouseText.notices(activity, home, states, System.currentTimeMillis());
        if (!reachable) notices.add(0, new HouseText.Notice(activity.getString(R.string.ha_unreachable), true, "", ""));
        StringBuilder sig = new StringBuilder();
        for (HouseText.Notice n : notices) sig.append(n.text).append(n.problem).append(';');
        if (sig.toString().equals(chipsShown)) return;   // unchanged: no relayout
        chipsShown = sig.toString();
        chips.removeAllViews();
        for (HouseText.Notice n : notices) {
            chips.addView(chip(n), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.px(R.dimen.syn_espace_6)));
        }
    }

    /**
     * A capsule: a sign, then the words. The sign is a warning for a problem, a person's initial for
     * someone who comes home, the robot for a vacuum, a dot otherwise.
     */
    private View chip(HouseText.Notice n) {
        int color = n.problem ? activity.getColor(R.color.syn_danger) : Tokens.primaryInk(activity);
        LinearLayout chip = new LinearLayout(activity);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackgroundResource(R.drawable.dash_capsule_bg);
        boolean person = !n.problem && "person".equals(n.domain);
        chip.setPadding(activity.px(person ? R.dimen.syn_espace_1 : R.dimen.syn_espace_2), 0, activity.px(R.dimen.syn_espace_2), 0);
        View sign;
        int size = activity.dp(FACT_ICON_DP);
        if (person) {
            // jeton manquant: syn_icone_grande (20), the person's initial disc
            int disc = activity.dp(20);
            String letter = n.name.isEmpty() ? "" : n.name.substring(0, 1).toUpperCase(Locale.getDefault());
            TextView initial = activity.text(letter, R.style.Role_CorpsFort);
            initial.setTextColor(activity.getColor(R.color.syn_nuit_profond));
            initial.setIncludeFontPadding(false);
            initial.setGravity(Gravity.CENTER);
            initial.setBackground(dot(activity.getColor(R.color.syn_succes), disc));
            sign = initial;
            size = disc;
        } else if (n.problem || "vacuum".equals(n.domain)) {
            ImageView icon = new ImageView(activity);
            icon.setImageResource(n.problem ? R.drawable.ic_warn : R.drawable.ic_vacuum);
            icon.setColorFilter(color);
            sign = icon;
        } else {
            sign = new View(activity);
            sign.setBackground(dot(color, activity.px(R.dimen.syn_espace_2)));
            size = activity.px(R.dimen.syn_espace_2);
        }
        chip.addView(sign, new LinearLayout.LayoutParams(size, size));
        TextView words = activity.text(n.text, R.style.Role_Note);
        words.setTextColor(color);
        words.setIncludeFontPadding(false);
        int wrap = ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(wrap, wrap);
        lp.setMarginStart(activity.px(R.dimen.syn_espace_1));
        chip.addView(words, lp);
        return chip;
    }

    private static GradientDrawable dot(int color, int size) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setSize(size, size);
        return d;
    }
}
