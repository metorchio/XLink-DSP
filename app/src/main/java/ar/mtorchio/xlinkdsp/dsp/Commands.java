package ar.mtorchio.xlinkdsp.dsp;

import java.util.Locale;

/**
 * El protocolo de escritura en un solo lugar: cada método arma el texto de un
 * comando de xair. Canales 0-based (CH1 = 0).
 * Los valores se recortan a los rangos de xair por las dudas, aunque la
 * interfaz ya los limite. Session agrega el "\r\n" final.
 */
public final class Commands {

    // Rangos de xair para los modelos con valores enteros (serie PX).
    public static final int MAIN_GAIN_MIN = -53;
    public static final int MAIN_GAIN_MAX = 0;
    public static final int CHANNEL_GAIN_MIN = -33;
    public static final int CHANNEL_GAIN_MAX = 9;
    public static final int DELAY_MAX_MM = 6500;
    public static final int INPUT_EQ_GAIN_MAX = 12;
    public static final int CHANNEL_EQ_GAIN_MAX = 18;
    public static final int FREQUENCY_MIN = 20;
    public static final int FREQUENCY_MAX = 20000;
    public static final float Q_MIN = 0.6f;
    public static final float Q_MAX = 9.9f;
    public static final int LIMIT_THRESHOLD_MIN = -54;
    public static final int LIMIT_THRESHOLD_MAX = 6;
    public static final int LIMIT_ATTACK_MIN = 1;
    public static final int LIMIT_ATTACK_MAX = 200;
    public static final int LIMIT_DECAY_MIN = 1;
    public static final int LIMIT_DECAY_MAX = 990;

    // Routing (modelos de 2 entradas) y fase.
    public static final int ROUTE_LR = 0;
    public static final int ROUTE_R = 1;
    public static final int ROUTE_L = 2;

    private Commands() {
    }

    // ---------------------------------------------------------------- Sesión

    /** Limpia el módulo BLE antes del login (xair BluetoothLeService.cleanLE). */
    static final String CLEAN = "AT+LESEND=0";

    /** Login: el DSP contesta [77][1][modelo][preset], o [24]ER si la password es incorrecta. */
    static String login(String password) {
        return "[0][" + password + "]";
    }

    /** Pide el volcado completo de la configuración (ver Config). */
    public static final String READ_CONFIG = "a;";

    // ---------------------------------------------------------------- Niveles

    /** [100][-1][dB] */
    public static Command masterGain(int db) {
        return new Command("GAIN-1", "[100][-1][" + clamp(db, MAIN_GAIN_MIN, MAIN_GAIN_MAX) + "]");
    }

    /** [100][ch][dB] */
    public static Command channelGain(int channel, int db) {
        return new Command("GAIN" + channel,
                "[100][" + channel + "][" + clamp(db, CHANNEL_GAIN_MIN, CHANNEL_GAIN_MAX) + "]");
    }

    /** [999][ch][1|0] */
    public static Command mute(int channel, boolean muted) {
        return new Command("MUTE" + channel, "[999][" + channel + "][" + (muted ? 1 : 0) + "]");
    }

    // ---------------------------------------------------------------- Limiter

    /**
     * Umbral del limiter: [103][ch][dB] en manual, [113][ch][dB] en auto (el DSP
     * calcula attack y decay a partir del HPF del canal).
     */
    public static Command limiterThreshold(int channel, int db, boolean auto) {
        return new Command("LIMT" + channel, "[" + (auto ? 113 : 103) + "][" + channel + "]["
                + clamp(db, LIMIT_THRESHOLD_MIN, LIMIT_THRESHOLD_MAX) + "]");
    }

    /** [102][ch][ms], solo en manual. */
    public static Command limiterAttack(int channel, int ms) {
        return new Command("LIMA" + channel,
                "[102][" + channel + "][" + clamp(ms, LIMIT_ATTACK_MIN, LIMIT_ATTACK_MAX) + "]");
    }

    /** [104][ch][ms], solo en manual. */
    public static Command limiterDecay(int channel, int ms) {
        return new Command("LIMD" + channel,
                "[104][" + channel + "][" + clamp(ms, LIMIT_DECAY_MIN, LIMIT_DECAY_MAX) + "]");
    }

    // ---------------------------------------------------------------- Canales

    /** [3][ch][0|1|2]: ROUTE_LR, ROUTE_R o ROUTE_L. */
    public static Command routing(int channel, int route) {
        return new Command("ROUTE" + channel, "[3][" + channel + "][" + clamp(route, 0, 2) + "]");
    }

    /** [6][ch][0|1]: false = 0°, true = 180°. */
    public static Command phase(int channel, boolean inverted) {
        return new Command("PHASE" + channel, "[6][" + channel + "][" + (inverted ? 1 : 0) + "]");
    }

    /** [7][ch][mm]: el delay viaja como distancia (343 mm = 1 ms de sonido). */
    public static Command delay(int channel, int mm) {
        return new Command("DELAY" + channel, "[7][" + channel + "][" + clamp(mm, 0, DELAY_MAX_MM) + "]");
    }

    // ---------------------------------------------------------------- Crossover

    /** [2][ch][0][Hz] */
    public static Command hpfFrequency(int channel, int hz) {
        return crossover(channel, 0, String.valueOf(clamp(hz, FREQUENCY_MIN, FREQUENCY_MAX)));
    }

    /** [2][ch][1][pendiente] */
    public static Command hpfSlope(int channel, Slope slope) {
        return crossover(channel, 1, slope.code);
    }

    /** [2][ch][2][Hz] */
    public static Command lpfFrequency(int channel, int hz) {
        return crossover(channel, 2, String.valueOf(clamp(hz, FREQUENCY_MIN, FREQUENCY_MAX)));
    }

    /** [2][ch][3][pendiente] */
    public static Command lpfSlope(int channel, Slope slope) {
        return crossover(channel, 3, slope.code);
    }

    private static Command crossover(int channel, int param, String value) {
        return new Command("XO" + channel + ":" + param, "[2][" + channel + "][" + param + "][" + value + "]");
    }

    // ---------------------------------------------------------------- EQ

    /** Ganancia de una banda del EQ de entrada (band 0-based): [202][-1][F0..F27][dB], ±12. */
    public static Command inputEqGain(int band, float db) {
        return new Command("G-1:" + band, "[202][-1][F" + band + "]["
                + clamp(Math.round(db), -INPUT_EQ_GAIN_MAX, INPUT_EQ_GAIN_MAX) + "]");
    }

    /** Ganancia de una banda del EQ de canal: [202][ch][Fn][dB], ±18. */
    public static Command eqChannelGain(int channel, int band, float db) {
        return new Command("G" + channel + ":" + band, "[202]" + eqChannelTarget(channel, band) + "["
                + clamp(Math.round(db), -CHANNEL_EQ_GAIN_MAX, CHANNEL_EQ_GAIN_MAX) + "]");
    }

    /** Frecuencia de una banda del EQ de canal: [203][ch][Fn][Hz]. */
    public static Command eqChannelFrequency(int channel, int band, float hz) {
        return new Command("F" + channel + ":" + band, "[203]" + eqChannelTarget(channel, band) + "["
                + clamp(Math.round(hz), FREQUENCY_MIN, FREQUENCY_MAX) + "]");
    }

    /** Q de una banda del EQ de canal: [201][ch][Fn][Q], con punto decimal (como Float.toString en xair). */
    public static Command eqChannelQ(int channel, int band, float q) {
        float clamped = Math.max(Q_MIN, Math.min(Q_MAX, q));
        return new Command("Q" + channel + ":" + band, "[201]" + eqChannelTarget(channel, band) + "["
                + String.format(Locale.ROOT, "%.1f", clamped) + "]");
    }

    /** El EQ de canal numera las bandas desde F1 (band 0-based -> F1..F3). */
    private static String eqChannelTarget(int channel, int band) {
        return "[" + channel + "][F" + (band + 1) + "]";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
