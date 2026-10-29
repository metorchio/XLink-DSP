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
import android.view.MotionEvent;
import android.view.View;

import ar.mtorchio.xlinkdsp.dsp.Commands;

/**
 * Entrada de una salida (xair Routing) como una sola pill: un parlante a cada lado,
 * apuntando hacia el centro y un poco hacia abajo, y en el centro L, R o LR (L+R). Se encienden los parlantes de la entrada elegida; tocar el
 * de la izquierda elige L, el de la derecha R y el centro L+R.
 */
final class RoutingView extends View {

    interface Listener {
        void onRouteChanged(int route);
    }

    private static final int STROKE = 0xFF262C3A;
    private static final int MUTED = 0xFF8A93A6;
    private static final int ACCENT = 0xFFFF8A1F;
    private static final float TILT_DEGREES = 18f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint speakerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path speaker = new Path();
    private final RectF pill = new RectF();
    private final RectF arc = new RectF();
    private final float density;

    private int route = -1;
    private Listener listener;

    RoutingView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        fillPaint.setColor(ACCENT);
        fillPaint.setAlpha(0x2E);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(density);
        strokePaint.setColor(STROKE);
        wavePaint.setStyle(Paint.Style.STROKE);
        wavePaint.setStrokeWidth(1.6f * density);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);
        wavePaint.setColor(ACCENT);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(14 * density);
        labelPaint.setFakeBoldText(true);
        setClickable(true);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Commands.ROUTE_L, ROUTE_R o ROUTE_LR; otro valor no enciende nada. */
    void setRoute(int route) {
        this.route = route;
        setContentDescription("Entrada " + label());
        invalidate();
    }

    private boolean leftOn() {
        return route == Commands.ROUTE_L || route == Commands.ROUTE_LR;
    }

    private boolean rightOn() {
        return route == Commands.ROUTE_R || route == Commands.ROUTE_LR;
    }

    private String label() {
        if (route == Commands.ROUTE_L) {
            return "L";
        }
        if (route == Commands.ROUTE_R) {
            return "R";
        }
        return route == Commands.ROUTE_LR ? "LR" : "·";
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float inset = density / 2;
        pill.set(inset, inset, w - inset, h - inset);
        float radius = h / 2;

        // Fondo naranja tenue bajo el lado (o los dos) que suena.
        if (leftOn() || rightOn()) {
            canvas.save();
            canvas.clipRect(leftOn() ? 0 : w / 2, 0, rightOn() ? w : w / 2, h);
            canvas.drawRoundRect(pill, radius, radius, fillPaint);
            canvas.restore();
        }
        strokePaint.setColor(route == -1 ? STROKE : blend(STROKE, ACCENT, 0.45f));
        canvas.drawRoundRect(pill, radius, radius, strokePaint);

        float cy = h / 2;
        float edge = 13 * density;
        drawSpeaker(canvas, edge, cy, 1, leftOn());
        drawSpeaker(canvas, w - edge, cy, -1, rightOn());

        labelPaint.setColor(route == -1 ? MUTED : ACCENT);
        Paint.FontMetrics metrics = labelPaint.getFontMetrics();
        canvas.drawText(label(), w / 2, cy - (metrics.ascent + metrics.descent) / 2, labelPaint);
    }

    /** Parlante con el cono hacia adentro (dir 1 = mira a la derecha), inclinado hacia abajo; encendido lleva ondas. */
    private void drawSpeaker(Canvas canvas, float cx, float cy, int dir, boolean on) {
        float s = 1.1f * density;
        canvas.save();
        canvas.scale(dir, 1, cx, cy);
        canvas.rotate(TILT_DEGREES, cx, cy);
        speaker.reset();
        speaker.addRect(cx - 5 * s, cy - 3 * s, cx - 2 * s, cy + 3 * s, Path.Direction.CW);
        speaker.moveTo(cx - 2 * s, cy - 3 * s);
        speaker.lineTo(cx + 3 * s, cy - 7 * s);
        speaker.lineTo(cx + 3 * s, cy + 7 * s);
        speaker.lineTo(cx - 2 * s, cy + 3 * s);
        speaker.close();
        speakerPaint.setColor(on ? ACCENT : MUTED);
        speakerPaint.setAlpha(on ? 0xFF : 0x66);
        canvas.drawPath(speaker, speakerPaint);
        if (on) {
            for (float r : new float[]{4 * s, 7 * s}) {
                arc.set(cx + 3 * s - r, cy - r, cx + 3 * s + r, cy + r);
                canvas.drawArc(arc, -40, 80, false, wavePaint);
            }
        }
        canvas.restore();
    }

    private static int blend(int from, int to, float t) {
        int r = Math.round(((from >> 16) & 0xFF) * (1 - t) + ((to >> 16) & 0xFF) * t);
        int g = Math.round(((from >> 8) & 0xFF) * (1 - t) + ((to >> 8) & 0xFF) * t);
        int b = Math.round((from & 0xFF) * (1 - t) + (to & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_UP) {
            float third = getWidth() / 3f;
            int picked = event.getX() < third ? Commands.ROUTE_L
                    : event.getX() > 2 * third ? Commands.ROUTE_R : Commands.ROUTE_LR;
            if (picked != route) {
                setRoute(picked);
                if (listener != null) {
                    listener.onRouteChanged(picked);
                }
            }
        }
        return super.onTouchEvent(event);
    }
}
