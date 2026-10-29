/*
 * XLink DSP
 * Copyright (C) 2026 Maximiliano Torchio
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package ar.mtorchio.xlinkdsp.dsp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Una conexión con un DSP sobre cualquier Transport: login, lectura de la
 * configuración y cola de escrituras.
 *
 * Login (xair BluetoothLeService): "AT+LESEND=0" -> "[0][password]" -> espera
 * "[77][1][modelo][preset]" ("[24]ER" = password inválida).
 *
 * Cola: cada comando espera el "OK" del DSP (o ACK_TIMEOUT_MS) antes del
 * siguiente; mandarlos de corrido hacía que el DSP descartara la mayoría. Un
 * comando con la misma clave que uno pendiente lo pisa. Los comandos "retenidos"
 * (hold) no salen con flush() hasta releaseHeld(): el crossover espera a Grabar.
 *
 * No es thread-safe: se usa desde el hilo donde el Transport entrega sus callbacks.
 */
public final class Session implements Transport.Listener {

    /** Timers en el mismo hilo que el Transport (Handler en Android, falso en los tests). */
    public interface Scheduler {
        void schedule(Runnable task, long delayMs);

        void cancel(Runnable task);
    }

    public interface Listener {
        /** Login aceptado. */
        void onReady(String model, String preset);

        /** Llegó el volcado pedido con readConfig(). */
        void onConfig(Config config);

        /** La conexión terminó sola. wasReady = false si falló antes del login. */
        void onClosed(String reason, boolean wasReady);

        /** Salió un comando de la cola; left = los que faltan. */
        default void onSending(int left) {
        }

        /** Se vació la cola; missedAcks = comandos que no recibieron "OK". */
        default void onFlushed(int missedAcks) {
        }

        default void onLog(String message) {
        }
    }

    public static final long ACK_TIMEOUT_MS = 500;
    static final long LOGIN_TIMEOUT_MS = 8000;
    static final long READ_TIMEOUT_MS = 10000;

    private enum State { IDLE, OPENING, CLEANING, LOGGING_IN, READY, CLOSED }

    private final Transport transport;
    private final String password;
    private final Scheduler scheduler;
    private final Listener listener;

    private State state = State.IDLE;
    private final StringBuilder rx = new StringBuilder();
    private final Map<String, String> pending = new LinkedHashMap<>();
    private final Map<String, String> held = new LinkedHashMap<>();
    // Sale ya, sin esperar a flush() ni pasar por lo pendiente (el fade del mute del master).
    private final Map<String, String> urgent = new LinkedHashMap<>();
    private boolean writing;
    private boolean flushing;
    private boolean readRequested;
    private boolean reading;
    private String awaitingAck;
    private int missedAcks;

    private final Runnable loginTimeout = () -> fail("El DSP no contestó a la password");
    private final Runnable ackTimeout = () -> {
        if (awaitingAck != null) {
            log("Sin OK del DSP para " + awaitingAck);
            awaitingAck = null;
            missedAcks++;
            pump();
        }
    };
    private final Runnable readTimeout = () -> {
        if (reading) {
            log("La configuración no llegó completa");
            reading = false;
            pump();
        }
    };

    public Session(Transport transport, String password, Scheduler scheduler, Listener listener) {
        this.transport = transport;
        this.password = password;
        this.scheduler = scheduler;
        this.listener = listener;
    }

    public void connect() {
        if (state != State.IDLE) {
            return;
        }
        state = State.OPENING;
        transport.open(this);
    }

    /** Corta la conexión sin avisar al listener. */
    public void close() {
        if (state == State.CLOSED) {
            return;
        }
        state = State.CLOSED;
        cancelTimers();
        pending.clear();
        held.clear();
        urgent.clear();
        transport.close();
    }

    public boolean isReady() {
        return state == State.READY;
    }

    /** Pide el volcado de configuración; llega por Listener.onConfig. */
    public void readConfig() {
        readRequested = true;
        pump();
    }

    /** Encola un comando; sale con el próximo flush(). */
    public void queue(Command command) {
        pending.put(command.key, command.text);
    }

    /** Encola un comando que espera a releaseHeld() aunque se llame a flush(). */
    public void hold(Command command) {
        held.put(command.key, command.text);
    }

    /** Pasa lo retenido a la cola normal. */
    public void releaseHeld() {
        pending.putAll(held);
        held.clear();
    }

    /**
     * Manda un comando ya, adelante de lo pendiente y sin mandar lo pendiente. Si
     * todavía no salió el anterior con la misma clave, lo pisa; un pendiente igual se
     * descarta para que un Grabar posterior no lo vuelva atrás.
     */
    public void sendNow(Command command) {
        pending.remove(command.key);
        held.remove(command.key);
        urgent.put(command.key, command.text);
        pump();
    }

    /** Tira lo encolado y lo retenido que todavía no salió. */
    public void discard() {
        pending.clear();
        held.clear();
        urgent.clear();
    }

    /** Empieza a mandar lo encolado. */
    public void flush() {
        if (pending.isEmpty() && !flushing) {
            return;
        }
        flushing = true;
        pump();
    }

    public int pendingCount() {
        return pending.size();
    }

    public int heldCount() {
        return held.size();
    }

    /** true mientras hay comandos saliendo (desde flush() hasta onFlushed). */
    public boolean isSending() {
        return flushing;
    }

    // ---------------------------------------------------------------- Transport.Listener

    @Override
    public void onOpen() {
        if (state != State.OPENING) {
            return;
        }
        state = State.CLEANING;
        rx.setLength(0);
        write(Commands.CLEAN);
    }

    @Override
    public void onWritten() {
        writing = false;
        if (state == State.CLEANING) {
            log("Enviando password…");
            state = State.LOGGING_IN;
            scheduler.schedule(loginTimeout, LOGIN_TIMEOUT_MS);
            write(Commands.login(password));
        } else {
            pump();
        }
    }

    @Override
    public void onData(String chunk) {
        rx.append(chunk);
        String received = rx.toString();
        if (state == State.LOGGING_IN) {
            if (received.contains("[77][1]")) {
                scheduler.cancel(loginTimeout);
                state = State.READY;
                rx.setLength(0);
                listener.onReady(field(received, 3), field(received, 4));
                // Si la escritura de la password sigue pendiente, la cola arranca en onWritten.
                pump();
            } else if (received.contains("[24]") || received.contains("ER")) {
                fail("Password inválida");
            }
            return;
        }
        if (state != State.READY) {
            return;
        }
        if (awaitingAck != null && chunk.contains("OK")) {
            scheduler.cancel(ackTimeout);
            awaitingAck = null;
            pump();
        }
        if (reading && Config.isComplete(received)) {
            scheduler.cancel(readTimeout);
            reading = false;
            rx.setLength(0);
            listener.onConfig(Config.parse(received));
            pump();
        }
    }

    @Override
    public void onClosed(String reason) {
        if (state == State.CLOSED) {
            return;
        }
        boolean wasReady = state == State.READY;
        state = State.CLOSED;
        cancelTimers();
        pending.clear();
        held.clear();
        urgent.clear();
        listener.onClosed(reason, wasReady);
    }

    @Override
    public void onLog(String message) {
        listener.onLog(message);
    }

    // ---------------------------------------------------------------- Cola

    private void pump() {
        if (state != State.READY || writing || awaitingAck != null || reading) {
            return;
        }
        if (readRequested) {
            readRequested = false;
            reading = true;
            rx.setLength(0);
            scheduler.schedule(readTimeout, READ_TIMEOUT_MS);
            write(Commands.READ_CONFIG);
            return;
        }
        if (!urgent.isEmpty()) {
            String key = urgent.keySet().iterator().next();
            awaitingAck = urgent.remove(key);
            scheduler.schedule(ackTimeout, ACK_TIMEOUT_MS);
            write(awaitingAck);
            return;
        }
        if (!flushing) {
            return;
        }
        if (pending.isEmpty()) {
            flushing = false;
            int missed = missedAcks;
            missedAcks = 0;
            listener.onFlushed(missed);
            return;
        }
        String key = pending.keySet().iterator().next();
        awaitingAck = pending.remove(key);
        scheduler.schedule(ackTimeout, ACK_TIMEOUT_MS);
        write(awaitingAck);
        listener.onSending(pending.size());
    }

    private void write(String line) {
        // No dejar la password a la vista en el log.
        log("TX -> " + (line.startsWith("[0][") ? "[0][****]" : line));
        writing = true;
        if (!transport.write(line + "\r\n")) {
            writing = false;
            log("ERROR: no se pudo escribir " + (line.startsWith("[0][") ? "la password" : line));
        }
    }

    private void fail(String reason) {
        if (state == State.CLOSED) {
            return;
        }
        boolean wasReady = state == State.READY;
        close();
        listener.onClosed(reason, wasReady);
    }

    private void cancelTimers() {
        scheduler.cancel(loginTimeout);
        scheduler.cancel(ackTimeout);
        scheduler.cancel(readTimeout);
        writing = false;
        flushing = false;
        reading = false;
        readRequested = false;
        awaitingAck = null;
        missedAcks = 0;
    }

    private void log(String message) {
        listener.onLog(message);
    }

    /** Campo entre corchetes: "[77][1][4][1]" -> 3 = modelo, 4 = preset. */
    private static String field(String response, int bracketIndex) {
        try {
            return response.split("\\[")[bracketIndex].split("\\]")[0];
        } catch (RuntimeException e) {
            return "?";
        }
    }
}
