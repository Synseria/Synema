package com.synseria.synema;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.view.InputDevice;

/**
 * Gives the remote's Assistant button to the house assistant. The system acts on that button before any
 * app or accessibility service sees it, so it is renamed below that: a key layout overlay
 * (res/raw/assistant_key.kcm) turns it into F12 for the remote, and Google's assistant never starts.
 *
 * The receiver only declares the overlay to the system. Choosing it for a device needs a permission the
 * adb shell holds, so tools/assistant-key.sh runs {@link #main} as the shell with app_process.
 */
public class KeyLayouts extends BroadcastReceiver {
    static final String LAYOUT = "com.synseria.synema/com.synseria.synema.KeyLayouts/assistant_key";

    @Override
    public void onReceive(Context context, Intent intent) {
    }

    /** {@code on} or {@code off}: the overlay on every hardware key device, or off them all. */
    public static void main(String[] args) throws Exception {
        boolean on = args.length > 0 && "on".equals(args[0]);
        Object im = InputManager.class.getMethod("getInstance").invoke(null);
        Class<?> identifier = Class.forName("android.hardware.input.InputDeviceIdentifier");
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual() || (d.getSources() & InputDevice.SOURCE_KEYBOARD) == 0) continue;
            Object ident = InputDevice.class.getMethod("getIdentifier").invoke(d);
            String method = on ? "setCurrentKeyboardLayoutForInputDevice" : "removeKeyboardLayoutForInputDevice";
            InputManager.class.getMethod(method, identifier, String.class).invoke(im, ident, LAYOUT);
            System.out.println((on ? "on  " : "off ") + d.getName());
        }
    }
}
