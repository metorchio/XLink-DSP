package ar.mtorchio.xlinkdsp.dsp;

/**
 * Un comando de escritura listo para mandar. La clave identifica qué ajuste toca
 * (por ejemplo "GAIN2" = ganancia de CH3): en la cola de Session, un comando nuevo
 * con la misma clave pisa al que todavía no salió, así nunca se mandan valores viejos.
 */
public final class Command {

    public final String key;
    public final String text;

    Command(String key, String text) {
        this.key = key;
        this.text = text;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Command && key.equals(((Command) other).key) && text.equals(((Command) other).text);
    }

    @Override
    public int hashCode() {
        return 31 * key.hashCode() + text.hashCode();
    }

    @Override
    public String toString() {
        return text;
    }
}
