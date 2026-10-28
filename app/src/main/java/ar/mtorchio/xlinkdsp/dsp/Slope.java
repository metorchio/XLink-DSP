/*
 * XLink DSP
 * Copyright (C) 2026 Maximiliano Torchio
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package ar.mtorchio.xlinkdsp.dsp;

/**
 * Pendientes del crossover (xair array spinnerHPF, igual para HPF y LPF).
 * code = cómo viaja en el protocolo (bloques O / R y comando [2]); label = rótulo corto.
 */
public enum Slope {
    OFF("OFF", "OFF"),
    BW6("6DBBTW", "BW 6"),
    BW12("12DBBTW", "BW 12"),
    BW18("18DBBTW", "BW 18"),
    BW24("24DBBTW", "BW 24"),
    LR12("12DBLKR", "LR 12"),
    LR24("24DBLKR", "LR 24"),
    LR36("36DBLKR", "LR 36"),
    LR48("48DBLKR", "LR 48"),
    BS12("12DBBES", "BS 12"),
    BS24("24DBBES", "BS 24");

    public final String code;
    public final String label;

    Slope(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** La pendiente con ese código, o null si el DSP mandó uno desconocido. */
    public static Slope fromCode(String code) {
        for (Slope slope : values()) {
            if (slope.code.equals(code)) {
                return slope;
            }
        }
        return null;
    }
}
