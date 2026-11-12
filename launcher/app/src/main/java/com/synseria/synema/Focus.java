package com.synseria.synema;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.AnimationUtils;

/**
 * The one look of focus, everywhere, the design system's: a white ring (syn_focus_trait wide) and a
 * lift of syn_focus_echelle. A card (a picture) shows it with a sheen on its frame, 3 dp from the
 * picture (dash_focus_ring), a tile under a veil (dash_tile_bg), a round button or a chip on its own
 * edge (icon_button_bg, capsule_button_bg). None casts a shadow, which costs this GPU a blur per frame.
 *
 * What holds a card leaves room round its picture for its lift and ring (room, from the lift here), and
 * only a row's window clips, across: nothing focused is ever cut, nor reaches a neighbour.
 */
final class Focus {
    /** The lift every element takes, whatever its kind. */
    @Deprecated
    static final float CARD = 1.04f, TILE = CARD, BUTTON = CARD;
    /** No card's picture on the dashboard is wider (dp): a row takes one more card rather than widen them. */
    static final int WIDEST_CARD_DP = 160;

    /** The lift of a focused element: the design system's syn_focus_echelle. */
    static float scale(Context ctx) {
        TypedValue value = new TypedValue();
        ctx.getResources().getValue(R.dimen.syn_focus_echelle, value, true);
        return value.getFloat();
    }

    private Focus() {
    }

    /** A tile's name: primary ink in every state, as focus no longer turns the tile white. */
    static int tileText(Context ctx) {
        return ctx.getColor(R.color.syn_encre);
    }

    /** A card: its frame (Card) shows the ring and sheen, and the card grows while focused. */
    static void card(View card) {
        card.setForeground(card.getContext().getDrawable(R.drawable.dash_focus_ring));
        focusable(card);
    }

    /** A tile of the House panel or a room page: lit and ringed by its background, it grows a little. */
    static void tile(View tile) {
        tile.setBackgroundResource(R.drawable.dash_tile_bg);
        focusable(tile);
    }

    /** A block that keeps its own background, a selector whose focused state is a tile's: it grows a little. */
    static void block(View v) {
        focusable(v);
    }

    /** A round button or a chip: its own edge takes the ring (its background), and it grows a little. */
    static void button(View button) {
        focusable(button);
    }

    private static void focusable(View v) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener(Focus::lift);
    }

    /**
     * How far a focused card's ring reaches past its picture (px), on a side {@code side} px long: its
     * gap and ring (card_inset), and half of what the frame grows by.
     */
    static float reach(Context ctx, float side) {
        int inset = ctx.getResources().getDimensionPixelSize(R.dimen.card_inset);
        return inset + (scale(ctx) - 1) * (side + 2 * inset) / 2;
    }

    /**
     * The room to keep clear round a picture {@code sideDp} long (px): the reach of its focused ring, and
     * a dp to spare so it touches nothing.
     */
    static int room(Context ctx, int sideDp) {
        float d = ctx.getResources().getDisplayMetrics().density;
        return (int) Math.ceil(reach(ctx, sideDp * d)) + Math.round(d);
    }

    /** Beside any card of the dashboard: between two pictures, and from a picture to what clips it. */
    static int cardRoom(Context ctx) {
        return room(ctx, WIDEST_CARD_DP);
    }

    /** Under any card of the dashboard: from its picture to the line under it. */
    static int cardRoomBelow(Context ctx) {
        return room(ctx, WIDEST_CARD_DP * 9 / 16);
    }

    /** How far a focused tile {@code side} px long reaches past it that way (px), its ring being inside: its lift, and a px to spare. */
    static int tileReach(Context ctx, int side) {
        return (int) Math.ceil((scale(ctx) - 1) * side / 2) + 1;
    }

    /** Grows {@code v} while it has the focus, back when it loses it. */
    static void lift(View v, boolean has) {
        lift(v, has, scale(v.getContext()));
    }

    /** {@link #lift(View, boolean)} with a scale of its own. */
    @Deprecated
    static void lift(View v, boolean has, float scale) {
        float s = has ? scale : 1f;
        v.setTag(R.id.lift, s);
        v.animate().scaleX(s).scaleY(s)
                .setDuration(v.getResources().getInteger(R.integer.syn_duree_survol))
                .setInterpolator(AnimationUtils.loadInterpolator(v.getContext(), R.interpolator.syn_courbe)).start();
    }

    /**
     * Ends {@code v}'s lift at once, at its size: over a page about to slide, a lift under way would
     * redraw the whole page every frame of the slide.
     */
    static void settle(View v) {
        Object s = v.getTag(R.id.lift);
        v.animate().cancel();
        if (s instanceof Float) {
            v.setScaleX((Float) s);
            v.setScaleY((Float) s);
        }
    }
}
