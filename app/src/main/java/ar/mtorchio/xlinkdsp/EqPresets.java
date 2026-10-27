package ar.mtorchio.xlinkdsp;

/**
 * EQ predefinidos. Cada uno es una curva objetivo (puntos Hz -> dB, interpolada
 * en escala logarítmica) que se muestrea en la frecuencia de cada banda: sirve
 * igual para el gráfico de entrada (28 bandas) que para el paramétrico de un
 * canal (se tocan las ganancias, frecuencia y Q quedan como están).
 */
final class EqPresets {

    static final String[] NAMES = {
            "Flat", "Bass Boost", "Vocal", "Treble", "V-Shape", "Rock / Hip-Hop", "Classical / Jazz"
    };
    // Para el widget, donde no entran los nombres largos.
    static final String[] SHORT_NAMES = {"Flat", "Bass", "Vocal", "Treble", "V-Shape", "Rock", "Jazz"};

    // {Hz, dB} por preset, ordenados por frecuencia.
    private static final float[][][] CURVES = {
            // Flat / Reference: todo a 0 dB.
            {{20, 0}, {20000, 0}},
            // Bass Boost: graves de 31 a 125 Hz arriba.
            {{31, 6}, {63, 6}, {125, 4}, {250, 1}, {400, 0}, {20000, 0}},
            // Vocal (inteligibilidad): presencia en 2-4 kHz, graves un poco abajo.
            {{31, -2}, {125, -1}, {500, 0}, {1000, 1}, {2000, 3}, {3150, 4}, {4000, 3},
                    {6300, 1}, {10000, 0}, {16000, -1}},
            // Treble / Acoustic: aire y brillo de 8 a 12 kHz en adelante.
            {{20, 0}, {2000, 0}, {4000, 1}, {8000, 4}, {12500, 5}, {16000, 5}},
            // V-Shape: graves y agudos arriba, pozo en 250-500 Hz.
            {{31, 5}, {80, 4}, {160, 1}, {315, -3}, {500, -3}, {1000, -1}, {2500, 1},
                    {6300, 4}, {16000, 5}},
            // Rock / Hip-Hop: graves con punch + presencia en 2-5 kHz.
            {{31, 3}, {63, 4}, {125, 2}, {250, 0}, {800, 0}, {2000, 2}, {3150, 3},
                    {5000, 2}, {10000, 1}, {16000, 0}},
            // Classical / Jazz: medio-graves tibios, agudos suaves.
            {{31, 1}, {125, 2}, {250, 2}, {500, 1}, {1000, 0}, {4000, 0}, {8000, -1}, {16000, -2}},
    };

    private EqPresets() {
    }

    /** Ganancia entera (dB) del preset en la frecuencia dada. */
    static float gainAt(int preset, float hz) {
        float[][] points = CURVES[preset];
        if (hz <= points[0][0]) {
            return points[0][1];
        }
        for (int i = 1; i < points.length; i++) {
            if (hz <= points[i][0]) {
                double t = Math.log(hz / points[i - 1][0]) / Math.log(points[i][0] / points[i - 1][0]);
                return Math.round(points[i - 1][1] + t * (points[i][1] - points[i - 1][1]));
            }
        }
        return points[points.length - 1][1];
    }
}
