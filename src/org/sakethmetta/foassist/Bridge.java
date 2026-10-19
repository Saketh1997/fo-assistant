package org.sakethmetta.foassist;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.speech.RecognitionService;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Settings plus the one HTTP call to fo-bridge. */
final class Bridge {
    static final String DEFAULT_URL = BuildConfig.BRIDGE_URL;  // from bridge.url at build time, see build.sh
    private static final int MAX_SHOT_PX = 1280;

    private Bridge() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("fo", Context.MODE_PRIVATE);
    }

    static String url(Context c) {
        return prefs(c).getString("url", DEFAULT_URL).replaceAll("/+$", "");
    }

    static String token(Context c) {
        return prefs(c).getString("token", "");
    }

    static boolean autoSend(Context c) {
        return prefs(c).getBoolean("autosend", true);
    }

    /** Screen + voice (true) or voice only; the panel's chip flips it and it sticks. */
    static boolean withScreenshot(Context c) {
        return prefs(c).getBoolean("screenshot", true);
    }

    static void setWithScreenshot(Context c, boolean on) {
        prefs(c).edit().putBoolean("screenshot", on).apply();
    }

    /** Fo's notifications as a floating bird on screen instead of in the notification bar. */
    static boolean bird(Context c) {
        return prefs(c).getBoolean("bird", false);
    }

    static void setBird(Context c, boolean on) {
        prefs(c).edit().putBoolean("bird", on).apply();
    }

    /** What the bird did with the last ntfy notification, for the settings screen. */
    /** The bird's last few decisions, newest first, so a missed pop-up can be traced afterwards. */
    static void noteBird(Context c, String title, String what) {
        String when = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
        String line = when + "  " + (title.isEmpty() ? "" : "\u201c" + title + "\u201d: ") + what;
        String[] old = prefs(c).getString("bird_log", "").split("\n");
        StringBuilder log = new StringBuilder(line);
        for (int i = 0; i < old.length && i < 5; i++) {
            if (!old[i].isEmpty()) {
                log.append('\n').append(old[i]);
            }
        }
        prefs(c).edit().putString("bird_log", log.toString()).apply();
    }

    static String lastBird(Context c) {
        return prefs(c).getString("bird_log", "no ntfy notification seen yet");
    }

    /** Result of a bridge call: HTTP status (0 = no connection) and the body or error text. */
    static final class Result {
        final int code;
        final String body;

        Result(int code, String body) {
            this.code = code;
            this.body = body;
        }

        boolean ok() {
            return code >= 200 && code < 300;
        }
    }

    /** An errand Fo is waiting on the user for, from GET /pending. */
    static final class Pending {
        final String job;
        final String q;
        final String errand;
        final boolean approval;

        Pending(JSONObject o) {
            job = o.optString("job");
            q = o.optString("q");
            errand = o.optString("errand");
            approval = "approval".equals(o.optString("kind"));
        }
    }

    /** GET /pending: null when nothing is waiting or the bridge can't say. Blocking; call off the main thread. */
    static Pending pending(Context c) {
        try {
            Result r = request(c, "GET", "/pending", null, 4000);
            JSONObject o = r.ok() ? new JSONObject(r.body) : null;
            return o != null && o.has("job") ? new Pending(o) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** POST /handle; replyTo (with its question id) answers that errand instead of starting one. Blocking. */
    static Result send(Context c, String text, Bitmap shot, Pending replyTo) {
        try {
            JSONObject body = new JSONObject().put("text", text);
            if (shot != null) {
                body.put("screenshot", encode(shot));
            }
            if (replyTo != null) {
                body.put("reply_to", replyTo.job).put("q", replyTo.q);
            }
            return post(c, body);
        } catch (Exception e) {
            return new Result(0, e.toString());
        }
    }

    /** POST /handle with no text: 400 means the token is right, 401 means it's wrong. Fo is never called. */
    static Result checkToken(Context c) {
        try {
            return post(c, new JSONObject().put("text", ""));
        } catch (Exception e) {
            return new Result(0, e.toString());
        }
    }

    private static Result post(Context c, JSONObject body) throws Exception {
        return request(c, "POST", "/handle", body, 20000);
    }

    /** Signed in with a Wajo account (as opposed to the bridge owner's token). */
    static boolean wajoAccount(Context c) {
        return "wajo".equals(prefs(c).getString("account", ""));
    }

    /** Forget this device's account, telling the bridge to drop its Wajo tokens first. Blocking. */
    static void signOut(Context c) {
        try {
            if (wajoAccount(c)) {
                request(c, "POST", "/logout", null, 8000);
            }
        } catch (Exception ignored) {  // offline: the key is forgotten here either way
        }
        prefs(c).edit().remove("token").remove("account").remove("ntfy_topic").remove("ntfy_url").apply();
    }

    static Result request(Context c, String method, String path, JSONObject body, int timeout) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url(c) + path).openConnection();
        conn.setConnectTimeout(Math.min(timeout, 8000));
        conn.setReadTimeout(timeout);
        conn.setRequestMethod(method);
        if (!token(c).isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token(c));
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = conn.getResponseCode();
        InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
        String text = "";
        if (in != null) {
            try (InputStream s = in) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                for (int n; (n = s.read(b)) > 0; ) {
                    buf.write(b, 0, n);
                }
                text = buf.toString("UTF-8");
            }
        }
        conn.disconnect();
        return new Result(code, text);
    }

    private static String encode(Bitmap shot) {
        float scale = Math.min(1f, (float) MAX_SHOT_PX / Math.max(shot.getWidth(), shot.getHeight()));
        Bitmap b = scale < 1f
                ? Bitmap.createScaledBitmap(shot, Math.round(shot.getWidth() * scale), Math.round(shot.getHeight() * scale), true)
                : shot;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 75, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    /**
     * Becoming the assistant also makes our stub the default voice input, so speech has to go to
     * a real recognizer by name. Prefer Google's; null means use the on-device one if there is one.
     */
    static ComponentName pickRecognizer(Context c) {
        List<ResolveInfo> services = c.getPackageManager()
                .queryIntentServices(new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        ComponentName best = null;
        int bestRank = Integer.MAX_VALUE;
        String[] preferred = {"com.google.android.googlequicksearchbox", "com.google.android.as", "com.google.android.tts"};
        for (ResolveInfo ri : services) {
            String pkg = ri.serviceInfo.packageName;
            if (pkg.equals(c.getPackageName())) {
                continue;
            }
            int rank = preferred.length;
            for (int i = 0; i < preferred.length; i++) {
                if (pkg.equals(preferred[i])) {
                    rank = i;
                }
            }
            if (rank < bestRank) {
                bestRank = rank;
                best = new ComponentName(pkg, ri.serviceInfo.name);
            }
        }
        return best;
    }
}
