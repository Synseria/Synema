package com.synseria.synema;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The AirPlay receiver's code, over whatever is showing: the receiver (airplay/) asks a new device for
 * a 4-digit code and writes it only in its own screen and in its notification, which a TV never shows.
 * This listener reads that notification and RemoteKeyService shows the code (Alerts) until it is
 * given. Notification access is granted over adb by build-and-install.sh.
 */
public class AirPlayPin extends NotificationListenerService {
    static final String RECEIVER = "io.github.jqssun.airplay";
    /** The receiver's text while it waits: "PIN: 1234". */
    private static final Pattern PIN = Pattern.compile("PIN:\\s*(\\d{4})");

    /** A code may be waiting already when Android connects the listener (after an install). */
    @Override
    public void onListenerConnected() {
        StatusBarNotification[] active = getActiveNotifications();
        for (StatusBarNotification sbn : active == null ? new StatusBarNotification[0] : active) onNotificationPosted(sbn);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!RECEIVER.equals(sbn.getPackageName())) return;
        Bundle extras = sbn.getNotification().extras;
        CharSequence text = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TEXT);
        Matcher m = PIN.matcher(text == null ? "" : text);
        RemoteKeyService.airPlayPin(m.find() ? m.group(1) : null);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (RECEIVER.equals(sbn.getPackageName())) RemoteKeyService.airPlayPin(null);
    }
}
