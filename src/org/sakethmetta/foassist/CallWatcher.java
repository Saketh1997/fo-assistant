package org.sakethmetta.foassist;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Busy auto-reply. When someone calls while you're already on a call (a phone call, or a WhatsApp/Meet-style
 * call), and you don't pick up, they get a text saying you'll call back. When you hang up, Fo reminds you
 * to call them, with a Call back button; with the bird on, the reminder pops up in the bird.
 *
 * The text goes out once their call stops ringing and the call log shows it went unanswered, so someone
 * you switch over to never gets "I'm on a call". Blocked callers, hidden numbers and (by default) numbers
 * not in your contacts get no text; the reminder still comes for everyone but blocked callers.
 */
public class CallWatcher extends BroadcastReceiver {
    static final String[] PERMISSIONS = Build.VERSION.SDK_INT >= 33
            ? new String[]{Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE, Manifest.permission.POST_NOTIFICATIONS}
            : new String[]{Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG, Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE};
    static final String DEFAULT_TEXT = "Sorry, I'm on another call right now. I'll call you back as soon as I'm done.";
    private static final String RECHECK = "org.sakethmetta.foassist.CALLS_RECHECK";
    private static final String CHANNEL = "callbacks";
    private static final long RETEXT_MS = 30 * 60_000L;  // one text per caller per half hour, however often they redial
    private static final ExecutorService WORK = Executors.newSingleThreadExecutor();  // keeps steps in broadcast order

    // ---- settings ----

    static boolean enabled(Context c) {
        return Bridge.prefs(c).getBoolean("busy_reply", false);
    }

    static boolean contactsOnly(Context c) {
        return Bridge.prefs(c).getBoolean("busy_contacts_only", true);
    }

    static String text(Context c) {
        String t = Bridge.prefs(c).getString("busy_text", "").trim();
        return t.isEmpty() ? DEFAULT_TEXT : t;
    }

    static String last(Context c) {
        return Bridge.prefs(c).getString("busy_last", "nothing yet");
    }

    static List<String> missing(Context c) {
        List<String> out = new ArrayList<>();
        for (String p : PERMISSIONS) {
            if (c.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                out.add(p.substring(p.lastIndexOf('.') + 1).toLowerCase().replace('_', ' '));
            }
        }
        return out;
    }

    // ---- call state ----

    @Override
    public void onReceive(Context context, Intent intent) {
        Context c = context.getApplicationContext();
        FoNotificationListener.reconnect(c);  // a call is when the bird matters and when Android tends to drop it
        if (!enabled(c)) {
            return;
        }
        SharedPreferences s = calls(c);
        if (RECHECK.equals(intent.getAction())) {
            later(c, () -> settle(c));
            return;
        }
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            return;
        }
        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        @SuppressWarnings("deprecation")
        String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            // Android sends this twice; only the copy for call-log readers carries the number
            if (number != null && !number.isEmpty() && s.getString("ring", null) == null && busy(c)) {
                s.edit().putString("ring", number + "|" + System.currentTimeMillis()).apply();
            }
            return;
        }
        boolean idle = TelephonyManager.EXTRA_STATE_IDLE.equals(state);
        s.edit().putBoolean("offhook", !idle).apply();
        String ring = s.getString("ring", null);
        if (ring != null) {
            s.edit().remove("ring").apply();  // the waiting call stopped ringing: answered, declined or gave up
        }
        later(c, () -> {
            if (ring != null) {
                rang(c, ring.substring(0, ring.lastIndexOf('|')), Long.parseLong(ring.substring(ring.lastIndexOf('|') + 1)));
            }
            if (idle) {
                settle(c);
            }
        });
    }

    /** On a call right now: a phone call, or any app holding a call (WhatsApp, Meet, Teams…). */
    private static boolean busy(Context c) {
        int mode = c.getSystemService(AudioManager.class).getMode();
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                || calls(c).getBoolean("offhook", false);
    }

    /** A call came in while busy and has stopped ringing. Text the caller unless the call was taken. */
    private static void rang(Context c, String number, long at) {
        Entry e = null;
        for (int i = 0; i < 8 && e == null; i++) {  // the call log is written a moment after the call ends
            e = logged(c, number, at, false);
            if (e == null) {
                SystemClock.sleep(1000);
            }
        }
        if (e != null && e.type == CallLog.Calls.BLOCKED_TYPE) {
            return;
        }
        String name = name(c, number);
        Owed o = Owed.find(c, number);
        o.count++;
        o.first = o.first == 0 ? at : o.first;
        o.name = name;
        String did;
        if (e == null) {
            did = "a call you picked up";  // still going, so nothing in the log yet; settle() rechecks it
        } else if (e.type == CallLog.Calls.INCOMING_TYPE && e.duration > 0) {
            did = "a call you picked up";
        } else if (contactsOnly(c) && name == null) {
            did = "no text, not in your contacts";
            o.why = "Not in your contacts, so I didn't text them.";
        } else if (System.currentTimeMillis() - Bridge.prefs(c).getLong("texted_" + digits(number), 0) < RETEXT_MS) {
            did = "already texted recently";
            o.why = "I already texted them that you'll call back.";
        } else {
            did = sms(c, number) ? "texted" : "the text failed";
            o.why = did.equals("texted") ? "I texted them that you'll call back." : "I couldn't text them.";
        }
        o.save(c);
        note(c, (name != null ? name : number) + ": " + did);
    }

    /** Every call has ended: remind about each caller who hasn't been talked to since. */
    private static void settle(Context c) {
        for (int i = 0; busy(c); i++) {  // the call audio can linger a moment after hanging up
            if (calls(c).getBoolean("offhook", false)) {
                return;  // a phone call started again; its IDLE settles later
            }
            if (i == 3) {
                recheckIn(c, 30_000);  // still on an app call (WhatsApp, Meet…); look again soon
                return;
            }
            SystemClock.sleep(1000);
        }
        for (Owed o : Owed.all(c)) {
            if (logged(c, o.number, o.first, true) == null) {  // not picked up, not called back
                remind(c, o);
            }
        }
        calls(c).edit().remove("owed").apply();
    }

    private static boolean sms(Context c, String number) {
        try {
            SmsManager sm = Build.VERSION.SDK_INT >= 31 ? c.getSystemService(SmsManager.class) : SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage(text(c));
            sm.sendMultipartTextMessage(number, null, parts, null, null);
            Bridge.prefs(c).edit().putLong("texted_" + digits(number), System.currentTimeMillis()).apply();
            return true;
        } catch (RuntimeException e) {  // no permission, no SIM, airplane mode…
            return false;
        }
    }

    // ---- the reminder ----

    static void remind(Context c, Owed o) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Call-back reminders", NotificationManager.IMPORTANCE_HIGH));
        String who = o.name != null ? o.name : PhoneNumberUtils.formatNumber(o.number, "US");
        if (who == null) {
            who = o.number;
        }
        String when = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(o.first));
        String body = who + " called " + (o.count > 1 ? o.count + " times, first " : "") + "at " + when
                + " while you were on a call. " + (o.why != null ? o.why : "");
        boolean canCall = c.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED;
        Uri tel = Uri.fromParts("tel", o.number, null);
        int id = digits(o.number).hashCode();
        PendingIntent call = PendingIntent.getActivity(c, id,
                new Intent(canCall ? Intent.ACTION_CALL : Intent.ACTION_DIAL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent dial = PendingIntent.getActivity(c, id + 1,
                new Intent(Intent.ACTION_DIAL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent sms = PendingIntent.getActivity(c, id + 2,
                new Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", o.number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_missed_call)
                .setContentTitle("Fo: call back " + who)  // "Fo…" so the bird picks it up
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(dial)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "Call back", call).build())
                .addAction(new Notification.Action.Builder(null, "Text", sms).build())
                .build();
        try {
            nm.notify("callback", id, n);
        } catch (SecurityException ignored) {  // notifications not allowed
        }
    }

    static void sample(Context c) {
        Owed o = new Owed("5555550123");
        o.name = "Test caller";
        o.count = 2;
        o.first = System.currentTimeMillis() - 4 * 60_000L;
        o.why = "This is a sample; nobody was texted.";
        remind(c, o);
    }

    // ---- helpers ----

    private static final class Entry {
        int type;
        long duration;
    }

    /**
     * The call log's latest entry for this number since `since`. With `talked`, only a call that was
     * actually connected (picked up, or a call-back you made) counts.
     */
    private static Entry logged(Context c, String number, long since, boolean talked) {
        try (Cursor q = c.getContentResolver().query(CallLog.Calls.CONTENT_URI,
                new String[]{CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION},
                CallLog.Calls.DATE + " >= ?", new String[]{String.valueOf(since - 15_000)}, CallLog.Calls.DATE + " DESC")) {
            while (q != null && q.moveToNext()) {
                if (!PhoneNumberUtils.compare(number, q.getString(0))) {
                    continue;
                }
                Entry e = new Entry();
                e.type = q.getInt(1);
                e.duration = q.getLong(2);
                boolean connected = e.duration > 0
                        && (e.type == CallLog.Calls.INCOMING_TYPE || e.type == CallLog.Calls.OUTGOING_TYPE);
                if (!talked || connected) {
                    return e;
                }
            }
        } catch (RuntimeException ignored) {  // no call-log permission
        }
        return null;
    }

    private static String name(Context c, String number) {
        try (Cursor q = c.getContentResolver().query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                new String[]{ContactsContract.PhoneLookup.DISPLAY_NAME}, null, null, null)) {
            return q != null && q.moveToFirst() ? q.getString(0) : null;
        } catch (RuntimeException e) {  // no contacts permission
            return null;
        }
    }

    private static String digits(String number) {
        String d = number.replaceAll("\\D", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    private static SharedPreferences calls(Context c) {
        return c.getSharedPreferences("calls", Context.MODE_PRIVATE);
    }

    private static void note(Context c, String what) {
        String when = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(new Date());
        Bridge.prefs(c).edit().putString("busy_last", when + "  " + what).apply();
    }

    private void later(Context c, Runnable r) {
        PendingResult done = goAsync();
        WORK.execute(() -> {
            try {
                r.run();
            } finally {
                done.finish();
            }
        });
    }

    private static void recheckIn(Context c, long ms) {
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(RECHECK).setClass(c, CallWatcher.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        c.getSystemService(AlarmManager.class).setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ms, 15_000, pi);
    }

    /** A caller still owed a call back, kept until every call has ended. */
    static final class Owed {
        final String number;
        String name;
        String why;
        int count;
        long first;

        Owed(String number) {
            this.number = number;
        }

        static List<Owed> all(Context c) {
            List<Owed> out = new ArrayList<>();
            for (String line : calls(c).getString("owed", "").split("\n")) {
                String[] f = line.split("\t", -1);
                if (f.length == 5) {
                    Owed o = new Owed(f[0]);
                    o.count = Integer.parseInt(f[1]);
                    o.first = Long.parseLong(f[2]);
                    o.name = f[3].isEmpty() ? null : f[3];
                    o.why = f[4].isEmpty() ? null : f[4];
                    out.add(o);
                }
            }
            return out;
        }

        static Owed find(Context c, String number) {
            for (Owed o : all(c)) {
                if (PhoneNumberUtils.compare(o.number, number)) {
                    return o;
                }
            }
            return new Owed(number);
        }

        void save(Context c) {
            StringBuilder b = new StringBuilder();
            for (Owed o : all(c)) {
                if (!PhoneNumberUtils.compare(o.number, number)) {
                    b.append(o.line()).append('\n');
                }
            }
            calls(c).edit().putString("owed", b.append(line()).toString()).apply();
        }

        private String line() {
            return number + "\t" + count + "\t" + first + "\t" + (name == null ? "" : clean(name)) + "\t" + (why == null ? "" : clean(why));
        }

        private static String clean(String s) {
            return s.replace('\t', ' ').replace('\n', ' ');
        }
    }
}
