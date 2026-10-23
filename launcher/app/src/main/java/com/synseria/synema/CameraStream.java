package com.synseria.synema;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;

/**
 * A camera live from Home Assistant's MJPEG proxy (/api/camera_proxy_stream), at the camera's own frame
 * rate instead of one snapshot a second. Frames are cut at the JPEG start and end markers, decoded at
 * the size shown, and a frame still waiting to be drawn is replaced rather than queued, so the picture
 * never lags behind. One thread per stream, until stop().
 */
final class CameraStream {
    private static final String TAG = "TvHome.Camera";

    private final HomeAssistant ha;
    private final String camera;
    private final int width;
    private final HomeAssistant.Callback<Bitmap> onFrame;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopped;
    private volatile HttpURLConnection connection;
    private Bitmap pending;

    CameraStream(HomeAssistant ha, String camera, int width, HomeAssistant.Callback<Bitmap> onFrame) {
        this.ha = ha;
        this.camera = camera;
        this.width = width;
        this.onFrame = onFrame;
    }

    void start() {
        Thread t = new Thread(this::run, "camera-" + camera);
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        stopped = true;
        HttpURLConnection c = connection;
        if (c != null) c.disconnect();
    }

    private void run() {
        while (!stopped) {
            try {
                connection = ha.openStream("/api/camera_proxy_stream/" + camera);
                read(new BufferedInputStream(connection.getInputStream(), 64 * 1024));
            } catch (Exception e) {
                if (!stopped) Log.w(TAG, camera + ": " + e);
            } finally {
                HttpURLConnection c = connection;
                if (c != null) c.disconnect();
            }
            if (stopped) return;
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void read(InputStream in) throws Exception {
        ByteArrayOutputStream frame = new ByteArrayOutputStream(256 * 1024);
        boolean inFrame = false;
        int prev = -1;
        for (int b; !stopped && (b = in.read()) != -1; prev = b) {
            if (!inFrame) {
                if (prev == 0xFF && b == 0xD8) {
                    inFrame = true;
                    frame.reset();
                    frame.write(0xFF);
                    frame.write(0xD8);
                }
                continue;
            }
            frame.write(b);
            if (prev == 0xFF && b == 0xD9) {
                inFrame = false;
                deliver(HomeAssistant.decode(frame.toByteArray(), width));
                b = -1;   // a new frame needs a fresh FF D8, not this frame's last FF
            }
        }
    }

    private void deliver(Bitmap bmp) {
        if (bmp == null) return;
        boolean post;
        synchronized (this) {
            post = pending == null;
            pending = bmp;
        }
        if (!post) return;
        main.post(() -> {
            Bitmap show;
            synchronized (this) {
                show = pending;
                pending = null;
            }
            if (!stopped && show != null) onFrame.done(show);
        });
    }
}
