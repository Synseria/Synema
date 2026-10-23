package com.synseria.synema;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import java.util.Arrays;

/**
 * Children in lines aligned to the right, wrapping onto {@link #MAX_LINES} lines at most, the lines
 * centred in the height. What does not fit is left out whole rather than squeezed or cut.
 */
public final class FlowRow extends ViewGroup {
    private static final int MAX_LINES = 2;
    private final int gap;
    /** Each child's line (-1 when left out), each line's width and height, and theirs together; set by onMeasure. */
    private int[] lineOf = new int[0];
    private final int[] lineWidth = new int[MAX_LINES], lineHeight = new int[MAX_LINES];
    private int lines, contentHeight;

    public FlowRow(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        gap = Math.round(4 * ctx.getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int room = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        int n = getChildCount();
        if (lineOf.length < n) lineOf = new int[n];
        Arrays.fill(lineWidth, 0);
        Arrays.fill(lineHeight, 0);
        int line = 0;
        for (int i = 0; i < n; i++) {
            View c = getChildAt(i);
            lineOf[i] = -1;
            if (c.getVisibility() == GONE || line >= MAX_LINES) continue;
            measureChild(c, MeasureSpec.makeMeasureSpec(room, MeasureSpec.AT_MOST), heightSpec);
            int w = c.getMeasuredWidth();
            if (lineWidth[line] > 0 && lineWidth[line] + gap + w > room) line++;
            if (line >= MAX_LINES || w > room) continue;
            lineWidth[line] += (lineWidth[line] > 0 ? gap : 0) + w;
            lineHeight[line] = Math.max(lineHeight[line], c.getMeasuredHeight());
            lineOf[i] = line;
        }
        lines = 0;
        contentHeight = 0;
        for (int l = 0; l < MAX_LINES && lineWidth[l] > 0; l++) contentHeight += (lines++ > 0 ? gap : 0) + lineHeight[l];
        setMeasuredDimension(resolveSize(width, widthSpec),
                resolveSize(contentHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int top = getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - contentHeight) / 2;
        for (int line = 0; line < lines; line++) {
            int x = getWidth() - getPaddingRight() - lineWidth[line];
            for (int i = 0; i < getChildCount(); i++) {
                if (lineOf[i] != line) continue;
                View c = getChildAt(i);
                int y = top + (lineHeight[line] - c.getMeasuredHeight()) / 2;
                c.layout(x, y, x + c.getMeasuredWidth(), y + c.getMeasuredHeight());
                x += c.getMeasuredWidth() + gap;
            }
            top += lineHeight[line] + gap;
        }
        for (int i = 0; i < getChildCount(); i++) if (lineOf[i] == -1) getChildAt(i).layout(0, 0, 0, 0);
    }
}
