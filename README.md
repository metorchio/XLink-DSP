# XLink DSP

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

## Protocolo, en corto

BLE con el servicio `fff0`: se escribe por `fff2` y se lee por notificaciones en `fff1`.

1. Login: `AT+LESEND=0`, después `[0][password]`. El DSP responde `[77][1][modelo][preset]`.
2. Con `a;` el DSP devuelve toda su configuración en bloques (`A[..]B[..]…`).
3. Cada escritura es un comando como `[100][canal][dB]`, y el DSP contesta `OK`.

El detalle de bloques y comandos está en `DspConfig.java` y en los comentarios de `MaxoXActivity.java`.

## Compilar

Abrir el proyecto en Android Studio, o:

```sh
./gradlew assembleDebug
```

Requiere Android 15 o más (minSdk 35).

En `tools/` hay herramientas de PC en Python: `dsp_client.py` se conecta al DSP real y `dsp_emulator.py` simula un DSP para probar la app. Se instalan con `pip install -r tools/requirements.txt`.

## Aviso

Proyecto independiente, **sin relación con Expert Electronics**. El protocolo se obtuvo estudiando la app oficial. Usalo bajo tu responsabilidad: un mal ajuste de crossover o de ganancia puede dañar parlantes.
