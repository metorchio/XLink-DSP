package ar.mtorchio.xlinkdsp.dsp;

/**
 * Lo que lleva líneas de texto ida y vuelta entre Session y el DSP: BLE en el
 * equipo real (BleTransport), respuestas simuladas en el demo (MockTransport).
 *
 * Una escritura a la vez: después de write() no se escribe de nuevo hasta
 * onWritten(). Todos los callbacks llegan en el mismo hilo (el principal en Android).
 */
public interface Transport {

    interface Listener {
        /** Listo para escribir (en BLE: conectado y con las notificaciones activas). */
        void onOpen();

        /** Terminó la escritura anterior. */
        void onWritten();

        /** Llegó un pedazo de respuesta (una notificación BLE). */
        void onData(String chunk);

        /** Se cerró solo (corte, error, sin permisos). No se llama después de close(). */
        void onClosed(String reason);

        default void onLog(String message) {
        }
    }

    void open(Listener listener);

    /** Manda una línea ya terminada en "\r\n". false si ni siquiera se pudo encolar. */
    boolean write(String line);

    void close();
}
