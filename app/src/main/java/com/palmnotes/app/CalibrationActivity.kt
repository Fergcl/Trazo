package com.palmnotes.app

import android.app.Activity
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

class CalibrationActivity : Activity() {

    private lateinit var prefs: Prefs

    private var mode = 0 // 0 nada, 1 punta, 2 palma
    private val penSamples = ArrayList<FloatArray>()
    private val palmSamples = ArrayList<FloatArray>()
    private val live = HashMap<Int, FloatArray>()
    private val liveTool = HashMap<Int, Int>()
    private val livePos = HashMap<Int, FloatArray>()
    private val gestureMax = FloatArray(5)
    private var gestureSimul = 0
    private var maxSplit = 1
    private val first = FloatArray(5) { Float.NaN }
    private val varies = BooleanArray(5)
    private var sawStylus = false

    private lateinit var readout: TextView
    private lateinit var sensors: TextView
    private lateinit var hint: TextView
    private lateinit var results: TextView
    private lateinit var thrLabel: TextView
    private lateinit var seek: SeekBar
    private lateinit var pad: PadView
    private lateinit var measPen: TextView
    private lateinit var measPalm: TextView
    private lateinit var measStop: TextView
    private lateinit var handR: TextView
    private lateinit var handL: TextView
    private lateinit var showRej: TextView
    private val metricBtns = ArrayList<TextView>()
    private val zoneBtns = ArrayList<TextView>()
    private lateinit var logView: TextView
    private val logLines = ArrayDeque<String>()
    private var lastMoveLog = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(col) }
        scroll.padForSystemBars(dpi(20f), dpi(20f), dpi(20f), dpi(20f))

        col.addGap(label("Calibrar el lápiz", 26f, bold = true), 4f)
        col.addGap(
            label(
                "La app compara lo que la pantalla informa de cada toque. Mide primero la punta del lápiz " +
                    "y después la palma; se elegirá la medida que mejor las separa.",
                15f, muted = true,
            ),
            12f,
        )

        readout = label("Toca el recuadro para ver las medidas", 16f, bold = true)
        col.addGap(readout, 4f)
        sensors = label("", 14f, muted = true)
        col.addGap(sensors, 8f)

        pad = PadView()
        col.addGap(pad.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(300f))
        }, 8f)
        hint = label("", 15f)
        col.addGap(hint, 8f)
        col.addGap(
            label(
                "Prueba clave: apoya la palma y, sin levantarla, escribe con el lápiz. " +
                    "Deberías ver dos círculos y 2 contactos en el registro.",
                14f, muted = true,
            ),
            4f,
        )
        logView = label("Registro de eventos vacío", 13f, muted = true).apply {
            typeface = android.graphics.Typeface.MONOSPACE
        }
        col.addGap(logView, 8f)

        val measureRow = row()
        measPen = button("Medir punta del lápiz") { startMeasure(1) }
        measPalm = button("Medir palma") { startMeasure(2) }
        measStop = button("Terminar medición") { stopMeasure() }
        pill(measStop, true)
        measureRow.addGap(measPen)
        measureRow.addGap(measPalm)
        measureRow.addGap(measStop)
        col.addGap(measureRow, 12f)

        results = label("", 15f)
        col.addGap(results, 16f)

        col.addGap(label("Medida usada para detectar la palma", 16f, bold = true), 6f)
        val metricRow = row()
        for (m in 0..4) {
            val b = button(Metrics.names[m]) { chooseMetric(m) }
            metricBtns.add(b)
            metricRow.addGap(b, 4f)
        }
        col.addGap(metricRow, 10f)

        thrLabel = label("", 15f)
        col.addGap(thrLabel, 4f)
        seek = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser || prefs.metric == Metrics.NONE) return
                    prefs.threshold = max(0.5f, progress / 1000f * Metrics.maxRange[prefs.metric])
                    updateThrLabel()
                    pad.invalidate()
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        col.addGap(seek, 16f)

        col.addGap(label("Mano con la que escribes", 16f, bold = true), 6f)
        val handRow = row()
        handR = button("Derecha") { prefs.rightHanded = true; refresh() }
        handL = button("Izquierda") { prefs.rightHanded = false; refresh() }
        handRow.addGap(handR)
        handRow.addGap(handL)
        col.addGap(handRow, 16f)

        col.addGap(label("Zona ignorada alrededor de la palma", 16f, bold = true), 6f)
        val zoneRow = row()
        listOf("Normal", "Reducida", "Desactivada").forEachIndexed { i, n ->
            val b = button(n) { prefs.zoneMode = i; refresh() }
            zoneBtns.add(b)
            zoneRow.addGap(b, 4f)
        }
        col.addGap(zoneRow, 4f)
        col.addGap(
            label("Si el lápiz no escribe cerca de la mano apoyada, prueba Reducida o Desactivada.", 14f, muted = true),
            16f,
        )

        showRej = button("Mostrar toques ignorados en el cuaderno") {
            prefs.showRejected = !prefs.showRejected
            refresh()
        }
        col.addGap(showRej, 20f)

        val done = button("Hecho") { finish() }
        pill(done, true)
        col.addGap(done, 0f)

        setContentView(scroll)
        refresh()
        updateHint()
        showResultsSummary()
    }

    // ---------- recuadro de prueba ----------

    inner class PadView : View(this@CalibrationActivity) {
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpi(1.5f).toFloat()
            pathEffect = DashPathEffect(floatArrayOf(dpi(8f).toFloat(), dpi(6f).toFloat()), 0f)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpi(2f).toFloat()
        }

        init {
            contentDescription = "Zona de prueba táctil"
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(context.getColor(R.color.paper))
            border.color = context.getColor(R.color.muted)
            val inset = border.strokeWidth
            canvas.drawRect(inset, inset, width - inset, height - inset, border)
            for (pos in livePos.values) {
                val c = context.getColor(if (pos[3] > 0f) R.color.palm else R.color.ink)
                val r = max(pos[2] / 2f, dpi(16f).toFloat())
                fill.color = c
                fill.alpha = 60
                ring.color = c
                canvas.drawCircle(pos[0], pos[1], r, fill)
                canvas.drawCircle(pos[0], pos[1], r, ring)
            }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            parent?.requestDisallowInterceptTouchEvent(true)
            handle(event)
            invalidate()
            return true
        }
    }

    private fun logEvent(ev: MotionEvent) {
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_MOVE) {
            val now = ev.eventTime
            if (now - lastMoveLog < 400) return
            lastMoveLog = now
        }
        val name = when (a) {
            MotionEvent.ACTION_DOWN -> "DOWN"
            MotionEvent.ACTION_POINTER_DOWN -> "POINTER_DOWN"
            MotionEvent.ACTION_MOVE -> "MOVE"
            MotionEvent.ACTION_POINTER_UP -> "POINTER_UP"
            MotionEvent.ACTION_UP -> "UP"
            MotionEvent.ACTION_CANCEL -> "CANCEL"
            else -> "otro($a)"
        }
        val cancelled = android.os.Build.VERSION.SDK_INT >= 33 &&
            (ev.flags and MotionEvent.FLAG_CANCELED) != 0
        val tools = (0 until ev.pointerCount).joinToString(" ") { i ->
            val t = when (ev.getToolType(i)) {
                MotionEvent.TOOL_TYPE_STYLUS -> "L"
                MotionEvent.TOOL_TYPE_FINGER -> "D"
                else -> "?"
            }
            t + String.format(Locale.getDefault(), "%.0f", Metrics.read(ev, i, -1, Metrics.TOUCH))
        }
        val line = "$name  contactos=${ev.pointerCount}  [$tools]" + if (cancelled) "  CANCELADO" else ""
        logLines.addFirst(line)
        while (logLines.size > 8) logLines.removeLast()
        logView.text = logLines.joinToString("\n")
    }

    private fun handle(ev: MotionEvent) {
        logEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureMax.fill(0f)
                gestureSimul = 0
                track(ev, ev.actionIndex, true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> track(ev, ev.actionIndex, true)
            MotionEvent.ACTION_MOVE -> for (i in 0 until ev.pointerCount) track(ev, i, false)
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val i = ev.actionIndex
                track(ev, i, false)
                release(ev.getPointerId(i), ev.actionMasked == MotionEvent.ACTION_UP)
            }
            MotionEvent.ACTION_CANCEL -> {
                live.clear()
                liveTool.clear()
                livePos.clear()
                gestureSimul = 0
            }
        }
    }

    private fun track(ev: MotionEvent, i: Int, isDown: Boolean) {
        val id = ev.getPointerId(i)
        val tool = ev.getToolType(i)
        if (isDown) {
            live[id] = FloatArray(5)
            liveTool[id] = tool
            gestureSimul = max(gestureSimul, live.size)
            if (tool == MotionEvent.TOOL_TYPE_STYLUS) sawStylus = true
        }
        val arr = live[id] ?: return
        for (m in 1..4) {
            val v = Metrics.read(ev, i, -1, m)
            if (v > arr[m]) arr[m] = v
            if (v > gestureMax[m]) gestureMax[m] = v
            if (first[m].isNaN()) first[m] = v else if (abs(v - first[m]) > 0.001f) varies[m] = true
        }
        val finger = tool == MotionEvent.TOOL_TYPE_FINGER || tool == MotionEvent.TOOL_TYPE_UNKNOWN
        val cur = Metrics.read(ev, i, -1, prefs.metric)
        val palm = finger && prefs.metric != Metrics.NONE && cur > prefs.threshold
        livePos[id] = floatArrayOf(ev.getX(i), ev.getY(i), ev.getTouchMajor(i), if (palm) 1f else 0f)

        val toolName = when (tool) {
            MotionEvent.TOOL_TYPE_STYLUS -> "lápiz activo"
            MotionEvent.TOOL_TYPE_ERASER -> "borrador del lápiz"
            MotionEvent.TOOL_TYPE_MOUSE -> "ratón"
            MotionEvent.TOOL_TYPE_FINGER -> "dedo o lápiz pasivo"
            else -> "desconocido"
        }
        readout.text = String.format(
            Locale.getDefault(),
            "Tamaño %.1f   Área %.0f   Presión %.0f   Herramienta %.1f   (%s)",
            ev.getTouchMajor(i), ev.getSize(i) * 1000f, ev.getPressure(i) * 1000f, ev.getToolMajor(i), toolName,
        )
        updateSensors()
    }

    private fun release(id: Int, last: Boolean) {
        val arr = live.remove(id)
        val tool = liveTool.remove(id)
        livePos.remove(id)
        val finger = tool == MotionEvent.TOOL_TYPE_FINGER || tool == MotionEvent.TOOL_TYPE_UNKNOWN
        if (arr != null && mode == 1 && finger) penSamples.add(arr)
        if (last) {
            if (mode == 2) {
                palmSamples.add(gestureMax.copyOf())
                maxSplit = max(maxSplit, gestureSimul)
            }
            gestureSimul = 0
        }
        updateHint()
    }

    private fun updateSensors() {
        val names = (1..4).filter { varies[it] }.map { Metrics.names[it] }
        val base = if (names.isEmpty()) {
            "Todavía ninguna medida ha cambiado. Prueba con la palma y con el lápiz."
        } else {
            "Medidas que cambian en esta pantalla: " + names.joinToString(", ") + "."
        }
        sensors.text = if (sawStylus) {
            "$base Android reconoce tu lápiz como stylus activo, así que el cuaderno lo acepta siempre."
        } else {
            base
        }
    }

    // ---------- medición ----------

    private fun startMeasure(m: Int) {
        mode = m
        if (m == 1) penSamples.clear() else {
            palmSamples.clear()
            maxSplit = 1
        }
        refresh()
        updateHint()
    }

    private fun stopMeasure() {
        mode = 0
        compute()
        refresh()
        updateHint()
    }

    private fun updateHint() {
        hint.text = when (mode) {
            1 -> "Escribe y toca con el lápiz varias veces sin apoyar la mano. Muestras: ${penSamples.size}"
            2 -> "Apoya la palma como cuando escribes y levántala, varias veces. Muestras: ${palmSamples.size}"
            else -> "Azul: se acepta como lápiz. Rojo: se ignora como palma."
        }
    }

    private fun pct(values: List<Float>, q: Float): Float {
        val s = values.sorted()
        return s[((s.size - 1) * q).roundToInt().coerceIn(0, s.size - 1)]
    }

    private fun fmt(v: Float) = String.format(Locale.getDefault(), if (v < 10f) "%.2f" else "%.0f", v)

    private fun compute() {
        if (penSamples.size < 3 || palmSamples.size < 3) {
            results.text = "Faltan muestras: mide la punta y la palma al menos 3 veces cada una " +
                "(punta: ${penSamples.size}, palma: ${palmSamples.size})."
            return
        }
        var best = Metrics.NONE
        var bestScore = 0f
        var bestThr = 0f
        val lines = StringBuilder()
        for (m in 1..4) {
            val penHi = pct(penSamples.map { it[m] }, 0.95f)
            val palmLo = pct(palmSamples.map { it[m] }, 0.10f)
            val score = when {
                palmLo <= 0f -> 0f
                penHi <= 0f -> 99f
                else -> palmLo / penHi
            }
            val ok = score >= 1.15f
            lines.append("${Metrics.names[m]}: punta hasta ${fmt(penHi)}, palma desde ${fmt(palmLo)}")
            lines.append(if (ok) ". Separa bien.\n" else ". No separa.\n")
            if (ok) {
                val thr = if (penHi <= 0f) palmLo / 2f else sqrt(penHi * palmLo)
                prefs.setSuggested(m, thr)
                if (score > bestScore) {
                    best = m
                    bestScore = score
                    bestThr = thr
                }
            }
        }
        prefs.metric = best
        if (best != Metrics.NONE) prefs.threshold = bestThr
        prefs.calibrated = true

        val summary = if (best == Metrics.NONE) {
            "Ninguna medida distingue la punta de la palma en esta pantalla. El filtro por tamaño queda " +
                "desactivado; siguen funcionando las reglas de un solo trazo y de zona de la mano."
        } else {
            "Se usará ${Metrics.names[best]} con umbral ${fmt(bestThr)}. Pruébalo en el recuadro: " +
                "la palma debería salir en rojo y el lápiz en azul."
        }
        val split = if (maxSplit > 1) {
            "\nTu pantalla divide la palma en hasta $maxSplit contactos; la regla de un solo trazo ayuda con eso."
        } else ""
        results.text = "$summary$split\n\n$lines".trimEnd()
    }

    private fun showResultsSummary() {
        if (!prefs.calibrated) return
        results.text = if (prefs.metric == Metrics.NONE) {
            "Calibración guardada: filtro por tamaño desactivado."
        } else {
            "Calibración guardada: ${Metrics.names[prefs.metric]} con umbral ${fmt(prefs.threshold)}."
        }
    }

    // ---------- ajustes ----------

    private fun chooseMetric(m: Int) {
        prefs.metric = m
        if (m != Metrics.NONE) {
            val s = prefs.suggested(m)
            prefs.threshold = if (s > 0f) s else Metrics.maxRange[m] * 0.15f
        }
        refresh()
        pad.invalidate()
    }

    private fun updateThrLabel() {
        val m = prefs.metric
        thrLabel.text = if (m == Metrics.NONE) {
            "El filtro por tamaño está desactivado."
        } else {
            "Umbral de ${Metrics.names[m]}: ${fmt(prefs.threshold)}. Por encima se considera palma."
        }
    }

    private fun refresh() {
        val m = prefs.metric
        metricBtns.forEachIndexed { i, b -> pill(b, i == m) }
        seek.isEnabled = m != Metrics.NONE
        if (m != Metrics.NONE) {
            seek.progress = (prefs.threshold / Metrics.maxRange[m] * 1000f).roundToInt().coerceIn(0, 1000)
        }
        updateThrLabel()
        pill(handR, prefs.rightHanded)
        pill(handL, !prefs.rightHanded)
        pill(showRej, prefs.showRejected)
        zoneBtns.forEachIndexed { i, b -> pill(b, i == prefs.zoneMode) }
        measPen.visibility = if (mode == 0) View.VISIBLE else View.GONE
        measPalm.visibility = if (mode == 0) View.VISIBLE else View.GONE
        measStop.visibility = if (mode != 0) View.VISIBLE else View.GONE
        updateSensors()
    }
}
