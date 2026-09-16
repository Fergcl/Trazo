package com.palmnotes.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Date

class NotesActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var listView: ListView
    private lateinit var empty: TextView
    private lateinit var banner: LinearLayout
    private var items: List<NoteMeta> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val rowView = (convertView as? LinearLayout) ?: makeRow()
            val m = items[position]
            (rowView.getChildAt(0) as TextView).text = m.title
            val date = DateFormat.getMediumDateFormat(this@NotesActivity).format(Date(m.updated))
            val time = DateFormat.getTimeFormat(this@NotesActivity).format(Date(m.updated))
            val pages = if (m.pageCount == 1) "1 página" else "${m.pageCount} páginas"
            (rowView.getChildAt(1) as TextView).text = "Editada el $date a las $time, $pages"
            return rowView
        }
    }

    private fun makeRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
        addView(label("", 18f, bold = true))
        addView(label("", 14f, muted = true))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.padForSystemBars(dpi(20f), dpi(20f), dpi(20f), 0)

        val header = row()
        header.addView(label("Trazo", 30f, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addGap(button("Calibrar") { startActivity(Intent(this, CalibrationActivity::class.java)) })
        val newBtn = button("Nueva nota") { openNote(null) }
        pill(newBtn, true)
        header.addGap(newBtn, 0f)
        root.addGap(header, 16f)

        banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f))
            pill(this, false)
            addView(label("Calibra el lápiz antes de escribir. Así la app aprende a distinguir la punta de la palma en tu pantalla."))
        }
        root.addGap(banner, 16f)

        empty = label("No hay notas todavía. Pulsa Nueva nota para empezar.", 16f, muted = true)
        root.addGap(empty, 8f)

        listView = ListView(this).apply {
            adapter = this@NotesActivity.adapter
            divider = null
            setOnItemClickListener { _, _, pos, _ -> openNote(items[pos].id) }
            setOnItemLongClickListener { _, _, pos, _ -> noteOptions(items[pos]); true }
        }
        root.addView(listView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items = Storage.list(this)
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        banner.visibility = if (prefs.calibrated) View.GONE else View.VISIBLE
    }

    private fun openNote(id: String?) {
        val i = Intent(this, EditorActivity::class.java)
        if (id != null) i.putExtra(EditorActivity.EXTRA_ID, id)
        startActivity(i)
    }

    private fun noteOptions(m: NoteMeta) {
        AlertDialog.Builder(this)
            .setTitle(m.title)
            .setItems(arrayOf("Cambiar nombre", "Eliminar nota")) { _, which ->
                if (which == 0) renameDialog(m) else confirmDelete(m)
            }
            .show()
    }

    private fun renameDialog(m: NoteMeta) {
        val input = EditText(this).apply {
            setText(m.title)
            setSelection(m.title.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Cambiar nombre")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) {
                    Storage.rename(this, m.id, t)
                    refresh()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDelete(m: NoteMeta) {
        AlertDialog.Builder(this)
            .setTitle("¿Eliminar \"${m.title}\"?")
            .setMessage("La nota se borrará de la tablet y no se puede recuperar.")
            .setPositiveButton("Eliminar") { _, _ ->
                Storage.delete(this, m.id)
                refresh()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
