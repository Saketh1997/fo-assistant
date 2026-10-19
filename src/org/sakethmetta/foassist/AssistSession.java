package org.sakethmetta.foassist;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionSession;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * The panel that opens on a long press of power: listens, shows what it heard next to a
 * thumbnail of the screenshot, and sends both to fo-bridge. Fo's progress then arrives as ntfy pushes.
 * When Fo is waiting on a question, the words answer that errand instead ("Replying to"); approvals
 * stay taps on the notification, so a misheard "yes" can never approve anything.
 *
 * Looks like the app icon: a white sheet with Fo perched on top. Fo squishes to your voice inside pink
 * listening rings, nods as words arrive, beams when it has them, hops while sending, and tilts its head
 * when something goes wrong.
 */
public class AssistSession extends VoiceInteractionSession {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Bitmap shot;
    private SpeechRecognizer recognizer;
    private boolean sending;
    private Bridge.Pending replyTo;     // the errand this panel answers; null = a new errand
    private boolean pendingChecked;     // /pending has answered (or failed) for this panel
    private boolean sendWhenChecked;    // speech finished before /pending did
    private int showCount;              // drops a late /pending answer from an earlier panel

    private TextView status;
    private EditText input;
    private ImageView thumb;
    private Button mic;
    private Button send;
    private TextView mode;
    private LinearLayout replyBar;
    private TextView replyText;
    private TextView newErrand;
    private LinearLayout card;
    private BirdView bird;
    private Pulse pulse;
    private ValueAnimator hop;
    private float level;        // smoothed mic loudness, 0..1
    private long lastNod;

    private static final int INK = 0xFF0A0A0A;       // Fo's eyes
    private static final int SOFT = 0xFFF4F2EF;      // warm light grey, like Fo's shading
    private static final int MUTED = 0xFF8C8782;
    private static final int BLUSH = 0xFFFF8A8A;     // Fo's cheeks

    AssistSession(Context context) {
        super(context);
    }

    @Override
    public View onCreateContentView() {
        Context c = getContext();
        FrameLayout root = new FrameLayout(c);
        root.setBackgroundColor(0x33000000);
        root.setOnClickListener(v -> hide());  // tap outside the sheet to dismiss

        int birdSize = dp(92), ring = dp(150);
        FrameLayout sheet = new FrameLayout(c);  // the card, with Fo perched on its top edge
        root.addView(sheet, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), birdSize / 2 + dp(10), dp(20), dp(22));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadii(new float[]{dp(32), dp(32), dp(32), dp(32), 0, 0, 0, 0});
        card.setBackground(bg);
        card.setElevation(dp(12));
        card.setClickable(true);  // keep taps on the card from dismissing
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = ring / 2;
        sheet.addView(card, cardLp);

        pulse = new Pulse(c);
        pulse.setElevation(dp(13));  // above the card
        sheet.addView(pulse, new FrameLayout.LayoutParams(ring, ring, Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        bird = new BirdView(c);
        bird.setElevation(dp(14));
        FrameLayout.LayoutParams birdLp = new FrameLayout.LayoutParams(birdSize, birdSize, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        birdLp.topMargin = (ring - birdSize) / 2;
        sheet.addView(bird, birdLp);
        bird.setOnClickListener(v -> {
            bird.blink(1);
            bird.beam(700);
            listen();  // tap Fo to talk again
        });

        status = new TextView(c);
        status.setTextColor(0xFF3D3A37);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        status.getPaint().setFakeBoldText(true);
        status.setGravity(Gravity.CENTER);
        card.addView(status);

        mode = new TextView(c);
        mode.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        mode.setPadding(dp(14), dp(6), dp(14), dp(6));
        mode.setOnClickListener(v -> {
            Bridge.setWithScreenshot(getContext(), !Bridge.withScreenshot(getContext()));
            showMode();
            bird.lookAt(0);
            bird.blink(1);
        });
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modeLp.gravity = Gravity.CENTER_HORIZONTAL;
        modeLp.topMargin = dp(8);
        card.addView(mode, modeLp);

        replyBar = new LinearLayout(c);
        replyBar.setOrientation(LinearLayout.HORIZONTAL);
        replyBar.setGravity(Gravity.CENTER_VERTICAL);
        replyBar.setPadding(dp(14), dp(10), dp(8), dp(10));
        replyBar.setBackground(round(0xFFFFF0EE, 16));  // a blush of pink
        replyBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(12);
        card.addView(replyBar, barLp);
        replyText = new TextView(c);
        replyText.setTextColor(0xFF5A3A36);
        replyText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        replyText.setMaxLines(2);
        replyBar.addView(replyText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        newErrand = new TextView(c);
        newErrand.setText("New errand");
        newErrand.setTextColor(Color.WHITE);
        newErrand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        newErrand.setPadding(dp(12), dp(6), dp(12), dp(6));
        newErrand.setBackground(round(INK, 14));
        newErrand.setOnClickListener(v -> showReply(null));
        replyBar.addView(newErrand);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(14));
        card.addView(row);

        thumb = new ImageView(c);
        thumb.setOnClickListener(v -> mode.performClick());
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setClipToOutline(true);
        thumb.setBackground(round(SOFT, 12));
        thumb.setVisibility(View.GONE);
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(50), dp(88));
        thumbLp.rightMargin = dp(12);
        row.addView(thumb, thumbLp);

        input = new EditText(c);
        input.setTextColor(INK);
        input.setHintTextColor(0xFFA8A29C);
        input.setHint("Ask Fo to do something");
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        input.setBackground(round(SOFT, 20));
        input.setPadding(dp(16), dp(14), dp(16), dp(14));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setMaxLines(5);
        input.setOnEditorActionListener((v, actionId, event) -> {
            sendNow();
            return true;
        });
        input.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                stopListening();  // typing instead of talking
                bird.lookAt(-0.6f);  // peeks at what you type
            }
        });
        row.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(buttons);
        mic = button(c, "Speak", SOFT, INK);
        mic.setOnClickListener(v -> listen());
        send = button(c, "Send to Fo", INK, Color.WHITE);
        send.setOnClickListener(v -> sendNow());
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(54), 1f);
        half.rightMargin = dp(10);
        buttons.addView(mic, half);
        buttons.addView(send, new LinearLayout.LayoutParams(0, dp(54), 1f));
        return root;
    }

    /** The sheet slides up and Fo pops in on top with a bounce, a double blink and a smile. */
    private void enter() {
        card.setTranslationY(dp(420));
        card.animate().translationY(0).setDuration(380).setInterpolator(new OvershootInterpolator(0.9f)).start();
        bird.setScaleX(0f);
        bird.setScaleY(0f);
        bird.setRotation(-20);
        bird.setTranslationY(dp(420));
        bird.animate().translationY(0).setDuration(380).setInterpolator(new DecelerateInterpolator()).start();
        bird.animate().scaleX(1f).scaleY(1f).rotation(0).setStartDelay(200).setDuration(520)
                .setInterpolator(new OvershootInterpolator(2.6f))
                .withEndAction(() -> {
                    bird.blink(2);
                    bird.beam(900);
                }).start();
    }

    /** A little nod: Fo is following along. */
    private void nod() {
        long now = System.currentTimeMillis();
        if (now - lastNod < 650) {
            return;
        }
        lastNod = now;
        ObjectAnimator n = ObjectAnimator.ofFloat(bird, View.TRANSLATION_Y, 0, dp(5), 0);
        n.setDuration(260);
        n.start();
    }

    /** Hop, hop, hop while the request is on its way. */
    private void hopping(boolean on) {
        if (hop != null) {
            hop.cancel();
            hop = null;
            bird.setTranslationY(0);
            bird.setScaleX(1f);
            bird.setScaleY(1f);
        }
        if (!on) {
            return;
        }
        hop = ValueAnimator.ofFloat(0f, 1f);
        hop.setDuration(460);
        hop.setRepeatCount(ValueAnimator.INFINITE);
        hop.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float up = (float) Math.sin(Math.PI * t);  // 0 -> 1 -> 0
            bird.setTranslationY(-dp(16) * up);
            float squash = t < 0.12f || t > 0.88f ? 0.1f : 0f;  // squish on each landing
            bird.setScaleX(1f + squash);
            bird.setScaleY(1f - squash);
        });
        hop.start();
    }

    /** Head tilt and a side glance: "hm?" */
    private void confused() {
        hopping(false);
        bird.lookAt(-1f);
        ObjectAnimator t = ObjectAnimator.ofFloat(bird, View.ROTATION, 0, -16, -10, -14);
        t.setDuration(520);
        t.start();
        main.postDelayed(() -> {
            bird.animate().rotation(0).setDuration(300).start();
            bird.lookAt(0);
        }, 1600);
    }

    /** It worked: a happy wiggle and the biggest smile. */
    private void cheer() {
        hopping(false);
        bird.beam(1100);
        bird.blink(1);
        ObjectAnimator w = ObjectAnimator.ofFloat(bird, View.ROTATION, 0, -12, 12, -8, 8, 0);
        w.setDuration(620);
        w.start();
        ObjectAnimator j = ObjectAnimator.ofFloat(bird, View.TRANSLATION_Y, 0, -dp(22), 0);
        j.setDuration(420);
        j.setInterpolator(new DecelerateInterpolator());
        j.start();
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        FoNotificationListener.reconnect(getContext());  // so Fo's answer to this errand gets the bird
        shot = null;
        sending = false;
        input.setText("");
        thumb.setVisibility(View.GONE);
        hopping(false);
        bird.setRotation(0);
        bird.lookAt(0);
        enter();
        showMode();
        setBusy(false);
        showReply(null);
        pendingChecked = false;
        sendWhenChecked = false;
        if (Bridge.token(getContext()).isEmpty()) {
            status.setText("Open Fo Assist and sign in first.");
            return;
        }
        checkPending();
        listen();
    }

    /** Ask the bridge whether Fo is waiting on the user; runs alongside the listening. */
    private void checkPending() {
        final int show = ++showCount;
        new Thread(() -> {
            Bridge.Pending p = Bridge.pending(getContext());
            main.post(() -> {
                if (show != showCount) {
                    return;
                }
                pendingChecked = true;
                showReply(p);
                if (sendWhenChecked) {
                    sendWhenChecked = false;
                    sendNow();
                }
            });
        }).start();
    }

    /** Show who the words go to. An approval is only pointed at: that answer is a tap, never voice. */
    private void showReply(Bridge.Pending p) {
        if (p == null) {
            replyTo = null;
            replyBar.setVisibility(View.GONE);
            input.setHint("Ask Fo to do something");
            return;
        }
        replyBar.setVisibility(View.VISIBLE);
        if (p.approval) {
            replyTo = null;
            replyText.setText("Fo wants your OK on \u201c" + p.errand + "\u201d: tap Approve on the notification. This starts a new errand.");
            newErrand.setVisibility(View.GONE);
            input.setHint("Ask Fo to do something");
        } else {
            replyTo = p;
            replyText.setText("Replying to: " + p.errand);
            newErrand.setVisibility(View.VISIBLE);
            input.setHint("Answer Fo");
        }
    }

    @Override
    public void onHandleScreenshot(Bitmap screenshot) {
        // arrives a moment after onShow, and only if "Use screenshot" is on in the assistant settings
        shot = screenshot;
        if (screenshot != null) {
            thumb.setImageBitmap(screenshot);
            thumb.setVisibility(View.VISIBLE);
            showMode();
        }
    }

    @Override
    public void onHide() {
        stopListening();
        hopping(false);
        super.onHide();
    }

    private void listen() {
        stopListening();
        Context c = getContext();
        if (c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status.setText("I can't hear you yet: allow the microphone in Fo Assist, or type below.");
            confused();
            return;
        }
        ComponentName service = Bridge.pickRecognizer(c);
        if (service != null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(c, service);
        } else if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(c)) {
            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(c);
        } else {
            status.setText("No speech recognizer on this phone; type below.");
            confused();
            return;
        }
        recognizer.setRecognitionListener(new Listener());
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.getPackageName());
        status.setText(replyTo != null ? "I'm listening for your answer…" : "I'm listening…");
        pulse.start();
        recognizer.startListening(i);
    }

    private void stopListening() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        if (pulse != null) {
            pulse.stop();
            level = 0;
            bird.setScaleX(1f);
            bird.setScaleY(1f);
        }
    }

    private void sendNow() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || sending) {
            return;
        }
        stopListening();
        if (!pendingChecked) {  // don't guess between a reply and a new errand
            sendWhenChecked = true;
            status.setText("One sec, checking if I asked you something…");
            return;
        }
        sending = true;
        setBusy(true);
        final Bitmap s = Bridge.withScreenshot(getContext()) ? shot : null;  // voice only: the screenshot stays here
        final Bridge.Pending to = replyTo;
        status.setText((to != null ? "Taking your answer" : "On my way") + (s != null ? ", screenshot too…" : "…"));
        hopping(true);
        new Thread(() -> {
            Bridge.Result r = Bridge.send(getContext(), text, s, to);
            main.post(() -> {
                sending = false;
                if (r.ok()) {
                    status.setText(to != null ? "Got your answer! I'll ping you with what's next."
                            : "On it! I'll ping you as I go.");
                    cheer();
                    main.postDelayed(this::leave, 1300);
                } else {
                    setBusy(false);
                    confused();
                    if (to != null && r.code == 409) {
                        // Fo moved on, or the words were a bare "yes": nothing was sent
                        showReply(null);
                        status.setText(errorText(r.body) + " Send again to start a new errand.");
                        return;
                    }
                    status.setText(r.code == 401 ? (Bridge.wajoAccount(getContext())
                            ? "Your Wajo sign-in has expired: open Fo Assist and sign in again."
                            : "Bridge rejected the token: fix it in Fo Assist.")
                            : r.code == 0 ? "Can't reach the bridge: " + r.body
                            : "Bridge error " + r.code + ": " + errorText(r.body));
                }
            });
        }).start();
    }

    private static String errorText(String body) {
        try {
            return new org.json.JSONObject(body).optString("error", body) + ".";
        } catch (org.json.JSONException e) {
            return body;
        }
    }

    /** Slide away with Fo waving bye, then close. */
    private void leave() {
        bird.animate().scaleX(0.6f).scaleY(0.6f).alpha(0f).setDuration(260).start();
        card.animate().translationY(card.getHeight() + dp(120)).setDuration(300)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(() -> {
                    hide();
                    bird.setAlpha(1f);
                }).start();
    }

    private void showMode() {
        boolean on = Bridge.withScreenshot(getContext());
        mode.setText(on ? "\uD83D\uDCF1 Screen + voice" : "\uD83C\uDF99 Voice only");
        mode.setTextColor(on ? Color.WHITE : INK);
        mode.setBackground(round(on ? INK : SOFT, 14));
        thumb.setAlpha(on ? 1f : 0.25f);  // dimmed = won't be sent
    }

    private void setBusy(boolean busy) {
        mic.setEnabled(!busy);
        send.setEnabled(!busy);
        input.setEnabled(!busy);
        mic.setAlpha(busy ? 0.5f : 1f);
        send.setAlpha(busy ? 0.5f : 1f);
    }

    private Button button(Context c, String label, int color, int text) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.getPaint().setFakeBoldText(true);
        b.setStateListAnimator(null);  // flat, no grey material shadow
        b.setBackground(round(color, 27));  // full pill
        b.setOnTouchListener((v, e) -> {  // squishy press
            if (e.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(90).start();
            } else if (e.getAction() == android.view.MotionEvent.ACTION_UP || e.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new OvershootInterpolator(3f)).start();
            }
            return false;
        });
        return b;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * getContext().getResources().getDisplayMetrics().density);
    }

    private class Listener implements RecognitionListener {
        @Override
        public void onPartialResults(Bundle partial) {
            String t = first(partial);
            if (t != null) {
                if (!t.equals(input.getText().toString())) {
                    nod();
                }
                input.setText(t);
            }
        }

        @Override
        public void onResults(Bundle results) {
            String t = first(results);
            if (t == null || t.isEmpty()) {
                status.setText("Hm, I didn't catch that. Tap me or Speak to try again.");
                confused();
                return;
            }
            input.setText(t);
            input.setSelection(t.length());
            if (Bridge.autoSend(getContext())) {
                sendNow();
            } else {
                status.setText("Look right? Send it my way.");
            }
        }

        @Override
        public void onError(int error) {
            pulse.stop();
            status.setText(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ? "Hm, I didn't catch that. Tap me or Speak to try again."
                    : "My ears glitched (speech error " + error + "). Tap Speak, or type.");
            confused();
        }

        @Override
        public void onEndOfSpeech() {
            pulse.stop();
            level = 0;
            bird.setScaleX(1f);
            bird.setScaleY(1f);
            bird.beam(900);
            status.setText("Got it!");
        }

        @Override
        public void onReadyForSpeech(Bundle params) {
        }

        @Override
        public void onBeginningOfSpeech() {
            bird.lookAt(0);
            bird.blink(1);
        }

        @Override
        public void onRmsChanged(float rmsdB) {
            float target = Math.max(0f, Math.min(1f, (rmsdB + 2f) / 12f));
            level += (target - level) * 0.35f;  // smooth the jitter
            if (recognizer != null) {
                bird.setScaleX(1f - level * 0.05f);  // Fo squishes to your voice
                bird.setScaleY(1f + level * 0.09f);
                pulse.level = level;
            }
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }

        private String first(Bundle b) {
            ArrayList<String> r = b == null ? null : b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            return r == null || r.isEmpty() ? null : r.get(0);
        }
    }

    /** Soft pink rings rippling out from behind Fo while it listens; they swell when you talk. */
    private static final class Pulse extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator anim;
        private float t;
        float level;

        Pulse(Context c) {
            super(c);
            paint.setColor(BLUSH);
        }

        void start() {
            if (anim != null) {
                return;
            }
            setAlpha(0f);
            animate().alpha(1f).setDuration(200).start();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(1600);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setInterpolator(null);
            anim.addUpdateListener(a -> {
                t = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        void stop() {
            if (anim == null) {
                return;
            }
            ValueAnimator a = anim;
            anim = null;
            animate().alpha(0f).setDuration(250).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator x) {
                    a.cancel();
                    animate().setListener(null);
                }
            }).start();
            level = 0;
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f, max = getWidth() / 2f;
            float base = max * 0.62f;  // just outside Fo
            for (int i = 0; i < 3; i++) {
                float p = (t + i / 3f) % 1f;
                float r = base + (max - base) * p * (0.55f + 0.45f * level);
                paint.setAlpha(Math.round((1f - p) * (70 + 110 * level)));
                c.drawCircle(cx, cy, r, paint);
            }
        }
    }
}
