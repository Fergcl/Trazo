package com.palmnotes.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import java.util.Date
import kotlin.math.min

class EditorActivity : Activity(), NoteView.Listener {

    companion object {
        const val EXTRA_ID = "note_id"
        private val WIDTHS = floatArrayOf(1.4f, 2.4f, 4.5f)
        private val WIDTH_NAMES = arrayOf("Fino", "Medio", "Grueso")
        private val PAPER_NAMES = arrayOf("Rayado", "Cuadrícula", "Liso")
    }

    private sealed class Action(val page: Int)
    private class AddAction(page: Int, val stroke: Stroke) : Action(page)
    private class EraseAction(page: Int, val items: List<Pair<Int, Stroke>>) : Action(page)
    private class ClearAction(page: Int, val strokes: List<Stroke>) : Action(page)

    private lateinit var prefs: Prefs
    private lateinit var note: Note
    private lateinit var noteView: NoteView
    private var page = 0
    private val undoStack = ArrayDeque<Action>()
    private val redoStack = ArrayDeque<Action>()
    private val handler = Handler(Looper.getMainLooper())
    private val saveRunnable = Runnable { Storage.save(this, note) }

    private lateinit var titleView: TextView
    private lateinit var statusView: TextView
    private lateinit var pageInfo: TextView
    private lateinit var undoBtn: TextView
    private lateinit var redoBtn: TextView
    private lateinit var prevBtn: TextView
    private lateinit var paperBtn: TextView
    private val toolBtns = ArrayList<TextView>()
    private val widthBtns = ArrayList<TextView>()
    private val swatches = ArrayList<View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val id = intent.getStringExtra(EXTRA_ID)
        note = id?.let { Storage.load(this, it) } ?: run {
            val now = Date()
            val title = "Nota del " + DateFormat.getMediumDateFormat(this).format(now)
            Storage.newNote(title).also { Storage.save(this, it) }
        }

        noteView = NoteView(this, prefs).apply {
            listener = this@EditorActivity
            paper = note.paper
            colorIndex = prefs.colorIndex.coerceIn(0, Palette.SHOWN - 1)
            widthDp = WIDTHS[prefs.widthIndex.coerceIn(0, 2)]
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.padForSystemBars(0, 0, 0, 0)
        root.addView(buildToolbar())

        val stage = FrameLayout(this)
        stage.addView(noteView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        statusView = label("Palma ignorada", 14f).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dpi(12f), dpi(6f), dpi(12f), dpi(6f))
            background = GradientDrawable().apply {
                cornerRadius = dpi(20f).toFloat()
                setColor(this@EditorActivity.getColor(R.color.palm))
            }
            visibility = View.GONE
        }
        stage.addView(statusView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
        ).apply { setMargins(0, dpi(10f), dpi(10f), 0) })
        root.addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            setMargins(dpi(8f), 0, dpi(8f), dpi(8f))
        })
        setContentView(root)

        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        showPage()
        selectTool(TOOL_PEN)
        selectColor(noteView.colorIndex)
        selectWidth(prefs.widthIndex.coerceIn(0, 2))
        updateButtons()
        if (!prefs.calibrated) toast("Sin calibrar: el filtro por tamaño está apagado. Pulsa Calibrar.")
    }

    override fun onResume() {
        super.onResume()
        noteView.invalidate()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(saveRunnable)
        Storage.save(this, note)
    }

    private fun scheduleSave() {
        note.updated = System.currentTimeMillis()
        handler.removeCallbacks(saveRunnable)
        handler.postDelayed(saveRunnable, 1500)
    }

    // ---------- barra de herramientas ----------

    private fun buildToolbar(): View {
        val bar = row().apply { setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(8f)) }

        bar.addGap(button("‹ Notas") { finish() })
        titleView = label(note.title, 16f, bold = true).apply {
            maxWidth = dpi(220f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dpi(6f), 0, dpi(6f), 0)
            setOnClickListener { renameDialog() }
            contentDescription = "Título: ${note.title}. Toca para cambiarlo"
        }
        bar.addGap(titleView)
        bar.addView(separator())

        listOf("Lápiz" to TOOL_PEN, "Marcador" to TOOL_HIGHLIGHTER, "Borrador" to TOOL_ERASER).forEach { (name, t) ->
            val b = button(name) { selectTool(t) }
            toolBtns.add(b)
            bar.addGap(b, 4f)
        }
        bar.addView(separator())

        for (i in 0 until Palette.SHOWN) {
            val v = View(this).apply {
                contentDescription = Palette.names[i]
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    selectColor(i)
                    if (noteView.tool == TOOL_ERASER) selectTool(TOOL_PEN)
                }
            }
            swatches.add(v)
            bar.addView(v, LinearLayout.LayoutParams(dpi(36f), dpi(36f)).apply { rightMargin = dpi(6f) })
        }
        bar.addView(separator())

        WIDTH_NAMES.forEachIndexed { i, n ->
            val b = button(n) { selectWidth(i) }
            widthBtns.add(b)
            bar.addGap(b, 4f)
        }
        bar.addView(separator())

        undoBtn = button("Deshacer") { undo() }
        redoBtn = button("Rehacer") { redo() }
        bar.addGap(undoBtn, 4f)
        bar.addGap(redoBtn, 4f)
        bar.addView(separator())

        prevBtn = button("‹") { prevPage() }.apply { contentDescription = "Página anterior" }
        bar.addGap(prevBtn, 4f)
        pageInfo = label("1 / 1", 14f, muted = true).apply {
            gravity = Gravity.CENTER
            minWidth = dpi(56f)
        }
        bar.addGap(pageInfo, 4f)
        bar.addGap(button("›") { nextPage() }.apply { contentDescription = "Página siguiente o nueva" }, 4f)
        paperBtn = button(PAPER_NAMES[note.paper]) { cyclePaper() }
        bar.addGap(paperBtn, 4f)
        bar.addView(separator())

        val exportBtn = button("Exportar") {}
        exportBtn.setOnClickListener { exportMenu(exportBtn) }
        bar.addGap(exportBtn, 4f)
        val moreBtn = button("Página…") {}
        moreBtn.setOnClickListener { pageMenu(moreBtn) }
        bar.addGap(moreBtn, 4f)
        bar.addGap(button("Calibrar") { startActivity(Intent(this, CalibrationActivity::class.java)) }, 0f)

        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
    }

    private fun selectTool(t: Int) {
        noteView.tool = t
        toolBtns.forEachIndexed { i, b -> pill(b, i == t) }
    }

    private fun selectColor(i: Int) {
        noteView.colorIndex = i
        prefs.colorIndex = i
        swatches.forEachIndexed { k, v ->
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.color(this@EditorActivity, k))
                val ctx = this@EditorActivity
                if (k == i) setStroke(ctx.dpi(3f), ctx.getColor(R.color.btn_on))
                else setStroke(ctx.dpi(1f), ctx.getColor(R.color.chrome_2))
            }
            v.isSelected = k == i
        }
    }

    private fun selectWidth(i: Int) {
        noteView.widthDp = WIDTHS[i]
        prefs.widthIndex = i
        widthBtns.forEachIndexed { k, b -> pill(b, k == i) }
    }

    private fun updateButtons() {
        undoBtn.isEnabled = undoStack.isNotEmpty()
        undoBtn.alpha = if (undoBtn.isEnabled) 1f else 0.4f
        redoBtn.isEnabled = redoStack.isNotEmpty()
        redoBtn.alpha = if (redoBtn.isEnabled) 1f else 0.4f
        prevBtn.isEnabled = page > 0
        prevBtn.alpha = if (prevBtn.isEnabled) 1f else 0.4f
    }

    private fun renameDialog() {
        val input = EditText(this).apply {
            setText(note.title)
            setSelection(note.title.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Cambiar nombre")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    note.title = t
                    titleView.text = t
                    scheduleSave()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- páginas ----------

    private fun showPage() {
        page = page.coerceIn(0, note.pages.size - 1)
        noteView.strokes = note.pages[page]
        pageInfo.text = "${page + 1} / ${note.pages.size}"
        updateButtons()
    }

    private fun prevPage() {
        if (page > 0) {
            page--
            showPage()
        }
    }

    private fun nextPage() {
        if (page == note.pages.size - 1) {
            if (note.pages[page].isEmpty()) {
                toast("Esta página está vacía")
                return
            }
            note.pages.add(ArrayList())
            scheduleSave()
        }
        page++
        showPage()
    }

    private fun cyclePaper() {
        note.paper = (note.paper + 1) % 3
        noteView.paper = note.paper
        paperBtn.text = PAPER_NAMES[note.paper]
        scheduleSave()
    }

    private fun pageMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Borrar contenido de la página")
        menu.menu.add(0, 2, 1, "Eliminar página")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> clearPage()
                2 -> deletePage()
            }
            true
        }
        menu.show()
    }

    private fun clearPage() {
        val list = note.pages[page]
        if (list.isEmpty()) return
        push(ClearAction(page, list.toList()))
        list.clear()
        noteView.redrawAll()
        scheduleSave()
        toast("Página borrada. Puedes deshacerlo.")
    }

    private fun deletePage() {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar la página ${page + 1}?")
            .setMessage("No se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ ->
                if (note.pages.size == 1) note.pages[0].clear() else note.pages.removeAt(page)
                undoStack.clear()
                redoStack.clear()
                if (page >= note.pages.size) page = note.pages.size - 1
                showPage()
                scheduleSave()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- deshacer ----------

    private fun push(a: Action) {
        undoStack.addLast(a)
        if (undoStack.size > 300) undoStack.removeFirst()
        redoStack.clear()
        updateButtons()
    }

    private fun undo() {
        val a = undoStack.removeLastOrNull() ?: return
        applyAction(a, reverse = true)
        redoStack.addLast(a)
        updateButtons()
    }

    private fun redo() {
        val a = redoStack.removeLastOrNull() ?: return
        applyAction(a, reverse = false)
        undoStack.addLast(a)
        updateButtons()
    }

    private fun applyAction(a: Action, reverse: Boolean) {
        if (a.page >= note.pages.size) return
        if (a.page != page) {
            page = a.page
            showPage()
        }
        val list = note.pages[a.page]
        when (a) {
            is AddAction -> if (reverse) list.remove(a.stroke) else list.add(a.stroke)
            is EraseAction -> if (reverse) {
                for (it in a.items.asReversed()) list.add(min(it.first, list.size), it.second)
            } else {
                for (it in a.items) list.remove(it.second)
            }
            is ClearAction -> if (reverse) list.addAll(a.strokes) else list.removeAll(a.strokes.toSet())
        }
        noteView.redrawAll()
        scheduleSave()
    }

    override fun onStrokeAdded(stroke: Stroke) {
        push(AddAction(page, stroke))
        scheduleSave()
    }

    override fun onErased(items: List<Pair<Int, Stroke>>) {
        push(EraseAction(page, items.toList()))
        scheduleSave()
    }

    override fun onPalmChanged(active: Boolean) {
        statusView.visibility = if (active) View.VISIBLE else View.GONE
    }

    // ---------- exportar ----------

    private fun exportMenu(anchor: View) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, "Página actual como imagen")
        menu.menu.add(0, 2, 1, "Nota completa en PDF")
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> exportPng()
                2 -> exportPdf()
            }
            true
        }
        menu.show()
    }

    private fun safeName(): String =
        note.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifEmpty { "nota" }.take(60)

    private fun exportPng() {
        val w = noteView.width
        val h = noteView.height
        if (w <= 0 || h <= 0) return
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        noteView.drawPageTo(Canvas(bmp), note.pages[page], w, h, note.paper)
        val name = "${safeName()} p${page + 1} ${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Trazo")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            toast("No se pudo crear la imagen")
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            finishPending(uri)
            toast("Guardada en Imágenes/Trazo")
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            toast("No se pudo guardar la imagen")
        } finally {
            bmp.recycle()
        }
    }

    private fun exportPdf() {
        val w = noteView.width
        val h = noteView.height
        if (w <= 0 || h <= 0) return
        val doc = PdfDocument()
        note.pages.forEachIndexed { i, list ->
            val info = PdfDocument.PageInfo.Builder(w, h, i + 1).create()
            val pg = doc.startPage(info)
            noteView.drawPageTo(pg.canvas, list, w, h, note.paper)
            doc.finishPage(pg)
        }
        val name = "${safeName()} ${System.currentTimeMillis()}.pdf"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Trazo")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            doc.close()
            toast("No se pudo crear el PDF")
            return
        }
        try {
            contentResolver.openOutputStream(uri)?.use { doc.writeTo(it) }
            finishPending(uri)
            toast("PDF guardado en Descargas/Trazo")
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            toast("No se pudo guardar el PDF")
        } finally {
            doc.close()
        }
    }

    private fun finishPending(uri: Uri) {
        val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        contentResolver.update(uri, done, null, null)
    }
}
