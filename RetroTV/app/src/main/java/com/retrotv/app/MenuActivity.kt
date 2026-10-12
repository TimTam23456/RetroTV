package com.retrotv.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Pause menu shown over the game. Opening it pauses emulation automatically. */
class MenuActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_HAS_STATE = "has_state"
        const val EXTRA_PLAYERS = "players"
        const val EXTRA_ACTION = "action"
        const val SAVE = "save"
        const val LOAD = "load"
        const val RESET = "reset"
        const val QUIT = "quit"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#B3000000")) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Palette.bg)
            }
        }

        panel.addView(TextView(this).apply {
            text = intent.getStringExtra(EXTRA_TITLE) ?: "Paused"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.text)
            maxLines = 2
        })
        val players = intent.getIntExtra(EXTRA_PLAYERS, 0)
        panel.addView(TextView(this).apply {
            text = when (players) {
                0, 1 -> "Paused · 1 player"
                else -> "Paused · $players players"
            }
            textSize = 14f
            setTextColor(Palette.muted)
            setPadding(0, dp(2), 0, dp(16))
        })

        val resume = addButton(panel, "Resume") { finish() }
        addButton(panel, "Save state") { done(SAVE) }
        addButton(panel, "Load state") { done(LOAD) }.apply {
            if (!intent.getBooleanExtra(EXTRA_HAS_STATE, false)) {
                isEnabled = false
                alpha = 0.4f
            }
        }
        addButton(panel, "Reset game") { done(RESET) }
        addButton(panel, "Quit to library") { done(QUIT) }

        overlay.addView(panel, FrameLayout.LayoutParams(dp(380), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(overlay)
        resume.requestFocus()
    }

    private fun addButton(parent: LinearLayout, label: String, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setTextColor(Palette.text)
            background = focusTile()
            setPadding(dp(18), dp(12), dp(18), dp(12))
            growOnFocus()
            setOnClickListener { onClick() }
        }
        parent.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8) })
        return b
    }

    private fun done(action: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_ACTION, action))
        finish()
    }
}
