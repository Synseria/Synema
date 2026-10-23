package com.synseria.synema;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home Assistant over its REST API, with the address and long-lived token from Secrets. Every call runs
 * on one background thread and answers on the main thread; a failure answers with null, so a dashboard
 * simply shows nothing for Home Assistant while it is unreachable.
 */
final class HomeAssistant {
    private static final String TAG = "TvHome.HA";
    private static final int TIMEOUT_MS = 6000;
    /** A language model behind the conversation agent can take a while to answer. */
    private static final int SLOW_TIMEOUT_MS = 45_000;
    /** One thread for every instance: requests stay ordered and the TV never runs more than one at a time. */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    /** The conversation agent's own thread: a slow answer must not hold up states or camera frames. */
    private static final ExecutorService CHAT = Executors.newSingleThreadExecutor();

    interface Callback<T> {
        void done(T result);
    }

    /** One entity as Home Assistant reports it. */
    static final class State {
        final String id;
        final String state;
        final JSONObject attrs;
        /** When the state last changed (epoch ms), 0 when Home Assistant did not say. */
        final long changed;

        State(JSONObject json) {
            this(json.optString("entity_id"), json.optString("state"), json.optJSONObject("attributes"),
                    epochMs(json.optString("last_changed")));
        }

        State(String id, String state, JSONObject attrs, long changed) {
            this.id = id;
            this.state = state;
            this.attrs = attrs != null ? attrs : new JSONObject();
            this.changed = changed;
        }

        private static long epochMs(String iso) {
            try {
                return iso.isEmpty() ? 0 : OffsetDateTime.parse(iso).toInstant().toEpochMilli();
            } catch (DateTimeParseException e) {
                return 0;
            }
        }

        String name() {
            return attrs.optString("friendly_name", id);
        }

        boolean available() {
            return !"unavailable".equals(state) && !"unknown".equals(state);
        }

        /** Lit, open or at work: a tile shows it on. */
        boolean on() {
            return "on".equals(state) || "open".equals(state) || "cleaning".equals(state);
        }
    }

    private final String base;
    private final String token;
    private final Handler main = new Handler(Looper.getMainLooper());

    private HomeAssistant(String base, String token) {
        this.base = base.replaceAll("/+$", "");
        this.token = token;
    }

    /** For sockets Home Assistant proxies (HaSocket.proxied), which sign in with a header. */
    String token() {
        return token;
    }

    /** A path Home Assistant handed out (a stream's signed URL) as a full address. */
    String url(String path) {
        return base + path;
    }

    /** The configured instance, or null when the secrets lack Home Assistant's address or token. */
    static HomeAssistant load(Context ctx) {
        JSONObject json = Secrets.read(ctx);
        String url = json.optString("ha_url"), token = json.optString("ha_token");
        return url.isEmpty() || token.isEmpty() ? null : new HomeAssistant(url, token);
    }

    /** A live connection for pushed events; call start() on it, and close() when done. */
    HaSocket socket(HaSocket.Listener listener) {
        return new HaSocket(base, token, listener);
    }

    /**
     * Calls a service such as "light/toggle", on an entity when one is given, with any extra fields;
     * cb gets whether Home Assistant accepted it.
     */
    void call(String service, String entityId, JSONObject data, Callback<Boolean> cb) {
        IO.execute(() -> {
            boolean ok;
            try {
                JSONObject body = data != null ? new JSONObject(data.toString()) : new JSONObject();
                if (entityId != null && !entityId.isEmpty()) body.put("entity_id", entityId);
                ok = request("POST", "/api/services/" + service, body.toString()) != null;
            } catch (Exception e) {
                ok = false;
            }
            final boolean result = ok;
            if (cb != null) main.post(() -> cb.done(result));
        });
    }

    /** What the conversation agent answered: its words, and the id to continue the same conversation. */
    static final class Reply {
        final String speech;
        final String conversationId;
        /** The agent asked something back and waits for the answer (Home Assistant 2025.4+). */
        final boolean expectsAnswer;

        Reply(String speech, String conversationId, boolean expectsAnswer) {
            this.speech = speech;
            this.conversationId = conversationId;
            this.expectsAnswer = expectsAnswer;
        }
    }

    /** Sets an entity's state in Home Assistant (one this TV owns, such as sensor.synema); fire and forget. */
    void setState(String entity, String state, JSONObject attributes) {
        IO.execute(() -> {
            try {
                request("POST", "/api/states/" + entity, new JSONObject().put("state", state).put("attributes", attributes).toString());
            } catch (Exception e) {
                Log.w(TAG, entity + ": " + e);
            }
        });
    }

    /** Sends a sentence to a conversation agent; cb gets null when Home Assistant did not answer. */
    void converse(String text, String agent, String language, String conversationId, Callback<Reply> cb) {
        CHAT.execute(() -> {
            Reply reply = null;
            try {
                JSONObject req = new JSONObject().put("text", text);
                if (!agent.isEmpty()) req.put("agent_id", agent);
                if (!language.isEmpty()) req.put("language", language);
                if (conversationId != null) req.put("conversation_id", conversationId);
                byte[] body = request("POST", "/api/conversation/process", req.toString());
                if (body != null) {
                    JSONObject res = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    JSONObject speech = res.optJSONObject("response");
                    speech = speech != null ? speech.optJSONObject("speech") : null;
                    speech = speech != null ? speech.optJSONObject("plain") : null;
                    reply = new Reply(speech != null ? speech.optString("speech") : "", res.optString("conversation_id", null),
                            res.optBoolean("continue_conversation"));
                }
            } catch (Exception e) {
                Log.w(TAG, "conversation: " + e);
            }
            final Reply r = reply;
            main.post(() -> cb.done(r));
        });
    }

    /**
     * Each sensor's states since {@code sinceMs}, oldest first, in one request: the first is the one it
     * had at that time. Values only, no attributes; those Home Assistant has nothing for are left out,
     * and the map is empty on failure.
     */
    void histories(List<String> sensors, long sinceMs, Callback<Map<String, List<State>>> cb) {
        IO.execute(() -> {
            Map<String, List<State>> out = new HashMap<>();
            byte[] body = request("GET", "/api/history/period/" + Instant.ofEpochMilli(sinceMs)
                    + "?filter_entity_id=" + String.join(",", sensors) + "&minimal_response&no_attributes", null);
            try {
                JSONArray all = body == null ? new JSONArray() : new JSONArray(new String(body, StandardCharsets.UTF_8));
                for (int i = 0; i < all.length(); i++) {
                    JSONArray series = all.optJSONArray(i);
                    if (series == null || series.length() == 0) continue;
                    // Only the first state of a minimal response names its entity.
                    String id = series.getJSONObject(0).optString("entity_id");
                    List<State> states = new ArrayList<>();
                    for (int j = 0; j < series.length(); j++) states.add(new State(series.getJSONObject(j)));
                    if (!id.isEmpty()) out.put(id, states);
                }
            } catch (Exception e) {
                Log.w(TAG, "history: " + e);
            }
            main.post(() -> cb.done(out));
        });
    }

    /** The answer of a template returning a JSON array; empty on failure. */
    void templateArray(String template, Callback<JSONArray> cb) {
        IO.execute(() -> {
            JSONArray out = new JSONArray();
            try {
                byte[] body = request("POST", "/api/template", new JSONObject().put("template", template).toString());
                if (body != null) out = new JSONArray(new String(body, StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.w(TAG, "template: " + e);
            }
            JSONArray result = out;
            main.post(() -> cb.done(result));
        });
    }

    /** A camera's current frame, downsampled to about maxWidth pixels wide. */
    void cameraFrame(String cameraId, int maxWidth, Callback<Bitmap> cb) {
        IO.execute(() -> {
            byte[] jpeg = request("GET", "/api/camera_proxy/" + cameraId, null);
            Bitmap bmp = jpeg == null ? null : decode(jpeg, maxWidth);
            main.post(() -> cb.done(bmp));
        });
    }

    static Bitmap decode(byte[] data, int maxWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(data, 0, data.length, o);
    }

    /** A long-lived GET (a camera stream) on the caller's own thread; the caller disconnects it. */
    HttpURLConnection openStream(String path) throws java.io.IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        c.setUseCaches(false);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(15_000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        if (c.getResponseCode() / 100 != 2) {
            c.disconnect();
            throw new java.io.IOException(path + ": HTTP " + c.getResponseCode());
        }
        return c;
    }

    /** The response body, or null on any failure. Runs on IO only. */
    private byte[] request(String method, String path, String json) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + path).openConnection();
            c.setUseCaches(false);   // states and camera frames are always the live ones, never the disk cache's (Posters)
            c.setRequestMethod(method);
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(path.startsWith("/api/conversation") ? SLOW_TIMEOUT_MS : TIMEOUT_MS);
            c.setRequestProperty("Authorization", "Bearer " + token);
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(json.getBytes(StandardCharsets.UTF_8));
                }
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                Log.w(TAG, method + " " + path + ": HTTP " + code);
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                return readAll(in);
            }
        } catch (Exception e) {
            Log.w(TAG, method + " " + path + ": " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }


    static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
