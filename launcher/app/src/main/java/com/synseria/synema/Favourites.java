package com.synseria.synema;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.util.DisplayMetrics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a favourite id stands for: an app (its package name) or a TV input ("input:", the HDMI port's
 * input id, "|" and the name it had when chosen). The port, not the device CEC finds on it: a device's
 * input disappears while it sleeps. Its name is the device's when it is there (Apple TV), else the one
 * saved. One place resolves both, so the dashboard and the edit menu show the same name, banner and
 * launch.
 */
final class Favourites {
    static final String INPUT = "input:";

    static final class Entry {
        final String id;
        final CharSequence label;
        final Drawable banner;
        final Intent launch;

        Entry(String id, CharSequence label, Drawable banner, Intent launch) {
            this.id = id;
            this.label = label;
            this.banner = banner;
            this.launch = launch;
        }

        /** An app publishes picks and can be closed; an input does neither. */
        boolean isApp() {
            return !id.startsWith(INPUT);
        }
    }

    private Favourites() {
    }

    /** The favourite, or null when its app is gone or its input is not there. */
    static Entry resolve(Context ctx, String id) {
        if (id.startsWith(INPUT)) {
            int bar = id.lastIndexOf('|');
            String inputId = id.substring(INPUT.length(), bar > 0 ? bar : id.length());
            TvInputInfo info = input(ctx, inputId);
            if (info == null) return null;
            String device = deviceOn(ctx, inputId);
            CharSequence label = device != null ? device : bar > 0 ? id.substring(bar + 1) : label(ctx, info);
            return new Entry(id, label, inputBanner(ctx, label), new Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(inputId)));
        }
        PackageManager pm = ctx.getPackageManager();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(id);
        if (launch == null) return null;
        try {
            return new Entry(id, pm.getApplicationLabel(pm.getApplicationInfo(id, 0)), bannerOrIcon(pm, id), launch);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** Banners read for the dashboard, by package: its cards ask for them on every row they fill. */
    private static final Map<String, Drawable.ConstantState> BANNERS = new HashMap<>();

    /** The app's banner (see banner), else its icon; null when the app is gone. On the main thread. */
    static Drawable bannerOrIcon(PackageManager pm, String pkg) {
        Drawable.ConstantState kept = BANNERS.get(pkg);
        if (kept != null) return kept.newDrawable();
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        ResolveInfo ri = launch == null ? null : pm.resolveActivity(launch, 0);
        if (ri == null) return null;
        Drawable banner = banner(pm, ri.activityInfo);
        if (banner == null) banner = ri.activityInfo.loadIcon(pm);
        if (banner.getConstantState() != null) BANNERS.put(pkg, banner.getConstantState());
        return banner;
    }

    /**
     * The TV entry's banner, else the app's, at three times the TV's density when the app ships it
     * (most ship 320 × 180 only; Netflix 480 × 270, Canal+ up to 1280 × 720); null when it has none. A
     * card scales it down to its own size (RoundedImageView): sharper than a picture scaled up, and
     * twice the card is enough for that.
     */
    static Drawable banner(PackageManager pm, ActivityInfo activity) {
        int res = activity.banner != 0 ? activity.banner : activity.applicationInfo.banner;
        if (res == 0) return null;
        try {
            return pm.getResourcesForApplication(activity.applicationInfo).getDrawableForDensity(res, DisplayMetrics.DENSITY_XXHIGH, null);
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            return activity.loadBanner(pm);
        }
    }

    /** The HDMI ports, named after the device on them when there is one, as [id, name], for the add menu and SourcesPage. */
    static List<String[]> inputs(Context ctx) {
        List<String[]> out = new ArrayList<>();
        TvInputManager tim = ctx.getSystemService(TvInputManager.class);
        if (tim == null) return out;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (info.getType() != TvInputInfo.TYPE_HDMI || info.getParentId() != null || info.isHidden(ctx)) continue;
            String device = deviceOn(ctx, info.getId());
            String name = device != null ? device : label(ctx, info).toString();
            out.add(new String[]{INPUT + info.getId() + "|" + name, name});
        }
        return out;
    }

    /** The name of the device CEC sees on this port, or null when none is there (or it sleeps). */
    private static String deviceOn(Context ctx, String portId) {
        TvInputManager tim = ctx.getSystemService(TvInputManager.class);
        if (tim == null) return null;
        for (TvInputInfo info : tim.getTvInputList()) {
            if (portId.equals(info.getParentId())) return label(ctx, info).toString();
        }
        return null;
    }

    private static TvInputInfo input(Context ctx, String inputId) {
        TvInputManager tim = ctx.getSystemService(TvInputManager.class);
        return tim == null ? null : tim.getTvInputInfo(inputId);
    }

    /**
     * The name set in the TV's settings, else the device's (CEC), else the port's. Philips stores a
     * renamed port as "name&TYPE" (Console de jeux&GAME_CONSOLE): the type is dropped.
     */
    private static CharSequence label(Context ctx, TvInputInfo info) {
        CharSequence custom = info.loadCustomLabel(ctx);
        String name = (custom != null && custom.length() > 0 ? custom : info.loadLabel(ctx)).toString();
        int amp = name.indexOf('&');
        return amp > 0 ? name.substring(0, amp) : name;
    }

    /** An input has no banner: its name on the dashboard's dark card, with the inputs icon above. */
    static Drawable inputBanner(Context ctx, CharSequence label) {
        int w = 320, h = 180;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint bg = new Paint();
        bg.setShader(new LinearGradient(0, 0, w, h, 0xFF26344D, 0xFF141B2A, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, bg);
        Drawable icon = ctx.getDrawable(R.drawable.ic_input);
        if (icon != null) {
            icon.setTint(0xCCFFFFFF);
            icon.setBounds(w / 2 - 22, 34, w / 2 + 22, 78);
            icon.draw(c);
        }
        Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        text.setColor(0xFFF4F6FA);
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(36);
        String s = label.toString();
        while (s.length() > 3 && text.measureText(s) > w - 32) s = s.substring(0, s.length() - 2) + "…";
        c.drawText(s, w / 2f, 130, text);
        return new BitmapDrawable(ctx.getResources(), b);
    }
}
