package ar.mtorchio.xlinkdsp;

/**
 * Respuestas simuladas del DSP para el modo demo (armar pantallas en el emulador,
 * que no tiene acceso al Bluetooth real).
 *
 * CONFIG_DUMP es la respuesta real a "a;" capturada de un PX 201 (modelo 4,
 * preset 1) el 2026-10-26, con el eco del módulo BLE incluido tal cual llega.
 */
final class MockDsp {

    static final String DEVICE_NAME = "PX 201 (demo)";
    static final String MODEL = "4";
    static final String PRESET = "1";

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

    // Formato inventado: todavía no capturamos una respuesta real a "V;".
    static final String VOLTAGE = "AT+LESEND=182,V:13.8 (simulado)";

    private MockDsp() {
    }

    /** Respuesta simulada a un comando, o null si el modo demo no lo conoce. */
    static String respond(String command) {
        switch (command) {
            case "a;":
                return CONFIG_DUMP;
            case "V;":
                return VOLTAGE;
            default:
                return null;
        }
    }
}
