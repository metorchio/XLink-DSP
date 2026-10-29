/*
 * XLink DSP
 * Copyright (C) 2026 Maximiliano Torchio
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package ar.mtorchio.xlinkdsp;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Animación en loop de la ayuda de grupos: un dedo agarra la pill de CH1, la
 * arrastra sobre CH2, CH2 copia sus ajustes (la barrita de abajo) y las dos
 * pills se funden en "CH1 · CH2".
 */
final class GroupsDemoView extends View {

    private static final int STROKE = 0xFF262C3A;
    private static final int TEXT = 0xFFE8ECF4;
    private static final int CYAN = 0xFF00E5FF;
    private static final int ORANGE = 0xFFFF8A1F;
    private static final int VIOLET = 0xFF7C4DFF;

    private static final long LOOP_MS = 4600;
    // Momentos de la animación, en ms dentro del loop.
    private static final float PRESS = 400, DRAG = 900, DROP = 1900, COPY = 2400, MERGE = 2900,
            FADE = 4200;

    private static final String[] NAMES = {"CH1", "CH2", "CH3"};
    private static final float[] LEVELS = {0.75f, 0.3f, 0.5f};
    private static final int[] COLORS = {ORANGE, VIOLET, VIOLET};

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final RectF[] pills = {new RectF(), new RectF(), new RectF()};
    private final RectF rect = new RectF();
    private final float density;
    private final ValueAnimator animator = ValueAnimator.ofFloat(0, LOOP_MS);

    private float time;

    GroupsDemoView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(density);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13,
                getResources().getDisplayMetrics()));
        animator.setDuration(LOOP_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            time = (float) a.getAnimatedValue();
            invalidate();
        });
        // Sin animaciones del sistema se queda en el resultado final.
        time = ValueAnimator.areAnimatorsEnabled() ? 0 : MERGE + 1;
        setContentDescription("Animación: se arrastra CH1 sobre CH2 y quedan vinculados");
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                Math.round(84 * density));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (ValueAnimator.areAnimatorsEnabled()) {
            animator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float gap = 10 * density;
        float height = 34 * density;
        float top = 14 * density;
        float total = -gap;
        float[] widths = new float[NAMES.length];
        for (int i = 0; i < NAMES.length; i++) {
            widths[i] = textPaint.measureText(NAMES[i]) + 28 * density;
            total += widths[i] + gap;
        }
        float x = (w - total) / 2;
        for (int i = 0; i < NAMES.length; i++) {
            pills[i].set(x, top, x + widths[i], top + height);
            x += widths[i] + gap;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float t = time;
        float alpha = 1 - phase(t, FADE, LOOP_MS);
        float lift = phase(t, PRESS, DRAG) * (1 - phase(t, DROP, COPY));
        float drag = ease(phase(t, DRAG, DROP));
        float copy = ease(phase(t, DROP, COPY));
        float merge = ease(phase(t, COPY, MERGE));
        RectF ch1 = pills[0];
        RectF ch2 = pills[1];
        float dx = ch2.left - ch1.left;

        // Barritas: los ajustes de cada canal. CH2 pasa a tener los de CH1.
        for (int i = 0; i < NAMES.length; i++) {
            float level = LEVELS[i];
            int color = COLORS[i];
            if (i == 1) {
                level += (LEVELS[0] - level) * copy;
                color = (int) argb.evaluate(copy, color, COLORS[0]);
            }
            drawBar(canvas, pills[i], level, color, alpha);
        }

        // CH3 queda suelto.
        drawPill(canvas, pills[2], NAMES[2], STROKE, 0, TEXT, alpha);

        if (t < COPY) {
            // CH1 en su lugar (apagado mientras se arrastra) y CH2 como destino.
            drawPill(canvas, ch1, NAMES[0], STROKE, 0, TEXT, alpha * (1 - 0.65f * lift));
            float target = phase(t, DROP - 400, DROP);
            drawPill(canvas, ch2, NAMES[1], (int) argb.evaluate(target, STROKE, CYAN), 0, TEXT, alpha);
        } else {
            // Las dos pills se funden en una sola, de CH2 hacia CH1.
            rect.set(ch2.left - (ch2.left - ch1.left) * merge, ch2.top, ch2.right, ch2.bottom);
            drawPill(canvas, ch1, NAMES[0], STROKE, 0, TEXT, alpha * 0.35f * (1 - merge));
            drawPill(canvas, rect, null, CYAN, 0x22, TEXT, alpha);
            drawText(canvas, rect, NAMES[1], CYAN, alpha * (1 - merge));
            drawText(canvas, rect, "CH1 · CH2", CYAN, alpha * merge);
        }

        // La pill agarrada sigue al dedo.
        float ghost = phase(t, PRESS, DRAG) * (1 - phase(t, DROP, COPY));
        if (ghost > 0) {
            float scale = 1 + 0.08f * lift;
            rect.set(ch1);
            rect.offset(dx * drag, -4 * density * lift);
            float cx = rect.centerX();
            float cy = rect.centerY();
            float hw = rect.width() / 2 * scale;
            float hh = rect.height() / 2 * scale;
            rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
            drawPill(canvas, rect, NAMES[0], CYAN, 0x33, TEXT, alpha * ghost);
        }

        // El dedo: aparece sobre CH1, aprieta, arrastra y se levanta.
        float finger = phase(t, PRESS - 300, PRESS) * (1 - phase(t, DROP, DROP + 300));
        if (finger > 0) {
            float fx = ch1.centerX() + dx * drag + 6 * density;
            float fy = ch1.centerY() + 10 * density;
            float press = phase(t, PRESS, PRESS + 200) * (1 - phase(t, DROP, DROP + 200));
            float radius = (16 - 3 * press) * density;
            fillPaint.setColor(0xFFFFFFFF);
            fillPaint.setAlpha(Math.round(0x40 * finger * alpha));
            canvas.drawCircle(fx, fy, radius, fillPaint);
            strokePaint.setColor(0xFFFFFFFF);
            strokePaint.setAlpha(Math.round(0xB0 * finger * alpha));
            canvas.drawCircle(fx, fy, radius, strokePaint);
        }
    }

    private void drawPill(Canvas canvas, RectF r, String name, int stroke, int fillAlpha, int text, float alpha) {
        float radius = r.height() / 2;
        if (fillAlpha > 0) {
            fillPaint.setColor(stroke);
            fillPaint.setAlpha(Math.round(fillAlpha * alpha));
            canvas.drawRoundRect(r, radius, radius, fillPaint);
        }
        strokePaint.setColor(stroke);
        strokePaint.setAlpha(Math.round(0xFF * alpha));
        canvas.drawRoundRect(r, radius, radius, strokePaint);
        if (name != null) {
            drawText(canvas, r, name, text, alpha);
        }
    }

    private void drawText(Canvas canvas, RectF r, String text, int color, float alpha) {
        if (alpha <= 0) {
            return;
        }
        textPaint.setColor(color);
        textPaint.setAlpha(Math.round(0xFF * alpha));
        float y = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2;
        canvas.drawText(text, r.centerX(), y, textPaint);
    }

    private void drawBar(Canvas canvas, RectF pill, float level, int color, float alpha) {
        float inset = 8 * density;
        float top = pill.bottom + 12 * density;
        float height = 4 * density;
        rect.set(pill.left + inset, top, pill.right - inset, top + height);
        fillPaint.setColor(STROKE);
        fillPaint.setAlpha(Math.round(0xFF * alpha));
        canvas.drawRoundRect(rect, height / 2, height / 2, fillPaint);
        rect.right = rect.left + rect.width() * level;
        fillPaint.setColor(color);
        fillPaint.setAlpha(Math.round(0xFF * alpha));
        canvas.drawRoundRect(rect, height / 2, height / 2, fillPaint);
    }

    /** 0 antes de start, 1 después de end, lineal en el medio. */
    private static float phase(float t, float start, float end) {
        return Math.max(0, Math.min(1, (t - start) / (end - start)));
    }

    private static float ease(float x) {
        return (float) (1 - Math.cos(Math.PI * x)) / 2;
    }
}
