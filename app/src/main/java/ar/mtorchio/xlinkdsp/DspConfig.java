package ar.mtorchio.xlinkdsp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser de la respuesta a "a;" (volcado de configuración del DSP).
 *
 * Formato reconstruido de xair MainActivity (armado de resultBLU + checkCommand)
 * y EQCH.restoreData: la respuesta viene entre '$' ... '$', en secciones separadas
 * por '#', cada una con bloques "A[...]B[...]C[...]..." identificados por una letra
 * (mayúsculas y minúsculas son bloques distintos). Dentro de un bloque los canales
 * se separan con '!' y los valores con ';', con coma decimal.
 *
 * EQ por canal: B = factor Q, C = frecuencia (Hz), D = ganancia (dB).
 * EQ de entrada (xair EQIN): bloque A, una ganancia entera (dB) por banda separadas
 * por '!', sobre bandas fijas de 1/3 de octava (31 Hz..16 kHz en la serie PX).
 *
 * Ganancias (xair Gain): X = master (dB, -53..0), E = ganancia por canal (dB enteros,
 * -33..+9; los modelos 29/30/78/79 la mandan en décimas), K = mute por canal (1 = mute).
 *
 * Por canal (xair Routing / Phase / Delay): L = entrada (0 = L+R, 1 = R, 2 = L en los
 * modelos de 2 entradas), J = fase (0 = 0°, 1 = 180°), F = delay en mm (distancia).
 *
 * Crossover (xair Crossover): N / O = frecuencia y pendiente del pasa altos (HPF),
 * Q / R = del pasa bajos (LPF). La pendiente viene como texto ("OFF", "12DBBTW"...).
 * Limiter (xair Limiter): G = umbral (dB, -54..+6), I = attack (ms), H = decay (ms).
 */
final class DspConfig {

    /** Banda editable: el editor de EQ la modifica en el lugar y manda el cambio al DSP. */
    static final class Band {
        float frequency;
        float q;
        float gainDb;

        Band(float frequency, float q, float gainDb) {
            this.frequency = frequency;
            this.q = q;
            this.gainDb = gainDb;
        }
    }

    private static final Pattern MODULE_PREFIX = Pattern.compile("AT\\+LESEND=\\d+,");
    private static final Pattern BLOCK = Pattern.compile("([A-Za-z])\\[([^\\[\\]$#]*)\\]");
    // Q por defecto cuando el modelo no manda bloque B (xair: M8).
    private static final float DEFAULT_Q = 1.4f;
    // Ecualizador gráfico de 1/3 de octava: Q = sqrt(2^(1/3)) / (2^(1/3) - 1).
    private static final float THIRD_OCTAVE_Q = 4.32f;
    // Rótulos de EQIN para la serie PX (rama "else" de xair EQIN).
    static final float[] INPUT_EQ_FREQUENCIES = {
            31, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800,
            1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000
    };

    final Map<Character, String> blocks;
    final List<List<Band>> eqChannels;
    final List<Band> inputEq;
    // Editables desde la solapa Ganancias. mainGain = NaN si no vino el bloque X.
    float mainGain;
    final float[] channelGains;
    final boolean[] channelMutes;
    final int[] routing;
    final int[] phase;
    final int[] delays;
    final int[] hpfFreq;
    final String[] hpfSlope;
    final int[] lpfFreq;
    final String[] lpfSlope;
    final int[] limThreshold;
    final int[] limAttack;
    final int[] limDecay;

    private DspConfig(Map<Character, String> blocks, List<List<Band>> eqChannels, List<Band> inputEq) {
        this.blocks = blocks;
        this.eqChannels = eqChannels;
        this.inputEq = inputEq;
        String main = blocks.get('X');
        mainGain = main != null && !main.trim().isEmpty() ? parseNumber(main) : Float.NaN;
        channelGains = values(blocks.get('E'));
        float[] mutes = values(blocks.get('K'));
        channelMutes = new boolean[channelGains.length];
        for (int i = 0; i < channelMutes.length && i < mutes.length; i++) {
            channelMutes[i] = mutes[i] == 1;
        }
        routing = ints(blocks.get('L'));
        phase = ints(blocks.get('J'));
        delays = ints(blocks.get('F'));
        hpfFreq = ints(blocks.get('N'));
        hpfSlope = strings(blocks.get('O'));
        lpfFreq = ints(blocks.get('Q'));
        lpfSlope = strings(blocks.get('R'));
        limThreshold = ints(blocks.get('G'));
        limAttack = ints(blocks.get('I'));
        limDecay = ints(blocks.get('H'));
    }

    private static String[] strings(String block) {
        if (block == null) {
            return new String[0];
        }
        List<String> list = new ArrayList<>();
        for (String value : block.split("!")) {
            if (!value.trim().isEmpty()) {
                list.add(value.trim());
            }
        }
        return list.toArray(new String[0]);
    }

    private static int[] ints(String block) {
        float[] floats = values(block);
        int[] result = new int[floats.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = Math.round(floats[i]);
        }
        return result;
    }

    /** Un valor por canal separados por '!' (bloques E, K, J...). */
    private static float[] values(String block) {
        if (block == null) {
            return new float[0];
        }
        List<Float> list = new ArrayList<>();
        for (String value : block.split("!")) {
            if (!value.trim().isEmpty()) {
                list.add(parseNumber(value));
            }
        }
        float[] result = new float[list.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = list.get(i);
        }
        return result;
    }

    /** Saca el eco del módulo BLE ("AT+LESEND=182,") y los saltos de línea. */
    static String clean(String raw) {
        return MODULE_PREFIX.matcher(raw).replaceAll("")
                .replace("\r", "")
                .replace("\n", "")
                .replace("##", "#")
                .trim();
    }

    /** true cuando llegó el volcado completo: empieza y termina con '$'. */
    static boolean isComplete(String raw) {
        String cleaned = clean(raw);
        int first = cleaned.indexOf('$');
        return first >= 0 && cleaned.length() > first + 1 && cleaned.endsWith("$");
    }

    static DspConfig parse(String raw) {
        String cleaned = clean(raw);
        int first = cleaned.indexOf('$');
        int last = cleaned.lastIndexOf('$');
        String body = first >= 0 && last > first ? cleaned.substring(first + 1, last) : cleaned;

        Map<Character, String> blocks = new LinkedHashMap<>();
        Matcher matcher = BLOCK.matcher(body);
        while (matcher.find()) {
            char key = matcher.group(1).charAt(0);
            if (!blocks.containsKey(key)) {
                blocks.put(key, matcher.group(2));
            }
        }

        List<float[]> q = channels(blocks.get('B'));
        List<float[]> freq = channels(blocks.get('C'));
        List<float[]> gain = channels(blocks.get('D'));
        List<List<Band>> eq = new ArrayList<>();
        int channelCount = Math.min(freq.size(), gain.size());
        for (int ch = 0; ch < channelCount; ch++) {
            float[] chFreq = freq.get(ch);
            float[] chGain = gain.get(ch);
            float[] chQ = ch < q.size() ? q.get(ch) : new float[0];
            int bandCount = Math.min(chFreq.length, chGain.length);
            List<Band> bands = new ArrayList<>();
            for (int band = 0; band < bandCount; band++) {
                float bandQ = band < chQ.length && chQ[band] > 0 ? chQ[band] : DEFAULT_Q;
                bands.add(new Band(chFreq[band], bandQ, chGain[band]));
            }
            eq.add(Collections.unmodifiableList(bands));
        }
        return new DspConfig(Collections.unmodifiableMap(blocks), Collections.unmodifiableList(eq),
                inputEq(blocks.get('A')));
    }

    private static List<Band> inputEq(String block) {
        List<Band> bands = new ArrayList<>();
        if (block == null) {
            return bands;
        }
        float frequency = INPUT_EQ_FREQUENCIES[0];
        int band = 0;
        for (String value : block.split("!")) {
            if (value.trim().isEmpty()) {
                continue;
            }
            // Si el equipo manda más bandas que la tabla, seguir en tercios de octava.
            frequency = band < INPUT_EQ_FREQUENCIES.length
                    ? INPUT_EQ_FREQUENCIES[band]
                    : frequency * (float) Math.pow(2, 1.0 / 3);
            bands.add(new Band(frequency, THIRD_OCTAVE_Q, parseNumber(value)));
            band++;
        }
        return Collections.unmodifiableList(bands);
    }

    private static List<float[]> channels(String block) {
        List<float[]> result = new ArrayList<>();
        if (block == null) {
            return result;
        }
        for (String channel : block.split("!")) {
            if (channel.trim().isEmpty()) {
                continue;
            }
            String[] parts = channel.split(";");
            float[] values = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                values[i] = parseNumber(parts[i]);
            }
            result.add(values);
        }
        return result;
    }

    private static float parseNumber(String value) {
        try {
            String normalized = value.trim().replace(',', '.');
            return normalized.isEmpty() ? 0f : Float.parseFloat(normalized);
        } catch (NumberFormatException e) {
            return 0f;
        }
    }
}
