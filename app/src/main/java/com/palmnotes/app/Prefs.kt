package com.palmnotes.app

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("trazo", Context.MODE_PRIVATE)

    /** Medida usada para separar punta y palma (ver [Metrics]). */
    var metric: Int
        get() = sp.getInt("metric", Metrics.NONE)
        set(v) { sp.edit().putInt("metric", v).apply() }

    var threshold: Float
        get() = sp.getFloat("threshold", 0f)
        set(v) { sp.edit().putFloat("threshold", v).apply() }

    var calibrated: Boolean
        get() = sp.getBoolean("calibrated", false)
        set(v) { sp.edit().putBoolean("calibrated", v).apply() }

    var rightHanded: Boolean
        get() = sp.getBoolean("rightHanded", true)
        set(v) { sp.edit().putBoolean("rightHanded", v).apply() }

    var showRejected: Boolean
        get() = sp.getBoolean("showRejected", true)
        set(v) { sp.edit().putBoolean("showRejected", v).apply() }

    var colorIndex: Int
        get() = sp.getInt("color", 0)
        set(v) { sp.edit().putInt("color", v).apply() }

    var widthIndex: Int
        get() = sp.getInt("width", 1)
        set(v) { sp.edit().putInt("width", v).apply() }

    fun suggested(m: Int): Float = sp.getFloat("suggested_$m", -1f)
    fun setSuggested(m: Int, v: Float) { sp.edit().putFloat("suggested_$m", v).apply() }
}
