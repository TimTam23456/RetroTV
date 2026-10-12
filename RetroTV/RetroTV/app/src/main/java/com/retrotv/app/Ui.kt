package com.retrotv.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View

object Palette {
    val bg = Color.parseColor("#0E0F13")
    val panel = Color.parseColor("#181822")
    val accent = Color.parseColor("#7C5CFF")
    val text = Color.parseColor("#ECEAF4")
    val muted = Color.parseColor("#A9A6B8")
    val good = Color.parseColor("#6EE7A8")
    val warn = Color.parseColor("#FFB86B")
    val bad = Color.parseColor("#FF7A8A")
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** A rounded tile that lights up when it has focus (what the remote/controller is pointing at). */
fun Context.focusTile(normal: Int = Palette.panel): StateListDrawable {
    fun shape(fill: Int, stroke: Int) = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(fill)
        setStroke(dp(3), stroke)
    }
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(Color.parseColor("#2A2346"), Palette.accent))
        addState(intArrayOf(android.R.attr.state_pressed), shape(Color.parseColor("#2A2346"), Palette.accent))
        addState(intArrayOf(), shape(normal, normal))
    }
}

fun View.growOnFocus() {
    setOnFocusChangeListener { v, has ->
        val s = if (has) 1.03f else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(120).start()
    }
}
