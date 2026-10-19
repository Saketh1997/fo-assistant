package org.sakethmetta.foassist;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** Setup screen: bridge address and token, microphone, and the way into the assistant settings. */
public class SettingsActivity extends Activity {
    private EditText url;
    private EditText token;
    private Switch autoSend;
    private Switch screenshot;
    private Switch bird;
    private TextView birdStatus;
    private Switch busy;
    private TextView busyStatus;
    private TextView status;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (Bridge.token(this).isEmpty() && !getIntent().getBooleanExtra("manual", false)) {
            startActivity(new Intent(this, LoginActivity.class));  // first run: sign in with Wajo
            finish();
            return;
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        col.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(col);
        setContentView(scroll);

        boolean wajo = Bridge.wajoAccount(this);
        TextView who = label(wajo ? "Signed in with your Wajo account. Errands run on it, and Fo's notifications come on your own ntfy channel."
                : "Using the bridge owner's token.");
        who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        col.addView(who);
        col.addView(action(wajo ? "Sign out" : "Sign in with a Wajo account instead", v -> {
            status.setText("Signing out…");
            new Thread(() -> {
                Bridge.signOut(this);
                FloatingBird.get(this).dismiss();
                runOnUiThread(() -> {
                    startActivity(new Intent(this, LoginActivity.class));
                    finish();
                });
            }).start();
        }));
        col.addView(label("Bridge address"));
        url = field(Bridge.url(this), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        col.addView(url);
        token = field(Bridge.token(this), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setHint("paste BRIDGE_TOKEN");
        if (!wajo) {  // a Wajo sign-in's device key is managed for you
            col.addView(label("Bridge token"));
            col.addView(token);
        }

        autoSend = new Switch(this);
        autoSend.setText("Send as soon as I stop talking");
        autoSend.setChecked(Bridge.autoSend(this));
        autoSend.setPadding(0, dp(12), 0, dp(12));
        col.addView(autoSend);

        screenshot = new Switch(this);
        screenshot.setText("Send the screenshot with my request (the chip on the panel flips this too)");
        screenshot.setChecked(Bridge.withScreenshot(this));
        screenshot.setPadding(0, dp(12), 0, dp(12));
        screenshot.setOnCheckedChangeListener((b, on) -> Bridge.setWithScreenshot(this, on));
        col.addView(screenshot);

        bird = new Switch(this);
        bird.setText("Show Fo's notifications as a floating bird on screen, instead of in the notification bar");
        bird.setChecked(Bridge.bird(this));
        bird.setPadding(0, dp(12), 0, dp(4));
        bird.setOnCheckedChangeListener((b, on) -> {
            Bridge.setBird(this, on);
            if (!on) {
                FloatingBird.get(this).dismiss();
            }
            showBirdState();
        });
        col.addView(bird);
        birdStatus = new TextView(this);
        birdStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        col.addView(birdStatus);
        col.addView(action("Bird: allow notification access", v -> open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, null)));
        col.addView(action("Bird: allow display over other apps",
                v -> open(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        col.addView(action("Show me the bird", v -> {
            if (Settings.canDrawOverlays(this)) {
                FloatingBird.get(this).show(FloatingBird.sample());
            } else {
                birdStatus.setText("Allow display over other apps first.");
            }
        }));

        busy = new Switch(this);
        busy.setText("When someone calls while I'm on a call, text them that I'll call back, and remind me when I hang up");
        busy.setChecked(CallWatcher.enabled(this));
        busy.setPadding(0, dp(20), 0, dp(4));
        busy.setOnCheckedChangeListener((b, on) -> {
            Bridge.prefs(this).edit().putBoolean("busy_reply", on).apply();
            if (on && !CallWatcher.missing(this).isEmpty()) {
                requestPermissions(CallWatcher.PERMISSIONS, 2);
            }
            showBusyState();
        });
        col.addView(busy);
        Switch contactsOnly = new Switch(this);
        contactsOnly.setText("Only text people in my contacts");
        contactsOnly.setChecked(CallWatcher.contactsOnly(this));
        contactsOnly.setPadding(0, dp(8), 0, dp(4));
        contactsOnly.setOnCheckedChangeListener((b, on) -> Bridge.prefs(this).edit().putBoolean("busy_contacts_only", on).apply());
        col.addView(contactsOnly);
        col.addView(label("The text they get"));
        EditText busyText = field(CallWatcher.text(this), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        busyText.setSingleLine(false);
        busyText.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence t, int a, int b, int n) {
            }

            public void onTextChanged(CharSequence t, int a, int b, int n) {
            }

            public void afterTextChanged(android.text.Editable e) {
                Bridge.prefs(SettingsActivity.this).edit().putString("busy_text", e.toString()).apply();
            }
        });
        col.addView(busyText);
        busyStatus = new TextView(this);
        busyStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        col.addView(busyStatus);
        col.addView(action("Calls: allow phone, call log, SMS and contacts", v -> requestPermissions(CallWatcher.PERMISSIONS, 2)));
        col.addView(action("Show a sample call-back reminder", v -> CallWatcher.sample(this)));

        col.addView(action("Save and test connection", v -> saveAndTest()));
        col.addView(action("Allow microphone", v -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1)));
        col.addView(action("Make Fo Assist the digital assistant", v -> openAssistantSettings()));

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        status.setPadding(0, dp(16), 0, 0);
        col.addView(status);

        TextView help = new TextView(this);
        help.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        help.setPadding(0, dp(20), 0, 0);
        help.setText("Setup:\n"
                + "1. Sign in with Wajo (or paste the bridge token), then Save and test.\n"
                + "2. Allow microphone.\n"
                + "3. Make Fo Assist the digital assistant app, and turn on \"Use screenshot\" there.\n"
                + "4. Set the power button's long press to the digital assistant "
                + "(Settings › System › Gestures › Press & hold power button on Pixel).\n"
                + "5. Subscribe to Fo's notifications in ntfy (the sign-in screen has a button for it).\n"
                + "Then long-press power anywhere, say what you want, and watch ntfy.\n\n"
                + "Floating bird: turn it on, then allow notification access and display over other apps. "
                + "If notification access is greyed out (Android 13+ for apps installed from a file), open "
                + "Settings › Apps › Fo Assist, tap ⋮ › Allow restricted settings, and try again. "
                + "While the phone is locked, Fo's notifications stay on the lock screen; they move into the bird when you unlock.\n\n"
                + "Calls: when a call comes in while you're on another call and you don't take it, the caller gets your text "
                + "once it stops ringing, and when you hang up Fo reminds you to call them back. Blocked and hidden numbers are "
                + "skipped. If Android greys out the call log or SMS permission, use Allow restricted settings as above.");
        col.addView(help);
    }

    @Override
    protected void onResume() {
        super.onResume();
        FoNotificationListener.reconnect(this);
        if (status != null) {
            screenshot.setChecked(Bridge.withScreenshot(this));
            showMicState();
            showBirdState();
            showBusyState();
        }
    }

    private void showBusyState() {
        java.util.List<String> missing = CallWatcher.missing(this);
        busyStatus.setText((!busy.isChecked() ? "Off." : missing.isEmpty() ? "Ready."
                : "Still needs: " + String.join(", ", missing) + ".")
                + "\nLast caller: " + CallWatcher.last(this));
    }

    private void showBirdState() {
        boolean listen = getSystemService(NotificationManager.class)
                .isNotificationListenerAccessGranted(new ComponentName(this, FoNotificationListener.class));
        boolean draw = Settings.canDrawOverlays(this);
        birdStatus.setText((!bird.isChecked() ? "Bird is off: Fo's notifications stay in the notification bar."
                : listen && draw ? "Bird is ready."
                : "Bird still needs: " + (listen ? "" : "notification access") + (listen || draw ? "" : " and ")
                        + (draw ? "" : "display over other apps") + ".")
                + (listen && !FoNotificationListener.connected ? "\nNotification access is granted but not connected right now; reconnecting." : "")
                + "\nRecent:\n" + Bridge.lastBird(this));
    }

    private void open(String action, Uri data) {
        try {
            startActivity(new Intent(action, data));
        } catch (ActivityNotFoundException e) {
            birdStatus.setText("Couldn't open that settings page on this phone.");
        }
    }

    private void saveAndTest() {
        Bridge.prefs(this).edit()
                .putString("url", url.getText().toString().trim())
                .putString("token", token.getText().toString().trim())
                .putBoolean("autosend", autoSend.isChecked())
                .apply();
        status.setText("Testing…");
        new Thread(() -> {
            Bridge.Result r = Bridge.checkToken(this);
            String msg = r.code == 400 ? "Connected, token accepted."
                    : r.code == 401 ? "Bridge reached, but the token is wrong."
                    : r.code == 0 ? "Can't reach the bridge (" + r.body + ")"
                    : "Unexpected reply " + r.code + ": " + r.body;
            runOnUiThread(() -> status.setText(msg));
        }).start();
    }

    private void openAssistantSettings() {
        for (String action : new String[]{Settings.ACTION_VOICE_INPUT_SETTINGS, Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS}) {
            try {
                startActivity(new Intent(action));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        status.setText("Open Settings › Apps › Default apps › Digital assistant app.");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        showMicState();
        showBusyState();
    }

    private void showMicState() {
        boolean mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        status.setText(mic ? "Microphone allowed." : "Microphone not allowed yet.");
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setPadding(0, dp(12), 0, 0);
        return t;
    }

    private EditText field(String value, int type) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(type);
        e.setSingleLine(true);
        return e;
    }

    private Button action(String label, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
