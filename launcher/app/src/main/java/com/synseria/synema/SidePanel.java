package com.synseria.synema;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/**
 * The dashboard's House panel on the right (HousePanel fills it). Down stops at its last line of tiles:
 * the focus finder would otherwise drop to a card of the column, lower on the left. Its rooms (TileScroll)
 * are as tall as they are, no taller than the panel leaves for what follows them: that follows them, the
 * spare height going under it.
 */
public final class SidePanel extends LinearLayout {
    public SidePanel(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        TileScroll scroll = null;
        for (int i = 0; i < getChildCount() && scroll == null; i++) if (getChildAt(i) instanceof TileScroll) scroll = (TileScroll) getChildAt(i);
        if (scroll == null) {
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        LayoutParams lp = (LayoutParams) scroll.getLayoutParams();
        lp.height = LayoutParams.WRAP_CONTENT;
        super.onMeasure(widthSpec, heightSpec);
        int used = getPaddingTop() + getPaddingBottom();
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            LayoutParams clp = (LayoutParams) c.getLayoutParams();
            used += c.getMeasuredHeight() + clp.topMargin + clp.bottomMargin;
        }
        int over = used - getMeasuredHeight();
        if (over > 0) {
            lp.height = Math.max(0, scroll.getMeasuredHeight() - over);
            super.onMeasure(widthSpec, heightSpec);
        }
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
