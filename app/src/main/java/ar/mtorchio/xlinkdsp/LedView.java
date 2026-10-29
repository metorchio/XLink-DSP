/*
 * XLink DSP
 * Copyright (C) 2026 Maximiliano Torchio
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package ar.mtorchio.xlinkdsp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * LED de un canal (limiter actuando): un punto con el nombre abajo. Apagado es un
 * punto oscuro con borde; prendido, rojo con un halo alrededor.
 */
final class LedView extends View {

    private static final int RED = 0xFFFF3B3B;
    private static final int OFF_FILL = 0xFF1E2330;
    private static final int OFF_STROKE = 0xFF323949;
    private static final int MUTED = 0xFF8A93A6;
    private static final int TEXT = 0xFFE8ECF4;

    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final String label;
    private boolean on;

    LedView(Context context, String label) {
        super(context);
        this.label = label;
        density = getResources().getDisplayMetrics().density;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(density);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(10 * density);
        labelPaint.setFakeBoldText(true);
        setContentDescription(label + ": limiter sin actuar");
    }

    void setOn(boolean on) {
        if (this.on == on) {
            return;
        }
        this.on = on;
        setContentDescription(label + (on ? ": limitando" : ": limiter sin actuar"));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float radius = 6 * density;
        float cy = 16 * density;
        if (on) {
            float glow = radius * 2.2f;
            glowPaint.setShader(new RadialGradient(cx, cy, glow, 0x88FF3B3B, 0x00FF3B3B, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, glow, glowPaint);
            dotPaint.setColor(RED);
            canvas.drawCircle(cx, cy, radius, dotPaint);
            // Brillo chico arriba a la izquierda, como un LED de verdad.
            dotPaint.setColor(0x99FFFFFF);
            canvas.drawCircle(cx - radius * 0.35f, cy - radius * 0.35f, radius * 0.3f, dotPaint);
        } else {
            dotPaint.setColor(OFF_FILL);
            canvas.drawCircle(cx, cy, radius, dotPaint);
            strokePaint.setColor(OFF_STROKE);
            canvas.drawCircle(cx, cy, radius, strokePaint);
        }
        labelPaint.setColor(on ? TEXT : MUTED);
        canvas.drawText(label, cx, getHeight() - 3 * density, labelPaint);
    }
}
