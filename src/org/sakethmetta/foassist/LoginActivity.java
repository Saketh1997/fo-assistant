package org.sakethmetta.foassist;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * First screen: sign in (or sign up) with your own Wajo account. The browser opens the bridge's /login,
 * which sends you to Wajo's own log-in / create-account page; Wajo sends you back to the bridge, and the
 * bridge hands this app a one-time code (foassist://login?code=…). The app trades it, plus the secret only
 * it knows, for a key for this device. Errands then run on your Wajo account, and Fo's notifications come
 * on your own ntfy topic.
 */
public class LoginActivity extends Activity {
    private TextView status;
    private LinearLayout col;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        col.setPadding(pad, dp(48), pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(col);
        setContentView(scroll);
        if (!handle(getIntent())) {
            showSignIn();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void showSignIn() {
        col.removeAllViews();
        BirdView bird = new BirdView(this);
        col.addView(bird, new LinearLayout.LayoutParams(dp(132), dp(132)));
        bird.postDelayed(() -> {
            bird.blink(2);
            bird.beam(900);
        }, 600);
        col.addView(text("Fo Assist", 28, true));
        col.addView(text("Long-press power, say what you need, and Fo gets it done from your own Wajo account.", 16, false));
        col.addView(button("Sign in or sign up with Wajo", true, v -> signIn()));
        col.addView(text("Wajo's page lets you log in or create an account. Your password goes to Wajo, never to this app.", 13, false));
        status = text("", 15, false);
        col.addView(status);
        col.addView(button("I run the bridge: use a bridge token", false, v -> {
            startActivity(new Intent(this, SettingsActivity.class).putExtra("manual", true));
            finish();
        }));
    }

    private void signIn() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String verifier = b64(raw);
        Bridge.prefs(this).edit().putString("login_verifier", verifier).apply();
        try {
            String challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(Bridge.url(this) + "/login?c=" + challenge)));
            status.setText("Finish signing in in the browser; it brings you back here.");
        } catch (Exception e) {
            status.setText("Couldn't open the browser: " + e.getMessage());
        }
    }

    /** foassist://login?code=… from the bridge's page: claim this device's key. */
    private boolean handle(Intent intent) {
        Uri u = intent == null ? null : intent.getData();
        if (u == null || !"foassist".equals(u.getScheme()) || u.getQueryParameter("code") == null) {
            return false;
        }
        if (status == null) {
            showSignIn();
        }
        String verifier = Bridge.prefs(this).getString("login_verifier", "");
        if (verifier.isEmpty()) {
            status.setText("That sign-in wasn't started from this phone. Tap Sign in to start again.");
            return true;
        }
        status.setText("Signing you in…");
        String code = u.getQueryParameter("code");
        new Thread(() -> {
            String err;
            try {
                Bridge.Result r = Bridge.request(this, "POST", "/login/claim",
                        new JSONObject().put("code", code).put("verifier", verifier), 15000);
                if (r.code == 200) {
                    JSONObject d = new JSONObject(r.body);
                    Bridge.prefs(this).edit()
                            .putString("token", d.getString("key"))
                            .putString("account", "wajo")
                            .putString("ntfy_topic", d.getString("topic"))
                            .putString("ntfy_url", d.getString("ntfy"))
                            .remove("login_verifier")
                            .apply();
                    runOnUiThread(this::showNotifications);
                    return;
                }
                err = r.code == 0 ? "Can't reach the bridge (" + r.body + ")." : new JSONObject(r.body).optString("error", "HTTP " + r.code);
            } catch (Exception e) {
                err = e.getMessage();
            }
            String msg = "Sign-in didn't finish: " + err;
            runOnUiThread(() -> status.setText(msg));
        }).start();
        return true;
    }

    /** Step two: Fo's replies arrive as ntfy notifications on this account's own topic. */
    private void showNotifications() {
        col.removeAllViews();
        BirdView bird = new BirdView(this);
        col.addView(bird, new LinearLayout.LayoutParams(dp(96), dp(96)));
        bird.postDelayed(() -> bird.beam(1200), 300);
        col.addView(text("You're signed in", 26, true));
        col.addView(text("Fo's replies, questions and Approve buttons come as notifications through the free ntfy app. "
                + "Tap below to subscribe to your private Fo channel (it opens ntfy, or the Play Store if you don't have it yet).", 16, false));
        col.addView(button("Get Fo's notifications in ntfy", true, v -> subscribe()));
        status = text("", 15, false);
        col.addView(status);
        col.addView(button("Continue to setup", false, v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            finish();
        }));
    }

    private void subscribe() {
        Uri ntfy = Uri.parse(Bridge.prefs(this).getString("ntfy_url", ""));
        Uri link = Uri.parse("ntfy://" + ntfy.getHost() + "/" + Bridge.prefs(this).getString("ntfy_topic", "") + "?display=Fo");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, link));
            status.setText("Subscribed in ntfy. Come back here and tap Continue.");
        } catch (ActivityNotFoundException e) {
            status.setText("Install ntfy, then come back and tap the button again.");
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + FoNotificationListener.NTFY)));
            } catch (ActivityNotFoundException e2) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + FoNotificationListener.NTFY)));
            }
        }
    }

    private static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private TextView text(String s, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(Gravity.CENTER);
        t.getPaint().setFakeBoldText(bold);
        t.setPadding(0, dp(12), 0, dp(4));
        return t;
    }

    private Button button(String label, boolean primary, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        if (primary) {
            b.setTextColor(Color.WHITE);
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF1F6FEB));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
