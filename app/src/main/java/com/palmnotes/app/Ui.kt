package com.palmnotes.app

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()

fun Context.pill(v: View, on: Boolean) {
    val ctx = this
    v.background = GradientDrawable().apply {
        cornerRadius = ctx.dpi(10f).toFloat()
        setColor(ctx.getColor(if (on) R.color.btn_on else R.color.btn))
        setStroke(ctx.dpi(1f), ctx.getColor(if (on) R.color.btn_on else R.color.chrome_2))
    }
    if (v is TextView) v.setTextColor(getColor(if (on) R.color.btn_on_text else R.color.text))
    v.isSelected = on
}

fun Context.button(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
    text = label
    textSize = 15f
    gravity = Gravity.CENTER
    minHeight = dpi(44f)
    minWidth = dpi(44f)
    setPadding(dpi(14f), 0, dpi(14f), 0)
    isClickable = true
    isFocusable = true
    contentDescription = label
    setOnClickListener { onClick() }
    pill(this, false)
}

fun Context.label(text: String, size: Float = 15f, bold: Boolean = false, muted: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(getColor(if (muted) R.color.muted else R.color.text))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.separator(): View = View(this).apply {
    setBackgroundColor(getColor(R.color.chrome_2))
    layoutParams = LinearLayout.LayoutParams(dpi(1f), dpi(28f)).apply {
        leftMargin = dpi(6f)
        rightMargin = dpi(6f)
    }
}

fun Context.row(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun LinearLayout.addGap(v: View, gapDp: Float = 6f) {
    val lp = (v.layoutParams as? LinearLayout.LayoutParams)
        ?: LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    if (orientation == LinearLayout.HORIZONTAL) lp.rightMargin = context.dpi(gapDp) else lp.bottomMargin = context.dpi(gapDp)
    addView(v, lp)
}

fun Activity.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

/** Android 15 dibuja la app bajo las barras del sistema; añade su espacio al relleno. */
fun View.padForSystemBars(left: Int, top: Int, right: Int, bottom: Int) {
    setOnApplyWindowInsetsListener { v, insets ->
        val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        v.setPadding(left + b.left, top + b.top, right + b.right, bottom + b.bottom)
        insets
    }
    requestApplyInsets()
}
