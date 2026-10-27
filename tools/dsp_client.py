#!/usr/bin/env python3
"""
Cliente BLE de PC para el DSP (xair/expertx): mismo handshake que
MaxoXActivity / xair BLEMainActivity, y después pide datos de solo lectura.

  escaneo por nombre -> conectar -> notificaciones en fff1 ->
  "AT+LESEND=0" -> "[0][<password>]" -> espera "[77][1][model][preset]" ->
  manda los comandos de lectura (por defecto "a;" = volcado de config y
  "V;" = voltaje, igual que MainActivity) e imprime todo lo que llega.

Uso:
    tools/venv/bin/python3 tools/dsp_client.py --password 1234
    tools/venv/bin/python3 tools/dsp_client.py --password 1234 --address AA:BB:CC:DD:EE:FF
    tools/venv/bin/python3 tools/dsp_client.py --password 1234 --cmd "a;" --cmd "V;" --listen 15

Solo manda lo que se le pase en --cmd (por defecto comandos de lectura);
no escribe parámetros del equipo.
"""
import argparse
import asyncio
import sys

from bleak import BleakClient, BleakScanner

CHAR_WRITE_UUID = "0000fff2-0000-1000-8000-00805f9b34fb"
CHAR_NOTIFY_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"

DEVICE_NAME_PREFIXES = ("PX 201", "PX 101", "PX 202", "PX 102", "PX 103", "PX 203", "PX 108", "PX 208", "P8",
                        "AMP", "X4", "X6", "X8", "A4", "A6", "A8", "B4", "B6", "B8", "D4", "D6", "D8",
                        "S6", "S8", "PRO6P", "PRO8", "MXAiR", "M8", "mn", "W8")


async def find_device(address: str | None, timeout: float):
    if address:
        print(f"Buscando {address}…")
        return await BleakScanner.find_device_by_address(address, timeout=timeout)

    print(f"Escaneando {timeout:.0f}s…")
    found = await BleakScanner.discover(timeout=timeout, return_adv=True)
    candidates = []
    for device, adv in found.values():
        name = adv.local_name or device.name or ""
        mark = ""
        if any(p in name for p in DEVICE_NAME_PREFIXES):
            candidates.append((device, adv))
            mark = "  <-- posible DSP"
        if name:
            print(f"  {device.address}  rssi={adv.rssi:4}  {name}{mark}")
    if not candidates:
        return None
    candidates.sort(key=lambda c: c[1].rssi, reverse=True)
    return candidates[0][0]


async def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--password", required=True)
    parser.add_argument("--address", help="MAC del DSP (salta el filtro por nombre)")
    parser.add_argument("--scan", type=float, default=8.0, help="segundos de escaneo")
    parser.add_argument("--cmd", action="append", help='comando a mandar tras el login (default: "a;" y "V;")')
    parser.add_argument("--listen", type=float, default=10.0, help="segundos escuchando tras cada comando")
    args = parser.parse_args()
    commands = args.cmd or ["a;", "V;"]

    device = await find_device(args.address, args.scan)
    if device is None:
        print("No se encontró ningún DSP. Probá con --address <MAC>.")
        return 1
    print(f"Conectando a {device.address} ({device.name})…")

    rx_buffer = []
    login = asyncio.get_running_loop().create_future()

    def on_notify(_char, data: bytearray) -> None:
        text = bytes(data).decode(errors="replace")
        rx_buffer.append(text)
        print(f"RX <- {text!r}")
        joined = "".join(rx_buffer)
        if not login.done():
            if "[77][1]" in joined:
                login.set_result(joined)
            elif "[24]" in joined or "ER" in joined:
                login.set_exception(PermissionError("password inválida"))

    async with BleakClient(device) as client:
        print("Conectado. Servicios:")
        for service in client.services:
            chars = ", ".join(f"{c.uuid[4:8]}({'/'.join(c.properties)})" for c in service.characteristics)
            print(f"  {service.uuid}: {chars}")

        async def send(text: str) -> None:
            print(f"TX -> {text!r}")
            await client.write_gatt_char(CHAR_WRITE_UUID, (text + "\r\n").encode(), response=True)

        await client.start_notify(CHAR_NOTIFY_UUID, on_notify)
        await send("AT+LESEND=0")
        await send(f"[0][{args.password}]")

        try:
            reply = await asyncio.wait_for(login, timeout=8)
        except asyncio.TimeoutError:
            print("Timeout: el DSP no contestó a la password")
            return 2
        except PermissionError as e:
            print(f"Login rechazado: {e}")
            return 3

        fields = [f.split("]")[0] for f in reply.split("[")[1:]]
        model = fields[2] if len(fields) > 2 else "?"
        preset = fields[3] if len(fields) > 3 else "?"
        print(f"\n✅ Login OK — modelo={model} preset={preset}\n")

        for command in commands:
            rx_buffer.clear()
            await send(command)
            await asyncio.sleep(args.listen)
            print(f"--- respuesta completa a {command!r} ({sum(map(len, rx_buffer))} bytes):")
            print("".join(rx_buffer) or "(nada)")
            print()

        await client.stop_notify(CHAR_NOTIFY_UUID)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(asyncio.run(main()))
    except KeyboardInterrupt:
        print("\nCortado.")
