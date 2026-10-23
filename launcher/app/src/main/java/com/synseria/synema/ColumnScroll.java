package com.synseria.synema;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import java.util.ArrayList;

/**
 * The dashboard's left column, scrolling under the featured title by whole sections (the direct children
 * of its content: the favourites, then each row with its title). It stays put while the focused section
 * is in full view; otherwise that section, title included, becomes the first one shown. The content has
 * room below for the last section to come up too.
 *
 * What lies above the top padding is never drawn, so a section shown first has nothing of the one
 * before above it; while the column moves, a fixed fade softens that edge. Both are drawn, not masked:
 * a computed mask would cost this GPU a layer.
 *
 * Up and Down always go to the next line of cards that way, whatever its distance, onto its card in
 * view nearest across: a row is never skipped for one further on that happens to sit closer. Down from
 * the last line goes nowhere.
 */
public final class ColumnScroll extends ScrollView {
    private static final int FADE_DP = 12;
    private static final int FADE_COLOR = 0xB30B1220;
    /** How long after the last scroll step the column counts as still. */
    private static final long SETTLE_MS = 100;

    private final int fadeHeight, lineSlack;
    private final GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{FADE_COLOR, FADE_COLOR & 0x00FFFFFF});
    private final Rect from = new Rect(), to = new Rect();
    private final ArrayList<View> candidates = new ArrayList<>();
    private boolean moving;
    private final Runnable settled = () -> {
        moving = false;
        invalidate();
    };

    public ColumnScroll(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float d = ctx.getResources().getDisplayMetrics().density;
        fadeHeight = Math.round(FADE_DP * d);
        lineSlack = Math.round(4 * d);
        // The rows' windows reach into the sides (CardRow): as wide as a card's focus needs.
        int room = Focus.cardRoom(ctx);
        setPaddingRelative(room, getPaddingTop(), room, getPaddingBottom());
    }

    /** The scroll that shows the section holding {@code rect}: none while it is in full view, else its top. */
    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        if (getChildCount() == 0) return 0;
        ViewGroup content = (ViewGroup) getChildAt(0);
        int y = rect.centerY() - content.getTop(), scroll = getScrollY();
        for (int i = 0; i < content.getChildCount(); i++) {
            View section = content.getChildAt(i);
            if (section.getVisibility() == GONE || y < section.getTop() || y >= section.getBottom()) continue;
            boolean whole = section.getTop() >= scroll
                    && content.getTop() + section.getBottom() - scroll <= getHeight() - getPaddingBottom();
            return whole ? 0 : section.getTop() - scroll;
        }
        return 0;
    }

    /** Up and Down: the nearest line of cards that way, then its card nearest across; never a free scroll. */
    @Override
    public boolean arrowScroll(int direction) {
        View current = findFocus();
        if (current == null || current == this || (direction != FOCUS_UP && direction != FOCUS_DOWN)) return false;
        boolean down = direction == FOCUS_DOWN;
        rectOf(current, from);
        candidates.clear();
        addFocusables(candidates, direction);   // CardRow lends only its cards in view
        // A row's cards share their top: the nearest line that way wins, then its card nearest across.
        View next = null;
        int nextLine = Integer.MAX_VALUE, nextDx = Integer.MAX_VALUE;
        for (View v : candidates) {
            if (v == this || v == current) continue;
            rectOf(v, to);
            if (down ? to.top < from.bottom : to.bottom > from.top) continue;
            int line = down ? to.top : -to.bottom, dx = Math.abs(to.centerX() - from.centerX());
            if (line < nextLine - lineSlack) {
                nextLine = line;
            } else if (line > nextLine + lineSlack || dx >= nextDx) {
                continue;
            }
            next = v;
            nextDx = dx;
        }
        candidates.clear();
        // Down from the last line stays there: the focus finder would take it to the panel beside.
        if (next == null) return down;
        rectOf(next, to);
        smoothScrollBy(0, computeScrollDeltaToGetChildRectOnScreen(to));
        return next.requestFocus(direction);
    }

    /** The view's bounds in this column's scrolled coordinates (rows' own scrolls included). */
    private void rectOf(View v, Rect out) {
        v.getDrawingRect(out);
        offsetDescendantRectToMyCoords(v, out);
    }

    /** Room under the content as tall as the column, so the last section can come to the top as well. */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (getChildCount() == 0) return;
        ViewGroup content = (ViewGroup) getChildAt(0);
        // Padded, it would clip to its padding box: the first card's lift, which reaches past its side.
        content.setClipToPadding(false);
        post(() -> content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(), h));
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        moving = true;
        removeCallbacks(settled);
        postDelayed(settled, SETTLE_MS);
    }

    /** The canvas is in the scrolled content's coordinates: the clip and the fade stay at the column's top. */
    @Override
    protected void dispatchDraw(Canvas canvas) {
        int top = getScrollY() + getPaddingTop();
        canvas.save();
        canvas.clipRect(0, top, getWidth(), getScrollY() + getHeight());
        super.dispatchDraw(canvas);
        canvas.restore();
        if (!moving) return;
        fade.setBounds(0, top, getWidth(), top + fadeHeight);
        fade.draw(canvas);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(settled);
        super.onDetachedFromWindow();
    }
}
