/*
 * XLink DSP
 * Copyright (C) 2026 Maximiliano Torchio
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package ar.mtorchio.xlinkdsp.dsp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Respuesta a V; (xair Monitor, que la pide cada 1 s): "[tensión][l0!l1!...]".
 * La tensión viene con coma decimal ("12,6"); cada l es 1 si el limiter de ese
 * canal está actuando. Formato sacado de la app oficial, sin confirmar con el equipo.
 */
public final class MonitorReading {

    private static final Pattern REPLY = Pattern.compile("\\[([^\\[\\]]*)\\]\\s*\\[([^\\[\\]]*)\\]");

    /** La respuesta tal cual llegó (sin el eco del módulo BLE). */
    public final String raw;
    /** Volts, o NaN si no vino. */
    public final float voltage;
    /** Un valor por canal (1 = limitando); vacío si no vino ("-"). */
    public final boolean[] limiting;

    private MonitorReading(String raw, float voltage, boolean[] limiting) {
        this.raw = raw;
        this.voltage = voltage;
        this.limiting = limiting;
    }

    /** null mientras no llegaron los dos corchetes. */
    public static MonitorReading parse(String received) {
        String cleaned = Config.clean(received);
        Matcher matcher = REPLY.matcher(cleaned);
        if (!matcher.find()) {
            return null;
        }
        float voltage;
        try {
            voltage = Float.parseFloat(matcher.group(1).trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            voltage = Float.NaN;
        }
        String list = matcher.group(2).trim();
        boolean[] limiting = new boolean[0];
        if (list.contains("!")) {
            String[] values = list.split("!");
            limiting = new boolean[values.length];
            for (int i = 0; i < values.length; i++) {
                limiting[i] = values[i].trim().equals("1");
            }
        }
        return new MonitorReading(matcher.group(0), voltage, limiting);
    }
}
