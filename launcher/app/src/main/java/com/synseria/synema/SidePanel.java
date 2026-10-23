package com.synseria.synema;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * The dashboard's House panel on the right (HousePanel fills it). Down stops at its last line of tiles:
 * the focus finder would otherwise drop to a card of the column, lower on the left.
 */
public final class SidePanel extends LinearLayout {
    public SidePanel(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
    }

    @Override
    public View focusSearch(View focused, int direction) {
        View next = super.focusSearch(focused, direction);
        if (direction == FOCUS_DOWN && (next == null || !isAncestorOf(next))) return focused;
        return next;
    }

    private boolean isAncestorOf(View v) {
        for (Object p = v.getParent(); p instanceof View; p = ((View) p).getParent()) if (p == this) return true;
        return false;
    }
}
