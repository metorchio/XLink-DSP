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
import android.view.View;

/** Símbolo de Bluetooth del estado de conexión: azul conectado, gris sin conexión. */
final class BluetoothView extends View {

    private static final int ONLINE = 0xFF2D8CFF;
    private static final int OFFLINE = 0xFF5A6275;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path rune = new Path();
    private boolean connected;

    BluetoothView(Context context) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setContentDescription("Sin conexión");
    }

    void setConnected(boolean connected) {
        if (this.connected == connected) {
            return;
        }
        this.connected = connected;
        setContentDescription(connected ? "Conectado por Bluetooth" : "Sin conexión");
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float tall = getHeight() * 0.42f;
        float wing = tall * 0.5f;
        // La runa: dos triángulos a la derecha del palo vertical y las dos patas a la izquierda.
        rune.reset();
        rune.moveTo(cx - wing, cy - tall / 2);
        rune.lineTo(cx + wing, cy + tall / 2);
        rune.lineTo(cx, cy + tall);
        rune.lineTo(cx, cy - tall);
        rune.lineTo(cx + wing, cy - tall / 2);
        rune.lineTo(cx - wing, cy + tall / 2);
        paint.setColor(connected ? ONLINE : OFFLINE);
        canvas.drawPath(rune, paint);
    }
}
