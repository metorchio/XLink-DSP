package ar.mtorchio.xlinkdsp;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;

/**
 * Widget chico de la pantalla de inicio: elegir el EQ de entrada entre el Custom
 * (lo que el usuario dejó grabado) y los presets de EqPresets. Si la app está
 * conectada lo aplica por esa conexión; si no, se conecta solo al último DSP
 * con la password guardada (DspLink), manda los comandos y se desconecta.
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
        if (MaxoXActivity.applyInputPresetFromWidget(preset)) {
            setStatus(context, null);
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(MaxoXActivity.PREFS, Context.MODE_PRIVATE);
        String address = prefs.getString(MaxoXActivity.PREF_LAST_ADDRESS, null);
        String password = prefs.getString(MaxoXActivity.PREF_PASSWORD, null);
        if (address == null || password == null) {
            setStatus(context, "Conectate una vez desde la app");
            return;
        }
        float[] gains = inputGains(prefs, preset);
        if (gains == null) {
            setStatus(context, "Sin Custom guardado: abrí la app");
            return;
        }
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < gains.length; i++) {
            commands.add("[202][-1][F" + i + "][" + Math.round(gains[i]) + "]");
        }

        setStatus(context, "Aplicando " + name + "…");
        PendingResult pending = goAsync();
        DspLink.send(context, address, password, commands, error -> {
            if (error == null) {
                prefs.edit().putInt(PREF_WIDGET_PRESET, preset).apply();
            }
            setStatus(context, error);
            pending.finish();
        });
    }

    /** Ganancias del EQ de entrada para el preset (-1 = Custom guardado), o null si no hay Custom. */
    private static float[] inputGains(SharedPreferences prefs, int preset) {
        float[] custom = loadCustomIn(prefs);
        int bands = custom != null ? custom.length : DspConfig.INPUT_EQ_FREQUENCIES.length;
        if (preset < 0) {
            return custom;
        }
        float[] gains = new float[bands];
        float frequency = DspConfig.INPUT_EQ_FREQUENCIES[0];
        for (int i = 0; i < bands; i++) {
            frequency = i < DspConfig.INPUT_EQ_FREQUENCIES.length
                    ? DspConfig.INPUT_EQ_FREQUENCIES[i]
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

    static void saveCustomIn(Context context, List<DspConfig.Band> bands) {
        StringBuilder saved = new StringBuilder();
        for (DspConfig.Band band : bands) {
            if (saved.length() > 0) {
                saved.append(',');
            }
            saved.append(Math.round(band.gainDb));
        }
        context.getSharedPreferences(MaxoXActivity.PREFS, Context.MODE_PRIVATE)
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
        SharedPreferences prefs = context.getSharedPreferences(MaxoXActivity.PREFS, Context.MODE_PRIVATE);
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
        Intent open = new Intent(context, MaxoXActivity.class);
        views.setOnClickPendingIntent(R.id.widget_title, PendingIntent.getActivity(context, 100, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        manager.updateAppWidget(widgetIds, views);
    }
}
