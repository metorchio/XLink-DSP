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

/**
 * Botón de grupos del encabezado: dos eslabones de cadena en una pill. Prendido
 * los eslabones van enganchados y en color; apagado, separados y en gris.
 */
final class ChainView extends View {

    private static final int STROKE = 0xFF262C3A;
    private static final int MUTED = 0xFF8A93A6;
    private static final int ACCENT = 0xFF00E5FF;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();
    private final RectF link = new RectF();
    private final float density;

    private boolean on;

    ChainView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        fillPaint.setColor(ACCENT);
        fillPaint.setAlpha(0x22);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(density);
        linkPaint.setStyle(Paint.Style.STROKE);
        linkPaint.setStrokeWidth(2 * density);
        setClickable(true);
        setLongClickable(true);
    }

    void setOn(boolean on) {
        this.on = on;
        setContentDescription(on ? "Grupos prendidos" : "Grupos apagados");
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float inset = density / 2;
        pill.set(inset, inset, w - inset, h - inset);
        float radius = h / 2;
        if (on) {
            canvas.drawRoundRect(pill, radius, radius, fillPaint);
        }
        strokePaint.setColor(on ? ACCENT : STROKE);
        canvas.drawRoundRect(pill, radius, radius, strokePaint);

        // Dos eslabones a 45°: enganchados si está prendido, con aire si no.
        float cx = w / 2;
        float cy = h / 2;
        float linkW = 11 * density;
        float linkH = 6 * density;
        float offset = (on ? 3.5f : 5.5f) * density;
        linkPaint.setColor(on ? ACCENT : MUTED);
        canvas.save();
        canvas.rotate(-45, cx, cy);
        link.set(cx - offset - linkW / 2, cy - linkH / 2, cx - offset + linkW / 2, cy + linkH / 2);
        canvas.drawRoundRect(link, linkH / 2, linkH / 2, linkPaint);
        link.offset(2 * offset, 0);
        canvas.drawRoundRect(link, linkH / 2, linkH / 2, linkPaint);
        canvas.restore();
    }
}
