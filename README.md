# <img src="docs/logo.svg" alt="" width="36" height="36" align="top"> XLink DSP

App Android **open source** para manejar los DSP de **Expert Electronics** por Bluetooth, sin depender de la app oficial.

La idea es documentar el protocolo y tener un código abierto, simple y moderno que cualquiera pueda usar, mejorar y adaptar a otros modelos de la marca.

> Probado con un **Expert PX 201** (modelo 4). Otros modelos de la línea comparten el protocolo, pero todavía no están verificados.

## Qué hace

- **Conexión:** busca el DSP, entra con su password y recuerda el último equipo.
- **Canales:** routing (L / R / L+R), fase (0° / 180°) y delay por salida.
- **Crossover:** HPF y LPF de cada canal con un mapa de frecuencias arrastrable. Butterworth, Linkwitz-Riley y Bessel.
- **EQ:** EQ de entrada de 28 bandas y paramétrico por canal, con presets y "Custom".
- **Niveles:** master, ganancia y mute por canal, y limiter.
- **Live o Grabar:** cada cambio sale al instante o se acumulan y se mandan juntos. El crossover siempre espera Grabar, porque un error puede romper un parlante.
- **Widget** para cambiar el EQ desde la pantalla de inicio.
- **Modo demo** con datos reales capturados, para desarrollar sin el equipo.

## Estado

| Función | Con el PX 201 |
|---|---|
| Conexión, lectura de configuración | ✅ |
| Canales, ganancias, mute | ✅ |
| EQ | 🔧 en prueba |
| Crossover, limiter, widget | ⏳ sin probar |

¿Tenés otro modelo de Expert? Probá la app y contanos cómo anduvo con un [issue de modelo nuevo](.github/ISSUE_TEMPLATE/nuevo-modelo.md). Así se va sumando soporte.

## Protocolo, en corto

BLE con el servicio `fff0`: se escribe por `fff2` y se lee por notificaciones en `fff1`.

1. Login: `AT+LESEND=0`, después `[0][password]`. El DSP responde `[77][1][modelo][preset]`.
2. Con `a;` el DSP devuelve toda su configuración en bloques (`A[..]B[..]…`).
3. Cada escritura es un comando como `[100][canal][dB]`, y el DSP contesta `OK`.

El detalle completo, con qué está verificado y qué no, está en **[PROTOCOL.md](PROTOCOL.md)**.

Todo el protocolo vive en el package `dsp`, separado de las pantallas:

| Clase | Qué hace |
|---|---|
| `Commands` | Arma cada comando de escritura, con los rangos de cada valor |
| `Config` | Parsea el volcado de configuración |
| `Session` | Login, cola de escrituras y espera del `OK` |
| `Transport` | Interfaz para llevar líneas al DSP: `BleTransport` (Bluetooth) y `MockTransport` (demo y tests) |

Los tests (`./gradlew testDebugUnitTest`) fijan los strings exactos que recibe el DSP.

## Compilar

Abrir el proyecto en Android Studio, o:

```sh
./gradlew assembleDebug
```

Requiere Android 15 o más (minSdk 35).

## Release

Las PR contra `main` corren los tests y un build debug. Al pushear un tag `vX.Y.Z`
(`git tag v1.0.0 && git push origin v1.0.0`) el workflow Release corre los tests, arma el
APK firmado y lo publica en un GitHub Release.

La firma sale de cuatro secrets del repo (Settings → Secrets and variables → Actions):

| Secret | Qué es |
|---|---|
| `XLINK_KEYSTORE_BASE64` | la keystore, `base64 -w0 release.jks` |
| `XLINK_KEYSTORE_PASSWORD` | password de la keystore |
| `XLINK_KEY_ALIAS` | alias de la clave |
| `XLINK_KEY_PASSWORD` | password de la clave |

Para crear una keystore: `keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias xlink`.
Guardala fuera del repo: si se pierde, las actualizaciones ya no instalan encima.

## Licencia

Copyright (C) 2026 Maximiliano Torchio.

[GPL-3.0](LICENSE). Podés usar, modificar y redistribuir el código, incluso cobrando, siempre que lo que distribuyas también sea GPL-3.0 y con su código fuente.

## Aviso

Proyecto independiente, **sin relación con Expert Electronics**. El protocolo se obtuvo estudiando la app oficial. Usalo bajo tu responsabilidad: un mal ajuste de crossover o de ganancia puede dañar parlantes.
