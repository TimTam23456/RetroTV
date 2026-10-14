package com.retrotv.app

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog

/**
 * Kids mode: the home screen shows only the games a grown-up picked, with no settings, deleting,
 * downloading or cheats. Leaving it needs the 4-digit PIN.
 */
object Kids {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("kids", Context.MODE_PRIVATE)

    fun on(ctx: Context) = prefs(ctx).getBoolean("on", false)
    fun hasPin(ctx: Context) = !prefs(ctx).getString("pin", null).isNullOrEmpty()

    fun isKidsGame(ctx: Context, key: String) = prefs(ctx).getStringSet("games", emptySet())!!.contains(key)
    fun setKidsGame(ctx: Context, key: String, on: Boolean) {
        val set = prefs(ctx).getStringSet("games", emptySet())!!.toMutableSet()
        if (on) set += key else set -= key
        prefs(ctx).edit().putStringSet("games", set).apply()
    }
    fun count(ctx: Context) = prefs(ctx).getStringSet("games", emptySet())!!.size

    private fun pinBox(ctx: Context, hint: String) = EditText(ctx).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        setPadding(ctx.dp(20), ctx.dp(14), ctx.dp(20), ctx.dp(14))
    }

    /** Asks a grown-up for a PIN (first time) and switches kids mode on. */
    fun turnOn(activity: Activity, done: () -> Unit) {
        if (hasPin(activity)) { prefs(activity).edit().putBoolean("on", true).apply(); done(); return }
        val box = pinBox(activity, "Choose a 4-digit PIN")
        AlertDialog.Builder(activity)
            .setTitle("Set a grown-up PIN")
            .setMessage("You'll need this PIN to leave kids mode. Pick games for the kids with Y (options) > Add to Kids games.")
            .setView(box)
            .setPositiveButton("Turn on kids mode") { _, _ ->
                val pin = box.text.toString()
                if (pin.length != 4) { Toast.makeText(activity, "The PIN needs 4 numbers", Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                prefs(activity).edit().putString("pin", pin).putBoolean("on", true).apply()
                done()
            }
            .setNegativeButton("Cancel", null)
            .show()
        box.requestFocus()
    }

    /** Asks for the PIN, then leaves kids mode. */
    fun askToLeave(activity: Activity, done: () -> Unit) {
        val box = pinBox(activity, "PIN")
        AlertDialog.Builder(activity)
            .setTitle("Grown-ups only")
            .setMessage("Enter the PIN to leave kids mode.")
            .setView(box)
            .setPositiveButton("Leave kids mode") { _, _ ->
                if (box.text.toString() == prefs(activity).getString("pin", "")) {
                    prefs(activity).edit().putBoolean("on", false).apply()
                    done()
                } else Toast.makeText(activity, "That's not the PIN", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
        box.requestFocus()
    }

    fun forgetPin(ctx: Context) = prefs(ctx).edit().remove("pin").apply()
}
