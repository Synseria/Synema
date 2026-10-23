package com.synseria.synema;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What the TV shows, told to Home Assistant as sensor.clean_tv: the app in front as its state, its
 * package, and the titles to continue. Automations (cinema mode) and an LLM agent can then know what
 * is on, and what "reprends ma série" means. Home Assistant forgets the entity on restart; the next
 * change of app sets it again.
 */
final class TvState {
    static final String ENTITY = "sensor.clean_tv";

    private TvState() {
    }

    /** From any thread; reads the TV provider on its own one. */
    static void report(Context ctx, HomeAssistant ha, String pkg) {
        new Thread(() -> {
            try {
                PackageManager pm = ctx.getPackageManager();
                String app;
                try {
                    app = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    app = pkg;
                }
                if (pkg.equals(ctx.getPackageName())) app = ctx.getString(R.string.tv_state_home);
                JSONArray resume = new JSONArray();
                for (TvListings.Item it : TvListings.watchNext(ctx, 10)) {
                    resume.put(it.title + " (" + label(pm, it.pkg) + ")");
                }
                ha.setState(ENTITY, app, new JSONObject()
                        .put("friendly_name", "TV du salon")
                        .put("icon", "mdi:television")
                        .put("package", pkg)
                        .put("a_reprendre", resume));
            } catch (Exception ignored) {
                // A report missed is caught up by the next one.
            }
        }, "tv-state").start();
    }

    private static String label(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
