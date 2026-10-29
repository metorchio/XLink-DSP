/*
 * XLink DSP
 * Copyright (C) 2026 Maximiliano Torchio
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package ar.mtorchio.xlinkdsp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

import ar.mtorchio.xlinkdsp.dsp.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * Curva de respuesta del EQ (20 Hz..20 kHz log, ±18 dB), sumando un filtro
 * peaking RBJ por banda, con los puntos de cada banda encima. Los puntos se
 * arrastran: vertical = ganancia, horizontal = frecuencia (solo EQ paramétrico;
 * en el gráfico de entrada las frecuencias son fijas). En el paramétrico se ve
 * además la campana de cada banda, y la elegida muestra su ancho (Q) con dos
 * manijas que se estiran para cambiarlo. Con un "fondo" (el EQ de entrada) se
 * dibuja ese EQ en gris y la suma de los dos punteada en ámbar: lo que suena en el canal.
 */
final class EqCurveView extends View {

    interface Listener {
        void onBandSelected(int band);

        /** done = false mientras se arrastra, true al soltar. */
        void onBandChanged(int band, boolean done);

        /** Se estiró el ancho de la banda (su Q); done = soltó el dedo. */
        void onBandQChanged(int band, boolean done);
    }

    static final float MIN_Q = 0.6f;
    static final float MAX_Q = 9.9f;

    private static final float MIN_HZ = 20f;
    private static final float MAX_HZ = 20000f;
    private static final float RANGE_DB = 18f;
    private static final double SAMPLE_RATE = 48000;
    private static final int POINTS = 200;
    private static final float[] GRID_HZ = {50, 100, 200, 500, 1000, 2000, 5000, 10000};
    private static final String[] GRID_LABELS = {"50", "100", "200", "500", "1k", "2k", "5k", "10k"};

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zeroPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bellFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint widthPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint qLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curve = new Path();
    private final Path fill = new Path();
    private final Path bell = new Path();
    private final Paint underlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint totalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path underlayCurve = new Path();
    private final Path totalCurve = new Path();
    private List<Config.Band> underlay;

    private List<Config.Band> bands = new ArrayList<>();
    private int highlightedBand = -1;
    private boolean graphic;
    private float maxGainDb = RANGE_DB;
    private Listener listener;
    private int draggingBand = -1;
    private boolean draggingQ;
    private boolean dragged;
    private float left, right, top, bottom;

    EqCurveView(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        gridPaint.setColor(0xFF262C3A);
        gridPaint.setStrokeWidth(density);
        zeroPaint.setColor(0xFF3A4256);
        zeroPaint.setStrokeWidth(density * 1.5f);
        labelPaint.setColor(0xFF8A93A6);
        labelPaint.setTextSize(10 * density);
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeWidth(3 * density);
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        curvePaint.setStrokeJoin(Paint.Join.ROUND);
        fillPaint.setStyle(Paint.Style.FILL);
        pointPaint.setStyle(Paint.Style.FILL);
        bellPaint.setStyle(Paint.Style.STROKE);
        bellPaint.setStrokeWidth(1.2f * density);
        bellFillPaint.setStyle(Paint.Style.FILL);
        bellFillPaint.setColor(0x1AFFFFFF);
        widthPaint.setStrokeWidth(2 * density);
        widthPaint.setStrokeCap(Paint.Cap.ROUND);
        widthPaint.setColor(0xCCFFFFFF);
        qLabelPaint.setColor(0xFFE8ECF4);
        qLabelPaint.setTextSize(11 * density);
        qLabelPaint.setFakeBoldText(true);
        qLabelPaint.setTextAlign(Paint.Align.LEFT);
        underlayPaint.setStyle(Paint.Style.STROKE);
        underlayPaint.setStrokeWidth(2 * density);
        underlayPaint.setColor(0xB38A93A6);
        totalPaint.setStyle(Paint.Style.STROKE);
        totalPaint.setStrokeWidth(2 * density);
        totalPaint.setStrokeCap(Paint.Cap.ROUND);
        totalPaint.setColor(0xFFFFC857);
        totalPaint.setPathEffect(new DashPathEffect(new float[]{6 * density, 4 * density}, 0));
        legendPaint.setTextSize(10 * density);
        legendPaint.setFakeBoldText(true);
    }

    /** EQ de fondo (el de entrada) para ver la suma con este; null lo saca. */
    void setUnderlay(List<Config.Band> underlay) {
        this.underlay = underlay;
        invalidate();
    }

    /** graphic = EQ de entrada (frecuencias fijas); maxGainDb = rango de ganancia editable. */
    void setBands(List<Config.Band> bands, boolean graphic, float maxGainDb) {
        this.bands = bands != null ? bands : new ArrayList<>();
        this.graphic = graphic;
        this.maxGainDb = maxGainDb;
        highlightedBand = -1;
        invalidate();
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setHighlightedBand(int band) {
        highlightedBand = band;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bands.isEmpty() || listener == null) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                draggingQ = !graphic && nearQHandle(event.getX(), event.getY());
                if (draggingQ) {
                    draggingBand = highlightedBand;
                    dragged = false;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
                draggingBand = nearestBand(event.getX(), event.getY());
                if (draggingBand < 0) {
                    return false;
                }
                dragged = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                setHighlightedBand(draggingBand);
                listener.onBandSelected(draggingBand);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (draggingBand >= 0 && draggingQ) {
                    Config.Band band = bands.get(draggingBand);
                    // Distancia a la manija = medio ancho de banda, en octavas.
                    double octaves = 2 * Math.abs(Math.log(hzFor(event.getX()) / band.frequency) / Math.log(2));
                    float q = Math.round(Math.max(MIN_Q, Math.min(MAX_Q, qFor(Math.max(0.05, octaves)))) * 10) / 10f;
                    if (q != band.q) {
                        band.q = q;
                        dragged = true;
                        invalidate();
                        listener.onBandQChanged(draggingBand, false);
                    }
                } else if (draggingBand >= 0) {
                    Config.Band band = bands.get(draggingBand);
                    float gain = Math.round(dbFor(event.getY()));
                    gain = Math.max(-maxGainDb, Math.min(maxGainDb, gain));
                    float frequency = graphic ? band.frequency : roundHz(hzFor(event.getX()));
                    if (gain != band.gainDb || frequency != band.frequency) {
                        band.gainDb = gain;
                        band.frequency = frequency;
                        dragged = true;
                        invalidate();
                        listener.onBandChanged(draggingBand, false);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (draggingBand >= 0 && dragged) {
                    if (draggingQ) {
                        listener.onBandQChanged(draggingBand, true);
                    } else {
                        listener.onBandChanged(draggingBand, true);
                    }
                }
                draggingBand = -1;
                draggingQ = false;
                return true;
            default:
                return false;
        }
    }

    /** Banda más cercana al toque: en el gráfico solo cuenta X (28 puntos pegados). */
    private int nearestBand(float x, float y) {
        float density = getResources().getDisplayMetrics().density;
        int best = -1;
        float bestDistance = graphic ? Float.MAX_VALUE : 36 * density;
        for (int i = 0; i < bands.size(); i++) {
            Config.Band band = bands.get(i);
            float dx = xFor(band.frequency, left, right) - x;
            float dy = graphic ? 0 : yFor(Math.max(-RANGE_DB, Math.min(RANGE_DB, band.gainDb)), top, bottom) - y;
            float distance = (float) Math.hypot(dx, dy);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /** Ancho de banda (octavas) de un peaking con esa Q, y al revés. */
    private static double octavesFor(float q) {
        return 2 / Math.log(2) * asinh(1 / (2.0 * q));
    }

    private static float qFor(double octaves) {
        return (float) (1 / (2 * Math.sinh(Math.log(2) / 2 * octaves)));
    }

    private static double asinh(double x) {
        return Math.log(x + Math.sqrt(x * x + 1));
    }

    /** Las manijas del ancho de la banda elegida: sus bordes, a media ganancia. */
    private float[] qHandles() {
        if (highlightedBand < 0 || highlightedBand >= bands.size()) {
            return null;
        }
        Config.Band band = bands.get(highlightedBand);
        double half = octavesFor(band.q) / 2;
        float lowX = xFor((float) (band.frequency / Math.pow(2, half)), left, right);
        float highX = xFor((float) (band.frequency * Math.pow(2, half)), left, right);
        float y = yFor(Math.max(-RANGE_DB, Math.min(RANGE_DB, band.gainDb / 2)), top, bottom);
        return new float[]{lowX, highX, y};
    }

    private boolean nearQHandle(float x, float y) {
        float[] handles = qHandles();
        if (handles == null) {
            return false;
        }
        // Con poca ganancia las manijas quedan pegadas al punto: gana el que esté más cerca.
        Config.Band band = bands.get(highlightedBand);
        double toPoint = Math.hypot(xFor(band.frequency, left, right) - x,
                yFor(Math.max(-RANGE_DB, Math.min(RANGE_DB, band.gainDb)), top, bottom) - y);
        double toHandle = Math.min(Math.hypot(handles[0] - x, handles[2] - y), Math.hypot(handles[1] - x, handles[2] - y));
        float reach = 20 * getResources().getDisplayMetrics().density;
        return toHandle < reach && toHandle < toPoint;
    }

    private float hzFor(float x) {
        double t = Math.max(0, Math.min(1, (x - left) / (right - left)));
        return (float) (MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, t));
    }

    private float dbFor(float y) {
        return RANGE_DB - (y - top) / (bottom - top) * 2 * RANGE_DB;
    }

    /** Redondeo "humano" de frecuencia para que el arrastre no deje 1037 Hz. */
    static float roundHz(float hz) {
        float step = hz < 200 ? 1 : hz < 1000 ? 5 : hz < 5000 ? 10 : 50;
        return Math.max(MIN_HZ, Math.min(MAX_HZ, Math.round(hz / step) * step));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        curvePaint.setShader(new LinearGradient(0, 0, w, 0, 0xFF00E5FF, 0xFF7C4DFF, Shader.TileMode.CLAMP));
        fillPaint.setShader(new LinearGradient(0, 0, 0, h, 0x3300E5FF, 0x337C4DFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float density = getResources().getDisplayMetrics().density;
        left = 26 * density;
        bottom = getHeight() - 16 * density;
        top = 6 * density;
        right = getWidth() - 6 * density;

        // Grilla
        for (int db = -12; db <= 12; db += 6) {
            float y = yFor(db, top, bottom);
            canvas.drawLine(left, y, right, y, db == 0 ? zeroPaint : gridPaint);
            canvas.drawText((db > 0 ? "+" : "") + db, 0, y + 4 * density, labelPaint);
        }
        for (int i = 0; i < GRID_HZ.length; i++) {
            float x = xFor(GRID_HZ[i], left, right);
            canvas.drawLine(x, top, x, bottom, gridPaint);
            float width = labelPaint.measureText(GRID_LABELS[i]);
            canvas.drawText(GRID_LABELS[i], x - width / 2, getHeight() - 2 * density, labelPaint);
        }

        if (bands.isEmpty()) {
            return;
        }

        // Curva = suma de los filtros peaking de todas las bandas
        curve.reset();
        fill.reset();
        float zeroY = yFor(0, top, bottom);
        for (int i = 0; i <= POINTS; i++) {
            double hz = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, (double) i / POINTS);
            double db = 0;
            for (Config.Band band : bands) {
                db += peakingDb(hz, band);
            }
            float x = xFor((float) hz, left, right);
            float y = yFor((float) Math.max(-RANGE_DB, Math.min(RANGE_DB, db)), top, bottom);
            if (i == 0) {
                curve.moveTo(x, y);
                fill.moveTo(x, zeroY);
            }
            curve.lineTo(x, y);
            fill.lineTo(x, y);
        }
        fill.lineTo(right, zeroY);
        fill.close();
        canvas.drawPath(fill, fillPaint);
        if (!graphic) {
            drawBells(canvas, zeroY, density);
        }
        canvas.drawPath(curve, curvePaint);
        if (underlay != null && !underlay.isEmpty()) {
            drawUnderlay(canvas, density);
        }
        if (!graphic) {
            drawQWidth(canvas, density);
        }

        // Puntos de las bandas
        for (int i = 0; i < bands.size(); i++) {
            Config.Band band = bands.get(i);
            if (band.frequency < MIN_HZ || band.frequency > MAX_HZ) {
                continue;
            }
            float x = xFor(band.frequency, left, right);
            float y = yFor(Math.max(-RANGE_DB, Math.min(RANGE_DB, band.gainDb)), top, bottom);
            boolean highlighted = i == highlightedBand;
            float radius = (highlighted ? 7 : bands.size() > 12 ? 3 : 5) * density;
            pointPaint.setColor(0xFF0E1116);
            canvas.drawCircle(x, y, radius + 2 * density, pointPaint);
            pointPaint.setColor(highlighted ? 0xFFFFFFFF : 0xFFFF8A1F);
            canvas.drawCircle(x, y, radius, pointPaint);
        }
    }

    /** El EQ de fondo atenuado, la suma con este punteada y una leyenda arriba. */
    private void drawUnderlay(Canvas canvas, float density) {
        underlayCurve.reset();
        totalCurve.reset();
        for (int i = 0; i <= POINTS; i++) {
            double hz = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, (double) i / POINTS);
            double below = 0;
            for (Config.Band band : underlay) {
                below += peakingDb(hz, band);
            }
            double own = 0;
            for (Config.Band band : bands) {
                own += peakingDb(hz, band);
            }
            float x = xFor((float) hz, left, right);
            float yBelow = yFor((float) Math.max(-RANGE_DB, Math.min(RANGE_DB, below)), top, bottom);
            float yTotal = yFor((float) Math.max(-RANGE_DB, Math.min(RANGE_DB, below + own)), top, bottom);
            if (i == 0) {
                underlayCurve.moveTo(x, yBelow);
                totalCurve.moveTo(x, yTotal);
            } else {
                underlayCurve.lineTo(x, yBelow);
                totalCurve.lineTo(x, yTotal);
            }
        }
        canvas.drawPath(underlayCurve, underlayPaint);
        canvas.drawPath(totalCurve, totalPaint);

        float y = top + 10 * density;
        float x = right - 4 * density;
        String total = "IN + canal";
        legendPaint.setColor(0xFFFFC857);
        x -= legendPaint.measureText(total);
        canvas.drawText(total, x, y, legendPaint);
        canvas.drawLine(x - 18 * density, y - 3 * density, x - 6 * density, y - 3 * density, totalPaint);
        String in = "IN";
        legendPaint.setColor(0xFF8A93A6);
        x -= 30 * density + legendPaint.measureText(in);
        canvas.drawText(in, x, y, legendPaint);
        canvas.drawLine(x - 18 * density, y - 3 * density, x - 6 * density, y - 3 * density, underlayPaint);
    }

    /** La campana de cada banda por separado; la elegida, rellena. */
    private void drawBells(Canvas canvas, float zeroY, float density) {
        for (int b = 0; b < bands.size(); b++) {
            Config.Band band = bands.get(b);
            if (band.gainDb == 0) {
                continue;
            }
            bell.reset();
            for (int i = 0; i <= POINTS; i++) {
                double hz = MIN_HZ * Math.pow(MAX_HZ / MIN_HZ, (double) i / POINTS);
                float x = xFor((float) hz, left, right);
                float y = yFor((float) Math.max(-RANGE_DB, Math.min(RANGE_DB, peakingDb(hz, band))), top, bottom);
                if (i == 0) {
                    bell.moveTo(x, zeroY);
                }
                bell.lineTo(x, y);
            }
            bell.lineTo(right, zeroY);
            boolean highlighted = b == highlightedBand;
            if (highlighted) {
                canvas.drawPath(bell, bellFillPaint);
            }
            bellPaint.setColor(highlighted ? 0x99FFFFFF : 0x40FFFFFF);
            canvas.drawPath(bell, bellPaint);
        }
    }

    /** Ancho de la banda elegida: una barra entre sus bordes con manijas y la Q. */
    private void drawQWidth(Canvas canvas, float density) {
        float[] handles = qHandles();
        if (handles == null) {
            return;
        }
        Config.Band band = bands.get(highlightedBand);
        canvas.drawLine(handles[0], handles[2], handles[1], handles[2], widthPaint);
        for (int i = 0; i < 2; i++) {
            pointPaint.setColor(0xFF0E1116);
            canvas.drawCircle(handles[i], handles[2], 6 * density, pointPaint);
            pointPaint.setColor(0xFFFFFFFF);
            canvas.drawCircle(handles[i], handles[2], 4 * density, pointPaint);
        }
        // La Q va al costado de la manija derecha (a la izquierda si no entra).
        String label = String.format(java.util.Locale.ROOT, "Q %.1f", band.q);
        float labelX = handles[1] + 10 * density;
        if (labelX + qLabelPaint.measureText(label) > right) {
            labelX = handles[0] - 10 * density - qLabelPaint.measureText(label);
        }
        canvas.drawText(label, labelX, handles[2] + 4 * density, qLabelPaint);
    }

    private static float xFor(float hz, float left, float right) {
        double t = Math.log(hz / MIN_HZ) / Math.log(MAX_HZ / MIN_HZ);
        return (float) (left + t * (right - left));
    }

    private static float yFor(float db, float top, float bottom) {
        return top + (RANGE_DB - db) / (2 * RANGE_DB) * (bottom - top);
    }

    /** Magnitud (dB) de un filtro peaking RBJ en la frecuencia hz. */
    private static double peakingDb(double hz, Config.Band band) {
        if (band.gainDb == 0 || band.frequency <= 0 || band.q <= 0 || band.frequency >= SAMPLE_RATE / 2) {
            return 0;
        }
        double a = Math.pow(10, band.gainDb / 40.0);
        double w0 = 2 * Math.PI * band.frequency / SAMPLE_RATE;
        double alpha = Math.sin(w0) / (2 * band.q);
        double cosW0 = Math.cos(w0);
        double b0 = 1 + alpha * a, b1 = -2 * cosW0, b2 = 1 - alpha * a;
        double a0 = 1 + alpha / a, a1 = -2 * cosW0, a2 = 1 - alpha / a;

        double w = 2 * Math.PI * hz / SAMPLE_RATE;
        double cos1 = Math.cos(w), sin1 = Math.sin(w), cos2 = Math.cos(2 * w), sin2 = Math.sin(2 * w);
        double numRe = b0 + b1 * cos1 + b2 * cos2, numIm = -(b1 * sin1 + b2 * sin2);
        double denRe = a0 + a1 * cos1 + a2 * cos2, denIm = -(a1 * sin1 + a2 * sin2);
        double magnitude2 = (numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm);
        return 10 * Math.log10(magnitude2);
    }
}
