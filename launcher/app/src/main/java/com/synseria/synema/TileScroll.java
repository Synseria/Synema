package com.synseria.synema;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.FocusFinder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

import java.util.Arrays;

/**
 * Tiles scrolling between fixed neighbours: the House panel's rooms under its tiles, a room page's things
 * over its graphs. It clips at its top and bottom only, so no tile is drawn over a neighbour while a
 * focused one's lift and ring still spill sideways: a tile focused comes into view with the room its
 * lift takes. Up from the first line and Down from the last leave it at once,
 * never a free scroll.
 */
public final class TileScroll extends ScrollView {
    private final Rect lifted = new Rect(), line = new Rect();
    /** The holders whose children are whole lines (byLines); none: a plain scroll. */
    private ViewGroup[] holders = {};
    /** The lines as last read, in the scrolled content's coordinates (those of getScrollY), top to bottom. */
    private int lines;
    private int[] tops = new int[8], bottoms = new int[8];

    public TileScroll(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        setClipChildren(false);
    }

    /**
     * Scrolls by whole lines, the children of these holders: it rests with a line where the first one rests,
     * or at the end, and a line that does not fit whole with its lift is not drawn (nothing shows cut). The
     * jump is immediate: a glide would show lines cut on its way.
     */
    void byLines(ViewGroup... holders) {
        this.holders = holders;
        setSmoothScrollingEnabled(false);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        int top = getScrollY(), bottom = top + getHeight();
        readLines();
        int first = -1, last = -1;
        for (int k = 0; k < lines; k++) {
            if (!fits(k, getScrollY())) continue;
            if (first < 0) first = k;
            last = k;
        }
        if (first >= 0) {
            top = Math.max(top, tops[first] - reach(first));
            bottom = Math.min(bottom, bottoms[last] + reach(last));
        }
        canvas.save();
        canvas.clipRect(-getWidth(), top, 2 * getWidth(), bottom);
        super.dispatchDraw(canvas);
        canvas.restore();
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        readLines();
        int k = lineOf(rect);
        if (k < 0) {
            lifted.set(rect);
            lifted.inset(0, -Focus.tileReach(getContext(), rect.height()));
            return super.computeScrollDeltaToGetChildRectOnScreen(lifted);
        }
        int y = getScrollY();
        if (fits(k, y)) return 0;
        // Up: the line where the first one rests. Down: the first resting place that shows it whole; past the
        // end the scroll stops at the end, which shows the last line whole.
        int target = tops[k] - tops[0];
        if (tops[k] - reach(k) >= y) {
            for (int j = 0; j < k; j++) {
                if (fits(k, tops[j] - tops[0])) {
                    target = tops[j] - tops[0];
                    break;
                }
            }
        }
        return target - y;
    }

    /** Nothing more that way inside: the key goes on to the tiles or the electricity, not to the last pixels. */
    @Override
    public boolean arrowScroll(int direction) {
        View current = findFocus();
        if (current != null && current != this && FocusFinder.getInstance().findNextFocus(this, current, direction) == null) return false;
        return super.arrowScroll(direction);
    }

    /** Line {@code k} shows whole, its lift included, when scrolled to {@code y}. */
    private boolean fits(int k, int y) {
        return tops[k] - reach(k) >= y && bottoms[k] + reach(k) <= y + getHeight();
    }

    private int reach(int k) {
        return Focus.tileReach(getContext(), bottoms[k] - tops[k]);
    }

    /** The line holding this rect, or -1. */
    private int lineOf(Rect r) {
        for (int k = 0; k < lines; k++) if (tops[k] <= r.top && r.bottom <= bottoms[k]) return k;
        return -1;
    }

    /** Read on every use, never kept: a line rebuilt or moved is never measured stale. */
    private void readLines() {
        lines = 0;
        for (ViewGroup h : holders) {
            if (h.getVisibility() != VISIBLE) continue;
            for (int i = 0; i < h.getChildCount(); i++) {
                View v = h.getChildAt(i);
                if (v.getVisibility() != VISIBLE) continue;
                v.getDrawingRect(line);
                offsetDescendantRectToMyCoords(v, line);
                if (lines == tops.length) {
                    tops = Arrays.copyOf(tops, 2 * lines);
                    bottoms = Arrays.copyOf(bottoms, 2 * lines);
                }
                tops[lines] = line.top;
                bottoms[lines] = line.bottom;
                lines++;
            }
        }
    }
}
