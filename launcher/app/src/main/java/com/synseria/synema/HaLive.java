package com.synseria.synema;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home Assistant's states as they change, pushed over its WebSocket API (HaSocket): one subscription
 * (subscribe_entities) to the entities a screen shows, answered at once with their states, then with
 * each change. The dashboard and the screensaver hold one while they show; nothing polls. The states
 * are kept for the process (STATES): a screen coming back draws the last ones at once. Commands that
 * only the WebSocket API has (statistics, the energy settings) go through it too (request).
 */
final class HaLive implements HaSocket.Listener {
    private static final String TAG = "TvHome.Live";
    /** Changes arriving together are drawn together: at most one redraw in this time. */
    private static final long CALM_MS = 300;

    interface Listener {
        /** On the main thread: states changed; STATES has them. */
        void onStates();

        /** On the main thread: Home Assistant answers, or does not (it is asked again by itself). */
        void onReachable(boolean reachable);
    }

    /** Every state received, by entity. Main thread only. */
    static final Map<String, HomeAssistant.State> STATES = new HashMap<>();

    private final HomeAssistant ha;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Sends asked from the main thread go out here: writing to the socket is network. */
    private final ExecutorService out = Executors.newSingleThreadExecutor();
    /** The states as the socket thread knows them, which the changes apply to. */
    private final Map<String, HomeAssistant.State> live = new HashMap<>();
    private HaSocket socket;
    private volatile boolean stopped, connected;
    /** The entities watched, set on the main thread, subscribed to from the socket's threads. */
    private volatile Set<String> watched = Collections.emptySet();
    /** The current subscription's id, or -1; guarded by this. */
    private int subscription = -1;
    /** Requests sent, by id, waiting for their result; and those asked before the connection. Guarded by this. */
    private final Map<Integer, HomeAssistant.Callback<JSONObject>> answers = new HashMap<>();
    private final List<Object[]> beforeConnection = new ArrayList<>();
    private boolean redrawPosted;

    HaLive(HomeAssistant ha, Listener listener) {
        this.ha = ha;
        this.listener = listener;
    }

    void start() {
        socket = ha.socket(this);
        socket.start();
    }

    void stop() {
        stopped = true;
        if (socket != null) socket.close();
        out.shutdown();
        main.removeCallbacks(redraw);
    }

    /**
     * Sends a command; {@code cb} gets its result on the main thread, or null when Home Assistant
     * refused it or the connection dropped first. Asked before the connection, it waits for it.
     */
    void request(JSONObject command, HomeAssistant.Callback<JSONObject> cb) {
        if (stopped) return;
        out.execute(() -> send(command, cb));
    }

    /** On the send thread or the socket's. */
    private synchronized void send(JSONObject command, HomeAssistant.Callback<JSONObject> cb) {
        if (!connected) {
            beforeConnection.add(new Object[]{command, cb});
            return;
        }
        try {
            answers.put(socket.send(command), cb);
        } catch (Exception e) {
            Log.w(TAG, "request: " + e);
            answer(cb, null);
        }
    }

    private void answer(HomeAssistant.Callback<JSONObject> cb, JSONObject result) {
        main.post(() -> {
            if (!stopped) cb.done(result);
        });
    }

    /** From now on, these entities: a new subscription when they changed. Main thread. */
    void watch(Collection<String> ids) {
        Set<String> set = new HashSet<>(ids);
        if (set.equals(watched)) return;
        watched = set;
        if (!stopped) out.execute(this::subscribe);
    }

    /** Replaces the subscription with one to {@code watched}; on the socket's thread or the send thread. */
    private synchronized void subscribe() {
        HaSocket s = socket;
        if (s == null || !connected || stopped) return;
        try {
            if (subscription >= 0) s.send(new JSONObject().put("type", "unsubscribe_events").put("subscription", subscription));
            subscription = -1;
            if (!watched.isEmpty()) {
                subscription = s.send(new JSONObject().put("type", "subscribe_entities").put("entity_ids", new JSONArray(watched)));
            }
        } catch (Exception e) {
            Log.w(TAG, "subscribe: " + e);
        }
    }

    // ---- HaSocket.Listener (socket thread) -------------------------------------------------------

    @Override
    public void onReady(HaSocket s) {
        List<Object[]> waiting;
        synchronized (this) {
            connected = true;
            subscription = -1;   // a new connection has none
            waiting = new ArrayList<>(beforeConnection);
            beforeConnection.clear();
        }
        subscribe();
        for (Object[] w : waiting) {
            @SuppressWarnings("unchecked") HomeAssistant.Callback<JSONObject> cb = (HomeAssistant.Callback<JSONObject>) w[1];
            send((JSONObject) w[0], cb);
        }
        main.post(() -> {
            if (!stopped) listener.onReachable(true);
        });
    }

    @Override
    public void onLost() {
        List<HomeAssistant.Callback<JSONObject>> unanswered;
        synchronized (this) {
            connected = false;
            unanswered = new ArrayList<>(answers.values());
            answers.clear();
        }
        for (HomeAssistant.Callback<JSONObject> cb : unanswered) answer(cb, null);
        main.post(() -> {
            if (!stopped) listener.onReachable(false);
        });
    }

    /**
     * An event of the subscription: "a" the full states (on subscribing), "c" what changed in some
     * ("+" added or changed, "-" attributes gone), "r" entities gone.
     */
    @Override
    public void onMessage(JSONObject msg) {
        if ("result".equals(msg.optString("type"))) {
            boolean ok = msg.optBoolean("success", true);
            if (!ok) Log.w(TAG, "refused: " + msg.optJSONObject("error"));
            HomeAssistant.Callback<JSONObject> cb;
            synchronized (this) {
                cb = answers.remove(msg.optInt("id", -2));
            }
            if (cb != null) answer(cb, ok ? msg : null);
            return;
        }
        if (!"event".equals(msg.optString("type"))) return;
        JSONObject event = msg.optJSONObject("event");
        if (event == null) return;
        Map<String, HomeAssistant.State> changed = new HashMap<>();
        List<String> removed = new ArrayList<>();
        synchronized (this) {
            // Waits for subscribe() to know its id: the first event can come before send() returns.
            if (msg.optInt("id", -2) != subscription) return;
            JSONObject added = event.optJSONObject("a");
            for (Iterator<String> it = added != null ? added.keys() : Collections.emptyIterator(); it.hasNext(); ) {
                String id = it.next();
                changed.put(id, full(id, added.optJSONObject(id)));
            }
            JSONObject diffs = event.optJSONObject("c");
            for (Iterator<String> it = diffs != null ? diffs.keys() : Collections.emptyIterator(); it.hasNext(); ) {
                String id = it.next();
                HomeAssistant.State old = live.get(id);
                if (old != null) changed.put(id, apply(old, diffs.optJSONObject(id)));
            }
            JSONArray gone = event.optJSONArray("r");
            for (int i = 0; gone != null && i < gone.length(); i++) removed.add(gone.optString(i));
            live.putAll(changed);
            for (String id : removed) live.remove(id);
        }
        boolean all = event.has("a");
        // setprop log.tag.TvHome.Live DEBUG: what is pushed.
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, (all ? "states " : "changed ") + changed.keySet() + " gone " + removed);
        main.post(() -> {
            if (stopped) return;
            STATES.putAll(changed);
            for (String id : removed) STATES.remove(id);
            if (redrawPosted) return;
            redrawPosted = true;
            // The first states at once; changes, gathered.
            main.postDelayed(redraw, all ? 0 : CALM_MS);
        });
    }

    private final Runnable redraw = this::redraw;

    private void redraw() {
        redrawPosted = false;
        if (!stopped) listener.onStates();
    }

    /** A compressed state: s, a, lc (seconds). */
    private static HomeAssistant.State full(String id, JSONObject c) {
        if (c == null) c = new JSONObject();
        return new HomeAssistant.State(id, c.optString("s"), c.optJSONObject("a"), Math.round(c.optDouble("lc", 0) * 1000));
    }

    private static HomeAssistant.State apply(HomeAssistant.State old, JSONObject diff) {
        String state = old.state;
        long lastChanged = old.changed;
        JSONObject attrs;
        try {
            attrs = new JSONObject(old.attrs.toString());
        } catch (org.json.JSONException e) {
            attrs = new JSONObject();
        }
        JSONObject plus = diff != null ? diff.optJSONObject("+") : null;
        if (plus != null) {
            if (plus.has("s")) state = plus.optString("s");
            if (plus.has("lc")) lastChanged = Math.round(plus.optDouble("lc") * 1000);
            JSONObject a = plus.optJSONObject("a");
            for (Iterator<String> it = a != null ? a.keys() : Collections.emptyIterator(); it.hasNext(); ) {
                String k = it.next();
                try {
                    attrs.put(k, a.opt(k));
                } catch (org.json.JSONException ignored) {
                }
            }
        }
        JSONObject minus = diff != null ? diff.optJSONObject("-") : null;
        JSONArray goneAttrs = minus != null ? minus.optJSONArray("a") : null;
        for (int i = 0; goneAttrs != null && i < goneAttrs.length(); i++) attrs.remove(goneAttrs.optString(i));
        return new HomeAssistant.State(old.id, state, attrs, lastChanged);
    }
}
