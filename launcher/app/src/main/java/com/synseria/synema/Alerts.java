package com.synseria.synema;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.TypefaceSpan;
import android.util.Log;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Home Assistant alerts over any app (home.json "alerts"): a camera alert shows the camera live in the
 * top-right corner (CameraStream), pauses the film and offers Enlarge, an optional Reply and Close;
 * other events show a small notice. Hosted by RemoteKeyService, the one component
 * that stays alive and may draw over other apps (an accessibility overlay needs no extra permission).
 * Events arrive over HaSocket as state triggers, so nothing polls. Home Assistant can also send one
 * from an automation, with the event {@value #NOTIFY_EVENT} and data {@code title}, optional
 * {@code message}, {@code camera} (an entity, shown live) and {@code pause}; and have it play a title
 * or open an app with {@value #PLAY_EVENT} (Launch).
 */
final class Alerts implements HaSocket.Listener {
    private static final String TAG = "TvHome.Alerts";
    private static final int SHOW_SECONDS = 30;
    private static final int NOTICE_SECONDS = 8;
    /** The AirPlay code stays this long at most, should the receiver never say it is given. */
    private static final int PIN_SECONDS = 120;
    /** The same alert is not shown again within this delay (people walking past a camera). */
    private static final long COOLDOWN_MS = 120_000;
    static final String NOTIFY_EVENT = "clean_tv_notify";
    /** Home Assistant asks the TV to play a title or open an app: data {@code title}, optional {@code app}. */
    static final String PLAY_EVENT = "clean_tv_play";
    /** The subscriptions to NOTIFY_EVENT and PLAY_EVENT, on the current connection. */
    private volatile int notifySubscription = -1, playSubscription = -1;

    private final Context ctx;
    private final HomeAssistant ha;
    private final HomeConfig config;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SparseArray<HomeConfig.Alert> bySubscription = new SparseArray<>();
    private final Map<HomeConfig.Alert, Long> lastShown = new HashMap<>();
    private HaSocket socket;
    private View shown;
    private HomeConfig.Alert shownAlert;
    /** The AirPlay code shown, and its card; null when none. */
    private String shownPin;
    private View pinView;
    /** The code closed (Back, or PIN_SECONDS passed): not shown again while the receiver waits for it. */
    private String closedPin;
    private CameraStream stream;
    private CameraRtc rtc;
    private boolean enlarged;
    private boolean pausedMedia;
    private int secondsLeft;

    Alerts(Context ctx, HomeAssistant ha, HomeConfig config) {
        this.ctx = ctx;
        this.ha = ha;
        this.config = config;
        this.wm = ctx.getSystemService(WindowManager.class);
    }

    void start() {
        socket = ha.socket(this);
        socket.start();
    }

    void stop() {
        if (socket != null) socket.close();
        main.post(() -> {
            dismiss();
            dismissPin();
        });
    }

    // ---- HaSocket.Listener (socket thread) -------------------------------------------------------

    @Override
    public void onReady(HaSocket s) {
        try {
            notifySubscription = s.send(new JSONObject().put("type", "subscribe_events").put("event_type", NOTIFY_EVENT));
            playSubscription = s.send(new JSONObject().put("type", "subscribe_events").put("event_type", PLAY_EVENT));
        } catch (Exception e) {
            Log.w(TAG, "cannot subscribe to " + NOTIFY_EVENT + ": " + e);
        }
        synchronized (bySubscription) {
            bySubscription.clear();
            for (HomeConfig.Alert a : config.alerts) {
                try {
                    JSONObject trigger = new JSONObject().put("platform", "state").put("entity_id", a.entity);
                    if (!a.to.isEmpty()) trigger.put("to", a.to);
                    if (!a.from.isEmpty()) trigger.put("from", a.from);
                    int id = s.send(new JSONObject().put("type", "subscribe_trigger").put("trigger", trigger));
                    bySubscription.put(id, a);
                } catch (Exception e) {
                    Log.w(TAG, "cannot subscribe to " + a.entity + ": " + e);
                }
            }
        }
    }

    @Override
    public void onMessage(JSONObject msg) {
        if (!"event".equals(msg.optString("type"))) {
            if ("result".equals(msg.optString("type")) && !msg.optBoolean("success", true)) {
                Log.w(TAG, "subscription refused: " + msg.optJSONObject("error"));
            }
            return;
        }
        if (msg.optInt("id") == playSubscription) {
            JSONObject event = msg.optJSONObject("event");
            JSONObject data = event != null ? event.optJSONObject("data") : null;
            if (data != null) play(data.optString("title"), data.optString("app"));
            return;
        }
        if (msg.optInt("id") == notifySubscription) {
            JSONObject event = msg.optJSONObject("event");
            JSONObject data = event != null ? event.optJSONObject("data") : null;
            if (data != null) main.post(() -> show(new HomeConfig.Alert(data), data.optString("message")));
            return;
        }
        HomeConfig.Alert a;
        synchronized (bySubscription) {
            a = bySubscription.get(msg.optInt("id"));
        }
        if (a != null) main.post(() -> show(a, ""));
    }

    /** On the socket thread, which may read the TV provider: the title or app, opened, or a notice why not. */
    private void play(String title, String app) {
        Launch.Target t = Launch.find(ctx, title + " " + app, app);
        main.post(() -> {
            if (t != null) Launch.open(ctx, t);
            else notice(ctx.getString(R.string.play_not_found, title.isEmpty() ? app : title));
        });
    }

    /** The camera's go2rtc stream, as home.json's cameras name it. */
    private String streamOf(String camera) {
        for (HomeConfig.Labelled c : config.cameras) if (c.entity.equals(camera)) return c.stream;
        return camera.substring(camera.indexOf('.') + 1);
    }

    /** A few seconds' notice bottom left. */
    private void notice(String text) {
        try {
            show(new HomeConfig.Alert(new JSONObject().put("title", text)), "");
        } catch (org.json.JSONException ignored) {
        }
    }

    // ---- overlay (main thread) -------------------------------------------------------------------

    /**
     * The AirPlay receiver waits for its code: a light card top right, where the alerts show,
     * never taking the remote, until the receiver has it ({@code code} null), Back closes it
     * (RemoteKeyService) or PIN_SECONDS have passed.
     */
    void pin(String code) {
        if (code == null) {
            closedPin = null;
            dismissPin();
            return;
        }
        // The receiver's notification changes for other reasons too.
        if (code.equals(shownPin) || code.equals(closedPin)) return;
        dismissPin();
        View v = LayoutInflater.from(ctx).inflate(R.layout.overlay_pin, null);
        LinearLayout digits = v.findViewById(R.id.digits);
        float d = ctx.getResources().getDisplayMetrics().density;
        for (int i = 0; i < code.length(); i++) {
            TextView digit = new TextView(ctx);
            digit.setText(code.substring(i, i + 1));
            digit.setTextSize(26);
            digit.setTextColor(0xFF1C1C1E);
            digit.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            digit.setGravity(Gravity.CENTER);
            digit.setBackgroundResource(R.drawable.pin_digit_bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (36 * d), (int) (46 * d));
            if (i > 0) lp.setMarginStart((int) (6 * d));
            digits.addView(digit, lp);
        }
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = (int) (28 * d);
        lp.y = (int) (28 * d);
        try {
            wm.addView(v, lp);
        } catch (Exception e) {
            Log.w(TAG, "cannot show the AirPlay code: " + e);
            return;
        }
        pinView = v;
        shownPin = code;
        main.postDelayed(pinTimeout, PIN_SECONDS * 1000L);
    }

    private final Runnable pinTimeout = this::closePin;

    boolean pinShown() {
        return shownPin != null;
    }

    /** Closes the code's card; it does not come back while the receiver waits for that code. */
    void closePin() {
        closedPin = shownPin;
        dismissPin();
    }

    private void dismissPin() {
        main.removeCallbacks(pinTimeout);
        if (pinView != null) {
            try {
                wm.removeView(pinView);
            } catch (Exception ignored) {
            }
        }
        pinView = null;
        shownPin = null;
    }

    private void show(HomeConfig.Alert a, String message) {
        // The cooldown is for home.json's alerts; Home Assistant's own notifications always show.
        if (config.alerts.contains(a)) {
            long now = SystemClock.elapsedRealtime();
            Long last = lastShown.get(a);
            if (last != null && now - last < COOLDOWN_MS) return;
            lastShown.put(a, now);
        }
        dismiss();
        boolean live = !a.camera.isEmpty();
        View v = LayoutInflater.from(ctx).inflate(R.layout.overlay_alert, null);
        TextView title = v.findViewById(R.id.title);
        if (message.isEmpty()) {
            title.setText(a.title);
        } else {
            SpannableString text = new SpannableString(a.title + "\n" + message);
            int start = a.title.length() + 1;
            text.setSpan(new RelativeSizeSpan(0.8f), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new TypefaceSpan("sans-serif"), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            title.setText(text);
            title.setMaxWidth((int) (420 * ctx.getResources().getDisplayMetrics().density));
        }
        v.findViewById(R.id.media).setVisibility(live ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.buttons).setVisibility(live ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.btnClose).setOnClickListener(x -> dismiss());
        v.findViewById(R.id.btnFull).setOnClickListener(x -> setEnlarged(!enlarged));
        TextView reply = v.findViewById(R.id.btnReply);
        if (a.reply != null && live) {
            reply.setText(a.replyLabel);
            reply.setVisibility(View.VISIBLE);
            reply.setOnClickListener(x -> {
                Actions.run(ctx, ha, a.reply, null);
                dismiss();
            });
        }
        v.setOnKeyListener((x, code, e) -> {
            if (code == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) dismiss();
            return code == KeyEvent.KEYCODE_BACK;
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // A notice never takes the remote away from the app; a live alert does, to be answered.
                live ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        float d = ctx.getResources().getDisplayMetrics().density;
        lp.gravity = live ? Gravity.TOP | Gravity.END : Gravity.BOTTOM | Gravity.START;
        lp.x = (int) (28 * d);
        lp.y = (int) (28 * d);
        try {
            wm.addView(v, lp);
        } catch (Exception e) {
            Log.w(TAG, "cannot show alert: " + e);
            return;
        }
        shown = v;
        shownAlert = a;
        enlarged = false;
        if (live) {
            if (a.pause) pausedMedia = Media.key(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE);
            v.findViewById(R.id.btnFull).requestFocus();
            ImageView frame = v.findViewById(R.id.frame);
            // Decoded at the full-screen width, so enlarging needs no new stream.
            stream = new CameraStream(ha, a.camera, ctx.getResources().getDisplayMetrics().widthPixels / 2, frame::setImageBitmap);
            stream.start();
            org.webrtc.SurfaceViewRenderer video = v.findViewById(R.id.video);
            video.setZOrderMediaOverlay(true);   // over the alert's own window, which sits over a film
            // Silent: the corner's camera never speaks over the film.
            rtc = new CameraRtc(ctx, ha, streamOf(a.camera), null, video, new CameraRtc.Listener() {
                @Override
                public void onVideo() {
                    if (stream != null) stream.stop();
                    stream = null;
                    frame.setVisibility(View.GONE);
                }

                @Override
                public void onFailed() {
                    // The pictures carry on.
                    if (rtc != null) rtc.release();
                    rtc = null;
                }

                @Override
                public void onMicLevel(float level) {
                }
            });
            rtc.start();
        }
        secondsLeft = live ? SHOW_SECONDS : NOTICE_SECONDS;
        main.post(countdown);
    }

    private final Runnable countdown = new Runnable() {
        @Override
        public void run() {
            if (shown == null) return;
            if (secondsLeft <= 0) {
                dismiss();
                return;
            }
            boolean live = !shownAlert.camera.isEmpty();
            ((TextView) shown.findViewById(R.id.countdown)).setText(live ? ctx.getString(R.string.alert_closes_in, secondsLeft) : "");
            secondsLeft--;
            main.postDelayed(this, 1000);
        }
    };

    /** The card in the corner, or the whole screen; enlarged, it stays until Close or Back. */
    private void setEnlarged(boolean on) {
        if (shown == null) return;
        enlarged = on;
        float d = ctx.getResources().getDisplayMetrics().density;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) shown.getLayoutParams();
        lp.width = on ? WindowManager.LayoutParams.MATCH_PARENT : WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = on ? WindowManager.LayoutParams.MATCH_PARENT : WindowManager.LayoutParams.WRAP_CONTENT;
        lp.x = on ? 0 : (int) (28 * d);
        lp.y = on ? 0 : (int) (28 * d);
        ImageView frame = shown.findViewById(R.id.frame);
        shown.findViewById(R.id.media).getLayoutParams().height = on ? ctx.getResources().getDisplayMetrics().heightPixels - (int) (110 * d) : (int) (180 * d);
        frame.setScaleType(on ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        ((TextView) shown.findViewById(R.id.btnFull)).setText(on ? R.string.alert_shrink : R.string.alert_full_screen);
        if (on) {
            main.removeCallbacks(countdown);
            ((TextView) shown.findViewById(R.id.countdown)).setText("");
        }
        wm.updateViewLayout(shown, lp);
    }

    private void dismiss() {
        main.removeCallbacks(countdown);
        if (stream != null) stream.stop();
        stream = null;
        if (rtc != null) rtc.release();
        rtc = null;
        if (shown != null) {
            try {
                wm.removeView(shown);
            } catch (Exception ignored) {
            }
        }
        shown = null;
        shownAlert = null;
        if (pausedMedia) Media.key(ctx, KeyEvent.KEYCODE_MEDIA_PLAY);
        pausedMedia = false;
    }
}
