package com.synseria.synema;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.view.Choreographer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The background, always: its own GL surface under the window, showing what Backdrop composes (the
 * artwork, a colour theme, a picture), a new one fading in over the old in the shader. Still, it draws
 * only while a fade runs; drifting (the moving background), it also drifts and breathes slowly, a few
 * percent of the screen over a minute, every few vsyncs, timed from the vsync itself. The dashboard's
 * views are never redrawn for it. Paused with the dashboard, and held still while a room page slides.
 *
 * It is under every full-screen window of the launcher on purpose (under): on this TV a window alone is
 * composed by the display's overlay hardware, which hands buffers back late (a third of the frames
 * missed), while a window over a surface is composed by the GPU, smoothly. The window then paints no
 * background of its own: the surface does.
 */
final class MovingBackdrop extends GLSurfaceView {
    /** One loop of the drift, in seconds. */
    private static final float PERIOD_S = 60f;
    private static final float FADE_S = 0.8f;

    private volatile long frameTimeNanos;
    private boolean running, ticking;
    /** Drifting (the moving background), else still: frames only while a fade runs. */
    private volatile boolean drift = true;
    /** Until when a fade needs frames (System.nanoTime), and whether one frame is owed (a new surface). */
    private long busyUntil;
    private boolean owed = true;
    /** Held still: no frame drawn, and the drift's clock stopped too, so it goes on without a jump. */
    private boolean held;
    private long heldSince, heldTotal;
    /** Draws on one vsync in this many: the drift is slow enough that fewer frames look the same. */
    private final int vsyncsPerFrame;
    private int vsyncs;
    private final Renderer renderer = new Renderer();
    private final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long nanos) {
            if (!running) return;
            boolean wanted = drift || owed || nanos < busyUntil;
            if (!held && wanted && ++vsyncs % vsyncsPerFrame == 0) {
                frameTimeNanos = nanos - heldTotal;
                owed = false;
                requestRender();
            }
            // Still and done: no more vsync callbacks until something changes.
            ticking = wanted || held;
            if (ticking) Choreographer.getInstance().postFrameCallback(this);
        }
    };

    /**
     * @param vsyncsPerFrame 2 on the dashboard (30 fps: each frame the compositor has to blend costs the
     *                       whole TV CPU time), more for the screensaver, which nobody watches closely
     */
    MovingBackdrop(Context ctx, int vsyncsPerFrame) {
        this(ctx, vsyncsPerFrame, Backdrop.SCENE_W, Backdrop.SCENE_H);
    }

    /** @param width,height the surface's pixels, stretched to the screen by the compositor */
    private MovingBackdrop(Context ctx, int vsyncsPerFrame, int width, int height) {
        super(ctx);
        this.vsyncsPerFrame = vsyncsPerFrame;
        setEGLContextClientVersion(2);
        getHolder().setFixedSize(width, height);
        // The picture survives the dashboard going behind an app: no navy flash on coming back.
        setPreserveEGLContextOnPause(true);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    /**
     * Puts a still surface under a full-screen activity's window, showing {@code color} until told
     * otherwise, and takes the window's own background away: the way every full-screen window of the
     * launcher gets the GPU to compose it. {@code pictures}: it will show Backdrop's pictures, at the
     * screen's size; else a plain colour, which a few pixels stretched show as well, in no memory.
     */
    static MovingBackdrop under(android.app.Activity activity, int color, boolean pictures) {
        MovingBackdrop surface = pictures ? new MovingBackdrop(activity, 2) : new MovingBackdrop(activity, 2, 16, 9);
        surface.drift = false;
        ((android.view.ViewGroup) activity.findViewById(android.R.id.content)).addView(surface, 0,
                new android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT));
        activity.getWindow().setBackgroundDrawable(null);
        surface.show(Bitmap.createBitmap(new int[]{color}, 1, 1, Bitmap.Config.ARGB_8888));
        return surface;
    }

    /** Drifting (the moving background) or still. */
    void setDrift(boolean on) {
        drift = on;
        owed = true;
        tick();
    }

    /** Makes sure vsync callbacks run while something is to be drawn. */
    private void tick() {
        if (running && !ticking) {
            ticking = true;
            Choreographer.getInstance().postFrameCallback(tick);
        }
    }

    /**
     * Stops drawing (the last frame stays on screen) or goes on from where it stopped: the dashboard's
     * slides get the GPU to themselves.
     */
    void hold(boolean hold) {
        if (hold == held) return;
        held = hold;
        if (hold) {
            heldSince = System.nanoTime();
        } else {
            long stopped = System.nanoTime() - heldSince;
            heldTotal += stopped;
            busyUntil += stopped;   // a fade under way goes on where it stopped
        }
        tick();
    }

    /** Fades to this picture (any size; opaque). */
    void show(Bitmap picture) {
        last = picture;
        long now = System.nanoTime();
        long start = now - heldTotal;
        queueEvent(() -> renderer.next(picture, start));
        busyUntil = now + (long) (FADE_S * 1e9) + 50_000_000L;
        tick();
    }

    /** The picture shown, uploaded again if the GL context was lost after all. */
    private volatile Bitmap last;

    @Override
    public void onResume() {
        super.onResume();
        if (!running) {
            running = true;
            owed = true;
            tick();
        }
    }

    @Override
    public void onPause() {
        running = false;
        ticking = false;
        Choreographer.getInstance().removeFrameCallback(tick);
        super.onPause();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        onResume();
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        ticking = false;
        Choreographer.getInstance().removeFrameCallback(tick);
        super.onDetachedFromWindow();
    }

    private static final String VERTEX = "attribute vec2 pos; varying vec2 uv; uniform float t; uniform float moving;"
            + "void main() {"
            // Drifting: zoomed in a little so the edges never show, then moving on a slow loop. Still:
            // the picture as composed, edge to edge.
            + "  float zoom = 1.0 + moving * (0.12 + 0.03 * sin(t * 0.5));"
            + "  vec2 drift = moving * vec2(0.045 * sin(t), 0.035 * cos(t * 0.8));"
            + "  vec2 base = vec2(pos.x * 0.5 + 0.5, 0.5 - pos.y * 0.5);"
            + "  uv = (base - 0.5) / zoom + 0.5 + drift;"
            + "  gl_Position = vec4(pos, 0.0, 1.0); }";

    private static final String FRAGMENT = "precision mediump float; varying vec2 uv;"
            + "uniform sampler2D now; uniform sampler2D before; uniform float mixing; uniform float dither;"
            + "void main() {"
            + "  vec3 c = mix(texture2D(before, uv).rgb, texture2D(now, uv).rgb, mixing);"
            // A touch of noise breaks the bands an 8-bit gradient shows once stretched. A picture has
            // none, and the noise is the dearest part of a frame on this GPU at the screen's size.
            + "  if (dither > 0.5) c += (fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453) - 0.5) / 255.0;"
            + "  gl_FragColor = vec4(c, 1.0); }";

    private final class Renderer implements GLSurfaceView.Renderer {
        private int program, pos, uT, uNow, uBefore, uMix, uMoving, uDither;
        private final int[] textures = new int[2];
        /** Which texture holds the newest picture; the other the one fading out. */
        private int current;
        private long fadeStart;
        private boolean any;
        /** The texture fading out still holds a picture; once the fade is done, it is let go. */
        private boolean before;
        /** The newest is a small gradient, stretched: dithered. */
        private boolean gradient;
        private Bitmap pending;
        private final FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});

        /** On the GL thread. */
        void next(Bitmap picture, long nanos) {
            if (program == 0) {
                pending = picture;   // the surface is not there yet
                return;
            }
            current = 1 - current;
            upload(textures[current], picture);
            if (!any) upload(textures[1 - current], picture);
            any = true;
            before = true;
            gradient = picture.getWidth() < Backdrop.SCENE_W / 4;
            fadeStart = nanos;
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            program = GLES20.glCreateProgram();
            GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX));
            GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT));
            GLES20.glLinkProgram(program);
            pos = GLES20.glGetAttribLocation(program, "pos");
            uT = GLES20.glGetUniformLocation(program, "t");
            uNow = GLES20.glGetUniformLocation(program, "now");
            uBefore = GLES20.glGetUniformLocation(program, "before");
            uMix = GLES20.glGetUniformLocation(program, "mixing");
            uMoving = GLES20.glGetUniformLocation(program, "moving");
            uDither = GLES20.glGetUniformLocation(program, "dither");
            GLES20.glGenTextures(2, textures, 0);
            any = false;
            Bitmap p = pending != null ? pending : last;
            pending = null;
            if (p != null) {
                next(p, frameTimeNanos);
                fadeStart = 0;   // straight in: it was already showing
            }
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            if (!any) {
                GLES20.glClearColor(0.043f, 0.071f, 0.125f, 1f);   // Backdrop.NAVY
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                return;
            }
            long now = frameTimeNanos;
            GLES20.glUseProgram(program);
            GLES20.glUniform1f(uT, (now / 1_000_000L % 3_600_000L) / 1000f / PERIOD_S * 6.2831853f);
            float mix = Math.min(1f, (now - fadeStart) / 1e9f / FADE_S);
            GLES20.glUniform1f(uMix, mix);
            GLES20.glUniform1f(uMoving, drift ? 1f : 0f);
            GLES20.glUniform1f(uDither, gradient ? 1f : 0f);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[current]);
            GLES20.glUniform1i(uNow, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1 - current]);
            GLES20.glUniform1i(uBefore, 1);
            quad.position(0);
            GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 0, quad);
            GLES20.glEnableVertexAttribArray(pos);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            if (mix >= 1f && before) {
                // A screen-sized picture nobody sees any more: a pixel instead, the memory back.
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1 - current]);
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0, GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(4));
                before = false;
            }
        }

        private void upload(int texture, Bitmap picture) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, picture, 0);
        }

        private int shader(int type, String source) {
            int s = GLES20.glCreateShader(type);
            GLES20.glShaderSource(s, source);
            GLES20.glCompileShader(s);
            return s;
        }
    }
}
