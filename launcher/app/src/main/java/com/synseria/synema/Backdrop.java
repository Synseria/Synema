package com.synseria.synema;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * What the dashboard's background shows, composed for MovingBackdrop (the surface under the window
 * that draws it and fades from one to the next): the focused title's artwork, sharp and centred, shaded
 * towards the base where the words and the rows sit; a colour theme's gradient; or a picture
 * (Config.backdrop). Artwork is composed off the main thread into a SCENE_W picture, the shading painted
 * in it, so the surface only draws a texture.
 */
final class Backdrop {
    static final int NAVY = 0xFF0B1220;
    /**
     * The artwork is decoded and composed this wide (16:9), and the surface is this size: the screen's
     * own pixels, so a picture is as sharp as the TV can show it. The surface draws only while it fades
     * or drifts, so the pixels cost frames only then.
     */
    static final int SCENE_W = 1920, SCENE_H = SCENE_W * 9 / 16;
    /** A theme's gradient is computed this small: smooth once stretched. */
    private static final int OUT_W = 128, OUT_H = 72;
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();

    private final MovingBackdrop surface;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Bumped on every change, so an artwork still being composed for a title left behind is dropped. */
    private int generation;

    Backdrop(MovingBackdrop surface) {
        this.surface = surface;
    }

    /** The focused title's artwork (any size), composed off the main thread, then faded in. */
    void show(Bitmap artwork) {
        if (artwork == null) return;
        int gen = ++generation;
        WORK.execute(() -> {
            Bitmap scene = scene(artwork);
            main.post(() -> {
                if (gen == generation) surface.show(scene);
            });
        });
    }

    /** A colour theme: a dark base lit by two soft glows, top left and bottom right. */
    static final class Theme {
        final String id;
        final int label, base, glowA, glowB;

        Theme(String id, int label, int base, int glowA, int glowB) {
            this.id = id;
            this.label = label;
            this.base = base;
            this.glowA = glowA;
            this.glowB = glowB;
        }
    }

    static final Theme[] THEMES = {
            new Theme("night", R.string.theme_night, NAVY, 0xFF155A70, 0xFF462A80),
            new Theme("aurora", R.string.theme_aurora, 0xFF06120F, 0xFF136B52, 0xFF1D4580),
            new Theme("dusk", R.string.theme_dusk, 0xFF150C14, 0xFF8A3A1C, 0xFF52226E),
            new Theme("ocean", R.string.theme_ocean, 0xFF06111C, 0xFF14508E, 0xFF0D6A78),
            new Theme("graphite", R.string.theme_graphite, 0xFF0E0F12, 0xFF33363E, 0xFF24262C),
    };

    /** The theme with this id, or null. */
    static Theme theme(String id) {
        for (Theme t : THEMES) if (t.id.equals(id)) return t;
        return null;
    }

    /** The theme's gradient, faded in. */
    void gradient(Theme theme) {
        generation++;
        surface.show(Bitmap.createBitmap(glows(theme), OUT_W, OUT_H, Bitmap.Config.ARGB_8888));
    }

    /** The theme's two glows. */
    private static int[] glows(Theme theme) {
        float ax = 0.12f, ay = 0.15f, bx = 0.92f, by = 0.85f;
        int[] px = new int[OUT_W * OUT_H];
        for (int y = 0; y < OUT_H; y++) {
            for (int x = 0; x < OUT_W; x++) {
                float u = x / (float) OUT_W, v = y / (float) OUT_H;
                px[y * OUT_W + x] = lit(theme, glow(u, v, ax, ay, 0.75f), glow(u, v, bx, by, 0.85f));
            }
        }
        return px;
    }

    /** The theme's base with each glow added at its strength (0 to 1). */
    static int lit(Theme t, float a, float b) {
        int[] out = new int[3];
        for (int c = 0, shift = 16; c < 3; c++, shift -= 8) {
            int base = (t.base >> shift) & 0xFF;
            float v = base + (((t.glowA >> shift) & 0xFF) - base) * a + (((t.glowB >> shift) & 0xFF) - base) * b;
            out[c] = Math.max(0, Math.min(255, Math.round(v)));
        }
        return 0xFF000000 | out[0] << 16 | out[1] << 8 | out[2];
    }

    /** A picture, as it is. */
    void picture(Bitmap full) {
        generation++;
        surface.show(full);
    }

    /** The focus moved on: an artwork still being composed for the title left is dropped. */
    void hold() {
        generation++;
    }

    /** Back to the plain navy, waiting for artwork. */
    void clear() {
        generation++;
        surface.show(Bitmap.createBitmap(new int[]{NAVY}, 1, 1, Bitmap.Config.ARGB_8888));
    }

    /** Soft round light at (cx, cy), 1 at its centre fading to 0 at {@code radius}. */
    private static float glow(float u, float v, float cx, float cy, float radius) {
        float d = (float) Math.hypot((u - cx) * 16 / 9f, v - cy) / radius;
        return d >= 1 ? 0 : (1 - d) * (1 - d);
    }

    /**
     * The screensaver's picture: the artwork as the dashboard shows it, at a third of its light (an
     * OLED panel kept dim while nobody watches), or the night theme when there is no artwork. Composed
     * here: call it off the main thread.
     */
    static Bitmap dream(Bitmap artwork) {
        int w = artwork != null ? SCENE_W : OUT_W, h = artwork != null ? SCENE_H : OUT_H;
        int[] px = new int[w * h];
        if (artwork != null) scene(artwork).getPixels(px, 0, w, 0, 0, w, h);
        else px = glows(THEMES[0]);
        for (int i = 0; i < px.length; i++) {
            int p = px[i];
            px[i] = 0xFF000000 | (((p >> 16) & 0xFF) / 3) << 16 | (((p >> 8) & 0xFF) / 3) << 8 | (p & 0xFF) / 3;
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /**
     * The artwork filling the screen's shape, centred (a little above the middle, where faces are), and
     * shaded towards the base: at the left behind the featured title's words, at the bottom behind the
     * rows, at the top behind the time, a touch at the right behind the House panel.
     */
    private static Bitmap scene(Bitmap artwork) {
        Bitmap out = Bitmap.createBitmap(SCENE_W, SCENE_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(NAVY);
        float scale = Math.max(SCENE_W / (float) artwork.getWidth(), SCENE_H / (float) artwork.getHeight());
        float w = artwork.getWidth() * scale, h = artwork.getHeight() * scale;
        RectF to = new RectF((SCENE_W - w) / 2, (SCENE_H - h) * 0.3f, 0, 0);
        to.right = to.left + w;
        to.bottom = to.top + h;
        c.drawBitmap(artwork, null, to, new Paint(Paint.FILTER_BITMAP_FLAG));
        shade(c, 0, 0, SCENE_W, 0, new float[]{0.92f, 0.70f, 0f}, new float[]{0, 0.30f, 0.58f});
        shade(c, 0, SCENE_H, 0, 0, new float[]{1f, 0.88f, 0f}, new float[]{0, 0.36f, 0.64f});
        shade(c, 0, 0, 0, SCENE_H, new float[]{0.80f, 0f}, new float[]{0, 0.20f});
        shade(c, SCENE_W, 0, 0, 0, new float[]{0.55f, 0f}, new float[]{0, 0.30f});
        return out;
    }

    /** The base laid over the whole picture from (x0, y0) towards (x1, y1), at these strengths and stops. */
    private static void shade(Canvas c, float x0, float y0, float x1, float y1, float[] alphas, float[] stops) {
        int[] colors = new int[alphas.length];
        for (int i = 0; i < alphas.length; i++) colors[i] = Math.round(alphas[i] * 255) << 24 | (NAVY & 0xFFFFFF);
        Paint p = new Paint();
        p.setShader(new LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, SCENE_W, SCENE_H, p);
    }
}
