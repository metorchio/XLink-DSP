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
 * Botón "seguir" del log (como el follow de los logs de Argo CD): una flecha hacia
 * abajo que apunta a una raya. Prendido va relleno en el naranja de la app.
 */
final class FollowView extends View {

    private static final int ORANGE = 0xFFFF8A1F;
    private static final int BG = 0xFF0E1116;
    private static final int FILL = 0xFF1E2330;
    private static final int STROKE = 0xFF323949;
    private static final int TEXT = 0xFFE8ECF4;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final Path arrow = new Path();
    private final float density;
    private boolean on;

    FollowView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(density);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(2 * density);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        setClickable(true);
        setContentDescription("Seguir la última línea");
    }

    void setOn(boolean on) {
        if (this.on == on) {
            return;
        }
        this.on = on;
        setContentDescription(on ? "Siguiendo la última línea" : "Seguir la última línea");
        invalidate();
    }

    boolean isOn() {
        return on;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = density / 2;
        pill.set(inset, inset, getWidth() - inset, getHeight() - inset);
        float radius = 12 * density;
        fillPaint.setColor(on ? ORANGE : FILL);
        canvas.drawRoundRect(pill, radius, radius, fillPaint);
        if (!on) {
            strokePaint.setColor(STROKE);
            canvas.drawRoundRect(pill, radius, radius, strokePaint);
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float half = 6 * density;
        arrow.reset();
        arrow.moveTo(cx, cy - half);
        arrow.lineTo(cx, cy + half * 0.45f);
        arrow.moveTo(cx - half * 0.6f, cy - half * 0.15f);
        arrow.lineTo(cx, cy + half * 0.45f);
        arrow.lineTo(cx + half * 0.6f, cy - half * 0.15f);
        arrow.moveTo(cx - half * 0.8f, cy + half);
        arrow.lineTo(cx + half * 0.8f, cy + half);
        iconPaint.setColor(on ? BG : TEXT);
        canvas.drawPath(arrow, iconPaint);
    }
}
