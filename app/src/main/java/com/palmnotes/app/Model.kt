package com.palmnotes.app

import android.content.Context
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.roundToInt

const val PAPER_LINES = 0
const val PAPER_GRID = 1
const val PAPER_BLANK = 2

const val TOOL_PEN = 0
const val TOOL_HIGHLIGHTER = 1
const val TOOL_ERASER = 2

/** Un trazo. Coordenadas y grosor normalizados por el ancho de la página. */
class Stroke(val color: Int, val width: Float, val highlighter: Boolean) {
    var pts = FloatArray(48)
        private set
    var size = 0
        private set

    fun add(x: Float, y: Float, w: Float) {
        if ((size + 1) * 3 > pts.size) pts = pts.copyOf(pts.size * 2)
        pts[size * 3] = x
        pts[size * 3 + 1] = y
        pts[size * 3 + 2] = w
        size++
    }

    fun x(i: Int) = pts[i * 3]
    fun y(i: Int) = pts[i * 3 + 1]
    fun w(i: Int) = pts[i * 3 + 2]
}

class Note(
    val id: String,
    var title: String,
    var paper: Int,
    val pages: MutableList<MutableList<Stroke>>,
    var updated: Long,
)

class NoteMeta(val id: String, val title: String, val updated: Long, val pageCount: Int)

object Palette {
    const val SHOWN = 5          // colores en la barra (0..4)
    const val HIGHLIGHT = 5      // amarillo del marcador
    val names = arrayOf("Grafito", "Azul", "Verde", "Naranja", "Rojo", "Amarillo")
    private val fixed = intArrayOf(0, 0, 0xFF1E8A5B.toInt(), 0xFFC2410C.toInt(), 0xFFC62828.toInt(), 0xFFF2B705.toInt())

    fun color(ctx: Context, i: Int): Int = when (i) {
        0 -> ctx.getColor(R.color.graphite)
        1 -> ctx.getColor(R.color.ink)
        in 2..5 -> fixed[i]
        else -> ctx.getColor(R.color.graphite)
    }
}

/** Las cuatro medidas de contacto que Android puede dar para un toque. */
object Metrics {
    const val NONE = 0
    const val TOUCH = 1
    const val AREA = 2
    const val PRESSURE = 3
    const val TOOL = 4
    val names = arrayOf("Desactivado", "Tamaño", "Área", "Presión", "Herramienta")
    val maxRange = floatArrayOf(1f, 400f, 1000f, 1000f, 400f)

    fun read(ev: MotionEvent, i: Int, h: Int, m: Int): Float = if (h < 0) {
        when (m) {
            TOUCH -> ev.getTouchMajor(i)
            AREA -> ev.getSize(i) * 1000f
            PRESSURE -> ev.getPressure(i) * 1000f
            TOOL -> ev.getToolMajor(i)
            else -> 0f
        }
    } else {
        when (m) {
            TOUCH -> ev.getHistoricalTouchMajor(i, h)
            AREA -> ev.getHistoricalSize(i, h) * 1000f
            PRESSURE -> ev.getHistoricalPressure(i, h) * 1000f
            TOOL -> ev.getHistoricalToolMajor(i, h)
            else -> 0f
        }
    }
}

object Storage {
    private val io = Executors.newSingleThreadExecutor()

    private fun dir(ctx: Context) = File(ctx.filesDir, "notes").apply { mkdirs() }

    fun newNote(title: String) = Note(
        UUID.randomUUID().toString(), title, PAPER_LINES,
        mutableListOf(mutableListOf()), System.currentTimeMillis(),
    )

    fun list(ctx: Context): List<NoteMeta> {
        val out = ArrayList<NoteMeta>()
        dir(ctx).listFiles { f -> f.name.endsWith(".meta") }?.forEach { f ->
            try {
                val o = JSONObject(f.readText())
                out.add(NoteMeta(o.getString("id"), o.getString("title"), o.getLong("updated"), o.optInt("pages", 1)))
            } catch (_: Exception) {
            }
        }
        return out.sortedByDescending { it.updated }
    }

    /** Espera a que terminen las escrituras pendientes antes de leer. */
    fun load(ctx: Context, id: String): Note? = io.submit(Callable { readNote(ctx, id) }).get()

    private fun readNote(ctx: Context, id: String): Note? {
        val f = File(dir(ctx), "$id.json")
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            val pagesJ = o.getJSONArray("pages")
            val pages = ArrayList<MutableList<Stroke>>()
            for (pi in 0 until pagesJ.length()) {
                val arr = pagesJ.getJSONArray(pi)
                val list = ArrayList<Stroke>(arr.length())
                for (si in 0 until arr.length()) {
                    val s = arr.getJSONObject(si)
                    val st = Stroke(s.getInt("c"), s.getDouble("w").toFloat(), s.optBoolean("h", false))
                    val p = s.getJSONArray("p")
                    var k = 0
                    while (k + 2 < p.length()) {
                        st.add(p.getDouble(k).toFloat(), p.getDouble(k + 1).toFloat(), p.getDouble(k + 2).toFloat())
                        k += 3
                    }
                    list.add(st)
                }
                pages.add(list)
            }
            if (pages.isEmpty()) pages.add(ArrayList())
            Note(o.getString("id"), o.getString("title"), o.optInt("paper", PAPER_LINES), pages, o.getLong("updated"))
        } catch (_: Exception) {
            null
        }
    }

    fun save(ctx: Context, note: Note) {
        val app = ctx.applicationContext
        // copia en el hilo principal; los trazos terminados no cambian
        val snapshot = note.pages.map { it.toList() }
        val id = note.id
        val title = note.title
        val paper = note.paper
        val updated = note.updated
        writeMeta(app, id, title, updated, snapshot.size)
        io.execute {
            val sb = StringBuilder()
            sb.append("{\"id\":").append(JSONObject.quote(id))
                .append(",\"title\":").append(JSONObject.quote(title))
                .append(",\"paper\":").append(paper)
                .append(",\"updated\":").append(updated)
                .append(",\"pages\":[")
            snapshot.forEachIndexed { pi, page ->
                if (pi > 0) sb.append(',')
                sb.append('[')
                page.forEachIndexed { si, s ->
                    if (si > 0) sb.append(',')
                    sb.append("{\"c\":").append(s.color)
                        .append(",\"w\":").append(r(s.width, 100000f))
                        .append(",\"h\":").append(s.highlighter)
                        .append(",\"p\":[")
                    for (k in 0 until s.size) {
                        if (k > 0) sb.append(',')
                        sb.append(r(s.x(k), 100000f)).append(',')
                            .append(r(s.y(k), 100000f)).append(',')
                            .append(r(s.w(k), 100f))
                    }
                    sb.append("]}")
                }
                sb.append(']')
            }
            sb.append("]}")
            atomicWrite(File(dir(app), "$id.json"), sb.toString())
        }
    }

    private fun r(v: Float, scale: Float): String {
        val n = (v * scale).roundToInt()
        return (n / scale.toDouble()).toString()
    }

    private fun writeMeta(ctx: Context, id: String, title: String, updated: Long, pages: Int) {
        val o = JSONObject()
            .put("id", id).put("title", title).put("updated", updated).put("pages", pages)
        atomicWrite(File(dir(ctx), "$id.meta"), o.toString())
    }

    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun rename(ctx: Context, id: String, title: String) {
        val n = load(ctx, id) ?: return
        n.title = title
        n.updated = System.currentTimeMillis()
        save(ctx, n)
    }

    fun delete(ctx: Context, id: String) {
        io.submit {
            File(dir(ctx), "$id.json").delete()
            File(dir(ctx), "$id.meta").delete()
        }.get()
    }
}
