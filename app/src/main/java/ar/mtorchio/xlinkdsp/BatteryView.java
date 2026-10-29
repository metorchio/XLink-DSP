/*
 * XLink DSP
 * Copyright (C) 2026 Maximiliano Torchio
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package ar.mtorchio.xlinkdsp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

/**
 * Batería chiquita con la tensión adentro (de V;). El relleno va de 10 a 14,5 V
 * (batería de auto: ~12,6 V en reposo, ~14 V con el motor andando) y cambia de
 * color: verde bien, ámbar baja, rojo muy baja. Sin dato queda vacía con "--".
 */
final class BatteryView extends View {

    private static final float EMPTY_V = 10f;
    private static final float FULL_V = 14.5f;
    private static final int STROKE = 0xFF8A93A6;
    private static final int TEXT = 0xFFE8ECF4;

    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF();
    private final RectF level = new RectF();
    private final float density;
    private float voltage = Float.NaN;

    BatteryView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(1.4f * density);
        outlinePaint.setColor(STROKE);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(10.5f * density);
        textPaint.setFakeBoldText(true);
        textPaint.setColor(TEXT);
        setContentDescription("Batería sin dato");
    }

    /** NaN = sin dato. */
    void setVoltage(float voltage) {
        if (Float.compare(this.voltage, voltage) == 0) {
            return;
        }
        this.voltage = voltage;
        setContentDescription(Float.isNaN(voltage) ? "Batería sin dato"
                : String.format(Locale.ROOT, "Batería %.1f volts", voltage));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float stroke = outlinePaint.getStrokeWidth();
        float nub = 2.5f * density;
        body.set(stroke / 2, stroke / 2, getWidth() - nub - stroke / 2, getHeight() - stroke / 2);
        float radius = 4 * density;

        if (!Float.isNaN(voltage)) {
            float fraction = Math.max(0, Math.min(1, (voltage - EMPTY_V) / (FULL_V - EMPTY_V)));
            int color = voltage < 11f ? 0xFFFF3B3B : voltage < 12f ? 0xFFFFC857 : 0xFF39FF88;
            fillPaint.setColor((color & 0x00FFFFFF) | 0x55000000);
            float inset = 2.2f * density;
            level.set(body.left + inset, body.top + inset,
                    body.left + inset + (body.width() - 2 * inset) * fraction, body.bottom - inset);
            canvas.drawRoundRect(level, radius / 2, radius / 2, fillPaint);
            outlinePaint.setColor(color);
        } else {
            outlinePaint.setColor(STROKE);
        }
        canvas.drawRoundRect(body, radius, radius, outlinePaint);
        // El polo de la batería, a la derecha.
        fillPaint.setColor(outlinePaint.getColor());
        level.set(body.right, body.centerY() - 3 * density, body.right + nub, body.centerY() + 3 * density);
        canvas.drawRoundRect(level, density, density, fillPaint);

        String label = Float.isNaN(voltage) ? "--" : String.format(Locale.ROOT, "%.1fV", voltage);
        float y = body.centerY() - (textPaint.descent() + textPaint.ascent()) / 2;
        canvas.drawText(label, body.centerX(), y, textPaint);
    }
}
