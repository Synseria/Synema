package com.synseria.synema;

import android.content.Context;
import android.content.res.Configuration;

/**
 * The whole interface at one ratio: the design system's TV sizes (12 sp floor, 14 sp body) are Android TV's
 * for a 960 dp wide screen, read from across a room; this dashboard is denser by choice. Rendering every
 * window at a lower density keeps the design system's proportions and gives the layouts a wider screen in dp,
 * instead of shrinking each size by hand. Every window of the launcher wraps its context here.
 */
final class Scale {
    /** 0.75: the screen becomes 1280 × 720 dp, the density the dashboard was composed at. */
    static final float RATIO = 0.75f;

    private Scale() {
    }

    /** The design system's horizontal safe margin in real pixels: its token is in this ratio's dp. */
    static int marginX(Context ctx) {
        return Math.round(ctx.getResources().getDimension(R.dimen.syn_marge_x) / RATIO);
    }

    /** The design system's vertical safe margin in real pixels. */
    static int marginY(Context ctx) {
        return Math.round(ctx.getResources().getDimension(R.dimen.syn_marge_y) / RATIO);
    }

    static Context wrap(Context base) {
        Configuration c = new Configuration(base.getResources().getConfiguration());
        int dpi = base.getResources().getDisplayMetrics().densityDpi;
        c.densityDpi = Math.round(dpi * RATIO);
        c.screenWidthDp = Math.round(c.screenWidthDp / RATIO);
        c.screenHeightDp = Math.round(c.screenHeightDp / RATIO);
        c.smallestScreenWidthDp = Math.round(c.smallestScreenWidthDp / RATIO);
        return base.createConfigurationContext(c);
    }
}
