---
name: Probé con otro modelo
about: Contanos cómo anduvo la app con un DSP Expert que no sea el PX 201
title: "Modelo: <nombre del equipo>"
labels: nuevo-modelo
---

**Equipo**

- Modelo (como figura en la caja o en la app oficial):
- Nombre con el que aparece al buscar por Bluetooth:
- Número de modelo y preset que muestra la app al conectar (`Modelo N · Preset N`):

**Qué anduvo y qué no**

- [ ] Conecta y carga la configuración
- [ ] Ganancias y mute
- [ ] Routing, fase y delay
- [ ] Crossover
- [ ] EQ
- [ ] Limiter

**Log**

Con el celu conectado a la PC por `adb`, conectá el DSP desde la app y pegá la salida de:

```sh
adb logcat -s XLinkDsp
```

Si no usás `adb`, abrí el panel ☰ de la app y copiá el log técnico.

> El log muestra la password como `[0][****]`, pero revisalo antes de pegarlo.

**Algo más**

Cualquier diferencia con lo que hace la app oficial.
