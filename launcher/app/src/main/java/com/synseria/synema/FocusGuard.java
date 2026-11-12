package com.synseria.synema;

import android.app.Activity;
import android.graphics.Rect;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;

/**
 * Checks that a focused element's lift (the scale Focus gave it) is not cut by a parent that clips: each
 * focus gain walks up the parents, and one that leaves the grown rectangle partly outside its visible area
 * is logged ("focus clipped", adb logcat -s FocusGuard). On in the installed build too, since the TV is the
 * only place the cut shows; a few parents per focus change cost nothing. A scroll view's own axis is
 * skipped: it brings the element into view with the room its lift takes.
 */
final class FocusGuard {
    private static final String TAG = "FocusGuard";

    private FocusGuard() {
    }

    /** Once per window, from onCreate after the content is set. */
    static void install(Activity activity) {
        activity.getWindow().getDecorView().getViewTreeObserver()
                .addOnGlobalFocusChangeListener((old, now) -> {
                    if (now != null) check(now);
                });
    }

    private static void check(View v) {
        Object tag = v.getTag(R.id.lift);
        float scale = tag instanceof Float ? (Float) tag : 1f;
        float dw = v.getWidth() * (scale - 1) / 2, dh = v.getHeight() * (scale - 1) / 2;
        Rect grown = new Rect();
        for (View c = v; c.getParent() instanceof ViewGroup; ) {
            ViewGroup p = (ViewGroup) c.getParent();
            boolean clipsX = p.getClipChildren() && !(p instanceof HorizontalScrollView);
            boolean clipsY = p.getClipChildren() && !(p instanceof ScrollView);
            if (clipsX || clipsY) {
                grown.set((int) Math.floor(-dw), (int) Math.floor(-dh), (int) Math.ceil(v.getWidth() + dw), (int) Math.ceil(v.getHeight() + dh));
                p.offsetDescendantRectToMyCoords(v, grown);
                Rect area = new Rect(0, 0, p.getWidth(), p.getHeight());
                if (p.getClipToPadding()) {
                    area.set(p.getPaddingLeft(), p.getPaddingTop(), p.getWidth() - p.getPaddingRight(), p.getHeight() - p.getPaddingBottom());
                }
                boolean cutX = clipsX && (grown.left < area.left || grown.right > area.right);
                boolean cutY = clipsY && (grown.top < area.top || grown.bottom > area.bottom);
                if (cutX || cutY) {
                    Log.e(TAG, "focus clipped: " + name(v) + " (x" + scale + ") by " + name(p) + ", grown " + grown
                            + " outside " + area);
                }
            }
            c = p;
        }
    }

    private static String name(View v) {
        String id = "";
        if (v.getId() != View.NO_ID) {
            try {
                id = "#" + v.getResources().getResourceEntryName(v.getId());
            } catch (android.content.res.Resources.NotFoundException ignored) {
            }
        }
        return v.getClass().getSimpleName() + id;
    }
}
