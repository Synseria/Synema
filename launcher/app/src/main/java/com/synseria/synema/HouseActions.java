package com.synseria.synema;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the assistant's agent may offer to do, as cards under its answer (AssistActivity): the house's
 * things (Rooms, with house_label's rule) and its scenes, listed for the agent with their state
 * (context), and the lines it answers with, "⚡ label | domain.service | entity", read back (take).
 * A line becomes a card only for one of those things and a service that suits its kind: a small model
 * that invents an entity or a service offers nothing. OK on a card calls the service, nothing between.
 */
final class HouseActions {
    /** A card: its words, and the service to call on the entity. */
    static final class Action {
        final String label, service, entity;

        Action(String label, String service, String entity) {
            this.label = label;
            this.service = service;
            this.entity = entity;
        }
    }

    private static final int MOST = 3;
    /** The services a card may call, by kind of thing: switching, a cover's, a vacuum's, a scene. */
    private static final Map<String, Set<String>> SERVICES = new LinkedHashMap<>();

    static {
        Set<String> onOff = new HashSet<>(Arrays.asList("turn_on", "turn_off", "toggle"));
        for (String d : new String[]{"light", "switch", "fan", "input_boolean"}) SERVICES.put(d, onOff);
        SERVICES.put("cover", new HashSet<>(Arrays.asList("open_cover", "close_cover", "stop_cover")));
        SERVICES.put("vacuum", new HashSet<>(Arrays.asList("start", "pause", "return_to_base")));
        SERVICES.put("scene", new HashSet<>(Arrays.asList("turn_on")));
    }

    /** A line the agent wrote, wherever it put it: a small model also writes it inside its sentence. */
    private static final Pattern LINE = Pattern.compile(
            "⚡[ \\t]*([^|\\n]+?)[ \\t]*\\|[ \\t]*([a-z_]+)\\.([a-z_]+)[ \\t]*\\|[ \\t]*([a-z_]+\\.[a-z0-9_]+)[ \\t]*");
    /** The scenes, which the rooms leave out: [[entity, name], …]. */
    private static final String SCENES = "{% set ns = namespace(o=[]) %}{% for s in states.scene %}"
            + "{% set ns.o = ns.o + [[s.entity_id, s.name]] %}{% endfor %}{{ ns.o | tojson }}";

    /** Each thing a card may act on, and how the agent reads it: "name (room)". */
    private final Map<String, String> things = new LinkedHashMap<>();

    /** Reads the rooms' things and the scenes; {@code done} once both answered. Main thread. */
    void load(HomeAssistant ha, String label, Runnable done) {
        Rooms.load(ha, null, label, true, rooms -> {
            for (Rooms.Room r : rooms) for (Rooms.Entity e : r.entities) things.put(e.id, e.name + " (" + r.name + ")");
            ha.templateArray(SCENES, scenes -> {
                for (int i = 0; i < scenes.length(); i++) {
                    org.json.JSONArray s = scenes.optJSONArray(i);
                    if (s != null) things.put(s.optString(0), s.optString(1));
                }
                done.run();
            });
        });
    }

    boolean isEmpty() {
        return things.isEmpty();
    }

    /** The things, a line each for the agent: "light.salon | Salon (Salon) | off", the state as last pushed (HaLive). */
    String context() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> t : things.entrySet()) {
            HomeAssistant.State s = HaLive.STATES.get(t.getKey());
            sb.append("- ").append(t.getKey()).append(" | ").append(t.getValue());
            if (s != null && !t.getKey().startsWith("scene.")) sb.append(" | ").append(s.state);
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    /** The actions in {@code reply} that may become cards, three at most; {@code words} gets the reply without any line. */
    List<Action> take(String reply, StringBuilder words) {
        List<Action> out = new ArrayList<>();
        Matcher m = LINE.matcher(reply);
        int from = 0;
        while (m.find()) {
            words.append(reply, from, m.start());
            from = m.end();
            String domain = m.group(2), service = m.group(3), entity = m.group(4);
            Set<String> allowed = SERVICES.get(domain);
            boolean fits = allowed != null && allowed.contains(service) && entity.startsWith(domain + ".") && things.containsKey(entity);
            if (fits && out.size() < MOST) out.add(new Action(m.group(1).trim(), domain + "/" + service, entity));
        }
        words.append(reply.substring(from));
        return out;
    }
}
