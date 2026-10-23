package com.synseria.synema;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The house room by room, as the dashboard's panel and the room pages show it: the Home Assistant areas
 * of its floors (all of them while it has none; an area off every floor, a technical room, only for the assistant) with
 * things a press can switch (only those carrying home.json's {@code house_label} when some do; locks,
 * media players, scenes and scripts never), each with its readings when it has sensors for them, and
 * its cameras. Read with one template and the area registry, which only the WebSocket gives (each
 * area's reference sensors); their states come from HaLive.
 */
final class Rooms {
    /** Domains a press can safely switch from the sofa, when entities are labelled for the TV. */
    private static final String DOMAINS = "light|switch|cover|fan|vacuum|input_boolean";
    /**
     * Without labels: switches are left out, as most are a device's settings (child lock, a speaker's
     * modes) that templates cannot tell from a plug.
     */
    private static final String DOMAINS_UNLABELLED = "light|cover|fan|vacuum";

    private Rooms() {
    }

    static final class Entity {
        final String id, name;

        Entity(JSONArray e) {
            id = e.optString(0);
            name = e.optString(1);
        }
    }

    static final class Room {
        final String name;
        final List<Entity> entities;
        /**
         * The room's temperature, humidity, CO₂ and noise sensors; empty when it has none. The area's
         * reference ones in Home Assistant for the first two, else its first sensor of the kind: a kitchen's
         * first thermometer can be the fridge's.
         */
        final String temperature, humidity, co2, noise;
        /** The room's cameras. */
        final List<String> cameras;

        /** From the template's line and the area's registry entry (null when not read). */
        Room(JSONArray r, JSONObject area) {
            name = r.optString(0);
            List<Entity> es = new ArrayList<>();
            JSONArray a = r.optJSONArray(1);
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONArray e = a.optJSONArray(i);
                if (e != null) es.add(new Entity(e));
            }
            entities = Collections.unmodifiableList(es);
            temperature = reference(area, "temperature_entity_id", r, 2);
            humidity = reference(area, "humidity_entity_id", r, 3);
            co2 = r.isNull(4) ? "" : r.optString(4);
            noise = r.isNull(5) ? "" : r.optString(5);
            List<String> cams = new ArrayList<>();
            JSONArray c = r.optJSONArray(6);
            for (int i = 0; c != null && i < c.length(); i++) cams.add(c.optString(i));
            cameras = Collections.unmodifiableList(cams);
        }

        private static String reference(JSONObject area, String field, JSONArray r, int found) {
            if (area != null && !area.isNull(field) && !area.optString(field).isEmpty()) return area.optString(field);
            return r.isNull(found) ? "" : r.optString(found);
        }

        /** The room's lights. */
        List<Entity> lights() {
            List<Entity> out = new ArrayList<>();
            for (Entity e : entities) if (e.id.startsWith("light.")) out.add(e);
            return out;
        }

        /** The room's other things: covers, switches, fans, a vacuum. */
        List<Entity> devices() {
            List<Entity> out = new ArrayList<>();
            for (Entity e : entities) if (!e.id.startsWith("light.")) out.add(e);
            return out;
        }

        /**
         * Some light of the room is on, among those its page shows: a room, not bulbs, as a lamp of
         * several bulbs or a light group would count many times over.
         */
        boolean lit(Map<String, HomeAssistant.State> states) {
            for (Entity e : entities) {
                HomeAssistant.State s = states.get(e.id);
                if (e.id.startsWith("light.") && s != null && "on".equals(s.state)) return true;
            }
            return false;
        }

        /** What the room shows from Home Assistant: its things and its sensors, to watch (HaLive). */
        void addEntities(Collection<String> out) {
            for (Entity e : entities) out.add(e.id);
            for (String sensor : new String[]{temperature, humidity, co2, noise}) if (!sensor.isEmpty()) out.add(sensor);
        }
    }

    /**
     * The rooms in Home Assistant's order; empty when it did not answer. The template and the registry
     * are asked together; without {@code live}, or when the registry is refused, each room's sensors are
     * the ones found. {@code offFloors} adds the areas off every floor: the assistant may act on a
     * technical room's things that the panel leaves out. Main thread.
     */
    static void load(HomeAssistant ha, HaLive live, String label, boolean offFloors, HomeAssistant.Callback<List<Room>> cb) {
        JSONArray[] lines = new JSONArray[1];
        @SuppressWarnings("unchecked") Map<String, JSONObject>[] areas = new Map[1];
        Runnable both = () -> {
            if (lines[0] == null || areas[0] == null) return;
            List<Room> out = new ArrayList<>();
            for (int i = 0; i < lines[0].length(); i++) {
                JSONArray r = lines[0].optJSONArray(i);
                if (r != null) out.add(new Room(r, areas[0].get(r.optString(7))));
            }
            cb.done(out);
        };
        ha.templateArray(template(label, offFloors), a -> {
            lines[0] = a;
            both.run();
        });
        if (live == null) {
            areas[0] = Collections.emptyMap();
            return;
        }
        try {
            live.request(new JSONObject().put("type", "config/area_registry/list"), result -> {
                Map<String, JSONObject> byId = new HashMap<>();
                JSONArray all = result == null ? null : result.optJSONArray("result");
                for (int i = 0; all != null && i < all.length(); i++) {
                    JSONObject area = all.optJSONObject(i);
                    if (area != null) byId.put(area.optString("area_id"), area);
                }
                areas[0] = byId;
                both.run();
            });
        } catch (org.json.JSONException e) {
            areas[0] = Collections.emptyMap();
        }
    }

    /** The rooms with some light on. */
    static int lit(List<Room> rooms, Map<String, HomeAssistant.State> states) {
        int n = 0;
        for (Room r : rooms) if (r.lit(states)) n++;
        return n;
    }

    /**
     * [[area name, [[entity, name], …], temperature, humidity, co2, noise, [camera, …], area id], …],
     * floor by floor: the labelled entities if any carry the label, else all those of the safe domains;
     * for each reading the area's first sensor of that kind that has one; its cameras.
     */
    private static String template(String label, boolean offFloors) {
        String l = label.replace("'", "");
        return "{% set tagged = label_entities('" + l + "') if '" + l + "' else [] %}"
                + "{% set domains = '^(" + DOMAINS + ")\\\\.' if tagged else '^(" + DOMAINS_UNLABELLED + ")\\\\.' %}"
                + "{% set fl = namespace(a=[]) %}{% for f in floors() %}{% set fl.a = fl.a + floor_areas(f) %}{% endfor %}"
                + "{% set ns = namespace(out=[]) %}{% for a in (fl.a if floors() and not " + offFloors + " else areas()) %}"
                + "{% set es = namespace(e=[]) %}"
                + "{% for e in area_entities(a) | select('match', domains) %}"
                + "{% if not tagged or e in tagged %}"
                + "{% set es.e = es.e + [[e, state_attr(e, 'friendly_name') or e]] %}{% endif %}{% endfor %}"
                + "{% if es.e %}"
                + reading("t", "temperature") + reading("h", "humidity") + reading("c", "carbon_dioxide")
                + reading("n", "sound_pressure")
                + "{% set cams = area_entities(a) | select('match', '^camera\\\\.') | list %}"
                + "{% set ns.out = ns.out + [[area_name(a), es.e, t, h, c, n, cams, a]] %}{% endif %}{% endfor %}"
                + "{{ ns.out | tojson }}";
    }

    /** Sets {@code var} to the area's first sensor of this device class with a reading, else none. */
    private static String reading(String var, String deviceClass) {
        return "{% set " + var + " = area_entities(a) | select('match', '^sensor\\\\.')"
                + " | select('is_state_attr', 'device_class', '" + deviceClass + "')"
                + " | reject('is_state', ['unknown', 'unavailable']) | first | default(none) %}";
    }
}
