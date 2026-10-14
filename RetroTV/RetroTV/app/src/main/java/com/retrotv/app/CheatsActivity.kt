package com.retrotv.app

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Cheat list for the game that's paused: switch cheats on and off, or add your own codes. */
class CheatsActivity : RetroActivity() {

    override val playsMusic = false

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_SYSTEM = "system"
        const val EXTRA_TITLE = "title"
    }

    private lateinit var game: Game
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private var dbCheats: List<Cheat> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sys = Systems.byId(intent.getStringExtra(EXTRA_SYSTEM)) ?: return finish()
        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        game = Game(File(path), sys, intent.getStringExtra(EXTRA_TITLE) ?: File(path).nameWithoutExtension)

        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#D9000000")) }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(20))
            background = Ui.panel(this@CheatsActivity)
        }
        panel.addView(TextView(this).apply {
            text = "Cheats"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.text)
        })
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(Palette.muted)
            setPadding(0, dp(2), 0, dp(10))
        }
        panel.addView(status)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list); isVerticalScrollBarEnabled = false }
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        overlay.addView(panel, FrameLayout.LayoutParams(dp(620), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER).apply {
            topMargin = dp(28); bottomMargin = dp(28)
        })
        setContentView(overlay)
        load()
    }

    private fun load() {
        status.text = "Looking up cheats for ${game.title}..."
        lifecycleScope.launch {
            dbCheats = withContext(Dispatchers.IO) { try { Cheats.findInDb(this@CheatsActivity, game) } catch (_: Exception) { emptyList() } }
            render()
        }
    }

    private fun render(focusIndex: Int = 0) {
        list.removeAllViews()
        val st = Cheats.state(this, game)
        val on = st.enabled
        status.text = when {
            !Cheats.hasDb(this) -> "Download the free cheat list once, or add your own codes."
            dbCheats.isEmpty() -> "No cheats found for ${game.title} in the cheat list. You can add your own codes."
            else -> "${dbCheats.size} cheats for ${game.title}. Press A to switch one on or off. Too many cheats at once can crash a game."
        }
        if (!Cheats.hasDb(this)) list.addView(Ui.listRow(this, "Download the cheat list", "Free libretro cheat database, about 15 MB, once") { download() })
        list.addView(Ui.listRow(this, "Add my own code", "Game Genie, Action Replay, GameShark or raw codes") { addCode() })
        if (on.isNotEmpty()) list.addView(Ui.listRow(this, "Turn all cheats off", "${on.size} on now") { Cheats.turnAllOff(this, game); render(0) })
        for (c in st.custom) list.addView(cheatRow(c, Cheats.idOf(c) in on).apply {
            setOnLongClickListener { removeCustom(c); true }
            setOnKeyListener { _, code, e -> if (code == KeyEvent.KEYCODE_BUTTON_Y && e.action == KeyEvent.ACTION_UP) { removeCustom(c); true } else false }
        })
        for (c in dbCheats) list.addView(cheatRow(c, Cheats.idOf(c) in on))
        if (Cheats.hasDb(this)) list.addView(Ui.listRow(this, "Update the cheat list", "Get the newest codes") { download() })
        list.post { (list.getChildAt(focusIndex.coerceIn(0, list.childCount - 1)))?.requestFocus() }
    }

    private fun cheatRow(c: Cheat, on: Boolean): LinearLayout =
        Ui.listRow(this, c.desc, if (c.custom) "Your code: ${c.code}  (hold OK or press Y to delete)" else null, if (on) "On" else "Off") {
            val nowOn = Cheats.toggle(this, game, c)
            val row = list.focusedChild as? LinearLayout
            (row?.getChildAt(row.childCount - 1) as? TextView)?.text = if (nowOn) "On" else "Off"
        }

    private fun removeCustom(c: Cheat) {
        Cheats.removeCustom(this, game, c)
        Toast.makeText(this, "Deleted ${c.desc}", Toast.LENGTH_SHORT).show()
        render(1)
    }

    private fun addCode() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val name = EditText(this).apply { hint = "Name (e.g. Infinite lives)"; inputType = InputType.TYPE_CLASS_TEXT }
        val code = EditText(this).apply {
            hint = "Code (e.g. SXIOPO or 7E0DBE09)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        box.addView(name); box.addView(code)
        AlertDialog.Builder(this)
            .setTitle("Add a cheat code")
            .setMessage("Several codes for one cheat can be joined with +")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                val c = code.text.toString().trim()
                if (c.isEmpty()) return@setPositiveButton
                Cheats.addCustom(this, game, name.text.toString().trim(), c)
                render(1)
            }
            .setNegativeButton("Cancel", null)
            .show()
        code.requestFocus()
    }

    private fun download() {
        status.text = "Downloading the cheat list..."
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { Cheats.download(this@CheatsActivity) { pct -> runOnUiThread { status.text = "Downloading the cheat list... $pct%" } } }
                load()
            } catch (e: Exception) {
                status.text = "Couldn't download the cheat list: ${e.message}. Check the TV's internet and try again."
            }
        }
    }
}
