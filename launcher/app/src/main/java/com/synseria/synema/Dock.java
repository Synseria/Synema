package com.synseria.synema;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * The favourites, never scrolling: six 16:9 cards to a line, their pictures from MIN_DP to MAX_DP wide as the width allows, the space between them taking the rest.
 * The pictures keep Focus.cardRoom from each other, and the first and last stand on the column's edges
 * like a card row's: the dock reaches a frame's inset past them (margins out, no side padding).
 */
public final class Dock extends ViewGroup {
    static final int PER_LINE = 6;
    private static final int MIN_DP = 96, MAX_DP = 116, GAP_DP = 16, LINE_GAP_DP = 6;

    private final int inset, min, max, gap, room, lineGap;
    /** The card width (frame) and the distance from one card to the next, set by onMeasure. */
    private int cell;
    private float step;

    public Dock(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float d = ctx.getResources().getDisplayMetrics().density;
        inset = ctx.getResources().getDimensionPixelSize(R.dimen.card_inset);
        min = Math.round(MIN_DP * d);
        max = Math.round(MAX_DP * d);
        gap = Math.round(GAP_DP * d);
        lineGap = Math.round(LINE_GAP_DP * d);
        room = Focus.cardRoom(ctx);
        setPadding(0, room - inset, 0, room - inset);
    }

    @Override
    protected void onAttachedToWindow() {
        // Left and right, not start and end: a relative margin set here is never resolved again.
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) getLayoutParams();
        lp.leftMargin = -inset;
        lp.rightMargin = -inset;
        super.onAttachedToWindow();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int frames = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int pictures = frames - 2 * inset;
        int picture = Math.max(min, Math.min(max, (pictures - (PER_LINE - 1) * gap) / PER_LINE));
        // Never so wide that the pictures come closer than the room a focused one needs.
        picture = Math.min(picture, (pictures - (PER_LINE - 1) * room) / PER_LINE);
        cell = picture + 2 * inset;
        step = cell + Math.max(0, frames - PER_LINE * cell) / (float) (PER_LINE - 1);
        int rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            rowHeight = Math.max(rowHeight, c.getMeasuredHeight());
        }
        int lines = (getChildCount() + PER_LINE - 1) / PER_LINE;
        int height = lines * rowHeight + Math.max(0, lines - 1) * lineGap;
        setMeasuredDimension(width, getChildCount() == 0 ? 0 : height + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            int x = getPaddingLeft() + Math.round(i % PER_LINE * step);
            int y = getPaddingTop() + i / PER_LINE * (c.getMeasuredHeight() + lineGap);
            c.layout(x, y, x + cell, y + c.getMeasuredHeight());
        }
    }
}
