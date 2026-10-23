package com.synseria.synema;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Reassigns remote buttons, from any app: each entry of {@code keys} in home.json (HomeConfig) opens an
 * app or calls a Home Assistant service instead of what the button normally does. Vendor buttons
 * (Philips App Gallery 326, Rakuten 779) carry keycodes beyond KeyEvent's constants, so entries are
 * plain numbers. An accessibility service sees keys before the vendor's global-key handler, which is
 * what lets it take a branded button over; keys not listed pass through untouched, and Home Assistant
 * buttons are left to the apps in {@code keys_off_in} (the Live TV app uses the colours for teletext).
 * It also hosts the Home Assistant alerts and the AirPlay code (Alerts): an accessibility service is the
 * one component that Android keeps alive and lets draw over other apps without further permission.
 * Opt-in: REMOTE_KEY_SERVICE=1 build-and-install.sh enables it; tools/remote-keys.sh learn prints the
 * keycode of each button pressed (verbose logging, off unless that script turns it on).
 */
public class RemoteKeyService extends AccessibilityService {

    private static final String TAG = "TvHome.RemoteKey";
    private HomeConfig config;
    private HomeAssistant ha;
    private Alerts alerts;
    /** The running service, for AirPlayPin; null when off. */
    private static RemoteKeyService running;
    /** A screen of ours takes the remote's voice buttons itself (CameraActivity talks with them). */
    private static volatile boolean voiceKeyHere;
    /** Package of the window in front, from window-state events. */
    private String foreground = "";
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Tells Home Assistant what the TV shows (TvState). */
    private final Runnable report = () -> {
        if (ha != null) TvState.report(this, ha, foreground);
    };

    @Override
    protected void onServiceConnected() {
        config = HomeConfig.load(this);
        ha = HomeAssistant.load(this);
        alerts = new Alerts(this, ha, config);
        if (ha != null) alerts.start();
        running = this;
    }

    @Override
    public void onDestroy() {
        running = null;
        if (alerts != null) alerts.stop();
        super.onDestroy();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // Back closes the AirPlay code's card, which never takes the remote from the app under it.
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && alerts != null && alerts.pinShown()) {
            if (event.getAction() == KeyEvent.ACTION_UP) alerts.closePin();
            return true;
        }
        HomeConfig.Action action = config != null ? config.keys.get(event.getKeyCode()) : null;
        if (action != null && !action.isLocal() && config.keysOffIn.contains(foreground)) action = null;
        if (action != null && action.assistant && voiceKeyHere) action = null;
        // A button taken here never reaches the screensaver, which would stay over what it opens: it ends first.
        boolean taken = action != null || event.getKeyCode() == KeyEvent.KEYCODE_HOME;
        if (taken && event.getAction() == KeyEvent.ACTION_DOWN) ClockDream.end();
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME) return home(event);
        if (event.getAction() == KeyEvent.ACTION_UP && Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "keycode " + event.getKeyCode() + " scan " + event.getScanCode() + " in " + foreground
                    + (action == null ? "" : " -> " + (action.isLocal() ? action.app + (action.assistant ? "assistant" : "") : action.service + " " + action.entity)));
        }
        if (action == null) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) Actions.run(this, ha, action, null);
        return true;
    }

    /** While {@code here}, the buttons set to open the assistant go to the screen in front instead. */
    static void voiceKeyHere(boolean here) {
        voiceKeyHere = here;
    }

    /** The AirPlay receiver's code to show, or null when it no longer waits for one (AirPlayPin). */
    static void airPlayPin(String code) {
        RemoteKeyService s = running;
        if (s != null && s.alerts != null) s.main.post(() -> s.alerts.pin(code));
    }

    /**
     * Home, with the launcher's own animation: the app in front draws back and fades while Home settles
     * over it. Home as the system starts it leaves the closing app its own animation, which keeps the
     * app on top until Home shows at once.
     */
    private boolean home(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home, ActivityOptions.makeCustomAnimation(this, R.anim.home_in, R.anim.app_out).toBundle());
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        if (config != null && event.getClassName() != null && config.dismiss.contains(event.getClassName().toString())) {
            Log.i(TAG, "sent away: " + event.getClassName());
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        String pkg = event.getPackageName().toString();
        if (pkg.equals(foreground)) return;
        foreground = pkg;
        // Settled for a few seconds: menus and overlays flash by without a report.
        main.removeCallbacks(report);
        main.postDelayed(report, 3_000);
    }

    @Override
    public void onInterrupt() {
    }
}
