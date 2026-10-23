package com.synseria.synema;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.view.KeyEvent;

/** Whatever another app is playing: paused for an alert, stopped when Home comes to the front. */
final class Media {
    private Media() {
    }

    /** Sends a media key to whatever is playing; true when an audio player was active to receive it. */
    static boolean key(Context ctx, int code) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null || (code == KeyEvent.KEYCODE_MEDIA_PAUSE && !am.isMusicActive())) return false;
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
        return true;
    }

    /**
     * Silences an app left playing behind Home (Canal+ autoplays a trailer on its page and keeps it
     * going in the background): taking audio focus for good makes well-behaved players stop, and the
     * pause key reaches those that ignore focus. Nothing happens when nothing plays.
     */
    static void stopBehindHome(Context ctx) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null || !am.isMusicActive()) return;
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .build();
        am.requestAudioFocus(focus);
        key(ctx, KeyEvent.KEYCODE_MEDIA_PAUSE);
        am.abandonAudioFocusRequest(focus);
    }
}
