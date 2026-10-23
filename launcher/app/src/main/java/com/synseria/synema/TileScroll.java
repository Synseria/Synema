package com.synseria.synema;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.FocusFinder;
import android.view.View;
import android.widget.ScrollView;

/**
 * Tiles scrolling between fixed neighbours: the House panel's rooms under its tiles, a room page's things
 * over its graphs. It clips at its top and bottom only, so no tile is drawn over a neighbour while a
 * focused one's lift and ring still spill sideways: a tile focused comes into view with the room its
 * lift takes. Up from the first line and Down from the last leave it at once,
 * never a free scroll.
 */
public final class TileScroll extends ScrollView {
    private final Rect lifted = new Rect();

    public TileScroll(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        setClipChildren(false);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        canvas.save();
        canvas.clipRect(-getWidth(), getScrollY(), 2 * getWidth(), getScrollY() + getHeight());
        super.dispatchDraw(canvas);
        canvas.restore();
    }

    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        lifted.set(rect);
        lifted.inset(0, -Focus.tileReach(rect.height()));
        return super.computeScrollDeltaToGetChildRectOnScreen(lifted);
    }

    /** Nothing more that way inside: the key goes on to the tiles or the electricity, not to the last pixels. */
    @Override
    public boolean arrowScroll(int direction) {
        View current = findFocus();
        if (current != null && current != this && FocusFinder.getInstance().findNextFocus(this, current, direction) == null) return false;
        return super.arrowScroll(direction);
    }
}
