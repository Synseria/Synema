package com.synseria.synema;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;

/**
 * The dashboard's House panel on the right (HousePanel fills it). Down stops at its last line of tiles:
 * the focus finder would otherwise drop to a card of the column, lower on the left. Its rooms (TileScroll)
 * are as tall as they are, no taller than the panel leaves for what follows them: that follows them, the
 * spare height going under it. Once the focus leaves the panel, the rooms are back at the top.
 */
public final class SidePanel extends LinearLayout {
    private final ViewTreeObserver.OnGlobalFocusChangeListener left = (from, to) -> {
        TileScroll scroll = rooms();
        if (scroll != null && from != null && isAncestorOf(from) && (to == null || !isAncestorOf(to))) scroll.scrollTo(0, 0);
    };

    public SidePanel(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalFocusChangeListener(left);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalFocusChangeListener(left);
        super.onDetachedFromWindow();
    }

    private TileScroll rooms() {
        for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof TileScroll) return (TileScroll) getChildAt(i);
        return null;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        TileScroll scroll = rooms();
        if (scroll == null) {
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        LayoutParams lp = (LayoutParams) scroll.getLayoutParams();
        lp.height = LayoutParams.WRAP_CONTENT;
        super.onMeasure(widthSpec, heightSpec);
        // The others at their natural height: measured after the rooms took the space, the last of them comes
        // out already squeezed, and the overflow would be undercounted (the electricity cut at the bottom).
        int used = getPaddingTop() + getPaddingBottom();
        int unbounded = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            LayoutParams clp = (LayoutParams) c.getLayoutParams();
            int h = c.getMeasuredHeight();
            if (c != scroll) {
                c.measure(MeasureSpec.makeMeasureSpec(c.getMeasuredWidth(), MeasureSpec.EXACTLY), unbounded);
                h = c.getMeasuredHeight();
            }
            used += h + clp.topMargin + clp.bottomMargin;
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
