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
import android.view.MotionEvent;
import android.view.View;

import ar.mtorchio.xlinkdsp.dsp.Config;

/**
 * Mapa de frecuencias del crossover: la respuesta HPF x LPF de cada canal
 * superpuesta (20 Hz..20 kHz log, +6..-36 dB). El canal elegido va resaltado y
 * sus dos cortes se arrastran horizontalmente.
 */
final class CrossoverView extends View {

    interface Listener {
        /** hpf = true si se movió el corte de graves. done = soltó el dedo. */
        void onCutoffChanged(int channel, boolean hpf, float frequency, boolean done);
    }

    static final int[] CHANNEL_COLORS = {
            0xFF00E5FF, 0xFF4DD0E1, 0xFF7C4DFF, 0xFFB388FF, 0xFFFF8A1F, 0xFFFF3D7F
    };

    private static final float MIN_HZ = 20f;
    private static final float MAX_HZ = 20000f;
    private static final float TOP_DB = 6f;
    private static final float BOTTOM_DB = -36f;
    private static final int POINTS = 160;
    private static final float[] GRID_HZ = {50, 100, 200, 500, 1000, 2000, 5000, 10000};
    private static final String[] GRID_LABELS = {"50", "100", "200", "500", "1k", "2k", "5k", "10k"};

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private Config config;
    private int selected;
    private int linked = -1;
    private Listener listener;
    private boolean draggingHpf;
    private boolean dragging;
    private float left, right, top, bottom;

    CrossoverView(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        gridPaint.setColor(0xFF262C3A);
        gridPaint.setStrokeWidth(density);
        labelPaint.setColor(0xFF8A93A6);
        labelPaint.setTextSize(10 * density);
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeJoin(Paint.Join.ROUND);
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        handlePaint.setStyle(Paint.Style.FILL);
    }

    void setConfig(Config config) {
        this.config = config;
        invalidate();
    }

    /** linked = canal que se mueve junto con el elegido (par L/R), o -1. */
    void setSelected(int channel, int linked) {
        this.selected = channel;
        this.linked = linked;
        invalidate();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    private int channelCount() {
        return config == null ? 0 : Math.min(Math.min(config.hpfFreq.length, config.hpfSlope.length),
                Math.min(config.lpfFreq.length, config.lpfSlope.length));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        left = 30 * density;
        right = getWidth() - 6 * density;
        top = 6 * density;
        bottom = getHeight() - 16 * density;

        for (int db = 0; db >= -30; db -= 10) {
            float y = yFor(db);
            canvas.drawLine(left, y, right, y, gridPaint);
            canvas.drawText(String.valueOf(db), 0, y + 4 * density, labelPaint);
        }
        for (int i = 0; i < GRID_HZ.length; i++) {
            float x = xFor(GRID_HZ[i]);
            canvas.drawLine(x, top, x, bottom, gridPaint);
            float width = labelPaint.measureText(GRID_LABELS[i]);
            canvas.drawText(GRID_LABELS[i], x - width / 2, getHeight() - 2 * density, labelPaint);
        }

        int count = channelCount();
        // Primero los demás canales, finitos; el elegido arriba de todo.
        for (int ch = 0; ch < count; ch++) {
            if (ch != selected) {
                drawChannel(canvas, ch, false, density);
            }
        }
        if (selected < count) {
            drawChannel(canvas, selected, true, density);
        }
    }

    private void drawChannel(Canvas canvas, int ch, boolean highlighted, float density) {
        int color = CHANNEL_COLORS[ch % CHANNEL_COLORS.length];
        path.reset();
        for (int i = 0; i <= POINTS; i++) {
            float hz = (float) (MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, (double) i / POINTS));
            float y = yFor((float) Math.max(BOTTOM_DB, responseDb(ch, hz)));
            if (i == 0) {
                path.moveTo(xFor(hz), y);
            } else {
                path.lineTo(xFor(hz), y);
            }
        }
        if (highlighted) {
            Path fill = new Path(path);
            fill.lineTo(right, bottom);
            fill.lineTo(left, bottom);
            fill.close();
            fillPaint.setColor((color & 0x00FFFFFF) | 0x2A000000);
            canvas.drawPath(fill, fillPaint);
        }
        curvePaint.setColor(highlighted || ch == linked ? color : (color & 0x00FFFFFF) | 0x66000000);
        curvePaint.setStrokeWidth((highlighted ? 3f : 1.5f) * density);
        canvas.drawPath(path, curvePaint);

        if (highlighted) {
            drawHandle(canvas, config.hpfSlope[ch], config.hpfFreq[ch], color, density);
            drawHandle(canvas, config.lpfSlope[ch], config.lpfFreq[ch], color, density);
        }
    }

    private void drawHandle(Canvas canvas, String slope, float frequency, int color, float density) {
        if ("OFF".equals(slope)) {
            return;
        }
        float x = xFor(frequency);
        float y = yFor(-3);
        handlePaint.setColor(0xFF0E1116);
        canvas.drawCircle(x, y, 9 * density, handlePaint);
        handlePaint.setColor(color);
        canvas.drawCircle(x, y, 7 * density, handlePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (listener == null || selected >= channelCount()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                boolean hpfOn = !"OFF".equals(config.hpfSlope[selected]);
                boolean lpfOn = !"OFF".equals(config.lpfSlope[selected]);
                if (!hpfOn && !lpfOn) {
                    return false;
                }
                float dHpf = hpfOn ? Math.abs(xFor(config.hpfFreq[selected]) - event.getX()) : Float.MAX_VALUE;
                float dLpf = lpfOn ? Math.abs(xFor(config.lpfFreq[selected]) - event.getX()) : Float.MAX_VALUE;
                draggingHpf = dHpf <= dLpf;
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float hz = EqCurveView.roundHz(hzFor(event.getX()));
                // El HPF no puede pasar al LPF ni al revés.
                if (draggingHpf && !"OFF".equals(config.lpfSlope[selected])) {
                    hz = Math.min(hz, config.lpfFreq[selected]);
                } else if (!draggingHpf && !"OFF".equals(config.hpfSlope[selected])) {
                    hz = Math.max(hz, config.hpfFreq[selected]);
                }
                int current = draggingHpf ? config.hpfFreq[selected] : config.lpfFreq[selected];
                if (Math.round(hz) != current) {
                    dragging = true;
                    listener.onCutoffChanged(selected, draggingHpf, hz, false);
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    int current = draggingHpf ? config.hpfFreq[selected] : config.lpfFreq[selected];
                    listener.onCutoffChanged(selected, draggingHpf, current, true);
                }
                dragging = false;
                return true;
            default:
                return false;
        }
    }

    /** Respuesta (dB) del canal: HPF x LPF. */
    private double responseDb(int ch, float hz) {
        return filterDb(config.hpfSlope[ch], config.hpfFreq[ch], hz, true)
                + filterDb(config.lpfSlope[ch], config.lpfFreq[ch], hz, false);
    }

    /**
     * Magnitud aproximada de cada familia: Butterworth de orden n, Linkwitz-Riley
     * (dos Butterworth en cascada) y Bessel de 2º / 4º orden.
     */
    static double filterDb(String slope, float fc, float hz, boolean highPass) {
        if (slope == null || "OFF".equals(slope) || fc <= 0) {
            return 0;
        }
        int db;
        try {
            db = Integer.parseInt(slope.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
        int order = Math.max(1, db / 6);
        double w = highPass ? fc / hz : hz / fc;
        if (slope.endsWith("LKR")) {
            // LR de orden n = dos Butterworth de orden n/2: |H| = 1 / (1 + w^n).
            return -20 * Math.log10(1 + Math.pow(w, order));
        }
        if (slope.endsWith("BES")) {
            return besselDb(order, w);
        }
        return -10 * Math.log10(1 + Math.pow(w, 2 * order));
    }

    private static double besselDb(int order, double w) {
        // Polinomios de Bessel (normalizados en retardo): 2º = s²+3s+3, 4º = s⁴+10s³+45s²+105s+105.
        double re, im, dc;
        if (order <= 2) {
            re = 3 - w * w;
            im = 3 * w;
            dc = 3;
        } else {
            double w2 = w * w;
            re = w2 * w2 - 45 * w2 + 105;
            im = -10 * w2 * w + 105 * w;
            dc = 105;
        }
        return 20 * Math.log10(dc / Math.hypot(re, im));
    }

    private float xFor(float hz) {
        double t = Math.log(Math.max(MIN_HZ, Math.min(MAX_HZ, hz)) / MIN_HZ) / Math.log(MAX_HZ / MIN_HZ);
        return (float) (left + t * (right - left));
    }

    private float hzFor(float x) {
        double t = Math.max(0, Math.min(1, (x - left) / (right - left)));
        return (float) (MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, t));
    }

    private float yFor(float db) {
        return top + (TOP_DB - db) / (TOP_DB - BOTTOM_DB) * (bottom - top);
    }
}
