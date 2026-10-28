# Protocolo de los DSP Expert Electronics

Cómo se habla con un DSP Expert por Bluetooth. Se reconstruyó estudiando la app oficial y se probó contra un **PX 201** (modelo 4). Está escrito para poder implementarlo en cualquier lenguaje, sin mirar el código de esta app.

Cada dato tiene su nivel de certeza:

- ✅ **verificado** con el PX 201;
- 🔧 **implementado**, falta confirmarlo con el equipo;
- ❓ **inferido**: sale de la app oficial o es una hipótesis.

La implementación de referencia está en el package [`dsp`](app/src/main/java/ar/mtorchio/xlinkdsp/dsp/).

---

## 1. Transporte: Bluetooth LE ✅

El DSP tiene un módulo BLE que funciona como un puerto serie: texto ASCII de ida y vuelta.

| | UUID |
|---|---|
| Servicio | `0000fff0-0000-1000-8000-00805f9b34fb` |
| Escritura (app → DSP) | `0000fff2-0000-1000-8000-00805f9b34fb` |
| Notificaciones (DSP → app) | `0000fff1-0000-1000-8000-00805f9b34fb` |
| CCCD de `fff1` | `00002902-0000-1000-8000-00805f9b34fb` |

- **Una operación GATT a la vez.** En Android, cada escritura tiene que esperar la confirmación de la anterior (`onCharacteristicWrite` / `onDescriptorWrite`). Si se mandan seguidas, se pierden.
- **Cada comando termina en `\r\n`.**
- **Cada respuesta llega con el prefijo `AT+LESEND=182,`** (eco del módulo BLE). Hay que sacarlo antes de interpretarla. ❓ El número parece el tamaño máximo de paquete, no la longitud real.
- **Una respuesta larga llega partida en varias notificaciones.** Hay que acumularlas en un buffer.
- **Si la app oficial está conectada, el DSP no aparece al escanear.**

### Nombres con los que se anuncia ❓

La app oficial considera DSP a un equipo cuyo nombre:

- contiene `AMP`, `X4`, `X6`, `X8`, `A4`, `A6`, `A8`, `B4`, `B6`, `B8`, `D4`, `D6`, `D8`, `S6`, `S8`, `PX 201`, `PX 202`, `PX 208`, `PX 101`, `PX 102`, `PX 108`, `PX 103`, `PX 203`, `PRO6P`, `PRO8` o `MXAiR`;
- empieza con `M8`, `mn` o `W8`;
- o es exactamente `P8`.

El PX 201 se anuncia como `PX 201` ✅.

---

## 2. Conexión y login ✅

```
1. Conectar GATT y descubrir servicios
2. Activar notificaciones en fff1 (escribir 01 00 en su CCCD)
3. App → DSP   AT+LESEND=0
4. App → DSP   [0][password]
5. DSP → App   AT+LESEND=182,[77][1][modelo][preset]     login aceptado
               AT+LESEND=182,[24]ER                      password inválida
```

- `AT+LESEND=0` es lo que la app oficial llama `CLEAN`, y siempre lo manda antes de la password. ❓ Probablemente limpia el estado del módulo o el buffer del DSP. No se probó qué pasa sin mandarlo.
- `modelo` es un número: el PX 201 contesta `4` ✅. La app oficial cambia rangos y formatos según este número (ver [sección 6](#6-diferencias-por-modelo-)).
- `preset` es el preset activo del DSP: el PX 201 contesta `1` ✅.
- Si en unos segundos no llega `[77][1]` ni `[24]`, la app da el login por fallido (8 s).

---

## 3. Lectura de la configuración: `a;` ✅

Al mandar `a;`, el DSP devuelve toda su configuración en varias notificaciones:

```
AT+LESEND=182,$#A[...]B[...]C[...]...#
AT+LESEND=182,#F[...]G[...]...#
AT+LESEND=182,#Q[...]R[...]X[...]Y[...]#$
```

Para interpretarla:

1. Sacar los prefijos `AT+LESEND=182,` y los saltos de línea.
2. La respuesta está completa cuando empieza y termina con `$`.
3. Adentro hay secciones separadas por `#`, con bloques `Letra[contenido]`. Mayúsculas y minúsculas son bloques distintos (`F` ≠ `f`).
4. Dentro de un bloque, los canales se separan con `!` y los valores de un canal con `;`.
5. Los decimales vienen con **coma** (`5,0`), los números con signo y ceros a la izquierda (`+03`, `-005`, `00080`).

### Bloques

Valores reales del PX 201: 6 canales, CH1 primero.

| Bloque | Qué es | Formato | PX 201 |
|---|---|---|---|
| `A` | EQ de entrada: ganancia por banda, 28 bandas | dB enteros, `!` por banda | `+00!+02!+03!…` |
| `B` | EQ por canal: Q de cada banda | `q;q;q;!` por canal | `5,0;5,0;5,0;!…` |
| `C` | EQ por canal: frecuencia de cada banda | Hz | `02000;08000;10000;!…` |
| `D` | EQ por canal: ganancia de cada banda | dB | `+03;+00;+04;!…` |
| `E` | Ganancia por canal | dB, −33 a +9 | `+00!+00!+05!-01!-04!-04!` |
| `F` | Delay por canal | **milímetros**, 0 a 6500 | `0000!…` |
| `G` | Limiter: umbral | dB, −54 a +6 | `+06!+06!-005!-005!-006!-006!` |
| `H` | Limiter: decay | ms, 1 a 990 | `60!90!160!160!220!220!` |
| `I` | Limiter: attack | ms, 1 a 200 | `4!6!10!10!14!14!` |
| `J` | Fase | `0` = 0°, `1` = 180° | `1!1!1!1!0!0!` |
| `K` | Mute | `1` = muteado | `0!0!1!1!0!0!` |
| `L` | Routing (entrada de cada salida) | `0` = L+R, `1` = R, `2` = L | `2!1!2!1!0!0!` |
| `N` | Crossover: frecuencia del pasa altos (HPF) | Hz | `00080!00080!00090!…` |
| `O` | Crossover: pendiente del HPF | texto (ver [pendientes](#pendientes-del-crossover)) | `12DBBTW!…` |
| `Q` | Crossover: frecuencia del pasa bajos (LPF) | Hz | `20000!20000!05000!…` |
| `R` | Crossover: pendiente del LPF | texto | `OFF!OFF!12DBBES!…` |
| `X` | Ganancia master | dB, −53 a 0 | `-20` |
| `Y` | ❓ Sin identificar | | `-53` |
| `f` | ❓ Sin identificar (¿delay de entrada?) | | `0` |

Todos los bloques de la tabla se leen bien del PX 201 ✅. Lo que no está verificado es el significado de `Y` y `f`.

### Frecuencias del EQ de entrada

El bloque `A` trae solo ganancias. Las frecuencias son fijas, en tercios de octava (serie PX):

```
31 40 50 63 80 100 125 160 200 250 315 400 500 630 800
1000 1250 1600 2000 2500 3150 4000 5000 6300 8000 10000 12500 16000
```

---

## 4. Escritura

Cada comando es una secuencia de campos entre corchetes. El DSP contesta `OK` a cada uno.

**Hay que esperar el `OK` antes de mandar el siguiente.** Si se mandan de corrido, el DSP descarta la mayoría. Si el `OK` no llega en 500 ms, la app sigue con el próximo y lo registra como "sin confirmar". El protocolo no tiene identificadores: un `OK` confirma el comando que está en curso.

Convenciones:

- **Canales desde 0**: CH1 = `0`, CH6 = `5`.
- `-1` en el lugar del canal = master (ganancia) o EQ de entrada.
- Valores enteros, salvo el Q.

### Niveles

| Comando | Qué hace | Rango | Estado |
|---|---|---|---|
| `[100][-1][dB]` | Ganancia master | −53 a 0 | ✅ |
| `[100][ch][dB]` | Ganancia de un canal | −33 a +9 | ✅ |
| `[999][ch][1\|0]` | Mute de un canal | | ✅ |

### Canales

| Comando | Qué hace | Valores | Estado |
|---|---|---|---|
| `[3][ch][v]` | Routing | `0` = L+R, `1` = R, `2` = L | ✅ |
| `[6][ch][v]` | Fase | `0` = 0°, `1` = 180° | ✅ |
| `[7][ch][mm]` | Delay, como distancia | 0 a 6500 mm (343 mm = 1 ms) | ✅ |

### EQ

| Comando | Qué hace | Rango | Estado |
|---|---|---|---|
| `[202][-1][Fn][dB]` | Ganancia de una banda del EQ de entrada, **n de 0 a 27** | ±12 | 🔧 |
| `[202][ch][Fn][dB]` | Ganancia de una banda del EQ de canal, **n de 1 a 3** | ±18 | 🔧 |
| `[203][ch][Fn][Hz]` | Frecuencia de una banda del EQ de canal | 20 a 20000 | 🔧 |
| `[201][ch][Fn][Q]` | Q de una banda del EQ de canal, **con punto decimal** (`5.0`) | 0.6 a 9.9 | 🔧 |

Ojo:

- **Numeración de bandas:** el EQ de entrada cuenta las bandas desde `F0`, y el de canal desde `F1`.
- **Separador decimal:** el volcado trae el Q con coma (`5,0`), pero la app oficial lo escribe con punto (`5.0`).

🔧 Un primer intento mandaba las 28 bandas sin esperar cada `OK` y el cambio no se escuchó. Con la espera del `OK` falta probarlo.

### Crossover

| Comando | Qué hace | Estado |
|---|---|---|
| `[2][ch][0][Hz]` | Frecuencia del HPF | 🔧 |
| `[2][ch][1][pendiente]` | Pendiente del HPF | 🔧 |
| `[2][ch][2][Hz]` | Frecuencia del LPF | 🔧 |
| `[2][ch][3][pendiente]` | Pendiente del LPF | 🔧 |

#### Pendientes del crossover

Se mandan y se leen como texto. Son las mismas para el HPF y el LPF.

| Código | Filtro |
|---|---|
| `OFF` | Sin filtro |
| `6DBBTW` `12DBBTW` `18DBBTW` `24DBBTW` | Butterworth de 6, 12, 18 y 24 dB/octava |
| `12DBLKR` `24DBLKR` `36DBLKR` `48DBLKR` | Linkwitz-Riley de 12, 24, 36 y 48 dB/octava |
| `12DBBES` `24DBBES` | Bessel de 12 y 24 dB/octava |

⚠️ Sacar el HPF (`OFF`) de un canal que alimenta un tweeter o un medio le manda graves y lo puede romper.

### Limiter

| Comando | Qué hace | Rango | Estado |
|---|---|---|---|
| `[103][ch][dB]` | Umbral, modo manual | −54 a +6 | 🔧 |
| `[113][ch][dB]` | Umbral, modo auto | −54 a +6 | 🔧 |
| `[102][ch][ms]` | Attack (solo manual) | 1 a 200 | 🔧 |
| `[104][ch][ms]` | Decay (solo manual) | 1 a 990 | 🔧 |

❓ **Modo auto.** Con auto, la app oficial manda el umbral con `[113]` y muestra attack y decay calculados desde el HPF del canal, sin mandarlos:

```
attack = 500 / HPF      (ms, entre 1 y 200)
decay  = attack × 16    (ms, entre 1 y 990)
```

Lo más probable es que el DSP haga la misma cuenta al recibir `[113]`.

---

## 5. Otros comandos ❓

| Comando | Qué hace | Estado |
|---|---|---|
| `V;` | Pide la tensión de alimentación | ❓ Falta capturar el formato real de la respuesta |

---

## 6. Diferencias por modelo ❓

La app oficial cambia algunos formatos según el número de modelo que devuelve el login. Ninguno de estos casos afecta al PX 201 (modelo 4), y ninguno está verificado:

- **Modelos 29, 30, 78 y 79:**
  - la ganancia por canal viaja en décimas de dB;
  - los valores del limiter llevan ceros a la izquierda (`[103][0][05]`, `[103][0][-05]`).
- **Modelos sin bloque `B`** (por ejemplo M8): el EQ de canal no trae Q; la app oficial asume 1.4.
- **Otros modelos:** pueden tener otra cantidad de canales, otras frecuencias en el EQ de entrada y otros rangos de delay.

Si tenés otro modelo de Expert, [abrí un issue](.github/ISSUE_TEMPLATE/nuevo-modelo.md) con lo que contesta tu equipo. Es la forma de ir sumando modelos.

---

## 7. Pendiente de investigar

- Si el DSP guarda los cambios en memoria al apagarse o solo los aplica en el momento.
- Qué son los bloques `Y` y `f`.
- La respuesta a `V;`.
- Si `AT+LESEND=0` hace falta.
- Cómo se cambia de preset.
