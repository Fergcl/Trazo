package com.palmnotes.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lienzo de una página. Decide qué toques son el lápiz con cuatro reglas:
 *  1. Tamaño: un contacto que supera el umbral (en la medida calibrada) es palma.
 *     Si un trazo empieza pequeño y crece, se deshace.
 *  2. Un solo trazo a la vez. Si el trazo activo está quieto (un trozo de palma que
 *     no llegó al umbral) y otro contacto más hacia el lado de la punta aparece o
 *     empieza a moverse, se entiende que el nuevo es el lápiz y se cambia a él.
 *  3. Zona de la mano: con una palma apoyada (o recién levantada) se ignoran los
 *     toques pegados a ella o en el lado donde queda la mano.
 *  4. Borde de entrada: si un trazo empezó hace menos de 200 ms justo en el
 *     sitio donde aparece una palma, se descarta.
 * Los lápices que el sistema reconoce como stylus se aceptan siempre, y se
 * respetan las cancelaciones que envíe el propio sistema.
 */
class NoteView(context: Context, private val prefs: Prefs) : View(context) {

    interface Listener {
        fun onStrokeAdded(stroke: Stroke)
        fun onErased(items: List<Pair<Int, Stroke>>)
        fun onPalmChanged(active: Boolean)
    }

    var listener: Listener? = null

    var strokes: MutableList<Stroke> = ArrayList()
        set(value) {
            cancelAll()
            field = value
            redrawAll()
        }

    var paper = PAPER_LINES
        set(value) {
            field = value
            invalidate()
        }

    var tool = TOOL_PEN
    var colorIndex = 0
    var widthDp = 2.4f

    private val dp = resources.displayMetrics.density
    private var bmp: Bitmap? = null
    private var bmpCanvas: Canvas? = null
    private var pageW = 1f

    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rulePaint = Paint().apply { strokeWidth = max(1f, dp * 0.6f) }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * dp
    }
    private val path = Path()

    private class Ptr(
        val id: Int,
        val x0: Float,
        val y0: Float,
        val t0: Long,
        val trusted: Boolean,
        val stylusEraser: Boolean,
    ) {
        var x = x0
        var y = y0
        var lastT = t0
        var ink = false
        var palm = false
        var travel = 0f          // distancia máxima recorrida desde el inicio
        var candidate = false    // rechazado solo por haber otro trazo activo
        var stroke: Stroke? = null
        var erased: MutableList<Pair<Int, Stroke>>? = null
    }

    private class Palm(var x: Float, var y: Float, var r: Float)
    private class Ring(val x: Float, val y: Float, val r: Float, val t: Long)

    private val pointers = HashMap<Int, Ptr>()
    private val palms = HashMap<Int, Palm>()
    private var lastPalm: Palm? = null
    private var lastPalmT = 0L
    private var activeInk = -1
    private val rings = ArrayList<Ring>()

    // ---------- tamaño y dibujo ----------

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pageW = max(1, w).toFloat()
        if (w > 0 && h > 0) {
            bmp?.recycle()
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp = b
            bmpCanvas = Canvas(b)
            redrawAll()
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // evita que la palma en el borde active el gesto de volver
        val w = right - left
        val h = bottom - top
        val band = (48 * dp).toInt()
        val tall = (200 * dp).toInt()
        val bottomY = h - (24 * dp).toInt()
        val topY = max(0, bottomY - tall)
        systemGestureExclusionRects = listOf(
            Rect(0, topY, band, bottomY),
            Rect(w - band, topY, w, bottomY),
        )
    }

    fun redrawAll() {
        val c = bmpCanvas
        if (c != null) {
            c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            for (s in strokes) drawStroke(c, s, pageW)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(context.getColor(R.color.paper))
        drawPaper(canvas, width, height)
        bmp?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (activeInk != -1) pointers[activeInk]?.stroke?.let { drawStroke(canvas, it, pageW) }
        drawFx(canvas)
    }

    private fun drawPaper(c: Canvas, w: Int, h: Int) {
        rulePaint.color = context.getColor(R.color.rule)
        when (paper) {
            PAPER_LINES -> {
                val step = 32 * dp
                var y = step
                while (y < h) { c.drawLine(0f, y, w.toFloat(), y, rulePaint); y += step }
            }
            PAPER_GRID -> {
                val step = 24 * dp
                var y = step
                while (y < h) { c.drawLine(0f, y, w.toFloat(), y, rulePaint); y += step }
                var x = step
                while (x < w) { c.drawLine(x, 0f, x, h.toFloat(), rulePaint); x += step }
            }
        }
    }

    /** Dibuja una página completa (para exportar). */
    fun drawPageTo(c: Canvas, list: List<Stroke>, w: Int, h: Int, pagePaper: Int) {
        c.drawColor(context.getColor(R.color.paper))
        val keep = paper
        paper = pagePaper
        drawPaper(c, w, h)
        paper = keep
        for (s in list) drawStroke(c, s, w.toFloat())
    }

    private fun drawStroke(c: Canvas, s: Stroke, scale: Float) {
        val n = s.size
        if (n == 0) return
        val col = Palette.color(context, s.color)
        val baseW = s.width * scale
        if (s.highlighter) {
            inkPaint.color = col
            inkPaint.alpha = 90
            inkPaint.strokeWidth = baseW
            path.reset()
            path.moveTo(s.x(0) * scale, s.y(0) * scale)
            if (n == 1) path.lineTo(s.x(0) * scale + 0.5f, s.y(0) * scale)
            for (i in 1 until n) {
                val mx = (s.x(i - 1) + s.x(i)) / 2 * scale
                val my = (s.y(i - 1) + s.y(i)) / 2 * scale
                path.quadTo(s.x(i - 1) * scale, s.y(i - 1) * scale, mx, my)
            }
            if (n > 1) path.lineTo(s.x(n - 1) * scale, s.y(n - 1) * scale)
            c.drawPath(path, inkPaint)
            inkPaint.alpha = 255
            return
        }
        if (n == 1) {
            fillPaint.color = col
            c.drawCircle(s.x(0) * scale, s.y(0) * scale, baseW / 2, fillPaint)
            return
        }
        inkPaint.color = col
        var px = s.x(0) * scale
        var py = s.y(0) * scale
        for (i in 1 until n) {
            val ax = s.x(i - 1) * scale
            val ay = s.y(i - 1) * scale
            val mx = (ax + s.x(i) * scale) / 2
            val my = (ay + s.y(i) * scale) / 2
            inkPaint.strokeWidth = baseW * s.w(i)
            path.reset()
            path.moveTo(px, py)
            path.quadTo(ax, ay, mx, my)
            c.drawPath(path, inkPaint)
            px = mx
            py = my
        }
        path.reset()
        path.moveTo(px, py)
        path.lineTo(s.x(n - 1) * scale, s.y(n - 1) * scale)
        c.drawPath(path, inkPaint)
    }

    private fun drawFx(canvas: Canvas) {
        if (!prefs.showRejected) {
            rings.clear()
            return
        }
        val now = SystemClock.uptimeMillis()
        val col = context.getColor(R.color.palm)
        var animate = false
        val it = rings.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val age = now - r.t
            if (age > 700) { it.remove(); continue }
            ringPaint.color = col
            ringPaint.alpha = (150 * (1f - age / 700f)).toInt()
            canvas.drawCircle(r.x, r.y, r.r, ringPaint)
            animate = true
        }
        if (palms.isNotEmpty()) {
            fillPaint.color = col
            fillPaint.alpha = 36
            for (p in palms.values) canvas.drawCircle(p.x, p.y, p.r, fillPaint)
            fillPaint.alpha = 255
        }
        if (animate) postInvalidateOnAnimation()
    }

    // ---------- entrada táctil ----------

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(ev)
                onDown(ev, ev.actionIndex)
            }
            MotionEvent.ACTION_POINTER_DOWN -> onDown(ev, ev.actionIndex)
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until ev.pointerCount) {
                    val p = pointers[ev.getPointerId(i)] ?: continue
                    for (h in 0 until ev.historySize) {
                        onMove(
                            p, ev.getHistoricalX(i, h), ev.getHistoricalY(i, h),
                            metricOf(ev, i, h), ev.getHistoricalTouchMajor(i, h), ev.getHistoricalEventTime(h),
                        )
                    }
                    onMove(p, ev.getX(i), ev.getY(i), metricOf(ev, i, -1), ev.getTouchMajor(i), ev.eventTime)
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                val cancelled = Build.VERSION.SDK_INT >= 33 &&
                    (ev.flags and MotionEvent.FLAG_CANCELED) != 0
                onUp(ev.getPointerId(ev.actionIndex), cancelled)
            }
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun cancelAll() {
        for (id in pointers.keys.toList()) onUp(id, true)
    }

    private fun metricOf(ev: MotionEvent, i: Int, h: Int): Float = Metrics.read(ev, i, h, prefs.metric)

    private fun isPalmMetric(m: Float) = prefs.metric != Metrics.NONE && m > prefs.threshold

    private fun ringR(major: Float) = max(major / 2f, 12 * dp)

    private fun onDown(ev: MotionEvent, i: Int) {
        val id = ev.getPointerId(i)
        val tt = ev.getToolType(i)
        val trusted = tt == MotionEvent.TOOL_TYPE_STYLUS ||
            tt == MotionEvent.TOOL_TYPE_ERASER ||
            tt == MotionEvent.TOOL_TYPE_MOUSE
        val x = ev.getX(i)
        val y = ev.getY(i)
        val p = Ptr(id, x, y, ev.eventTime, trusted, tt == MotionEvent.TOOL_TYPE_ERASER)
        pointers[id] = p
        val major = ev.getTouchMajor(i)

        if (!trusted && isPalmMetric(metricOf(ev, i, -1))) {
            becomePalm(p, major)
            return
        }
        if (activeInk != -1) {
            val a = pointers[activeInk]
            if (a != null && !trusted && shouldSwap(a, p, recent = p.t0 - a.t0 < 200)) {
                rollback(a)
                addRing(a.x0, a.y0, 14 * dp)
            } else {
                // puede ser el lápiz; se vuelve a evaluar cuando se mueva
                p.candidate = !trusted
                addRing(x, y, ringR(major))
                return
            }
        }
        if (!trusted && palmZoneHit(x, y)) {
            addRing(x, y, ringR(major))
            return
        }
        accept(p, x, y)
    }

    /** ¿El contacto nuevo [p] es más probablemente el lápiz que el trazo activo [a]? */
    private fun shouldSwap(a: Ptr, p: Ptr, recent: Boolean): Boolean {
        if (a.trusted) return false
        if (penScore(p) <= penScore(a) + 8 * dp) return false
        val still = a.travel < 6 * dp
        val nearHand = nearPalm(a.x, a.y)
        val closeStart = recent && hypot(p.x0 - a.x0, p.y0 - a.y0) < 220 * dp
        return still || nearHand || closeStart
    }

    private fun nearPalm(x: Float, y: Float): Boolean {
        for (z in palms.values) if (hypot(x - z.x, y - z.y) < z.r * 1.3f + 30 * dp) return true
        return false
    }

    private fun accept(p: Ptr, sx: Float, sy: Float) {
        p.candidate = false
        p.ink = true
        activeInk = p.id
        if (tool == TOOL_ERASER || p.stylusEraser) {
            p.erased = ArrayList()
            eraseAt(sx, sy, p)
        } else {
            val hl = tool == TOOL_HIGHLIGHTER
            val wpx = if (hl) widthDp * 6f * dp else widthDp * dp
            val c = if (hl && colorIndex == 0) Palette.HIGHLIGHT else colorIndex
            val s = Stroke(c, wpx / pageW, hl)
            s.add(sx / pageW, sy / pageW, 1f)
            p.lastT = SystemClock.uptimeMillis()
            p.stroke = s
        }
        invalidate()
    }

    private fun onMove(p: Ptr, x: Float, y: Float, m: Float, major: Float, t: Long) {
        p.x = x
        p.y = y
        p.travel = max(p.travel, hypot(x - p.x0, y - p.y0))
        if (p.palm) {
            palms[p.id]?.let {
                it.x = x
                it.y = y
                it.r = max(it.r, palmRadius(major))
            }
            return
        }
        if (!p.trusted && isPalmMetric(m)) {
            becomePalm(p, major)
            return
        }
        if (!p.ink) {
            if (p.candidate && p.travel > 6 * dp) promoteCandidate(p, x, y)
            return
        }
        if (p.erased != null) {
            eraseAt(x, y, p)
        } else {
            p.stroke?.let { addPoint(p, it, x, y, t) }
        }
    }

    /** Un contacto rechazado por haber otro trazo activo empieza a escribir. */
    private fun promoteCandidate(p: Ptr, x: Float, y: Float) {
        val a = if (activeInk != -1) pointers[activeInk] else null
        if (a != null) {
            if (a.trusted || a.travel >= 6 * dp) return
            if (!(nearPalm(a.x, a.y) || penScore(p) > penScore(a))) return
            rollback(a)
            addRing(a.x0, a.y0, 14 * dp)
        }
        if (palmZoneHit(x, y)) return
        accept(p, x, y)
    }

    private fun palmRadius(major: Float) = (major / 2f).coerceIn(30 * dp, 70 * dp)

    private fun addPoint(p: Ptr, s: Stroke, x: Float, y: Float, t: Long) {
        val n = s.size
        val lx = s.x(n - 1) * pageW
        val ly = s.y(n - 1) * pageW
        val d = hypot(x - lx, y - ly)
        if (d < 0.6f * dp) return
        val dt = max(1L, t - p.lastT).toFloat()
        p.lastT = t
        val speed = d / dp / dt
        val target = if (s.highlighter) 1f else 1.15f - min(0.45f, speed * 0.18f)
        val lw = s.w(n - 1)
        s.add(x / pageW, y / pageW, lw + (target - lw) * 0.3f)
    }

    private fun onUp(id: Int, cancelled: Boolean) {
        val p = pointers.remove(id) ?: return
        if (p.palm) {
            palms.remove(id)?.let {
                lastPalm = it
                lastPalmT = SystemClock.uptimeMillis()
            }
            if (palms.isEmpty()) listener?.onPalmChanged(false)
        }
        if (!p.ink) {
            invalidate()
            return
        }
        if (cancelled) {
            rollback(p)
            addRing(p.x0, p.y0, 14 * dp)
            return
        }
        if (activeInk == id) activeInk = -1
        val er = p.erased
        if (er != null) {
            if (er.isNotEmpty()) listener?.onErased(er)
        } else {
            p.stroke?.let { s ->
                strokes.add(s)
                bmpCanvas?.let { drawStroke(it, s, pageW) }
                listener?.onStrokeAdded(s)
            }
        }
        invalidate()
    }

    private fun rollback(p: Ptr) {
        if (!p.ink) return
        p.ink = false
        p.erased?.let {
            restore(it)
            p.erased = null
        }
        p.stroke = null
        if (activeInk == p.id) activeInk = -1
        invalidate()
    }

    private fun becomePalm(p: Ptr, major: Float) {
        rollback(p)
        p.palm = true
        p.candidate = false
        val r = palmRadius(major)
        palms[p.id] = Palm(p.x, p.y, r)
        addRing(p.x, p.y, r)
        if (activeInk != -1) {
            val a = pointers[activeInk]
            val close = a != null && hypot(a.x - p.x, a.y - p.y) < r * 1.1f + 20 * dp
            val young = a != null && SystemClock.uptimeMillis() - a.t0 < 200
            if (a != null && !a.trusted && close && (young || a.travel < 6 * dp)) {
                rollback(a)
                addRing(a.x0, a.y0, 14 * dp)
            }
        }
        if (palms.size == 1) listener?.onPalmChanged(true)
    }

    private fun palmZoneHit(x: Float, y: Float): Boolean {
        val mode = prefs.zoneMode
        if (mode == 2) return false
        val zones = ArrayList<Palm>(palms.values)
        lastPalm?.let { if (SystemClock.uptimeMillis() - lastPalmT < 350) zones.add(it) }
        val k = if (mode == 1) 0.6f else 1f
        for (z in zones) {
            if (hypot(x - z.x, y - z.y) < (z.r * 0.9f + 12 * dp) * k) return true
            if (mode == 0) {
                // dedos que quedan hacia el lado de la mano y por debajo del centro de la palma
                val dx = if (prefs.rightHanded) x - z.x else z.x - x
                if (dx > 0f && y > z.y - z.r * 0.2f) return true
            }
        }
        return false
    }

    /** Cuanto más arriba y más hacia el lado contrario a la mano, más probable que sea la punta. */
    private fun penScore(p: Ptr): Float {
        val side = if (prefs.rightHanded) -p.x0 else p.x0
        return -p.y0 + side * 0.5f
    }

    private fun addRing(x: Float, y: Float, r: Float) {
        if (!prefs.showRejected) return
        rings.add(Ring(x, y, r, SystemClock.uptimeMillis()))
        postInvalidateOnAnimation()
    }

    // ---------- borrador ----------

    private fun eraseAt(x: Float, y: Float, p: Ptr) {
        val er = p.erased ?: return
        val radius = 14 * dp
        var changed = false
        var i = strokes.size - 1
        while (i >= 0) {
            val s = strokes[i]
            val tol = radius + s.width * pageW / 2
            var hit = false
            for (k in 0 until s.size) {
                if (hypot(s.x(k) * pageW - x, s.y(k) * pageW - y) < tol) { hit = true; break }
            }
            if (hit) {
                er.add(Pair(i, s))
                strokes.removeAt(i)
                changed = true
            }
            i--
        }
        if (changed) redrawAll()
    }

    private fun restore(items: List<Pair<Int, Stroke>>) {
        for (it in items.asReversed()) strokes.add(min(it.first, strokes.size), it.second)
        redrawAll()
    }
}
