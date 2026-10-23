package com.synseria.synema;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

/** Runs a HomeConfig action, the same way from the dashboard and from a remote button. */
final class Actions {
    private static final String TAG = "TvHome.Actions";

    private Actions() {
    }

    /** @param done told once a Home Assistant call is answered (to refresh the states); may be null */
    static void run(Context ctx, HomeAssistant ha, HomeConfig.Action action, Runnable done) {
        if (!action.app.isEmpty()) {
            Intent launch = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(action.app);
            if (launch == null) launch = ctx.getPackageManager().getLaunchIntentForPackage(action.app);
            if (launch == null) Log.w(TAG, "no launch intent for " + action.app);
            else start(ctx, launch);
        } else if (action.assistant) {
            start(ctx, new Intent(ctx, AssistActivity.class));
        } else if (action.sources) {
            start(ctx, SourcesPage.intent(ctx));
        } else if (ha == null) {
            Toast.makeText(ctx, R.string.ha_not_configured, Toast.LENGTH_SHORT).show();
        } else {
            ha.call(action.service, action.entity, action.data, ok -> {
                if (!ok) Toast.makeText(ctx, R.string.ha_unreachable, Toast.LENGTH_SHORT).show();
                if (done != null) done.run();
            });
        }
    }

    private static void start(Context ctx, Intent i) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
