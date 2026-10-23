package com.synseria.synema;

import android.content.Context;
import android.util.Log;
import android.util.SparseArray;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the dashboard shows from Home Assistant and what the remote's buttons do: res/raw/home.json, or
 * {@code home.json} in the app's external files directory when one was pushed, which replaces it whole.
 * Entity ids only; the address and token are in Secrets.
 */
final class HomeConfig {
    private static final String TAG = "TvHome.HomeConfig";
    static final String FILE_NAME = "home.json";

    /**
     * Opens an app, opens the assistant, opens the sources (SourcesPage), or calls a Home Assistant service ("light/toggle") on an entity,
     * with optional extra service fields in {@code data} (a notify message, for instance).
     */
    static final class Action {
        final String app;
        final boolean assistant;
        final boolean sources;
        final String service;
        final String entity;
        final JSONObject data;

        Action(JSONObject j) {
            app = j.optString("app");
            assistant = j.optBoolean("assistant");
            sources = j.optBoolean("sources");
            service = j.optString("service");
            entity = j.optString("entity");
            data = j.optJSONObject("data");
        }

        /** Runs on the TV without Home Assistant: an app, the assistant screen or the sources. */
        boolean isLocal() {
            return !app.isEmpty() || assistant || sources;
        }
    }

    /** A named button: a Home Assistant tile or shortcut. */
    static final class Item {
        final String name;
        final Action action;

        Item(JSONObject j) {
            name = j.optString("name");
            action = new Action(j);
        }

        /** The press that suits a room's thing (Rooms): a vacuum starts, anything else toggles. */
        static Item forEntity(String entity, String name) {
            String service = entity.startsWith("vacuum.") ? "vacuum/start" : "homeassistant/toggle";
            try {
                return new Item(new JSONObject().put("name", name).put("service", service).put("entity", entity));
            } catch (org.json.JSONException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** A camera and the name it is shown under. */
    static final class Labelled {
        final String entity;
        final String label;
        /**
         * A camera's go2rtc stream for live video (Frigate names them after the camera; a lighter
         * "_sub" one holds better over WebRTC); default: the entity's name.
         */
        final String stream;
        /**
         * Its go2rtc stream for the sound both ways (listening, and talking to its speaker); default:
         * the entity's name, the camera's main stream, which Frigate gives the sound in Opus and the
         * speaker's back channel. A "_sub" stream has neither.
         */
        final String sound;

        Labelled(JSONObject j) {
            entity = j.optString("entity");
            label = j.optString("label");
            String name = entity.substring(entity.indexOf('.') + 1);
            stream = j.optString("stream", name);
            sound = j.optString("sound", name);
        }
    }

    /**
     * A place's readings: {@code entity} is its temperature; humidity, pressure, CO₂ and noise are
     * optional sensors, empty when not set.
     */
    static final class Climate {
        final String entity;
        final String label;
        final String humidity, pressure, co2, noise;

        Climate(JSONObject j) {
            entity = j.optString("entity");
            label = j.optString("label");
            humidity = j.optString("humidity");
            pressure = j.optString("pressure");
            co2 = j.optString("co2");
            noise = j.optString("noise");
        }

        void addEntities(Set<String> ids) {
            for (String id : new String[]{entity, humidity, pressure, co2, noise}) if (!id.isEmpty()) ids.add(id);
        }
    }

    /**
     * Something worth a word only when out of the ordinary, and read nowhere else: the entity's state in
     * words, shown while every condition it sets holds. An entry without any condition always shows.
     */
    static final class Status {
        final String entity;
        /** Put before the state's words; empty: the entity's name in Home Assistant. */
        final String label;
        /** The states in words; a state left out reads as Home Assistant has it. */
        final Map<String, String> states;
        /** Shows only in one of these states; empty: any. */
        final Set<String> show;
        /** Shows only once the state has held this long (ms); 0: at once. */
        final long forMs;
        /** Shows only this long (ms) after the state changed; 0: for as long as it lasts. */
        final long withinMs;
        /** Red (a problem) in these states, or in all with {@code "alert": true}; otherwise violet (an activity). */
        final Set<String> alert;
        final boolean alertAlways;

        Status(JSONObject j) {
            entity = j.optString("entity");
            label = j.optString("label");
            states = strings(j.optJSONObject("states"));
            show = new HashSet<>(list(j.optJSONArray("show")));
            forMs = j.optLong("for") * 1000;
            withinMs = j.optLong("within") * 1000;
            alert = new HashSet<>(list(j.optJSONArray("alert")));
            alertAlways = j.optBoolean("alert");
        }

        /** Whether it shows now, for a state that changed at {@code changedMs} (0: unknown, long ago). */
        boolean shows(String state, long changedMs, long nowMs) {
            if (!show.isEmpty() && !show.contains(state)) return false;
            long age = nowMs - changedMs;
            return age >= forMs && (withinMs == 0 || age < withinMs);
        }

        boolean alert(String state) {
            return alertAlways || alert.contains(state);
        }
    }

    /** Something Home Assistant reports that must show at once, over any app (Alerts). */
    static final class Alert {
        final String entity;
        /** Required new state, and optional previous state; empty matches any. */
        final String to;
        final String from;
        final String title;
        /** Camera shown live in the alert; empty for a small notice that closes by itself. */
        final String camera;
        /** Pause what is playing while the alert is open. */
        final boolean pause;
        /** Optional "Reply" button: its label and action (an announcement on a speaker, say). */
        final String replyLabel;
        final Action reply;

        Alert(JSONObject j) {
            entity = j.optString("entity");
            to = j.optString("to");
            from = j.optString("from");
            title = j.optString("title");
            camera = j.optString("camera");
            pause = j.optBoolean("pause");
            JSONObject r = j.optJSONObject("reply");
            replyLabel = r != null ? r.optString("label") : "";
            reply = r != null ? new Action(r) : null;
        }
    }

    /** The sky: a weather entity, whose state is the condition ("rainy"). */
    final String weather;
    /** Readings outside; null when not set. */
    final Climate outside;
    /** The rooms' readings, the TV's own room first. */
    final List<Climate> temperatures;
    /** The house's electricity: live power (W or VA), the power subscribed (kVA or VA), the subscription's
     *  price a month (€, added to what the Energy dashboard counts), and the EDF Tempo colour (bleu, blanc,
     *  rouge) of today and tomorrow; any may be empty. */
    final String energyPower, energySubscribed, energySubscription, energyTempo, energyTempoTomorrow;
    /** The power not to go over (the subscription's, less a margin); NaN when not set. */
    final double energyLimit;
    /** What is out of the ordinary: capsules on the dashboard's top line, lines in the screensaver. */
    final List<Status> status;
    /** Cameras in the panel, the first shown; OK opens them full screen, Left and Right switch. */
    final List<Labelled> cameras;
    final List<Item> tiles;
    /** The rooms (Rooms) show the entities carrying this label (all controllable ones without). */
    final String houseLabel;
    final List<Alert> alerts;
    /**
     * An assistant to talk to: a name, a Home Assistant conversation agent ("conversation.home_assistant"
     * or an LLM agent), and an optional context sent ahead of a conversation's first sentence, where
     * {app} and {time} become what the TV shows and the hour.
     */
    static final class Preset {
        final String name;
        final String agent;
        final String context;

        Preset(JSONObject j) {
            name = j.optString("name");
            agent = j.optString("agent");
            context = j.optString("context");
        }
    }

    /** The assistants offered, the first by default; empty without one. */
    final List<Preset> assistantPresets;
    final String assistantLanguage;
    final List<String> assistantSuggestions;
    final SparseArray<Action> keys;
    /** Packages in front of which the Home Assistant buttons are left to the app (teletext uses the colours). */
    final Set<String> keysOffIn;
    /**
     * Screens (activity class names) sent away as soon as they show, Home coming back over them: a
     * vendor's prompt that comes back again and again and cannot be closed (RemoteKeyService).
     */
    final Set<String> dismiss;

    private HomeConfig(JSONObject j) {
        weather = j.optString("weather");
        JSONObject out = j.optJSONObject("outside");
        outside = out != null && !out.optString("entity").isEmpty() ? new Climate(out) : null;
        temperatures = new ArrayList<>();
        JSONArray te = j.optJSONArray("temperatures");
        for (int i = 0; te != null && i < te.length(); i++) {
            JSONObject o = te.optJSONObject(i);
            if (o != null && !o.optString("entity").isEmpty()) temperatures.add(new Climate(o));
        }
        JSONObject en = j.optJSONObject("energy");
        energyPower = en != null ? en.optString("power") : "";
        energySubscribed = en != null ? en.optString("subscribed") : "";
        energySubscription = en != null ? en.optString("subscription") : "";
        energyTempo = en != null ? en.optString("tempo") : "";
        energyTempoTomorrow = en != null ? en.optString("tempo_tomorrow") : "";
        energyLimit = en != null ? en.optDouble("limit", Double.NaN) : Double.NaN;
        status = new ArrayList<>();
        JSONArray st = j.optJSONArray("status");
        for (int i = 0; st != null && i < st.length(); i++) status.add(new Status(st.optJSONObject(i)));
        cameras = labelled(j.optJSONArray("cameras"));
        tiles = items(j.optJSONArray("tiles"));
        houseLabel = j.optString("house_label");
        alerts = new ArrayList<>();
        JSONArray al = j.optJSONArray("alerts");
        for (int i = 0; al != null && i < al.length(); i++) {
            JSONObject o = al.optJSONObject(i);
            if (o != null && !o.optString("entity").isEmpty()) alerts.add(new Alert(o));
        }
        JSONObject as = j.optJSONObject("assistant");
        assistantPresets = new ArrayList<>();
        JSONArray pr = as != null ? as.optJSONArray("presets") : null;
        for (int i = 0; pr != null && i < pr.length(); i++) {
            JSONObject o = pr.optJSONObject(i);
            if (o != null && !o.optString("agent").isEmpty()) assistantPresets.add(new Preset(o));
        }
        assistantLanguage = as != null ? as.optString("language") : "";
        assistantSuggestions = as != null ? list(as.optJSONArray("suggestions")) : Collections.emptyList();
        keys = new SparseArray<>();
        JSONObject k = j.optJSONObject("keys");
        if (k != null) {
            for (Iterator<String> it = k.keys(); it.hasNext(); ) {
                String code = it.next();
                try {
                    keys.put(Integer.parseInt(code), new Action(k.getJSONObject(code)));
                } catch (Exception e) {
                    Log.w(TAG, "ignoring key " + code);
                }
            }
        }
        keysOffIn = new HashSet<>(list(j.optJSONArray("keys_off_in")));
        dismiss = new HashSet<>(list(j.optJSONArray("dismiss")));
    }

    static HomeConfig load(Context ctx) {
        File dir = Config.dir(ctx);
        File f = dir != null ? new File(dir, FILE_NAME) : null;
        try (InputStream in = f != null && f.isFile() ? new FileInputStream(f) : ctx.getResources().openRawResource(R.raw.home)) {
            return new HomeConfig(new JSONObject(new String(HomeAssistant.readAll(in), StandardCharsets.UTF_8)));
        } catch (Exception e) {
            Log.w(TAG, "unreadable home.json, using none: " + e);
            return new HomeConfig(new JSONObject());
        }
    }

    /** Every entity the dashboard reads, for one refresh. */
    List<String> entities() {
        Set<String> ids = new HashSet<>();
        if (!weather.isEmpty()) ids.add(weather);
        if (outside != null) outside.addEntities(ids);
        for (Climate t : temperatures) t.addEntities(ids);
        if (!energyPower.isEmpty()) ids.add(energyPower);
        if (!energySubscribed.isEmpty()) ids.add(energySubscribed);
        if (!energySubscription.isEmpty()) ids.add(energySubscription);
        if (!energyTempo.isEmpty()) ids.add(energyTempo);
        if (!energyTempoTomorrow.isEmpty()) ids.add(energyTempoTomorrow);
        for (Status s : status) ids.add(s.entity);
        for (Item t : tiles) if (!t.action.entity.isEmpty()) ids.add(t.action.entity);
        return new ArrayList<>(ids);
    }

    private static List<Labelled> labelled(JSONArray a) {
        List<Labelled> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && !o.optString("entity").isEmpty()) out.add(new Labelled(o));
        }
        return out;
    }

    private static List<Item> items(JSONArray a) {
        List<Item> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(new Item(o));
        }
        return out;
    }

    private static List<String> list(JSONArray a) {
        if (a == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private static Map<String, String> strings(JSONObject o) {
        Map<String, String> out = new HashMap<>();
        if (o == null) return out;
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            out.put(k, o.optString(k));
        }
        return out;
    }
}
