package com.synseria.synema;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * Children side by side on one line, aligned to the left and centred in the height. What does not fit
 * is left out whole, with everything after it, rather than squeezed or cut.
 */
public final class FlowRow extends ViewGroup {
    private final int gap;
    /** How many children are laid out (the first ones) and their height; set by onMeasure. */
    private int shown, contentHeight;

    public FlowRow(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        gap = ctx.getResources().getDimensionPixelSize(R.dimen.syn_espace_2);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int room = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int used = 0;
        shown = 0;
        contentHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            measureChild(c, MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST), heightSpec);
            int w = c.getMeasuredWidth();
            if (used > 0 && used + gap + w > room) break;
            if (w > room) continue;
            used += (used > 0 ? gap : 0) + w;
            contentHeight = Math.max(contentHeight, c.getMeasuredHeight());
            shown = i + 1;
        }
        setMeasuredDimension(resolveSize(width, widthSpec),
                resolveSize(contentHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int top = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - contentHeight) / 2;
        int x = getPaddingLeft();
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (i >= shown || c.getVisibility() == GONE || c.getMeasuredWidth() > getWidth() - getPaddingLeft() - getPaddingRight()) {
                c.layout(0, 0, 0, 0);
                continue;
            }
            int y = top + (contentHeight - c.getMeasuredHeight()) / 2;
            c.layout(x, y, x + c.getMeasuredWidth(), y + c.getMeasuredHeight());
            x += c.getMeasuredWidth() + gap;
        }
    }
}
