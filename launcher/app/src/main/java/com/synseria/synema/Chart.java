package com.synseria.synema;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.TypedValue;
import android.view.View;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A graph as the TV draws them, on a card of its own: its title and what each curve reads now at the
 * top, then faint lines at round values (labelled at the left, and at the right for a second curve)
 * and at round times (labelled under). Readings are smooth lines through a few dozen periods
 * (Stats.Span), each period's min to max a faint band round them. A meter is bars (Stats.Range), one
 * per hour, day or month, its meters stacked in their colours. Drawn once per answer: nothing moves.
 * Bare (the screensaver's), a curve alone, without card, lines or labels, over what is known of its span.
 */
final class Chart extends View {
    /** How a kind of value is written, beside the title and on the axis. */
    enum Unit {
        DEGREES("°", 1, 2, -100, 100), PERCENT(" %", 0, 10, 0, 100), PPM(" ppm", 0, 200, 0, 1e9),
        DECIBELS(" dB", 0, 10, 0, 200), KWH(" kWh", 1, 1, 0, 1e9);

        private final String suffix;
        private final int decimals;
        /** The smallest range a graph spans: a steady day stays a flat line, not a zigzag. */
        final double minRange;
        /** What a value can be: the axis never goes past (no 105 %). */
        private final double floor, ceiling;

        Unit(String suffix, int decimals, double minRange, double floor, double ceiling) {
            this.suffix = suffix;
            this.decimals = decimals;
            this.minRange = minRange;
            this.floor = floor;
            this.ceiling = ceiling;
        }

        String value(double v) {
            return String.format(Locale.getDefault(), "%." + decimals + "f", v) + suffix;
        }

        /** An axis label: as few decimals as the step needs, the unit only when it is short. */
        String tick(double v, double step) {
            int d = step >= 1 ? 0 : 1;
            String n = String.format(Locale.getDefault(), "%." + d + "f", v);
            return this == DEGREES ? n + "°" : this == PERCENT ? n + " %" : n;
        }
    }

    /** A curve or a meter: its name, colour and points. */
    static final class Series {
        final String name;
        final int color;
        final Unit unit;
        final List<Stats.Point> points;

        Series(String name, int color, Unit unit, List<Stats.Point> points) {
            this.name = name;
            this.color = color;
            this.unit = unit;
            this.points = points;
        }
    }

    /** A dark card: a busy picture behind does not cross the curves. */
    private static final int GRID = 0x1AFFFFFF, LABEL = 0x8CF2F2F5, TITLE = 0x9EF2F2F5, CARD = 0xB80B0D12;

    private final float d;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG), grid = new Paint();
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private String title = "";
    private List<Series> series = Collections.emptyList();
    private Stats.Span span;
    private Stats.Range range;
    /** Under the title on the right, for bars (a total); lines show each curve's last value. */
    private String summary = "";
    /** Nothing to draw yet, or nothing known. */
    private boolean waiting = true;
    /** The curve alone. */
    private boolean bare;

    Chart(Context ctx) {
        super(ctx);
        d = ctx.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        grid.setColor(GRID);
        grid.setStrokeWidth(Math.max(1, d / 2));
        text.setFontFeatureSettings("tnum");
    }

    /** Only the curve, edge to edge: no card, no lines, no labels. */
    void bare() {
        bare = true;
    }

    void title(String t) {
        title = t;
        invalidate();
    }

    /** Readings over {@code span}; the second series, if any, has its own scale on the right. */
    void lines(Stats.Span span, List<Series> s) {
        this.span = span;
        this.range = null;
        show(s, "");
    }

    /** A meter over {@code range}, its series stacked; {@code summary} at the top right. */
    void bars(Stats.Range range, List<Series> s, String summary) {
        this.range = range;
        this.span = null;
        show(s, summary);
    }

    /** Being read: the card and its title only. */
    void reading() {
        waiting = true;
        invalidate();
    }

    private void show(List<Series> s, String summary) {
        series = s;
        this.summary = summary;
        waiting = false;
        invalidate();
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), pad = 10 * d;
        fill.setShader(null);
        if (bare) {
            if (!waiting && anyPoint()) drawLines(canvas, d, w - 4 * d, 2 * d, h - 2 * d, false);
            return;
        }
        fill.setColor(CARD);
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, 10 * d, 10 * d, fill);

        text.setTextSize(sp(8.5f));
        text.setLetterSpacing(0.15f);
        text.setColor(TITLE);
        text.setTextAlign(Paint.Align.LEFT);
        float titleY = pad + sp(8.5f);
        canvas.drawText(title.toUpperCase(Locale.getDefault()), pad, titleY, text);
        text.setLetterSpacing(0);
        if (waiting || !anyPoint()) return;
        header(canvas, w - pad, titleY + d);

        boolean second = range == null && series.size() > 1;
        text.setTextSize(sp(8.5f));
        float left = pad + text.measureText("8888") + 4 * d, right = w - pad - (second ? text.measureText("88 %") + 6 * d : 2 * d);
        float top = titleY + 12 * d, bottom = h - pad - sp(8.5f) - 4 * d;
        if (range != null) drawBars(canvas, left, right, top, bottom);
        else drawLines(canvas, left, right, top, bottom, second);
    }

    private boolean anyPoint() {
        for (Series s : series) if (!s.points.isEmpty()) return true;
        return false;
    }

    /** At the top right: each curve's last value in its colour, or the bars' summary. */
    private void header(Canvas canvas, float x, float y) {
        text.setTextSize(sp(10));
        text.setTextAlign(Paint.Align.RIGHT);
        if (range != null) {
            text.setColor(0xE0F2F2F5);
            canvas.drawText(summary, x, y, text);
            return;
        }
        for (int i = series.size() - 1; i >= 0; i--) {
            Series s = series.get(i);
            double last = last(s);
            if (Double.isNaN(last)) continue;
            String v = s.unit.value(last);
            text.setColor(s.color);
            canvas.drawText(v, x, y, text);
            x -= text.measureText(v);
            fill.setColor(s.color);
            canvas.drawCircle(x - 6 * d, y - sp(10) / 3, 2.5f * d, fill);
            x -= 16 * d;
        }
    }

    private static double last(Series s) {
        for (int i = s.points.size() - 1; i >= 0; i--) if (!Double.isNaN(s.points.get(i).mean)) return s.points.get(i).mean;
        return Double.NaN;
    }

    // ---- lines -------------------------------------------------------------------------------------

    private void drawLines(Canvas canvas, float left, float right, float top, float bottom, boolean second) {
        long t1 = System.currentTimeMillis(), t0 = t1 - span.ms;
        long period = span.drawnMs;
        if (!bare) timeGrid(canvas, left, right, top, bottom, t0, t1);
        for (int i = Math.min(series.size(), 2) - 1; i >= 0; i--) {
            Series s = series.get(i);
            List<Stats.Point> points = gather(s.points, period);
            double[] scale = scale(points, s.unit, !bare);
            if (scale == null) continue;
            // Bare, there is no time axis to read: a span only partly known (statistics begun a few
            // hours ago) spans the width rather than a sliver at the right that looks cut.
            if (bare) t0 = Math.min(t1 - period, Math.max(t0, points.get(0).start + period / 2));
            if (!bare) {
                if (i == 0) valueGrid(canvas, left, right, top, bottom, scale, s.unit, true, LABEL);
                else valueGrid(canvas, left, right, top, bottom, scale, s.unit, false, (s.color & 0x00FFFFFF) | 0xB3000000);
                drawBand(canvas, points, s.color, scale, left, right, top, bottom, t0, t1, period);
            }
            curve(points, scale, left, right, top, bottom, t0, t1, period);
            if (bare) {
                // A soft fill under the curve, down to the axis.
                Path area = new Path(path);
                area.lineTo(lastX, bottom);
                area.lineTo(firstX, bottom);
                area.close();
                fill.setShader(new LinearGradient(0, top, 0, bottom, (s.color & 0x00FFFFFF) | 0x47000000,
                        s.color & 0x00FFFFFF, Shader.TileMode.CLAMP));
                canvas.drawPath(area, fill);
                fill.setShader(null);
            }
            stroke.setColor(s.color);
            stroke.setStrokeWidth((i == 0 ? 2f : 1.5f) * d);
            canvas.drawPath(path, stroke);
            fill.setColor(s.color);
            canvas.drawCircle(lastX, lastY, (i == 0 ? 3f : 2.5f) * d, fill);
        }
    }

    /**
     * The points gathered into periods of {@code length}, on local time: the mean of their means, the
     * lowest min, the highest max. Fewer points read better than every five minutes' wobble.
     */
    private static List<Stats.Point> gather(List<Stats.Point> points, long length) {
        List<Stats.Point> out = new ArrayList<>();
        java.util.TimeZone zone = java.util.TimeZone.getDefault();
        long at = Long.MIN_VALUE;
        double sum = 0, lo = Double.NaN, hi = Double.NaN;
        int n = 0;
        for (Stats.Point p : points) {
            long start = p.start - Math.floorMod(p.start + zone.getOffset(p.start), length);
            if (start != at) {
                if (n > 0 || !Double.isNaN(lo)) out.add(new Stats.Point(at, n > 0 ? sum / n : Double.NaN, lo, hi));
                at = start;
                sum = 0;
                n = 0;
                lo = hi = Double.NaN;
            }
            if (!Double.isNaN(p.mean)) {
                sum += p.mean;
                n++;
            }
            if (!Double.isNaN(p.min)) lo = Double.isNaN(lo) ? p.min : Math.min(lo, p.min);
            if (!Double.isNaN(p.max)) hi = Double.isNaN(hi) ? p.max : Math.max(hi, p.max);
        }
        if (n > 0 || !Double.isNaN(lo)) out.add(new Stats.Point(at, n > 0 ? sum / n : Double.NaN, lo, hi));
        return out;
    }

    private float firstX, lastX, lastY;

    /** The curve through each period's mean, at its middle, smooth; a gap where Home Assistant has none. */
    private void curve(List<Stats.Point> points, double[] scale, float left, float right, float top, float bottom,
                       long t0, long t1, long period) {
        path.reset();
        float[] xs = new float[points.size()], ys = new float[points.size()];
        int n = 0;
        boolean first = true;
        for (int i = 0; i <= points.size(); i++) {
            Stats.Point p = i < points.size() ? points.get(i) : null;
            float x = p == null ? 0 : at(p, left, right, t0, t1, period);
            if (p == null || Double.isNaN(p.mean) || x < left) {
                if (n > 0) {
                    smooth(path, xs, ys, n, true);
                    if (first) firstX = xs[0];
                    first = false;
                    lastX = xs[n - 1];
                    lastY = ys[n - 1];
                }
                n = 0;
                continue;
            }
            xs[n] = x;
            ys[n++] = y(p.mean, scale, top, bottom);
        }
    }

    /** Where a period's point stands: at its middle; today's, not yet past, at now. */
    private float at(Stats.Point p, float left, float right, long t0, long t1, long period) {
        return Math.min(right, x(p.start + period / 2, left, right, t0, t1));
    }

    /** Each period's min to max, faint in the curve's colour: what the smooth curve leaves out. */
    private void drawBand(Canvas canvas, List<Stats.Point> points, int color, double[] scale, float left, float right,
                          float top, float bottom, long t0, long t1, long period) {
        List<float[]> kept = new ArrayList<>();
        for (Stats.Point p : points) {
            if (Double.isNaN(p.min) || Double.isNaN(p.max)) continue;
            float x = at(p, left, right, t0, t1, period);
            if (x >= left) kept.add(new float[]{x, y(p.max, scale, top, bottom), y(p.min, scale, top, bottom)});
        }
        int n = kept.size();
        if (n < 2) return;
        float[] xs = new float[n], highs = new float[n], lows = new float[n];
        for (int i = 0; i < n; i++) {
            xs[i] = kept.get(i)[0];
            highs[i] = kept.get(i)[1];
        }
        Path band = new Path();
        smooth(band, xs, highs, n, true);
        // The low edge goes back from the end: one closed outline.
        float[] back = new float[n];
        for (int i = 0; i < n; i++) {
            back[i] = kept.get(n - 1 - i)[0];
            lows[i] = kept.get(n - 1 - i)[2];
        }
        smooth(band, back, lows, n, false);
        band.close();
        fill.setColor((color & 0x00FFFFFF) | 0x2E000000);
        canvas.drawPath(band, fill);
    }

    /**
     * A monotone cubic through the points (Fritsch–Carlson): smooth, yet never past a point's value, so
     * a curve never shows a peak or a dip that was not read. {@code xs} only rises, or only falls.
     */
    private static void smooth(Path p, float[] xs, float[] ys, int n, boolean move) {
        if (move) p.moveTo(xs[0], ys[0]);
        else p.lineTo(xs[0], ys[0]);
        if (n < 2) return;
        float[] slope = new float[n - 1], m = new float[n];
        for (int k = 0; k < n - 1; k++) {
            float dx = xs[k + 1] - xs[k];
            slope[k] = Math.abs(dx) < 1e-3f ? 0 : (ys[k + 1] - ys[k]) / dx;
        }
        m[0] = slope[0];
        m[n - 1] = slope[n - 2];
        for (int k = 1; k < n - 1; k++) m[k] = slope[k - 1] * slope[k] <= 0 ? 0 : (slope[k - 1] + slope[k]) / 2;
        for (int k = 0; k < n - 1; k++) {
            if (slope[k] == 0) {
                m[k] = 0;
                m[k + 1] = 0;
                continue;
            }
            float a = m[k] / slope[k], b = m[k + 1] / slope[k], h = a * a + b * b;
            if (h > 9) {
                float t = 3 / (float) Math.sqrt(h);
                m[k] = t * a * slope[k];
                m[k + 1] = t * b * slope[k];
            }
        }
        for (int k = 0; k < n - 1; k++) {
            float third = (xs[k + 1] - xs[k]) / 3;
            p.cubicTo(xs[k] + third, ys[k] + m[k] * third, xs[k + 1] - third, ys[k + 1] - m[k + 1] * third, xs[k + 1], ys[k + 1]);
        }
    }

    /** {lo, hi, step}: round values round what the points read, at least their unit's range; null when they read nothing. */
    private static double[] scale(List<Stats.Point> points, Unit unit, boolean band) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (Stats.Point p : points) {
            double lo = band && !Double.isNaN(p.min) ? p.min : p.mean, hi = band && !Double.isNaN(p.max) ? p.max : p.mean;
            if (!Double.isNaN(lo)) min = Math.min(min, lo);
            if (!Double.isNaN(hi)) max = Math.max(max, hi);
        }
        if (min > max) return null;
        double[] scale = nice(min, max, unit.minRange);
        scale[0] = Math.max(scale[0], unit.floor);
        scale[1] = Math.min(scale[1], unit.ceiling);
        return scale;
    }

    private static double[] nice(double min, double max, double minRange) {
        if (max - min < minRange) {
            double mid = (min + max) / 2;
            min = mid - minRange / 2;
            max = mid + minRange / 2;
        }
        double raw = (max - min) / 3, mag = Math.pow(10, Math.floor(Math.log10(raw))), step = mag;
        for (double m : new double[]{1, 2, 2.5, 5, 10}) {
            if (m * mag >= raw) {
                step = m * mag;
                break;
            }
        }
        return new double[]{Math.floor(min / step) * step, Math.ceil(max / step) * step, step};
    }

    private float x(long t, float left, float right, long t0, long t1) {
        return left + (right - left) * (t - t0) / (float) (t1 - t0);
    }

    private static float y(double v, double[] scale, float top, float bottom) {
        return (float) (bottom - (v - scale[0]) / (scale[1] - scale[0]) * (bottom - top));
    }

    /** Lines at round values, labelled at the left ({@code onLeft}) or the right. */
    private void valueGrid(Canvas canvas, float left, float right, float top, float bottom, double[] scale, Unit unit,
                           boolean onLeft, int color) {
        text.setTextSize(sp(8.5f));
        text.setColor(color);
        text.setTextAlign(onLeft ? Paint.Align.RIGHT : Paint.Align.LEFT);
        for (double v = scale[0]; v <= scale[1] + scale[2] / 2; v += scale[2]) {
            float y = y(v, scale, top, bottom);
            if (onLeft) canvas.drawLine(left, y, right, y, grid);
            canvas.drawText(unit.tick(v, scale[2]), onLeft ? left - 5 * d : right + 5 * d, y + sp(8.5f) / 3, text);
        }
    }

    /** Lines at round times under the curves, labelled under the axis. */
    private void timeGrid(Canvas canvas, float left, float right, float top, float bottom, long t0, long t1) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime start = Instant.ofEpochMilli(t0).atZone(zone);
        ZonedDateTime t;
        long stepHours;
        DateTimeFormatter label;
        switch (span) {
            case HOURS_6:
                t = start.truncatedTo(ChronoUnit.HOURS).plusHours(1);
                stepHours = 1;
                label = DateTimeFormatter.ofPattern("H'h'");
                break;
            case DAY:
                t = start.truncatedTo(ChronoUnit.HOURS).plusHours(1);
                while (t.getHour() % 3 != 0) t = t.plusHours(1);
                stepHours = 3;
                label = DateTimeFormatter.ofPattern("H'h'");
                break;
            case WEEK:
                t = start.truncatedTo(ChronoUnit.DAYS).plusDays(1);
                stepHours = 24;
                label = DateTimeFormatter.ofPattern("EEE", Locale.getDefault());
                break;
            default:
                t = start.truncatedTo(ChronoUnit.DAYS).plusDays(1);
                while (t.getDayOfMonth() % 5 != 0) t = t.plusDays(1);
                stepHours = 24 * 5;
                label = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault());
        }
        text.setTextSize(sp(8.5f));
        text.setColor(LABEL);
        text.setTextAlign(Paint.Align.CENTER);
        float labelY = bottom + 4 * d + sp(8.5f);
        for (; t.toInstant().toEpochMilli() < t1; t = t.plusHours(stepHours)) {
            float x = x(t.toInstant().toEpochMilli(), left, right, t0, t1);
            canvas.drawLine(x, top, x, bottom, grid);
            // A day's name sits under its middle, a time under its line.
            float lx = span == Stats.Span.WEEK ? x + (right - left) * 12 * History.HOUR_MS / (float) (t1 - t0) : x;
            if (lx < right) canvas.drawText(label.format(t), lx, labelY, text);
        }
        canvas.drawLine(left, bottom, right, bottom, grid);
    }

    // ---- bars --------------------------------------------------------------------------------------

    private void drawBars(Canvas canvas, float left, float right, float top, float bottom) {
        int slots = range == Stats.Range.TODAY ? 24 : range == Stats.Range.DAYS ? 30 : 12;
        double[] totals = new double[slots];
        for (Series s : series) {
            for (Stats.Point p : s.points) {
                int i = slot(p.start);
                if (i >= 0 && i < slots && !Double.isNaN(p.change)) totals[i] += Math.max(0, p.change);
            }
        }
        double max = 0;
        for (double v : totals) max = Math.max(max, v);
        double[] scale = nice(0, Math.max(max, 0.1), 0);
        scale[0] = 0;
        Unit unit = series.get(0).unit;
        valueGrid(canvas, left, right, top, bottom, scale, unit, true, LABEL);
        float slot = (right - left) / slots, bar = slot * 0.64f, radius = Math.min(3 * d, bar / 2);
        double[] base = new double[slots];
        for (int k = 0; k < series.size(); k++) {
            Series s = series.get(k);
            fill.setColor(s.color);
            for (Stats.Point p : s.points) {
                int i = slot(p.start);
                if (i < 0 || i >= slots || Double.isNaN(p.change) || p.change <= 0) continue;
                float x = left + slot * i + (slot - bar) / 2;
                float yLow = y(base[i], scale, top, bottom), yHigh = y(base[i] + p.change, scale, top, bottom);
                base[i] += p.change;
                boolean topmost = Math.abs(base[i] - totals[i]) < 1e-6;
                rect.set(x, yHigh, x + bar, yLow);
                if (topmost && yLow - yHigh > radius) {
                    canvas.drawRoundRect(rect, radius, radius, fill);
                    rect.top += radius;
                }
                canvas.drawRect(rect, fill);
            }
        }
        // Labels: every third hour, every fifth day, every month.
        text.setTextSize(sp(8.5f));
        text.setColor(LABEL);
        text.setTextAlign(Paint.Align.CENTER);
        float labelY = bottom + 4 * d + sp(8.5f);
        LocalDate first = Instant.ofEpochMilli(range.start()).atZone(ZoneId.systemDefault()).toLocalDate();
        for (int i = 0; i < slots; i++) {
            String l = null;
            if (range == Stats.Range.TODAY && i % 3 == 0) l = i + "h";
            else if (range == Stats.Range.DAYS && first.plusDays(i).getDayOfMonth() % 5 == 0) l = String.valueOf(first.plusDays(i).getDayOfMonth());
            else if (range == Stats.Range.MONTHS) l = DateTimeFormatter.ofPattern("MMM", Locale.getDefault()).format(first.plusMonths(i));
            if (l != null) canvas.drawText(l, left + slot * (i + 0.5f), labelY, text);
        }
        canvas.drawLine(left, bottom, right, bottom, grid);
    }

    /** Which bar a period starting at {@code start} is. */
    private int slot(long start) {
        ZonedDateTime t = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault());
        LocalDate first = Instant.ofEpochMilli(range.start()).atZone(ZoneId.systemDefault()).toLocalDate();
        switch (range) {
            case TODAY:
                return t.toLocalDate().equals(first) ? t.getHour() : -1;
            case DAYS:
                return (int) ChronoUnit.DAYS.between(first, t.toLocalDate());
            default:
                return (int) ChronoUnit.MONTHS.between(first.withDayOfMonth(1), t.toLocalDate().withDayOfMonth(1));
        }
    }
}
