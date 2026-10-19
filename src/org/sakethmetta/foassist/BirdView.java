package org.sakethmetta.foassist;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Fo's floating head, after Wajo's Fo (media.wajo.ai/og-card-fo-built-on-trust-v1.png): a soft white
 * sphere with tall black eyes, a small smile and pink cheeks, plus a count badge. Drawn, not a bitmap.
 */
final class BirdView extends View {
    private final Paint ball = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint eye = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smile = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blush = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float shaded;  // size the shaders were built for
    private int count;
    private float open = 1f;   // eye openness: 1 open, ~0 shut (a blink)
    private float joy;         // 0..1: wider smile, rosier cheeks
    private float look;        // -1..1: eyes glance left/right, toward the message
    private final Runnable idleBlink = new Runnable() {
        @Override
        public void run() {
            blink(Math.random() < 0.25 ? 2 : 1);  // now and then a double blink
            postDelayed(this, 3500 + (long) (Math.random() * 3500));
        }
    };

    BirdView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        setLayerType(LAYER_TYPE_SOFTWARE, null);  // shadow layers need a software layer
        ball.setShadowLayer(4 * d, 0, 1.5f * d, 0x66000000);  // keeps a white ball visible on white screens
        eye.setColor(0xFF0A0A0A);
        glint.setColor(0xCCFFFFFF);
        smile.setColor(0xFF0A0A0A);
        smile.setStyle(Paint.Style.STROKE);
        smile.setStrokeCap(Paint.Cap.ROUND);
        badge.setColor(0xFF1F6FEB);
        badgeText.setColor(0xFFFFFFFF);
        badgeText.setFakeBoldText(true);
        badgeText.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postDelayed(idleBlink, 4000);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(idleBlink);
        super.onDetachedFromWindow();
    }

    /** Close and reopen the eyes, `times` times in a row. */
    void blink(int times) {
        float[] k = new float[times * 2 + 1];
        for (int i = 0; i < k.length; i++) {
            k[i] = i % 2 == 0 ? 1f : 0.06f;
        }
        ValueAnimator a = ValueAnimator.ofFloat(k);
        a.setDuration(170L * times);
        a.addUpdateListener(v -> {
            open = (float) v.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    /** A happy beat: the smile widens and the cheeks flush, then settle. */
    void beam(long ms) {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f, 1f, 0f);
        a.setDuration(ms);
        a.addUpdateListener(v -> {
            joy = (float) v.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    /** Glance toward one side (-1 left, 1 right, 0 ahead). */
    void lookAt(float to) {
        ValueAnimator a = ValueAnimator.ofFloat(look, to);
        a.setDuration(260);
        a.addUpdateListener(v -> {
            look = (float) v.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    void setCount(int n) {
        count = n;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight()), cx = s / 2, cy = s / 2, rad = 0.44f * s;
        if (shaded != s) {
            shaded = s;
            // lit from the upper left, like the 3D Fo: white on top, a warm grey toward the lower right edge
            ball.setShader(new RadialGradient(0.38f * s, 0.32f * s, 0.62f * s,
                    new int[]{0xFFFFFFFF, 0xFFF7F5F3, 0xFFDCD8D4}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            smile.setStrokeWidth(0.04f * s);
        }
        c.drawCircle(cx, cy, rad, ball);

        for (int side = -1; side <= 1; side += 2) {
            float ex = cx + side * 0.165f * s + look * 0.03f * s, ey = 0.44f * s;
            float bx = cx + side * 0.28f * s, by = 0.64f * s, br = 0.09f * s;  // pink cheeks, fading out
            int pink = Math.round(0x66 + joy * 0x50) << 24 | 0xFF8A8A;
            blush.setShader(new RadialGradient(bx, by, br * (1 + joy * 0.2f), pink, 0x00FF8A8A, Shader.TileMode.CLAMP));
            c.drawCircle(bx, by, br, blush);

            c.save();
            c.rotate(side * -8f, ex, ey);  // eyes tilt in slightly at the top
            float ry = 0.125f * s * Math.max(open, 0.06f);
            r.set(ex - 0.08f * s, ey - ry, ex + 0.08f * s, ey + ry);
            c.drawOval(r, eye);
            if (open > 0.6f) {
                r.set(ex - 0.048f * s, ey - 0.095f * s * open, ex - 0.010f * s, ey - 0.035f * s * open);  // the glossy highlight
                c.drawOval(r, glint);
            }
            c.restore();
        }

        float w = 0.10f + joy * 0.025f;  // a small smile that grows when Fo is happy
        r.set(cx - w * s, (0.53f - joy * 0.02f) * s, cx + w * s, (0.67f + joy * 0.03f) * s);
        c.drawArc(r, 25 - joy * 10, 130 + joy * 20, false, smile);

        if (count > 0) {
            float br = 0.14f * s, bx = 0.84f * s, by = 0.16f * s;
            c.drawCircle(bx, by, br, badge);
            badgeText.setTextSize(br * 1.3f);
            c.drawText(count > 9 ? "9+" : String.valueOf(count), bx, by + br * 0.45f, badgeText);
        }
    }
}
