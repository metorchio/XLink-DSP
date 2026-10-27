#!/usr/bin/env python3
"""
Emulador BLE "pedorro" del DSP (xair/expertx) para probar MaxoXActivity
("Widget eXe" / maXoX) sin necesitar el hardware real.

Levanta un servidor GATT (via `bless`) que se anuncia con un nombre
reconocido por MaxoXActivity.DEVICE_NAME_PREFIXES, expone el mismo servicio
y las mismas características que usa el hardware real (fff0/fff1/fff2), y
responde al protocolo real de password: `[0][<password>]` ->
`[77][1][<model>][<preset>]` si es correcta, o `[24]ER` si no.

Uso:
    tools/venv/bin/python3 tools/dsp_emulator.py

Si tira error de permisos al registrar la app / advertising en BlueZ, correr
con sudo (usando el mismo binario del venv):
    sudo tools/venv/bin/python3 tools/dsp_emulator.py
"""
import asyncio
import logging
import subprocess

from bless import (
    BlessServer,
    BlessGATTCharacteristic,
    GATTCharacteristicProperties,
    GATTAttributePermissions,
)

# --- Config editable ---
DEVICE_NAME = "PX 201"  # tiene que empezar con uno de los prefijos que reconoce MaxoXActivity
EXPECTED_PASSWORD = "1234"
FAKE_MODEL = "7"
FAKE_PRESET = "2"
# ------------------------

SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
CHAR_WRITE_UUID = "0000fff2-0000-1000-8000-00805f9b34fb"  # la app escribe acá (comandos)
CHAR_NOTIFY_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"  # la app se suscribe acá (respuestas)

logging.basicConfig(level=logging.WARNING)

server: BlessServer = None  # type: ignore[assignment]


def desktop_notify(title: str, message: str) -> None:
    """Aviso best-effort via notify-send; si no hay entorno gráfico, no rompe nada."""
    try:
        subprocess.run(["notify-send", title, message], timeout=2)
    except Exception:
        pass


def on_client_connect(_arg) -> None:
    print("\n🔌 Alguien se conectó (se suscribió a notificaciones)")
    desktop_notify("dsp_emulator", "Alguien se conectó al emulador BLE")


def on_client_disconnect(_arg) -> None:
    print("🔌 Cliente desconectado")


def notify(text: str) -> None:
    characteristic = server.get_characteristic(CHAR_NOTIFY_UUID)
    characteristic.value = bytearray(text.encode())
    server.update_value(SERVICE_UUID, CHAR_NOTIFY_UUID)
    print(f"TX -> {text!r}")


def on_write(characteristic: BlessGATTCharacteristic, value: bytearray, **_kwargs) -> None:
    text = bytes(value).decode(errors="replace")
    print(f"RX <- {text!r}")

    if text.startswith("AT+LESEND"):
        # Comando de "limpieza" del módulo BLE; el hardware real no contesta nada.
        return

    if text.startswith("[0]["):
        password = text.split("[0][", 1)[1].split("]", 1)[0]
        ok = password == EXPECTED_PASSWORD
        print(f"🔑 Intento de conexión con clave: {password!r} -> {'OK' if ok else 'RECHAZADA'}")
        desktop_notify(
            "dsp_emulator",
            f"Intento de conexión con clave: {password} ({'OK' if ok else 'rechazada'})",
        )
        if ok:
            notify(f"[77][1][{FAKE_MODEL}][{FAKE_PRESET}]\r\n")
        else:
            notify("[24]ER\r\n")
        return

    print("(comando no reconocido, ignorado)")


async def main() -> None:
    global server
    server = BlessServer(name=DEVICE_NAME)
    server.write_request_func = on_write

    await server.add_new_service(SERVICE_UUID)

    # bless no expone un callback público de "cliente conectado"; la señal más
    # cercana en este protocolo es cuando el celu se suscribe a notificaciones
    # (StartNotify), que pasa justo después de conectar por GATT, antes de
    # mandar la password.
    server.app.StartNotify = on_client_connect
    server.app.StopNotify = on_client_disconnect

    write_props = (
        GATTCharacteristicProperties.write
        | GATTCharacteristicProperties.write_without_response
    )
    notify_props = GATTCharacteristicProperties.notify | GATTCharacteristicProperties.read
    perms = GATTAttributePermissions.readable | GATTAttributePermissions.writeable

    await server.add_new_characteristic(
        SERVICE_UUID, CHAR_WRITE_UUID, write_props, None, perms
    )
    await server.add_new_characteristic(
        SERVICE_UUID, CHAR_NOTIFY_UUID, notify_props, None, perms
    )

    await server.start()
    print(f"Anunciando como {DEVICE_NAME!r} (servicio {SERVICE_UUID})")
    print(
        f"Password esperada: {EXPECTED_PASSWORD!r} -> "
        f"respuesta modelo={FAKE_MODEL} preset={FAKE_PRESET}"
    )
    print("Ctrl+C para cortar.\n")

    try:
        while True:
            await asyncio.sleep(1)
    finally:
        await server.stop()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("\nCortado.")
