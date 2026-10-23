package com.synseria.synema;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import java.util.List;

/**
 * Plays what a sentence names: a title the apps published (continue watching, their channels) or an
 * app. "L'amour est dans le pré sur Canal+" opens that title in Canal+, "Netflix" opens Netflix. The
 * assistant asks it before Home Assistant, and Home Assistant can ask it too (the event
 * {@value Alerts#PLAY_EVENT}), so an LLM agent's script can start a film. Reads the TV provider: call
 * it off the main thread.
 */
final class Launch {
    /** What to open, and how to say it. */
    static final class Target {
        final String title;
        final String app;
        final Intent intent;

        Target(String title, String app, Intent intent) {
            this.title = title;
            this.app = app;
            this.intent = intent;
        }
    }

    private Launch() {
    }

    /**
     * The title or app {@code request} names, or null. {@code appName}, when given, picks the app
     * (else one named in the request does): "sur Netflix", "dans Canal+".
     */
    static Target find(Context ctx, String request, String appName) {
        PackageManager pm = ctx.getPackageManager();
        List<ResolveInfo> apps = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0);
        String said = " " + TvListings.plain(request) + " ";
        String wanted = appName == null || appName.isEmpty() ? null : " " + TvListings.plain(appName) + " ";
        String pkg = null, label = null;
        int longest = 0;
        for (ResolveInfo ri : apps) {
            String l = ri.loadLabel(pm).toString(), p = TvListings.plain(l);
            if (p.length() < 3 || p.length() <= longest) continue;
            if ((wanted != null ? wanted : said).contains(" " + p + " ")) {
                pkg = ri.activityInfo.packageName;
                label = l;
                longest = p.length();
            }
        }
        TvListings.Item item = TvListings.find(TvListings.catalogue(ctx), request, pkg);
        if (item != null) return new Target(item.title, appLabel(pm, item.pkg), new Intent(item.intent));
        // No title: the app alone, when the request is little more than its name.
        if (pkg != null && said.replace(" " + TvListings.plain(label) + " ", " ").trim().split(" ").length <= 2) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch != null) return new Target("", label, launch);
        }
        return null;
    }

    /**
     * Opens an app's screen; from a card, the app's window grows out of that card (the system animates
     * it, so it costs the launcher nothing). Says so when it cannot.
     */
    static void start(Activity activity, Intent i, View card) {
        Bundle options = card == null ? null
                : ActivityOptions.makeScaleUpAnimation(card, 0, 0, card.getWidth(), card.getHeight()).toBundle();
        try {
            activity.startActivity(new Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    static void open(Context ctx, Target t) {
        try {
            ctx.startActivity(t.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            android.util.Log.w("TvHome.Launch", "cannot open " + t.title + ": " + e);
        }
    }

    private static String appLabel(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
