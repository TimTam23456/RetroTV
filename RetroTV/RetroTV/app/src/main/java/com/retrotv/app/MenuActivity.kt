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
class MenuActivity : RetroActivity() {

    override val playsMusic = false

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_HAS_STATE = "has_state"
        const val EXTRA_PLAYERS = "players"
        const val EXTRA_ACTION = "action"
        const val EXTRA_PATH = "path"
        const val EXTRA_SYSTEM = "system"
        const val EXTRA_SPEED = "speed"
        const val FAST = "fast"
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
            background = Ui.panel(this@MenuActivity)
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
        addButton(panel, "Cheats") {
            startActivity(Intent(this, CheatsActivity::class.java)
                .putExtra(CheatsActivity.EXTRA_PATH, intent.getStringExtra(EXTRA_PATH))
                .putExtra(CheatsActivity.EXTRA_SYSTEM, intent.getStringExtra(EXTRA_SYSTEM))
                .putExtra(CheatsActivity.EXTRA_TITLE, intent.getStringExtra(EXTRA_TITLE)))
        }
        val speed = intent.getIntExtra(EXTRA_SPEED, 1)
        addButton(panel, if (speed > 1) "Fast forward: ${speed}x (press for normal)" else "Fast forward") { done(FAST) }
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
