package com.synseria.synema;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.FocusFinder;
import android.view.View;
import android.widget.ScrollView;

/**
 * The House panel's rooms, scrolling alone under its fixed tiles. It clips at its bounds, so no room is
 * drawn over the tiles or the electricity: a room focused comes into view with the room its lift takes.
 * Up from the first line and Down from the last leave it at once, never a free scroll.
 */
public final class RoomsScroll extends ScrollView {
    private final Rect lifted = new Rect();

    public RoomsScroll(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
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
