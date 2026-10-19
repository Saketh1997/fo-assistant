package org.sakethmetta.foassist;

import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.AnticipateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Messenger-style chat head for Fo: a floating bird that pops up with Fo's message on screen, instead of
 * the notification bar. Drag it anywhere (it snaps to the nearest edge), tap it to open or close the
 * messages, drag it onto the X at the bottom to dismiss. Buttons fire the original notification's own
 * actions, so Approve/Deny still go through ntfy and the bridge's single-use checks. Main thread only.
 */
final class FloatingBird {
    /** One Fo notification, lifted out of the notification bar. */
    static final class Item {
        final String key;
        final String title;
        final String text;
        final PendingIntent open;
        final List<String> labels = new ArrayList<>();
        final List<PendingIntent> actions = new ArrayList<>();

        Item(String key, String title, String text, PendingIntent open) {
            this.key = key;
            this.title = title;
            this.text = text;
            this.open = open;
        }

        /** Something to answer (Approve, Reply, a form) stays until handled; progress lines come and go. */
        boolean sticky() {
            return !actions.isEmpty();
        }
    }

    private static final int MAX_STICKY = 5;
    private static FloatingBird instance;

    private final Context c;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Item> items = new ArrayList<>();
    private final float d;

    private BirdView head;
    private WindowManager.LayoutParams headLp;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView target;
    private long collapsedAt;
    private final Runnable collapse = this::collapse;
    private final Runnable leave = this::flyAway;

    static FloatingBird get(Context c) {
        if (instance == null) {
            instance = new FloatingBird(c.getApplicationContext());
        }
        return instance;
    }

    private FloatingBird(Context c) {
        this.c = c;
        wm = c.getSystemService(WindowManager.class);
        d = c.getResources().getDisplayMetrics().density;
    }

    void show(Item it) {
        items.removeIf(x -> x.key.equals(it.key) || (!it.sticky() && !x.sticky()));  // keep only the newest progress line
        items.add(0, it);
        int sticky = 0;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).sticky() && ++sticky > MAX_STICKY) {
                items.remove(i--);
            }
        }
        if (addHead()) {
            arrive();
            main.postDelayed(this::open, 520);  // the message floats in once Fo has landed
        } else {
            greet();
            open();
        }
    }

    private boolean onRight() {
        return headLp.x + size() / 2 > screen().widthPixels / 2;
    }

    /** Roll in from the edge, land with a squishy bounce, then blink, beam and wiggle hello. */
    private void arrive() {
        int to = headLp.x, from = onRight() ? screen().widthPixels : -size();
        headLp.x = from;
        wm.updateViewLayout(head, headLp);
        head.setRotation(onRight() ? 140 : -140);  // rolling in
        ValueAnimator slide = ValueAnimator.ofInt(from, to);
        slide.setDuration(480);
        slide.setInterpolator(new OvershootInterpolator(1.6f));
        slide.addUpdateListener(an -> moveTo((int) an.getAnimatedValue()));
        ObjectAnimator roll = ObjectAnimator.ofFloat(head, View.ROTATION, head.getRotation(), 0f);
        roll.setDuration(480);
        roll.setInterpolator(new DecelerateInterpolator(1.5f));
        AnimatorSet in = new AnimatorSet();
        in.playTogether(slide, roll);
        in.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (head != null) {
                    squish(true);
                    head.postDelayed(FloatingBird.this::cheer, 260);
                }
            }
        });
        in.start();
    }

    /** A new message while Fo is already out: a squish and a happy hello, no re-entry. */
    private void greet() {
        squish(false);
        head.postDelayed(this::cheer, 200);
    }

    /** Jelly squash-and-stretch: flattened against the wall it landed on, or a little hop in place. */
    private void squish(boolean sideways) {
        float[] wide = sideways ? new float[]{1f, 0.84f, 1.08f, 0.97f, 1f} : new float[]{1f, 1.12f, 0.92f, 1.04f, 1f};
        float[] tall = sideways ? new float[]{1f, 1.10f, 0.94f, 1.02f, 1f} : new float[]{1f, 0.88f, 1.08f, 0.97f, 1f};
        ObjectAnimator j = ObjectAnimator.ofPropertyValuesHolder(head,
                PropertyValuesHolder.ofFloat(View.SCALE_X, wide), PropertyValuesHolder.ofFloat(View.SCALE_Y, tall));
        j.setDuration(460);
        j.start();
    }

    /** Blink, beam, wiggle, and glance toward the message. */
    private void cheer() {
        if (head == null) {
            return;
        }
        head.blink(1);
        head.beam(1300);
        ObjectAnimator wiggle = ObjectAnimator.ofFloat(head, View.ROTATION, 0f, -12f, 10f, -6f, 3f, 0f);
        wiggle.setStartDelay(180);
        wiggle.setDuration(620);
        wiggle.start();
        head.postDelayed(() -> {
            if (head != null) {
                head.lookAt(onRight() ? -1f : 1f);
            }
        }, 820);
    }

    private void moveTo(int x) {
        if (head != null && head.getParent() != null) {
            headLp.x = x;
            wm.updateViewLayout(head, headLp);
        }
    }

    /** Nothing left to answer: a little wave, then roll back out the side it came from. */
    private void flyAway() {
        if (head == null) {
            return;
        }
        BirdView h = head;
        h.lookAt(0f);
        h.blink(1);
        ObjectAnimator wave = ObjectAnimator.ofFloat(h, View.ROTATION, 0f, -14f, 12f, -8f, 0f);
        wave.setDuration(520);
        int to = onRight() ? screen().widthPixels : -size();
        ValueAnimator out = ValueAnimator.ofInt(headLp.x, to);
        out.setDuration(380);
        out.setInterpolator(new AccelerateInterpolator(1.4f));
        out.addUpdateListener(an -> moveTo((int) an.getAnimatedValue()));
        ObjectAnimator roll = ObjectAnimator.ofFloat(h, View.ROTATION, 0f, onRight() ? 160f : -160f);
        roll.setDuration(380);
        AnimatorSet away = new AnimatorSet();
        away.play(wave).before(out);
        away.play(out).with(roll);
        away.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (head != h) {
                    return;
                }
                if (items.isEmpty()) {
                    dismiss();
                } else {  // a message came in while Fo was leaving: roll back in
                    h.setRotation(0f);
                    headLp.x = to > 0 ? screen().widthPixels - size() - Math.round(4 * d) : Math.round(4 * d);
                    arrive();
                }
            }
        });
        away.start();
    }

    /** Dropped on the X: Fo shrinks away into it. */
    private void popInto() {
        BirdView h = head;
        h.blink(1);
        h.animate().scaleX(0f).scaleY(0f).setDuration(260).setInterpolator(new AnticipateInterpolator(2f))
                .withEndAction(() -> {
                    if (head == h) {
                        dismiss();
                    }
                }).start();
    }

    private int stickyCount() {
        int n = 0;
        for (Item i : items) {
            n += i.sticky() ? 1 : 0;
        }
        return n;
    }

    private int size() {
        return Math.round(60 * d);
    }

    private DisplayMetrics screen() {
        return c.getResources().getDisplayMetrics();
    }

    private static WindowManager.LayoutParams params(int w, int h, int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | flags, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    /** True when the head was just added (so it should make an entrance). */
    private boolean addHead() {
        main.removeCallbacks(leave);
        if (head != null) {
            head.setCount(stickyCount());
            return false;
        }
        head = new BirdView(c);
        head.setCount(stickyCount());
        headLp = params(size(), size(), WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);  // so it can roll in from off screen
        headLp.x = screen().widthPixels - size() - Math.round(4 * d);
        headLp.y = screen().heightPixels / 4;
        head.setOnTouchListener(new Drag());
        wm.addView(head, headLp);
        return true;
    }

    /** Show the messages next to the bird. Progress-only pops close on their own; questions stay open. */
    private void open() {
        main.removeCallbacks(collapse);
        main.removeCallbacks(leave);
        if (items.isEmpty()) {
            collapse();
            return;
        }
        if (panel == null) {
            panel = new LinearLayout(c);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(Math.round(10 * d), 0, Math.round(10 * d), 0);
            panel.setOnTouchListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    collapse();
                }
                return false;
            });
            panelLp = params(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        }
        boolean fresh = panel.getParent() == null;
        panel.removeAllViews();
        for (int i = 0; i < items.size(); i++) {
            View card = card(items.get(i));
            if (fresh || i == 0) {  // cards float in from Fo's side, one after another
                card.setAlpha(0f);
                card.setScaleX(0.9f);
                card.setScaleY(0.9f);
                card.setTranslationY((headLp.y < screen().heightPixels / 2 ? -14 : 14) * d);
                card.setPivotX(onRight() ? screen().widthPixels : 0);
                card.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(i * 60L)
                        .setDuration(300).setInterpolator(new OvershootInterpolator(1.2f)).start();
            }
            panel.addView(card);
        }
        place();
        if (fresh) {
            wm.addView(panel, panelLp);
        } else {
            wm.updateViewLayout(panel, panelLp);
        }
        head.setCount(stickyCount());
        if (stickyCount() == 0) {
            main.postDelayed(collapse, 5000);
        }
    }

    /** Below the bird when it's in the top half of the screen, above it otherwise. */
    private void place() {
        int gap = Math.round(8 * d), h = screen().heightPixels;
        if (headLp.y < h / 2) {
            panelLp.gravity = Gravity.TOP | Gravity.START;
            panelLp.y = headLp.y + size() + gap;
        } else {
            panelLp.gravity = Gravity.BOTTOM | Gravity.START;
            panelLp.y = h - headLp.y + gap;
        }
    }

    private void collapse() {
        main.removeCallbacks(collapse);
        if (panel != null && panel.getParent() != null) {
            wm.removeView(panel);
            collapsedAt = System.currentTimeMillis();
        }
        items.removeIf(x -> !x.sticky());  // a progress line has been seen once it's gone
        if (head != null) {
            head.setCount(stickyCount());
            if (items.isEmpty()) {
                main.postDelayed(leave, 8000);  // nothing to answer: the bird flies off by itself
            }
        }
    }

    void dismiss() {
        main.removeCallbacks(collapse);
        main.removeCallbacks(leave);
        items.clear();
        for (View v : new View[]{panel, head, target}) {
            if (v != null && v.getParent() != null) {
                wm.removeView(v);
            }
        }
        head = null;
        target = null;
    }

    private void done(Item it) {
        items.remove(it);
        if (items.isEmpty()) {
            collapse();
        } else {
            open();
        }
    }

    private View card(Item it) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(14 * d);
        card.setPadding(p, Math.round(12 * d), p, Math.round(12 * d));
        card.setBackground(round(0xF21F2328, 18));
        card.setElevation(6 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Math.round(8 * d);
        card.setLayoutParams(lp);

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(it.title, 15, Color.WHITE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("✕", 16, 0xFF7D8590);
        close.setPadding(Math.round(12 * d), 0, 0, 0);
        close.setOnClickListener(v -> done(it));
        top.addView(close);
        card.addView(top);

        if (!it.text.isEmpty() && !it.text.equals(it.title)) {
            TextView body = text(it.text, 14, 0xFFC9D1D9);
            body.setMaxLines(it.sticky() ? 8 : 3);
            body.setEllipsize(android.text.TextUtils.TruncateAt.END);
            body.setPadding(0, Math.round(4 * d), 0, 0);
            card.addView(body);
        }

        if (!it.actions.isEmpty()) {
            LinearLayout row = new LinearLayout(c);
            row.setPadding(0, Math.round(10 * d), 0, 0);
            for (int i = 0; i < it.actions.size(); i++) {
                String label = it.labels.get(i);
                PendingIntent pi = it.actions.get(i);
                boolean yes = label.equalsIgnoreCase("approve") || label.equalsIgnoreCase("call back"), no = label.equalsIgnoreCase("deny");
                TextView b = text(label, 14, Color.WHITE);
                b.setGravity(Gravity.CENTER);
                b.setPadding(Math.round(10 * d), Math.round(9 * d), Math.round(10 * d), Math.round(9 * d));
                b.setBackground(round(yes ? 0xFF1A7F37 : no ? 0xFFCF222E : 0xFF30363D, 12));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                blp.rightMargin = i < it.actions.size() - 1 ? Math.round(8 * d) : 0;
                b.setOnClickListener(v -> {
                    fire(pi);
                    done(it);
                });
                row.addView(b, blp);
            }
            card.addView(row);
        }

        card.setOnClickListener(v -> {  // the card itself does what tapping the notification did
            if (it.open != null) {
                fire(it.open);
            }
            done(it);
            collapse();
        });
        return card;
    }

    /** Send a notification's PendingIntent. Our overlay is on screen, which lets it open activities. */
    @SuppressWarnings("deprecation")
    private void fire(PendingIntent pi) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions o = ActivityOptions.makeBasic();
                o.setPendingIntentBackgroundActivityStartMode(Build.VERSION.SDK_INT >= 36
                        ? ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                        : ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                pi.send(c, 0, null, null, null, null, o.toBundle());
            } else {
                pi.send();
            }
        } catch (PendingIntent.CanceledException e) {
            android.widget.Toast.makeText(c, "That notification has expired", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * d);
        return g;
    }

    /** Drag to move, snap to an edge on release, drop on the X to dismiss; a touch without movement is a tap. */
    private final class Drag implements View.OnTouchListener {
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;
        private final int slop = ViewConfiguration.get(c).getScaledTouchSlop();

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = headLp.x;
                    startY = headLp.y;
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        if (panel != null && panel.getParent() != null) {
                            wm.removeView(panel);
                        }
                        showTarget();
                    }
                    if (dragging) {
                        headLp.x = startX + Math.round(dx);
                        headLp.y = startY + Math.round(dy);
                        wm.updateViewLayout(head, headLp);
                        float scale = overTarget() ? 1.3f : 1f;
                        target.setScaleX(scale);
                        target.setScaleY(scale);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!dragging) {
                        tap();
                        return true;
                    }
                    if (overTarget()) {
                        if (target != null && target.getParent() != null) {
                            wm.removeView(target);
                        }
                        popInto();
                        return true;
                    }
                    if (target != null && target.getParent() != null) {
                        wm.removeView(target);
                    }
                    snap();
                    return true;
            }
            return false;
        }

        private void tap() {
            if (panel != null && panel.getParent() != null) {
                collapse();
            } else if (System.currentTimeMillis() - collapsedAt > 300) {  // the same tap already closed it
                open();
            }
        }

        private void snap() {
            int w = screen().widthPixels, edge = Math.round(4 * d);
            int to = headLp.x + size() / 2 < w / 2 ? edge : w - size() - edge;
            headLp.y = Math.max(0, Math.min(headLp.y, screen().heightPixels - size()));
            ValueAnimator a = ValueAnimator.ofInt(headLp.x, to);
            a.setDuration(220);
            a.addUpdateListener(an -> {
                if (head != null) {
                    headLp.x = (int) an.getAnimatedValue();
                    wm.updateViewLayout(head, headLp);
                }
            });
            a.start();
            if (!items.isEmpty() && stickyCount() > 0) {
                main.postDelayed(FloatingBird.this::open, 240);
            }
        }

        private void showTarget() {
            if (target == null) {
                target = text("✕", 26, Color.WHITE);
                target.setGravity(Gravity.CENTER);
                GradientDrawable g = round(0xCC30363D, 0);
                g.setShape(GradientDrawable.OVAL);
                target.setBackground(g);
            }
            if (target.getParent() == null) {
                int t = Math.round(64 * d);
                WindowManager.LayoutParams lp = params(t, t, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                lp.y = Math.round(56 * d);
                wm.addView(target, lp);
            }
        }

        private boolean overTarget() {
            float tx = screen().widthPixels / 2f, ty = screen().heightPixels - 56 * d - 32 * d;
            float hx = headLp.x + size() / 2f, hy = headLp.y + size() / 2f;
            return Math.hypot(hx - tx, hy - ty) < 90 * d;
        }
    }

    /** A sample pop for the settings screen's "Show me the bird" button. */
    static Item sample() {
        return new Item("sample", "Fo", "This is how Fo's messages will look. Drag me around, tap me, or drop me on the X.", null);
    }
}
