package com.synseria.synema;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A page beside the dashboard (a room, the electricity, the sources): OK pushes the home out to the left
 * as the page comes in from the right, edge to edge; Back pushes it back, onto what opened it. The page
 * has the clock and the way back at the top, its title, then what RoomPage, EnergyPage or SourcesPage
 * puts in it.
 *
 * On this GPU the home and the page each slide as one hardware layer, prepared a step per frame before
 * they move, and the background stays put. They never overlap and neither fades or shrinks: each frame
 * covers the screen once over the background, which is all this GPU affords (three full-screen layers
 * blended made frames late).
 */
final class SidePage {
    private static final long SLIDE_MS = 450;
    private static final PathInterpolator EASE = new PathInterpolator(0.22f, 0.8f, 0.24f, 1f);

    private final MainActivity activity;
    private final FrameLayout root;
    private final View home;
    private final LinearLayout page;
    private final TextView title;
    private final FrameLayout body;
    /** What is shown (a room's name, EnergyPage.KEY, SourcesPage.KEY), or null when closed. */
    private String shown;
    /** Where the focus goes back to on closing, looked up then: the home may have rebuilt it meanwhile. */
    private Supplier<View> back;
    private float homeX;
    /** Bumped on each open and close: a staged slide that is no longer the last one does not start. */
    private int moves;
    /**
     * While the page or the home is being prepared or moves: what would redraw either waits (whenStill),
     * and the moving background is held.
     */
    private boolean moving;
    private final List<Runnable> afterMove = new ArrayList<>();

    SidePage(MainActivity activity) {
        this.activity = activity;
        root = activity.findViewById(R.id.root);
        home = activity.findViewById(R.id.content);
        page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(activity.dp(32), activity.dp(20), activity.dp(32), activity.dp(14));
        page.setClipToPadding(false);
        page.setClipChildren(false);
        page.setVisibility(View.GONE);

        FrameLayout top = new FrameLayout(activity);
        TextView back = activity.text(activity.getString(R.string.room_back), 10, 0x99F2F2F5);
        back.setLetterSpacing(0.04f);
        top.addView(back, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));
        TextClock clock = new TextClock(activity);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(26);
        clock.setTextColor(0xFFF2F2F5);
        clock.setTypeface(activity.getResources().getFont(R.font.outfit));
        clock.setFontVariationSettings("'wght' 200");
        clock.setIncludeFontPadding(false);
        top.addView(clock, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL));
        page.addView(top);

        title = activity.text("", 34, 0xFFF2F2F5);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title);

        body = new FrameLayout(activity);
        body.setClipChildren(false);
        body.setClipToPadding(false);
        page.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    boolean isOpen() {
        return shown != null;
    }

    /** What is shown, or null. */
    String shown() {
        return shown;
    }

    /**
     * Slides in {@code content} under {@code name}, as {@code key}; {@code first} takes the focus, and
     * {@code back} gets it again on closing.
     */
    void open(String key, String name, View content, View first, Supplier<View> back) {
        open(key, name, content, first, back, true);
    }

    /**
     * As above; without {@code slide}, the page is put in place at once: the dashboard is not on screen
     * yet (the remote's Source button from another app), and a slide would only delay the page.
     */
    void open(String key, String name, View content, View first, Supplier<View> back, boolean slide) {
        // A slide under way stops first: its end must not undo this one's layers.
        home.animate().cancel();
        page.animate().cancel();
        if (!isOpen()) homeX = home.getTranslationX();
        shown = key;
        this.back = back;
        title.setText(name);
        if (content.getParent() != body) {
            body.removeAllViews();
            if (content.getParent() != null) ((ViewGroup) content.getParent()).removeView(content);
            body.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        // The home keeps the focus out while it is away.
        ((ViewGroup) home).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        page.setVisibility(View.VISIBLE);
        if (!slide) {
            still();
            layer(home, false);
            layer(page, false);
            home.setVisibility(View.INVISIBLE);
            page.setTranslationX(0);
            moveFocus(first);
            return;
        }
        page.setTranslationX(root.getWidth());
        moveFocus(first);
        afterLayers(() -> {
            home.animate().translationX(homeX - root.getWidth()).setDuration(SLIDE_MS).setInterpolator(EASE).setListener(null)
                    .withEndAction(() -> {
                        home.setVisibility(View.INVISIBLE);
                        layer(home, false);
                    }).start();
            page.animate().translationX(0).setDuration(SLIDE_MS).setInterpolator(EASE).setListener(settle(() -> layer(page, false)))
                    .start();
        });
    }

    /** Back: the page goes off to the right and the home comes back; false when none is open. */
    boolean close() {
        return close(true);
    }

    /** As above; without {@code slide}, at once: the dashboard has left the screen. */
    boolean close(boolean slide) {
        if (!isOpen()) return false;
        shown = null;
        View to = back.get();
        back = null;
        home.animate().cancel();
        page.animate().cancel();
        ((ViewGroup) home).setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        // Put away at once, maybe before the screen had its width: the home comes back from beside the page all the same.
        if (home.getVisibility() != View.VISIBLE) home.setTranslationX(homeX - root.getWidth());
        home.setVisibility(View.VISIBLE);
        if (!slide) {
            still();
            layer(home, false);
            layer(page, false);
            home.setTranslationX(homeX);
            page.setVisibility(View.GONE);
            moveFocus(to);
            return true;
        }
        moveFocus(to);
        afterLayers(() -> {
            home.animate().translationX(homeX).setDuration(SLIDE_MS).setInterpolator(EASE)
                    .setListener(null).withEndAction(() -> layer(home, false)).start();
            page.animate().translationX(root.getWidth()).setDuration(SLIDE_MS).setInterpolator(EASE).setListener(settle(() -> {
                layer(page, false);
                if (!isOpen()) page.setVisibility(View.GONE);
            })).start();
        });
        return true;
    }

    /**
     * Gives {@code to} the focus, the lifts of the view that had it and of {@code to} done at once: a
     * lift under way would redraw the whole home or page every frame of the slide.
     */
    private void moveFocus(View to) {
        View from = activity.getCurrentFocus();
        if (to != null) to.requestFocus();
        if (from != null) Focus.settle(from);
        if (to != null) Focus.settle(to);
    }

    /** Runs {@code r} now, or once the slide under way has ended: nothing redraws the home or the page meanwhile. */
    void whenStill(Runnable r) {
        if (moving) afterMove.add(r);
        else r.run();
    }

    /**
     * Prepares, then slides, one heavy step per frame: the page (just filled, still off screen) is laid
     * out in the current frame, the home's hardware layer is built in the next, the page's in the one
     * after, and only then do they move, as two pictures. Done in one frame, these steps made one long
     * frame at the start of every slide.
     */
    private void afterLayers(Runnable slide) {
        int my = ++moves;
        if (!moving) {
            moving = true;
            activity.motion().hold(true);
        }
        page.postOnAnimation(() -> {
            if (my != moves) return;
            layer(home, true);
            home.buildLayer();
            page.postOnAnimation(() -> {
                if (my != moves) return;
                layer(page, true);
                page.buildLayer();
                page.postOnAnimation(() -> {
                    if (my == moves) slide.run();
                });
            });
        });
    }

    /**
     * At the slide's end, {@code end}; then, unless another move started meanwhile, the background goes
     * on and what waited is drawn.
     */
    private AnimatorListenerAdapter settle(Runnable end) {
        int my = moves;
        return new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                end.run();
                if (my == moves) still();
            }
        };
    }

    /**
     * Nothing moves any more: a staged slide will not start, the background goes on and what waited is
     * drawn.
     */
    private void still() {
        moves++;
        if (!moving) return;
        moving = false;
        activity.motion().hold(false);
        List<Runnable> waiting = new ArrayList<>(afterMove);
        afterMove.clear();
        for (Runnable r : waiting) r.run();
    }

    private static void layer(View v, boolean on) {
        v.setLayerType(on ? View.LAYER_TYPE_HARDWARE : View.LAYER_TYPE_NONE, null);
    }
}
