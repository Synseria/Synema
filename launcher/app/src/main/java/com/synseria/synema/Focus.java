package com.synseria.synema;

import android.content.Context;
import android.view.View;

/**
 * The one look of focus, everywhere: a card (a picture) grows a little under a white ring with a sheen
 * (dash_focus_ring, on the card's frame), a tile lights up under a ring (dash_tile_bg), a top button
 * becomes a white disc (icon_button_bg). None casts a shadow, which costs this GPU a blur per frame.
 *
 * What holds a card leaves room round its picture for its lift and ring (room, from the constants
 * here), and only a row's window clips, across: nothing focused is ever cut, nor reaches a neighbour.
 */
final class Focus {
    static final float CARD = 1.04f, TILE = 1.04f, BUTTON = 1.12f;
    /** No card's picture on the dashboard is wider (dp): a row takes one more card rather than widen them. */
    static final int WIDEST_CARD_DP = 160;
    /** A tile's name: light in every state, as focus no longer turns the tile white. */
    static final int TILE_TEXT = 0xFFF2F2F5;
    private static final long LIFT_MS = 120;

    private Focus() {
    }

    /** A card: its frame (Card) shows the ring and sheen, and the card grows while focused. */
    static void card(View card) {
        card.setForeground(card.getContext().getDrawable(R.drawable.dash_focus_ring));
        focusable(card, CARD);
    }

    /** A tile of the House panel or a room page: lit and ringed by its background, it grows a little. */
    static void tile(View tile) {
        tile.setBackgroundResource(R.drawable.dash_tile_bg);
        focusable(tile, TILE);
    }

    /** A block that keeps its own background, a selector whose focused state is a tile's: it grows a little. */
    static void block(View v) {
        focusable(v, TILE);
    }

    private static void focusable(View v, float scale) {
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnFocusChangeListener((view, has) -> lift(view, has, scale));
    }

    /**
     * How far a focused card's ring reaches past its picture (px), on a side {@code side} px long: its
     * gap and ring (card_inset), and half of what the frame grows by.
     */
    static float reach(Context ctx, float side) {
        int inset = ctx.getResources().getDimensionPixelSize(R.dimen.card_inset);
        return inset + (CARD - 1) * (side + 2 * inset) / 2;
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
    static int tileReach(int side) {
        return (int) Math.ceil((TILE - 1) * side / 2) + 1;
    }

    /** Grows {@code v} while it has the focus, back when it loses it. */
    static void lift(View v, boolean has, float scale) {
        float s = has ? scale : 1f;
        v.setTag(R.id.lift, s);
        v.animate().scaleX(s).scaleY(s).setDuration(LIFT_MS).start();
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
