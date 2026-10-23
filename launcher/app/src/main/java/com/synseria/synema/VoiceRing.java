package com.synseria.synema;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * A soft disc behind a mic button, reaching past it as far as the voice is loud, inside the room its
 * parent keeps round the button: it never covers anything else. Hidden while nothing listens. The
 * assistant's mic and the camera's talk button wear it.
 */
final class VoiceRing extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final View mic;
    /** 0..1 smoothed, or -1: not listening. */
    private float shown = -1;

    /** @param color the halo's colour; its alpha follows the voice */
    VoiceRing(Context ctx, View mic, int color) {
        super(ctx);
        this.mic = mic;
        paint.setColor(color);
    }

    /** The voice's level, 0 (silence) to 1; -1 when listening stops. */
    void level(float level) {
        shown = level < 0 ? -1 : shown < 0 ? level : shown * 0.6f + level * 0.4f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (shown < 0) return;
        float inner = mic.getWidth() * mic.getScaleX() / 2f, outer = Math.min(getWidth(), getHeight()) / 2f;
        paint.setAlpha(Math.round(255 * (0.16f + 0.24f * shown)));
        canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, inner + (outer - inner) * (0.25f + 0.75f * shown), paint);
    }
}
