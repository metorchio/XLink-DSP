/*
 * XLink DSP
 * Copyright (C) 2026 Maximiliano Torchio
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package ar.mtorchio.xlinkdsp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Fase de una salida (xair Phase) como un solo botón que alterna: dibuja un ciclo de
 * onda que arranca para arriba (0°) o invertida y en naranja (180°), con los grados al lado.
 */
final class PhaseView extends View {

    interface Listener {
        void onPhaseChanged(boolean inverted);
    }

    private static final int STROKE = 0xFF262C3A;
    private static final int TEXT = 0xFFE8ECF4;
    private static final int MUTED = 0xFF8A93A6;
    private static final int ACCENT = 0xFFFF8A1F;
    private static final int POINTS = 32;

    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private final RectF pill = new RectF();
    private final float density;

    private boolean inverted;
    private Listener listener;

    PhaseView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(density);
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(2 * density);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);
        wavePaint.setStrokeJoin(Paint.Join.ROUND);
        axisPaint.setColor(STROKE);
        axisPaint.setStrokeWidth(density);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(12 * density);
        labelPaint.setFakeBoldText(true);
        setClickable(true);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setInverted(boolean inverted) {
        this.inverted = inverted;
        setContentDescription(inverted ? "Fase 180°, invertida" : "Fase 0°, normal");
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float inset = density / 2;
        pill.set(inset, inset, w - inset, h - inset);
        float radius = h / 2;
        strokePaint.setColor(STROKE);
        canvas.drawRoundRect(pill, radius, radius, strokePaint);

        // Un ciclo de seno; invertido arranca para abajo.
        float cy = h / 2;
        float left = 12 * density;
        float span = 22 * density;
        float amp = (inverted ? 1 : -1) * 7 * density;
        canvas.drawLine(left, cy, left + span, cy, axisPaint);
        wave.reset();
        for (int i = 0; i <= POINTS; i++) {
            float t = i / (float) POINTS;
            float y = cy + amp * (float) Math.sin(2 * Math.PI * t);
            if (i == 0) {
                wave.moveTo(left + t * span, y);
            } else {
                wave.lineTo(left + t * span, y);
            }
        }
        wavePaint.setColor(inverted ? ACCENT : TEXT);
        canvas.drawPath(wave, wavePaint);

        labelPaint.setColor(MUTED);
        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        float textCenter = (left + span + w - 10 * density) / 2;
        canvas.drawText(inverted ? "180°" : "0°", textCenter,
                cy - (metrics.ascent + metrics.descent) / 2, labelPaint);
    }

    @Override
    public boolean performClick() {
        setInverted(!inverted);
        if (listener != null) {
            listener.onPhaseChanged(inverted);
        }
        return super.performClick();
    }
}
