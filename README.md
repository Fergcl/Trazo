# Trazo

Cuaderno de notas para Android con rechazo de palma por software, pensado para lápices capacitivos genéricos.

**[⬇ Descargar la última versión (APK)](../../releases/latest)**

> [!IMPORTANT]
> **Limitación comprobada en la OnePlus Pad 2 (OxygenOS 16):** con un lápiz pasivo **no se puede escribir con la palma apoyada**.
> Mientras detecta una palma, el controlador táctil deja de enviar *todos* los toques, tanto del lápiz como de los dedos. Ocurre en el firmware o el driver, por debajo de Android, así que ninguna app, módulo root ni ROM personalizada puede evitarlo.
> Se verificó leyendo los eventos en bruto del kernel con `getevent`: la palma aparece unos milisegundos, se da por levantada y después no llega ningún evento hasta que se retira. El registro completo está en [`Touchs.txt`](Touchs.txt) y los detalles en el [hilo de XDA](https://xdaforums.com/t/passive-stylus-palm-rejection-is-impossible-on-pad-2-touch-controller-blocks-the-whole-screen-getevent-proof.4801889/).
>
> **Lo que sí funciona:** un guante de dibujo de dos dedos, para que la palma no toque el cristal, o el OnePlus Stylo / Stylo 2 oficial.

> **English summary:** On the OnePlus Pad 2, the touch controller stops reporting *all* touches while a palm is detected, so passive-stylus palm rejection is impossible at any software level (verified with `getevent`, see [`Touchs.txt`](Touchs.txt) and the [XDA thread](https://xdaforums.com/t/passive-stylus-palm-rejection-is-impossible-on-pad-2-touch-controller-blocks-the-whole-screen-getevent-proof.4801889/)). Use a two-finger drawing glove or the official Stylo. Trazo still works well with a glove, and its calibration screen includes a touch event log to test other tablets.

## Para qué sirve entonces

- **En la OnePlus Pad 2:** como cuaderno con un guante de dos dedos. Las reglas de rechazo filtran los roces de los dedos.
- **En otras tablets:** puede funcionar con la palma apoyada si su pantalla no bloquea los toques. La pantalla de calibración incluye un registro de eventos para comprobarlo.

## Cómo distingue la punta de la palma

Android da cuatro medidas por cada toque: tamaño (`touchMajor`), área (`size`), presión (`pressure`) y tamaño de herramienta (`toolMajor`). Cada pantalla informa de unas u otras. La calibración mide tu lápiz y tu palma, y elige la medida que mejor las separa.

Además del filtro por tamaño, el cuaderno aplica estas reglas:

- **Un solo trazo a la vez.** Si el trazo activo está quieto (un trozo de palma que no llegó al umbral) y otro contacto más hacia el lado de la punta aparece o empieza a moverse, se entiende que el nuevo es el lápiz y se cambia a él.
- **Zona de la mano.** Mientras la palma está apoyada, y un momento después de levantarla, se ignoran los toques pegados a ella o en el lado donde queda la mano. Se puede poner en Normal, Reducida o Desactivada.
- **Borde de entrada.** Si un trazo recién empezado está quieto justo donde aparece la palma, se descarta.
- **Cancelaciones del sistema.** Se respetan las que envíe Android.
- **Stylus activos.** Los lápices que Android reconoce como stylus activo se aceptan siempre.

## Funciones

- Varias notas con páginas, en papel rayado, cuadriculado o liso.
- Lápiz, marcador y borrador de trazos, con 5 colores y 3 grosores.
- Deshacer y rehacer.
- Exportar la página como PNG (a Imágenes/Trazo) o la nota completa como PDF (a Descargas/Trazo).
- Modo inmersivo, y bloqueo del gesto de volver en la parte baja de los bordes para que no lo active la palma.
- Tema claro y oscuro.
- Pantalla de calibración con registro de eventos táctiles, que muestra contactos simultáneos, tamaño y cancelaciones del sistema.

## Instalación

1. Descarga `Trazo-v2.0.apk` desde [Releases](../../releases/latest).
2. Ábrelo en la tablet y permite la instalación desde esa fuente cuando Android lo pida.
3. Si Play Protect avisa de que la app es desconocida, pulsa **Instalar de todos modos**.

## Primer uso

1. Abre **Calibrar**.
2. Pulsa **Medir punta del lápiz**, escribe y toca varias veces sin apoyar la mano, y pulsa **Terminar medición**.
3. Pulsa **Medir palma**, apóyala y levántala varias veces como cuando escribes, y pulsa **Terminar medición**.
4. Comprueba el resultado en el recuadro: el lápiz debe salir en azul y la palma en rojo. Si hace falta, ajusta el umbral a mano.
5. Elige la mano con la que escribes y el tamaño de la zona ignorada.

Si ninguna medida separa la punta de la palma, elige **Desactivado**. Las demás reglas siguen funcionando.

## ¿Bloquea tu tablet los toques con la palma?

En **Calibrar**, apoya la palma en el recuadro y toca con el lápiz. Luego mira el registro:

- **Si aparece `POINTER_DOWN contactos=2`:** tu pantalla envía ambos contactos y el rechazo de palma puede funcionar.
- **Si solo ves un contacto, o un `CANCEL ... CANCELADO` al apoyar la mano, y después nada:** tu tablet bloquea los toques como la OnePlus Pad 2.

Para confirmarlo a nivel de kernel, sin root (activa antes la depuración USB):

```bash
adb shell "getevent -pl | grep -E 'add device|name:|ABS_MT_POSITION_X'"      # busca la pantalla táctil y sustituye eventN por el evento correcto, en mi caso era event6
adb shell "timeout 40 getevent -lt /dev/input/eventN > /sdcard/Download/Touchs.txt"
adb pull /sdcard/Download/Touchs.txt
```

Si mientras la palma está apoyada no aparecen nuevos `ABS_MT_TRACKING_ID` al tocar con el lápiz, el bloqueo está en el controlador táctil. En la OnePlus Pad 2 la pantalla táctil es `/dev/input/event6`.

## Compilar

El APK se compila automáticamente con GitHub Actions en cada subida. Entra en la pestaña **Actions**, abre la última ejecución en verde y descarga el artefacto **Trazo-apk**. También puedes abrir el proyecto con Android Studio.

## Requisitos

Android 11 o superior.
