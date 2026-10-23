package com.synseria.synema;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Screensaver: the launcher's thin clock and date over what was last watched, dim and slowly
 * moving (MovingBackdrop; the night theme when nothing was), with the house from Home Assistant when it
 * is set up: outside and the TV's room as the top line shows them, with their trends (HouseHeader.column),
 * each with its last day's temperature in a thin curve (Chart), then the other rooms' temperatures and
 * what is out of the ordinary, as Home Assistant pushes them (HaLive); the trends and the curves are
 * read again every five minutes. The clock
 * glides: one pixel every two seconds, bouncing off the screen's edges, too slow to see, so no pixel
 * stays lit the same way and an OLED panel never marks. Enable it with tools/screensaver.sh.
 */
public class ClockDream extends DreamService {

    private static final long DRIFT_MS = 2_000L;
    private static final long HOUSE_MS = 5 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private View content;
    private TextView house;
    private View outside, room;
    /** Each column's last day, under it. */
    private Chart outsideDay, roomDay;
    /** The screensaver on screen, for RemoteKeyService: a button it takes ends the screensaver first. */
    private static ClockDream showing;
    /** The readings beside a place's temperature, larger than the top line's. */
    private static final float FACTS_SP = 14;
    private HomeConfig home;
    private HomeAssistant ha;
    private HaLive live;
    /** Home Assistant answers; when not, the clock alone rather than dashes. */
    private boolean reachable = true;

    /** The trends, as time passes: the history is read again when due (History), shared with the dashboard. */
    private final Runnable trends = new Runnable() {
        @Override
        public void run() {
            java.util.Set<String> sensors = new java.util.HashSet<>();
            if (home.outside != null) Place.of(home.outside, HaLive.STATES).addSensors(sensors);
            if (!home.temperatures.isEmpty()) Place.of(home.temperatures.get(0), HaLive.STATES).addSensors(sensors);
            History.need(ha, sensors, Place.Kind.PRESSURE.spanMs + 30 * 60_000L, () -> showHouse());
            showHouse();
            readDays();
            handler.postDelayed(this, HOUSE_MS);
        }
    };

    private void showHouse() {
        if (house == null) return;
        Map<String, HomeAssistant.State> states = HaLive.STATES;
        findViewById(R.id.dreamPlaces).setVisibility(!reachable || states.isEmpty() ? View.GONE : View.VISIBLE);
        if (home.outside != null) {
            outside.setVisibility(View.VISIBLE);
            HouseHeader.column(this, outside, home, home.outside, states, true, FACTS_SP);
        }
        if (!home.temperatures.isEmpty()) {
            room.setVisibility(View.VISIBLE);
            HouseHeader.column(this, room, home, home.temperatures.get(0), states, false, FACTS_SP);
        }
        List<String> lines = new ArrayList<>();
        List<String> rooms = new ArrayList<>();
        for (int i = 1; i < home.temperatures.size(); i++) {
            String text = HouseText.temperature(home.temperatures.get(i), states);
            if (text != null) rooms.add(text);
        }
        if (!rooms.isEmpty()) lines.add(TextUtils.join(" · ", rooms));
        for (HouseText.Notice n : HouseText.notices(this, home, states, System.currentTimeMillis())) lines.add(n.text);
        house.setText(TextUtils.join("\n", lines));
        house.setVisibility(lines.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** The two columns' temperatures over the last day, from Home Assistant's statistics. */
    private void readDays() {
        String out = home.outside != null ? home.outside.entity : "", in = home.temperatures.isEmpty() ? "" : home.temperatures.get(0).entity;
        List<String> sensors = new ArrayList<>();
        if (!out.isEmpty()) sensors.add(out);
        if (!in.isEmpty()) sensors.add(in);
        Stats.readings(live, sensors, Stats.Span.DAY, read -> {
            day(outsideDay, read.get(out));
            day(roomDay, read.get(in));
        });
    }

    private static void day(Chart chart, List<Stats.Point> points) {
        if (points == null || points.isEmpty()) {
            chart.setVisibility(View.GONE);
            return;
        }
        chart.setVisibility(View.VISIBLE);
        List<Chart.Series> s = new ArrayList<>();
        s.add(new Chart.Series("", (RoomPage.TEMPERATURE & 0x00FFFFFF) | 0xB3000000, Chart.Unit.DEGREES, points));
        chart.lines(Stats.Span.DAY, s);
    }

    /** Ends the screensaver on screen, if one is; true when it did. */
    static boolean end() {
        ClockDream d = showing;
        if (d == null) return false;
        d.finish();
        return true;
    }

    /** Direction of the glide, one pixel per step on each axis. */
    private int dx = 1, dy = 1;

    private final Runnable drift = new Runnable() {
        @Override
        public void run() {
            View parent = (View) content.getParent();
            float maxX = Math.max(0, parent.getWidth() - content.getWidth());
            float maxY = Math.max(0, parent.getHeight() - content.getHeight());
            float x = content.getX() + dx, y = content.getY() + dy;
            if (x <= 0 || x >= maxX) dx = -dx;
            if (y <= 0 || y >= maxY) dy = -dy;
            moveTo(Math.max(0, Math.min(maxX, x)), Math.max(0, Math.min(maxY, y)));
            handler.postDelayed(this, DRIFT_MS);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);
        Posters.keepOnDisk(this);
        setContentView(R.layout.dream_clock);

        content = findViewById(R.id.dreamContent);
        MovingBackdrop backdrop = new MovingBackdrop(this, 4);
        ((android.widget.FrameLayout) content.getParent()).addView(backdrop, 0, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        new Thread(() -> {
            List<TvListings.Item> last = TvListings.watchNext(this, 1);
            String poster = last.isEmpty() ? null : last.get(0).posterUri;
            if (poster == null) {
                Bitmap night = Backdrop.dream(null);
                handler.post(() -> backdrop.show(night));
                return;
            }
            // The dim picture is composed off the main thread, as the dashboard's is.
            Posters.load(this, poster, Backdrop.SCENE_W, bmp -> new Thread(() -> {
                Bitmap dim = Backdrop.dream(bmp);
                handler.post(() -> backdrop.show(dim));
            }, "dream-picture").start());
        }, "dream-backdrop").start();
        house = findViewById(R.id.dreamHouse);
        outside = findViewById(R.id.dreamOutside);
        room = findViewById(R.id.dreamRoom);
        for (View column : new View[]{outside, room}) {
            ((TextView) column.findViewById(R.id.climateTemp)).setTextSize(56);
            ((TextView) column.findViewById(R.id.climateLabel)).setTextSize(13);
            ((TextView) column.findViewById(R.id.climateSky)).setTextSize(13);
        }
        outsideDay = dayUnder(outside);
        roomDay = dayUnder(room);
        home = HomeConfig.load(this);
        ha = HomeAssistant.load(this);
    }

    /** A thin curve under a column, as wide as it. */
    private Chart dayUnder(View column) {
        Chart c = new Chart(this);
        c.bare();
        c.setVisibility(View.GONE);
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, Math.round(30 * d));
        lp.topMargin = Math.round(8 * d);
        ((android.view.ViewGroup) column).addView(c, lp);
        return c;
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        showing = this;
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.postDelayed(drift, DRIFT_MS);
        if (ha == null) return;
        live = new HaLive(ha, new HaLive.Listener() {
            @Override
            public void onStates() {
                showHouse();
            }

            @Override
            public void onReachable(boolean on) {
                reachable = on;
                showHouse();
            }
        });
        live.start();
        live.watch(home.entities());
        handler.post(trends);
    }

    @Override
    public void onDreamingStopped() {
        if (showing == this) showing = null;
        handler.removeCallbacks(drift);
        handler.removeCallbacks(trends);
        if (live != null) live.stop();
        live = null;
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        handler.removeCallbacks(drift);
        handler.removeCallbacks(trends);
        if (live != null) live.stop();
        live = null;
        super.onDetachedFromWindow();
    }

    /** A random start that keeps the whole clock on screen, gliding in a random direction. */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        dx = random.nextBoolean() ? 1 : -1;
        dy = random.nextBoolean() ? 1 : -1;
        moveTo(maxX * (0.1f + 0.8f * random.nextFloat()), maxY * (0.1f + 0.8f * random.nextFloat()));
    }

    private void moveTo(float x, float y) {
        content.setX(x);
        content.setY(y);
    }
}
