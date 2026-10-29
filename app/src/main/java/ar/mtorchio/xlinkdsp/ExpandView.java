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
 * Botoncito de agrandar / achicar el gráfico: cuatro esquinas que apuntan hacia
 * afuera (agrandar) o hacia adentro (achicar), en una pill.
 */
final class ExpandView extends View {

    private static final int STROKE = 0xFF262C3A;
    private static final int TEXT = 0xFFE8ECF4;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final Path corners = new Path();
    private final float density;
    private final boolean collapse;

    /** collapse = true dibuja las esquinas hacia adentro (volver a chico). */
    ExpandView(Context context, boolean collapse) {
        super(context);
        this.collapse = collapse;
        density = getResources().getDisplayMetrics().density;
        fillPaint.setColor(0xCC0E1116);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(density);
        strokePaint.setColor(STROKE);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(1.8f * density);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        iconPaint.setStrokeJoin(Paint.Join.ROUND);
        iconPaint.setColor(TEXT);
        setClickable(true);
        setContentDescription(collapse ? "Achicar el gráfico" : "Agrandar el gráfico");
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float inset = density / 2;
        pill.set(inset, inset, w - inset, h - inset);
        float radius = h / 2;
        canvas.drawRoundRect(pill, radius, radius, fillPaint);
        canvas.drawRoundRect(pill, radius, radius, strokePaint);

        // Un cuadrado de 12 dp; cada esquina es una L de 4 dp.
        float cx = w / 2;
        float cy = h / 2;
        float half = 6 * density;
        float arm = 4 * density;
        corners.reset();
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                float x = cx + sx * half;
                float y = cy + sy * half;
                // Agrandar: la L abraza la esquina de afuera; achicar: la de adentro.
                float dir = collapse ? 1 : -1;
                corners.moveTo(x + dir * sx * arm, y);
                corners.lineTo(x, y);
                corners.lineTo(x, y + dir * sy * arm);
            }
        }
        canvas.drawPath(corners, iconPaint);
    }
}
