package ar.mtorchio.xlinkdsp.dsp;

/**
 * DSP simulado para el modo demo y los tests: contesta el login, el volcado de
 * configuración y un "OK" a cada escritura, con pequeñas demoras como el equipo
 * real. Así el demo pasa por el mismo camino de Session que el Bluetooth.
 *
 * CONFIG_DUMP es la respuesta real a "a;" capturada de un PX 201 (modelo 4,
 * preset 1) el 2026-10-26, con el eco del módulo BLE incluido tal cual llega.
 */
public final class MockTransport implements Transport {

    public static final String DEVICE_NAME = "PX 201 (demo)";
    public static final String MODEL = "4";
    public static final String PRESET = "1";

    static final String CONFIG_DUMP = "AT+LESEND=182,$#"
            + "A[+00!+02!+03!+03!+05!+05!+04!+03!+03!+03!+00!+00!+00!+00!+00!+04!+04!+04!+04!+04!"
            + "+03!+04!+04!+04!+06!+06!+06!+06!]"
            + "B[5,0;5,0;5,0;!5,0;5,0;5,0;!3,4;5,0;5,0;!3,5;5,0;5,0;!5,0;5,0;5,0;!5,0;5,0;5,0;!]"
            + "C[02000;08000;10000;!02000;08000;10000;!00100;00250;00500;!00100;00250;00500;!"
            + "00125;00250;00500;!00125;00250;00500;!]"
            + "D[+03;+00;+04;!+03;+00;+04;!-05;-03;+02;!-12;-03;+02;!+12;+12;+12;!+12;+12;+12;!]"
            + "E[+00!+00!+05!-01!-04!-04!]J[1!1!1!1!0!0!]#"
            + "AT+LESEND=182,#F[0000!0000!0000!0000!0000!0000!]f[0]G[+06!+06!-005!-005!-006!-006!]"
            + "H[60!90!160!160!220!220!]I[4!6!10!10!14!14!]K[0!0!1!1!0!0!]L[2!1!2!1!0!0!]"
            + "N[00080!00080!00090!00090!00035!00035!]"
            + "O[12DBBTW!12DBBTW!12DBBTW!12DBBTW!12DBBTW!12DBBTW!]#"
            + "AT+LESEND=182,#Q[20000!20000!05000!05000!00125!00125!]"
            + "R[OFF!OFF!12DBBES!12DBBES!24DBBTW!24DBBTW!]X[-20]Y[-53]#$";

    private static final String PREFIX = "AT+LESEND=182,";
    private static final long WRITE_MS = 20;
    private static final long REPLY_MS = 40;
    // Para que se vea el "Cargando…" como con el equipo real.
    private static final long CONFIG_MS = 400;

    private final Session.Scheduler scheduler;
    private Listener listener;
    private boolean open;

    public MockTransport(Session.Scheduler scheduler) {
        this.scheduler = scheduler;
    }

    @Override
    public void open(Listener listener) {
        this.listener = listener;
        open = true;
        later(listener::onOpen, WRITE_MS);
    }

    @Override
    public boolean write(String line) {
        if (!open) {
            return false;
        }
        String command = line.trim();
        later(listener::onWritten, WRITE_MS);
        String reply = reply(command);
        if (reply != null) {
            later(() -> listener.onData(reply), command.equals(Commands.READ_CONFIG) ? CONFIG_MS : REPLY_MS);
        }
        return true;
    }

    @Override
    public void close() {
        open = false;
    }

    /** Respuesta simulada a un comando, o null si no contesta nada. */
    static String reply(String command) {
        if (command.equals(Commands.CLEAN)) {
            return null;
        }
        if (command.startsWith("[0][")) {
            return PREFIX + "[77][1][" + MODEL + "][" + PRESET + "]";
        }
        if (command.equals(Commands.READ_CONFIG)) {
            return CONFIG_DUMP;
        }
        return command.startsWith("[") ? PREFIX + "OK" : null;
    }

    private void later(Runnable task, long delayMs) {
        scheduler.schedule(() -> {
            if (open) {
                task.run();
            }
        }, delayMs);
    }
}
