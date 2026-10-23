package com.synseria.synema;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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
    static final int TEXT = 0xE0F2F2F5, FAIR = 0xFFF2B64A;
    private static final int ICON = 0x80F2F2F5, UNIT = 0x8CF2F2F5;
    private static final int ACTIVITY = 0xFFC9A7FF, PROBLEM = 0xFFFF8A8A;
    private static final int GOOD = 0xFF4FD18B, BAD = 0xFFFF6A6A;

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
        if (outside.getVisibility() == View.VISIBLE) column(activity, outside, home, home.outside, states, true, 9.5f);
        if (room.getVisibility() == View.VISIBLE) column(activity, room, home, home.temperatures.get(0), states, false, 9.5f);
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
     * its other readings beside it at {@code sp}; the top line's, and the screensaver's. Unchanged: no
     * relayout.
     */
    static void column(Context ctx, View column, HomeConfig home, HomeConfig.Climate c, Map<String, HomeAssistant.State> states,
                       boolean sky, float sp) {
        HomeAssistant.State w = sky ? states.get(home.weather) : null;
        String condition = w != null && w.available() ? w.state : null;
        Place place = Place.of(c, states);
        String signature = c.label + "|" + condition + "|" + place.signature();
        if (signature.equals(column.getTag())) return;
        column.setTag(signature);

        ((TextView) column.findViewById(R.id.climateLabel)).setText(condition != null ? c.label + " ·" : c.label);
        TextView skyText = column.findViewById(R.id.climateSky);
        skyText.setVisibility(condition != null ? View.VISIBLE : View.GONE);
        if (condition != null) {
            skyText.setText(HouseText.condition(ctx, condition));
            skyText.setCompoundDrawablesRelativeWithIntrinsicBounds(HouseText.conditionIcon(ctx, condition), 0, 0, 0);
        }
        ((TextView) column.findViewById(R.id.climateTemp)).setText(degrees(place, "–"));
        facts(ctx, column.findViewById(R.id.climateFacts), sp, false, place);
    }

    /** The place's temperature, its trend's arrow small after it; {@code none} when unknown. */
    static CharSequence degrees(Place p, String none) {
        if (p.temperature.value == null) return none;
        SpannableStringBuilder sb = new SpannableStringBuilder(HouseText.degrees(p.temperature.value));
        Place.Trend trend = Place.Kind.TEMPERATURE.trend(p.temperature.sensor);
        if (trend != null) {
            int at = sb.length();
            sb.append(trend.arrow);
            sb.setSpan(new RelativeSizeSpan(0.4f), at, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(UNIT), at, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /**
     * A place's readings beside its temperature, one per line of {@code facts} at {@code sp}: humidity,
     * pressure, CO₂ (with its dot), noise, those known, each with its trend. {@code compact} (a room tile
     * of the panel) leaves out the units the icons already say.
     */
    static void facts(Context ctx, LinearLayout facts, float sp, boolean compact, Place p) {
        facts.removeAllViews();
        List<TextView> lines = new ArrayList<>();
        if (p.humidity.value != null) {
            lines.add(fact(ctx, sp, R.drawable.ic_drop, 0, HouseText.whole(p.humidity.value) + " %", null,
                    Place.Kind.HUMIDITY.trend(p.humidity.sensor)));
        }
        if (p.pressure.value != null) {
            lines.add(fact(ctx, sp, R.drawable.ic_gauge, 0, HouseText.whole(p.pressure.value), ctx.getString(R.string.unit_hpa),
                    Place.Kind.PRESSURE.trend(p.pressure.sensor)));
        }
        if (p.co2.value != null) {
            double co2 = p.co2.value;
            int dot = co2 < HouseText.CO2_FRESH ? GOOD : co2 < HouseText.CO2_STALE ? FAIR : BAD;
            lines.add(fact(ctx, sp, 0, dot, HouseText.whole(co2), compact ? null : ctx.getString(R.string.unit_co2),
                    Place.Kind.CO2.trend(p.co2.sensor)));
        }
        if (p.noise.value != null) {
            lines.add(fact(ctx, sp, R.drawable.ic_noise, 0, HouseText.whole(p.noise.value), compact ? null : ctx.getString(R.string.unit_db),
                    Place.Kind.NOISE.trend(p.noise.sensor)));
        }
        for (TextView line : lines) facts.addView(line);
    }

    /**
     * One reading on its line: an icon (or, with {@code dot}, a dot of that colour), the value, its unit
     * smaller and dimmer, and its trend's arrow.
     */
    private static TextView fact(Context ctx, float sp, int icon, int dot, String value, String unit, Place.Trend trend) {
        SpannableStringBuilder sb = new SpannableStringBuilder(value);
        if (unit != null) {
            int at = sb.length();
            sb.append(' ').append(unit);
            sb.setSpan(new RelativeSizeSpan((sp - 1) / sp), at, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(UNIT), at, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (trend != null) {
            int at = sb.length();
            sb.append(' ').append(trend.arrow);
            sb.setSpan(new ForegroundColorSpan(trend.color), at, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView line = new TextView(ctx);
        line.setText(sb);
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        line.setTextColor(TEXT);
        line.setSingleLine(true);
        line.setIncludeFontPadding(false);
        // The icons a dp under the text's size; a dot of five ninths of it, centred in the same slot, so
        // every value starts at the same place.
        int slot = dp(ctx, Math.round(sp) - 1);
        Drawable d;
        if (dot != 0) {
            int size = slot * 5 / 9;
            d = new InsetDrawable(dot(dot, size), (slot - size) / 2);
        } else {
            d = ctx.getDrawable(icon).mutate();
            d.setTint(ICON);
        }
        d.setBounds(0, 0, slot, slot);
        line.setCompoundDrawablesRelative(d, null, null, null);
        line.setCompoundDrawablePadding(dp(ctx, 4));
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
            chips.addView(chip(n), new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, activity.dp(20)));
        }
    }

    /**
     * A capsule: a sign, then the words. The sign is a warning for a problem, a person's initial for
     * someone who comes home, the robot for a vacuum, a dot otherwise.
     */
    private View chip(HouseText.Notice n) {
        int color = n.problem ? PROBLEM : ACTIVITY;
        LinearLayout chip = new LinearLayout(activity);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setBackgroundResource(R.drawable.dash_capsule_bg);
        boolean person = !n.problem && "person".equals(n.domain);
        chip.setPadding(activity.dp(person ? 3 : 7), 0, activity.dp(7), 0);
        View sign;
        int size = activity.dp(10);
        if (person) {
            String letter = n.name.isEmpty() ? "" : n.name.substring(0, 1).toUpperCase(Locale.getDefault());
            TextView initial = activity.text(letter, 7.5f, 0xFF0B2A19);
            initial.setTypeface(Typeface.DEFAULT_BOLD);
            initial.setIncludeFontPadding(false);
            initial.setGravity(Gravity.CENTER);
            initial.setBackground(dot(GOOD, activity.dp(14)));
            sign = initial;
            size = activity.dp(14);
        } else if (n.problem || "vacuum".equals(n.domain)) {
            ImageView icon = new ImageView(activity);
            icon.setImageResource(n.problem ? R.drawable.ic_warn : R.drawable.ic_vacuum);
            icon.setColorFilter(color);
            sign = icon;
        } else {
            sign = new View(activity);
            sign.setBackground(dot(color, activity.dp(6)));
            size = activity.dp(6);
        }
        chip.addView(sign, new LinearLayout.LayoutParams(size, size));
        TextView words = activity.text(n.text, 9.5f, color);
        words.setIncludeFontPadding(false);
        int wrap = ViewGroup.LayoutParams.WRAP_CONTENT;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(wrap, wrap);
        lp.setMarginStart(activity.dp(5));
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
