# Trazo

Cuaderno de notas para Android con rechazo de palma por software, pensado para lápices capacitivos genéricos en tablets como la OnePlus Pad 2.

## Cómo distingue la punta de la palma

Android da cuatro medidas por cada toque: tamaño (`touchMajor`), área (`size`), presión (`pressure`) y tamaño de herramienta (`toolMajor`). Cada pantalla informa de unas u otras. La calibración mide tu lápiz y tu palma, y elige la medida que mejor las separa.

Además del filtro por tamaño, el cuaderno aplica estas reglas:

- **Un solo trazo a la vez.** Si en los primeros 200 ms aparece un contacto más hacia el lado de la punta, se entiende que el primero era el borde de la mano y se cambia al nuevo.
- **Zona de la mano.** Mientras la palma está apoyada, y medio segundo después de levantarla, se ignoran los toques pegados a ella o en el lado donde queda la mano.
- **Borde de entrada.** Si un trazo empezó justo donde aparece la palma, se descarta.
- **Cancelaciones del sistema.** Se respetan las que envíe Android.
- **Stylus activos.** Los lápices que Android reconoce como stylus activo se aceptan siempre.

## Funciones

- Varias notas con páginas, en papel rayado, cuadriculado o liso.
- Lápiz, marcador y borrador de trazos, con 5 colores y 3 grosores.
- Deshacer y rehacer.
- Exportar la página como PNG (a Imágenes/Trazo) o la nota completa como PDF (a Descargas/Trazo).
- Modo inmersivo, y bloqueo del gesto de volver en la parte baja de los bordes para que no lo active la palma.
- Tema claro y oscuro.

## Primer uso

1. Abre **Calibrar**.
2. Pulsa **Medir punta del lápiz**, escribe y toca varias veces sin apoyar la mano, y pulsa **Terminar medición**.
3. Pulsa **Medir palma**, apóyala y levántala varias veces como cuando escribes, y pulsa **Terminar medición**.
4. Comprueba el resultado en el recuadro: el lápiz debe salir en azul y la palma en rojo. Si hace falta, ajusta el umbral a mano.
5. Elige la mano con la que escribes.

Si ninguna medida separa la punta de la palma, el filtro por tamaño queda desactivado y siguen funcionando las demás reglas.

## Requisitos

Android 11 o superior.
