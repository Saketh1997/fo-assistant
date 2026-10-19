package org.sakethmetta.foassist;

import android.app.KeyguardManager;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

/**
 * Moves Fo's ntfy notifications out of the notification bar and into the floating bird. Only ntfy
 * notifications whose title starts with "Fo" (all of fo-bridge's do; cluster alerts don't) are taken,
 * after the emoji ntfy puts in front of the title for each tag ("⌛ Fo is working").
 * While the phone is locked they stay where they are, because overlays don't show over the lock
 * screen; they move into the bird on unlock.
 */
public class FoNotificationListener extends NotificationListenerService {
    static final String NTFY = "io.heckel.ntfy";
    private static final java.util.regex.Pattern LEADING_EMOJI = java.util.regex.Pattern.compile("^[^\\p{L}\\p{N}]+");

    /** True while Android has us connected; Android can drop the connection when it closes the app for memory. */
    static volatile boolean connected;

    private final BroadcastReceiver unlocked = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            sweep();
        }
    };

    @Override
    public void onListenerConnected() {
        connected = true;
        Bridge.noteBird(this, "", "notification access connected");
        registerReceiver(unlocked, new IntentFilter(Intent.ACTION_USER_PRESENT));
        sweep();
    }

    @Override
    public void onListenerDisconnected() {
        connected = false;
        Bridge.noteBird(this, "", "notification access dropped; asked Android to reconnect");
        try {
            unregisterReceiver(unlocked);
        } catch (IllegalArgumentException ignored) {
        }
        reconnect(this);
    }

    /**
     * Ask Android to reconnect us if it dropped the connection (it can, when it closes the app to free memory
     * during a video call or screen recording, and doesn't always come back on its own). Harmless when connected.
     */
    static void reconnect(Context c) {
        if (connected) {
            return;
        }
        ComponentName me = new ComponentName(c, FoNotificationListener.class);
        if (c.getSystemService(android.app.NotificationManager.class).isNotificationListenerAccessGranted(me)) {
            try {
                requestRebind(me);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        take(sbn);
    }

    private void sweep() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all == null) {
                return;
            }
            Arrays.sort(all, Comparator.comparingLong(StatusBarNotification::getPostTime));
            for (StatusBarNotification sbn : all) {
                take(sbn);
            }
        } catch (SecurityException ignored) {  // access was revoked under us
        }
    }

    private void take(StatusBarNotification sbn) {
        if (!NTFY.equals(sbn.getPackageName()) && !getPackageName().equals(sbn.getPackageName())) {  // ntfy, or our call-back reminders
            return;
        }
        Notification n = sbn.getNotification();
        if ((n.flags & (Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_FOREGROUND_SERVICE)) != 0) {
            return;
        }
        Bundle x = n.extras;
        String title = str(x.getCharSequence(Notification.EXTRA_TITLE));
        String bare = LEADING_EMOJI.matcher(title).replaceFirst("");
        if (!bare.toLowerCase(Locale.ROOT).startsWith("fo")) {
            Bridge.noteBird(this, title, "left alone: not from Fo");
            return;
        }
        if (!Bridge.bird(this)) {
            Bridge.noteBird(this, title, "left alone: the bird is switched off");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Bridge.noteBird(this, title, "left alone: display over other apps isn't allowed");
            return;
        }
        if (getSystemService(KeyguardManager.class).isKeyguardLocked()
                || !getSystemService(PowerManager.class).isInteractive()) {
            Bridge.noteBird(this, title, "waiting for unlock");
            return;  // leave it on the lock screen; sweep() picks it up on unlock
        }
        String text = str(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (text.isEmpty()) {
            text = str(x.getCharSequence(Notification.EXTRA_TEXT));
        }
        FloatingBird.Item it = new FloatingBird.Item(sbn.getKey(), title, text, n.contentIntent);
        if (n.actions != null) {
            for (Notification.Action a : n.actions) {
                if (a.actionIntent != null && a.title != null) {
                    it.labels.add(a.title.toString());
                    it.actions.add(a.actionIntent);
                }
            }
        }
        try {
            FloatingBird.get(this).show(it);
        } catch (RuntimeException e) {  // e.g. the overlay was refused: keep the notification
            Bridge.noteBird(this, title, "bird failed: " + e);
            return;
        }
        Bridge.noteBird(this, title, "shown by the bird");
        cancelNotification(sbn.getKey());
        dropEmptySummary();
    }

    /** ntfy groups its notifications; once the last Fo one is lifted out, don't leave an empty group header. */
    private void dropEmptySummary() {
        StatusBarNotification[] all = getActiveNotifications();
        if (all == null) {
            return;
        }
        boolean children = false;
        for (StatusBarNotification s : all) {
            children |= NTFY.equals(s.getPackageName())
                    && (s.getNotification().flags & (Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_FOREGROUND_SERVICE)) == 0;
        }
        if (!children) {
            for (StatusBarNotification s : all) {
                if (NTFY.equals(s.getPackageName()) && (s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                    cancelNotification(s.getKey());
                }
            }
        }
    }

    private static String str(CharSequence s) {
        return s == null ? "" : s.toString().trim();
    }
}
