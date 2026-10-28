/*
 * XLink DSP
 * Copyright (C) 2026 Maximiliano Torchio
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package ar.mtorchio.xlinkdsp;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.RemoteViews;

import ar.mtorchio.xlinkdsp.dsp.BleTransport;
import ar.mtorchio.xlinkdsp.dsp.Command;
import ar.mtorchio.xlinkdsp.dsp.Commands;
import ar.mtorchio.xlinkdsp.dsp.Config;
import ar.mtorchio.xlinkdsp.dsp.Session;

import java.util.ArrayList;
import java.util.List;

/**
 * Widget chico de la pantalla de inicio: elegir el EQ de entrada entre el Custom
 * (lo que el usuario dejó grabado) y los presets de EqPresets. Si la app está
 * conectada lo aplica por esa conexión; si no, abre una Session propia con el
 * último DSP y la password guardada, manda los comandos y se desconecta.
 */
public class EqWidget extends AppWidgetProvider {

    static final String PREF_WIDGET_PRESET = "widget_preset";
    static final String PREF_CUSTOM_IN = "custom_in";

    private static final String ACTION_PRESET = "ar.mtorchio.xlinkdsp.EQ_PRESET";
    private static final String EXTRA_PRESET = "preset";
    // Pills: Custom (-1) + presets 0..6 de EqPresets.
    private static final int[] PILL_IDS = {
            R.id.pill_custom, R.id.pill_0, R.id.pill_1, R.id.pill_2,
            R.id.pill_3, R.id.pill_4, R.id.pill_5, R.id.pill_6
    };

    // Conexión + login, más un "OK" (o su timeout) por comando.
    private static final long SEND_TIMEOUT_MS = 9000;

    // Estado efímero que muestra el widget ("Aplicando…", errores).
    private static String statusText;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] widgetIds) {
        refresh(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (!ACTION_PRESET.equals(intent.getAction())) {
            return;
        }
        int preset = intent.getIntExtra(EXTRA_PRESET, -1);
        String name = preset < 0 ? "Custom" : EqPresets.SHORT_NAMES[preset];

        // App abierta y conectada: que lo aplique ella (actualiza también la pantalla).
        if (XLinkActivity.applyInputPresetFromWidget(preset)) {
            setStatus(context, null);
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(XLinkActivity.PREFS, Context.MODE_PRIVATE);
        String address = prefs.getString(XLinkActivity.PREF_LAST_ADDRESS, null);
        String password = prefs.getString(XLinkActivity.PREF_PASSWORD, null);
        if (address == null || password == null) {
            setStatus(context, "Conectate una vez desde la app");
            return;
        }
        float[] gains = inputGains(prefs, preset);
        if (gains == null) {
            setStatus(context, "Sin Custom guardado: abrí la app");
            return;
        }
        List<Command> commands = new ArrayList<>();
        for (int i = 0; i < gains.length; i++) {
            commands.add(Commands.inputEqGain(i, gains[i]));
        }

        setStatus(context, "Aplicando " + name + "…");
        PendingResult pending = goAsync();
        sendOnce(context, address, password, commands, error -> {
            if (error == null) {
                prefs.edit().putInt(PREF_WIDGET_PRESET, preset).apply();
            }
            setStatus(context, error);
            pending.finish();
        });
    }

    /**
     * Conexión "de un tiro": conecta, se autentica, manda los comandos esperando
     * cada OK y se desconecta. onDone recibe null si salió todo bien.
     */
    private static void sendOnce(Context context, String address, String password, List<Command> commands,
                                 java.util.function.Consumer<String> onDone) {
        Handler handler = new Handler(Looper.getMainLooper());
        Session.Scheduler scheduler = new Session.Scheduler() {
            @Override
            public void schedule(Runnable task, long delayMs) {
                handler.postDelayed(task, delayMs);
            }

            @Override
            public void cancel(Runnable task) {
                handler.removeCallbacks(task);
            }
        };
        Session[] session = new Session[1];
        boolean[] done = new boolean[1];
        java.util.function.Consumer<String> finish = error -> {
            if (done[0]) {
                return;
            }
            done[0] = true;
            handler.removeCallbacksAndMessages(null);
            session[0].close();
            Log.i(XLinkActivity.TAG, "Widget: " + (error == null ? "OK" : error));
            onDone.accept(error);
        };
        session[0] = new Session(new BleTransport(context, address), password, scheduler, new Session.Listener() {
            @Override
            public void onReady(String model, String preset) {
                if (commands.isEmpty()) {
                    finish.accept(null);
                    return;
                }
                for (Command command : commands) {
                    session[0].queue(command);
                }
                session[0].flush();
            }

            @Override
            public void onConfig(Config config) {
            }

            @Override
            public void onFlushed(int missedAcks) {
                finish.accept(null);
            }

            @Override
            public void onClosed(String reason, boolean wasReady) {
                if (wasReady) {
                    finish.accept("Se cortó la conexión");
                } else if (reason.contains("permiso")) {
                    finish.accept("Sin permiso de Bluetooth: abrí la app");
                } else if (reason.startsWith("Desconectado")) {
                    finish.accept("No se pudo conectar al DSP");
                } else {
                    finish.accept(reason);
                }
            }

            @Override
            public void onLog(String message) {
                Log.i(XLinkActivity.TAG, "Widget: " + message);
            }
        });
        handler.postDelayed(() -> finish.accept(session[0].isReady() ? "El DSP dejó de responder" : "No se encontró el DSP"),
                SEND_TIMEOUT_MS + commands.size() * Session.ACK_TIMEOUT_MS);
        session[0].connect();
    }

    /** Ganancias del EQ de entrada para el preset (-1 = Custom guardado), o null si no hay Custom. */
    private static float[] inputGains(SharedPreferences prefs, int preset) {
        float[] custom = loadCustomIn(prefs);
        int bands = custom != null ? custom.length : Config.INPUT_EQ_FREQUENCIES.length;
        if (preset < 0) {
            return custom;
        }
        float[] gains = new float[bands];
        float frequency = Config.INPUT_EQ_FREQUENCIES[0];
        for (int i = 0; i < bands; i++) {
            frequency = i < Config.INPUT_EQ_FREQUENCIES.length
                    ? Config.INPUT_EQ_FREQUENCIES[i]
                    : frequency * (float) Math.pow(2, 1.0 / 3);
            gains[i] = Math.max(-12, Math.min(12, EqPresets.gainAt(preset, frequency)));
        }
        return gains;
    }

    static float[] loadCustomIn(SharedPreferences prefs) {
        String saved = prefs.getString(PREF_CUSTOM_IN, null);
        if (saved == null || saved.isEmpty()) {
            return null;
        }
        String[] parts = saved.split(",");
        float[] gains = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            gains[i] = Float.parseFloat(parts[i]);
        }
        return gains;
    }

    static void saveCustomIn(Context context, List<Config.Band> bands) {
        StringBuilder saved = new StringBuilder();
        for (Config.Band band : bands) {
            if (saved.length() > 0) {
                saved.append(',');
            }
            saved.append(Math.round(band.gainDb));
        }
        context.getSharedPreferences(XLinkActivity.PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_CUSTOM_IN, saved.toString()).apply();
    }

    private static void setStatus(Context context, String status) {
        statusText = status;
        refresh(context);
    }

    /** Redibuja todos los widgets con el preset activo guardado en prefs. */
    static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] widgetIds = manager.getAppWidgetIds(new ComponentName(context, EqWidget.class));
        if (widgetIds.length == 0) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(XLinkActivity.PREFS, Context.MODE_PRIVATE);
        int active = prefs.getInt(PREF_WIDGET_PRESET, -1);
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_eq);
        views.setTextViewText(R.id.widget_status, statusText != null ? statusText
                : "EQ de entrada · " + (active < 0 ? "Custom" : EqPresets.SHORT_NAMES[active]));
        for (int i = 0; i < PILL_IDS.length; i++) {
            int preset = i - 1;
            boolean selected = preset == active;
            views.setTextViewText(PILL_IDS[i], preset < 0 ? "Custom" : EqPresets.SHORT_NAMES[preset]);
            views.setInt(PILL_IDS[i], "setBackgroundResource",
                    selected ? R.drawable.widget_pill_on : R.drawable.widget_pill_off);
            views.setTextColor(PILL_IDS[i], selected ? 0xFF0E1116 : 0xFFE8ECF4);
            Intent intent = new Intent(context, EqWidget.class)
                    .setAction(ACTION_PRESET)
                    .putExtra(EXTRA_PRESET, preset);
            views.setOnClickPendingIntent(PILL_IDS[i], PendingIntent.getBroadcast(context, i, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        Intent open = new Intent(context, XLinkActivity.class);
        views.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(context, 100, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        manager.updateAppWidget(widgetIds, views);
    }
}
