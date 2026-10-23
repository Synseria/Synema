package com.synseria.synema;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;

/** The picture behind the dashboard (Config.wallpaper), decoded for Backdrop.picture. */
final class Wallpaper {
    private Wallpaper() {
    }

    /**
     * Downsampled to the screen, so a 4K JPEG costs a 1080p bitmap at most; null when it cannot be
     * read: a bad or huge picture must never take the home screen down.
     */
    static Bitmap decode(File file, int screenW, int screenH) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), opts);
            int sample = 1;
            while (opts.outWidth / (sample * 2) >= screenW && opts.outHeight / (sample * 2) >= screenH) sample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;   // opaque: drawn without blending, half the memory
            return BitmapFactory.decodeFile(file.getPath(), opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
