package com.synseria.synema;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;

import java.util.ArrayList;

/**
 * A row of cards filling its width: as many as fit at MIN_DP or wider, stretched to fill it exactly.
 * Its children are cells (a Card, and what goes under it). Beyond those in view, the row slides by one
 * card as focus walks along it: Left and Right reach the hidden cards, Left on the first does nothing,
 * Right on the last leaves the row. The picture at an edge fades out when more cards wait that way; the
 * focused card is drawn last, over the fades and its neighbours. Only the cards in view take part in
 * focus searches, so Up and Down land on the nearest visible one.
 *
 * The pictures stand Focus.cardRoom apart, and the row's window reaches as far past the first and the
 * last, into the column's padding (negative margins, set here): a focused card's lift and ring never
 * reach a neighbour nor the window's edge. The window clips across only; nothing clips it up and down.
 */
public final class CardRow extends ViewGroup {
    /** Narrowest picture (dp). */
    private static final int MIN_DP = 118;
    /**
     * How much of the edge picture fades (dp), towards the dashboard's navy: a mask would let the
     * background through, but costs this GPU a layer per frame of a slide.
     */
    private static final int FADE_DP = 40;
    private static final int FADE_COLOR = 0xE60B1220;
    /** Short: every frame of a slide redraws the row. */
    private static final long SLIDE_MS = 180;

    private final int inset, gap, min, widest, fadeWidth;
    private final float corner;
    private final Paint fadeLeft = new Paint(Paint.ANTI_ALIAS_FLAG), fadeRight = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Cards in view, the cell width (frame) and the distance from one cell to the next, set by onMeasure. */
    private int fit = 1, cell;
    private float step;
    /** The first card in view, and how many cells hold a card (placeholders do not). */
    private int first, items;
    /** While drawing: the focused cell, drawn last, and whether the fades are down yet. */
    private int drawnLast = -1;
    private boolean faded;
    private ValueAnimator slide;

    public CardRow(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        float d = ctx.getResources().getDisplayMetrics().density;
        inset = ctx.getResources().getDimensionPixelSize(R.dimen.card_inset);
        corner = ctx.getResources().getDimension(R.dimen.card_corner);
        gap = Focus.cardRoom(ctx);
        min = Math.round(MIN_DP * d);
        widest = Math.round(Focus.WIDEST_CARD_DP * d);
        fadeWidth = Math.round(FADE_DP * d);
        int clear = FADE_COLOR & 0x00FFFFFF;
        fadeLeft.setShader(new LinearGradient(0, 0, fadeWidth, 0, FADE_COLOR, clear, Shader.TileMode.CLAMP));
        fadeRight.setShader(new LinearGradient(0, 0, fadeWidth, 0, clear, FADE_COLOR, Shader.TileMode.CLAMP));
        setChildrenDrawingOrderEnabled(true);
        setClipChildren(false);
        setClipToPadding(false);
    }

    /** The window reaches the room past the column's content on each side: margins out, padding back in. */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) getLayoutParams();
        lp.setMarginStart(-gap);
        lp.setMarginEnd(-gap);
        setPaddingRelative(gap - inset, getPaddingTop(), gap - inset, getPaddingBottom());
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        // The frames span the content; the pictures, inset in them, span it less the insets.
        int pictures = Math.max(0, width - getPaddingLeft() - getPaddingRight() - 2 * inset);
        // As many as fit at min or wider, and never wider than Focus allows room for.
        fit = Math.max(Math.max(1, (pictures + gap) / (min + gap)), (int) Math.ceil((pictures + gap) / (double) (widest + gap)));
        float picture = (pictures - (fit - 1) * gap) / (float) fit;
        step = picture + gap;
        cell = Math.round(picture) + 2 * inset;
        int height = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            height = Math.max(height, c.getMeasuredHeight());
        }
        setMeasuredDimension(width, height + getPaddingTop() + getPaddingBottom());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        items = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            int x = getPaddingLeft() + Math.round(i * step);
            c.layout(x, getPaddingTop(), x + cell, getPaddingTop() + c.getMeasuredHeight());
            if (c.hasFocusable()) items++;
        }
        first = Math.max(0, Math.min(first, items - fit));
        if (slide == null || !slide.isRunning()) setScrollX(Math.round(first * step));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        View focused = getFocusedChild();
        int code = e.getKeyCode();
        if (focused != null && e.getAction() == KeyEvent.ACTION_DOWN
                && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            int next = indexOfChild(focused) + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            if (next < 0) return true;
            if (next < items) {
                getChildAt(next).requestFocus();
                return true;
            }
            // Past the last card the key goes on, and the focus finder leaves the row.
        }
        return super.dispatchKeyEvent(e);
    }

    /** A card taking focus is brought into view: the row slides just enough. */
    @Override
    public void requestChildFocus(View child, View focused) {
        super.requestChildFocus(child, focused);
        int i = indexOfChild(child);
        int target = i >= first + fit ? i - fit + 1 : Math.min(i, first);
        if (target != first) slideTo(target);
    }

    private void slideTo(int target) {
        first = target;
        if (slide != null) slide.cancel();
        slide = ValueAnimator.ofInt(getScrollX(), Math.round(first * step));
        slide.setDuration(SLIDE_MS);
        slide.setInterpolator(new PathInterpolator(0.22f, 0.8f, 0.24f, 1f));
        slide.addUpdateListener(a -> setScrollX((int) a.getAnimatedValue()));
        slide.start();
    }

    /** The cards in view only (those the row slides to): Up and Down never aim at a hidden one. */
    @Override
    public void addFocusables(ArrayList<View> views, int direction, int focusableMode) {
        int left = getPaddingLeft() + Math.round(first * step) - 1;
        int right = left + getWidth() - getPaddingLeft() - getPaddingRight() + 2;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getLeft() >= left && c.getRight() <= right) c.addFocusables(views, direction, focusableMode);
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        drawnLast = indexOfChild(getFocusedChild());
        faded = false;
        // The window, across only: a focused card's lift and ring spill up and down freely.
        canvas.save();
        canvas.clipRect(getScrollX(), -getHeight(), getScrollX() + getWidth(), 2 * getHeight());
        super.dispatchDraw(canvas);
        if (!faded) drawFades(canvas);
        canvas.restore();
    }

    @Override
    protected int getChildDrawingOrder(int count, int i) {
        if (drawnLast < 0) return i;
        if (i == count - 1) return drawnLast;
        return i >= drawnLast ? i + 1 : i;
    }

    /**
     * Cards whose picture is out of the pictures' span are left out of the frame, so the window's room
     * for a ring never shows a sliver of the next one; the focused card goes over the fades.
     */
    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        int left = getScrollX() + getPaddingLeft() + inset, right = getScrollX() + getWidth() - getPaddingRight() - inset;
        if (child.getRight() - inset <= left || child.getLeft() + inset >= right) return false;
        if (drawnLast >= 0 && child == getChildAt(drawnLast)) drawFades(canvas);
        return super.drawChild(canvas, child, drawingTime);
    }

    /**
     * Over the picture at each edge that has more beyond it, and nothing else: not past it, not on the
     * line under it. The canvas is in the scrolled content's coordinates, so the fades stay put while
     * the cards slide under them; at rest they match the edge pictures, corners included.
     */
    private void drawFades(Canvas canvas) {
        faded = true;
        boolean left = first > 0, right = first + fit < items;
        if (!left && !right) return;
        float top = getPaddingTop() + inset, bottom = top + Card.pictureHeight(cell - 2 * inset);
        if (left) fade(canvas, getScrollX() + getPaddingLeft() + inset, top, bottom, fadeLeft);
        if (right) fade(canvas, getScrollX() + getWidth() - getPaddingRight() - inset - fadeWidth, top, bottom, fadeRight);
    }

    private void fade(Canvas canvas, float x, float top, float bottom, Paint paint) {
        canvas.save();
        canvas.translate(x, top);
        canvas.drawRoundRect(0, 0, fadeWidth, bottom - top, corner, corner, paint);
        canvas.restore();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (slide != null) slide.cancel();
        super.onDetachedFromWindow();
    }
}
