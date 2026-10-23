package com.synseria.synema;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.ImageView;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * A centre-cropped picture with rounded corners, drawn as a rounded rectangle filled with the bitmap.
 * Rounding by clipping (clipToOutline) makes the TV's GPU wait on every frame of a focus animation —
 * the dashboard dropped most of its frames — while a bitmap shader costs no more than a plain image.
 * With no picture yet it is a faint rounded placeholder, the same shape. Every picture has the cards'
 * corners (card_corner), so the focus ring round a card's frame follows them.
 *
 * A larger picture is scaled down to the view once, when either changes, then drawn pixel for pixel:
 * the GPU's own filter, from two or three times the size, blurs some details and drops others (the
 * letters of a logo).
 */
public final class RoundedImageView extends ImageView {
    private final float radius;
    private static final int PLACEHOLDER = 0x14FFFFFF;
    /** Pictures already scaled for a view, by original: rows rebuilt and grids scrolled reuse them. */
    private static final Map<Bitmap, Bitmap> FITTED = new WeakHashMap<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint empty = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final RectF bounds = new RectF();
    private Bitmap bitmap;

    RoundedImageView(Context ctx) {
        this(ctx, null);
    }

    public RoundedImageView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        radius = ctx.getResources().getDimension(R.dimen.card_corner);
        empty.setColor(PLACEHOLDER);
    }

    @Override
    public void setImageDrawable(Drawable d) {
        super.setImageDrawable(d);
        bitmap = d == null ? null : toBitmap(d);
        fit();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fit();
    }

    /** The shader for this picture at this size: scaled down beforehand, or up by the GPU. */
    private void fit() {
        int w = getWidth(), h = getHeight();
        if (bitmap == null || w == 0 || h == 0) {
            paint.setShader(null);
            return;
        }
        float scale = Math.max(w / (float) bitmap.getWidth(), h / (float) bitmap.getHeight());
        Bitmap shown = scale < 1f
                ? scaledDown(bitmap, Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale))
                : bitmap;
        float s = scale < 1f ? 1f : scale;
        matrix.setScale(s, s);
        matrix.postTranslate(Math.round((w - shown.getWidth() * s) / 2f), Math.round((h - shown.getHeight() * s) / 2f));
        BitmapShader shader = new BitmapShader(shown, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);
    }

    /**
     * {@code src} at w × h: halved while still twice as large (each step averages 2 × 2 pixels, so
     * nothing drops out), then filtered to the size.
     */
    private static Bitmap scaledDown(Bitmap src, int w, int h) {
        Bitmap kept = FITTED.get(src);
        if (kept != null && kept.getWidth() == w && kept.getHeight() == h) return kept;
        Bitmap b = src;
        while (b.getWidth() / 2 >= w && b.getHeight() / 2 >= h) {
            Bitmap half = Bitmap.createScaledBitmap(b, b.getWidth() / 2, b.getHeight() / 2, true);
            if (b != src) b.recycle();
            b = half;
        }
        if (b.getWidth() != w || b.getHeight() != h) {
            Bitmap sized = Bitmap.createScaledBitmap(b, Math.max(1, w), Math.max(1, h), true);
            if (b != src) b.recycle();
            b = sized;
        }
        FITTED.put(src, b);
        return b;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() == 0) return;
        bounds.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(bounds, radius, radius, paint.getShader() != null ? paint : empty);
    }

    /** A banner is often a vector or adaptive drawable: rasterised once at its own size. */
    private static Bitmap toBitmap(Drawable d) {
        if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) return ((BitmapDrawable) d).getBitmap();
        int w = Math.max(1, d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 320);
        int h = Math.max(1, d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 180);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return b;
    }
}
