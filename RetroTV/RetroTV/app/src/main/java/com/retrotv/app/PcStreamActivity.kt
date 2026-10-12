package com.retrotv.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.limelight.nvstream.http.PairingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Play from PC": link the TV to a PC running RetroTV PC Setup (Sunshine), then stream its games.
 * Streaming itself is done by the built-in Moonlight engine - no other app needed.
 */
class PcStreamActivity : RetroActivity() {

    override val tabIndex = 1

    private lateinit var leftCol: LinearLayout
    private lateinit var rightCol: LinearLayout
    private lateinit var overlay: FrameLayout

    private var finder: PcFinder? = null
    private val found = LinkedHashMap<String, FoundPc>()
    private var foundList: LinearLayout? = null
    private var searchStatus: TextView? = null
    private var pcAddressLine: TextView? = null
    private var statusLine: TextView? = null

    private var pc: SavedPc? = null
    private var online: Boolean? = null
    private var pairGeneration = 0
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { background = Scrims.screen() }
        val columns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(48), dp(12), dp(48), dp(24))
        }
        leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(32), 0)
        }
        rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(32))
        }
        columns.addView(ScrollView(this).apply { addView(leftCol) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        columns.addView(ScrollView(this).apply { addView(rightCol) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.4f))
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(TabBar.build(this, 1))
        page.addView(columns, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(page)
        overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#D90E0F13"))
            visibility = View.GONE
            isClickable = true
        }
        root.addView(overlay)
        setContentView(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UploadHub.address.collect { updateAddressLine(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AutoLink.state.collect { st ->
                    if (st.linked && overlay.visibility != View.VISIBLE) {
                        val linked = PcStore.get(this@PcStreamActivity)
                        if (linked != null && linked.uuid != pc?.uuid) {
                            pc = linked
                            finder?.stop()
                            finder = null
                            Toast.makeText(this@PcStreamActivity, st.message, Toast.LENGTH_LONG).show()
                            showLinked()
                        }
                    } else if (st.working && pc == null) {
                        searchStatus?.text = "Your PC is linking itself now..."
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        pc = PcStore.get(this)
        if (pc == null) showFinder() else showLinked()
    }

    override fun onResume() {
        super.onResume()
        showStreamProblemIfAny(this)
    }

    override fun onStop() {
        super.onStop()
        finder?.stop()
        finder = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlay.visibility == View.VISIBLE) {
            cancelPairing()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ================= not linked yet: find the PC =================

    private fun showFinder() {
        leftCol.removeAllViews()
        rightCol.removeAllViews()

        leftCol.addView(heading("Play from your PC"))
        leftCol.addView(text(
            "Your PC runs the console (PS2, GameCube, Wii, PS3, Switch and more) and streams it here. " +
                "Set up once, then PC games show up in your RetroTV library.",
            Palette.muted, 15f).apply { setPadding(0, dp(8), 0, dp(16)) })

        val step1 = stepCard(leftCol, 1, "ON YOUR PC", "Run RetroTV PC Setup", "")
        pcAddressLine = step1
        updateAddressLine(UploadHub.address.value)
        stepCard(leftCol, 2, "THAT'S IT", "The TV links itself",
            "When setup finishes, this screen switches to your PC's games by itself. " +
                "Already set up? Just select your PC on the right.")
        leftCol.addView(button("Back to games") { finish() }, wrap().apply { topMargin = dp(10) })

        rightCol.addView(text("PCs on your network", Palette.text, 20f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), 0, 0, dp(6))
        })
        searchStatus = text("Looking for PCs...", Palette.muted, 14f).apply { setPadding(dp(6), 0, 0, dp(8)) }
        rightCol.addView(searchStatus)
        foundList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        rightCol.addView(foundList)
        rightCol.addView(tile("Enter PC address yourself", "Use this if your PC doesn't show up (the setup window shows its address).") {
            askForAddress()
        })

        found.clear()
        finder?.stop()
        finder = PcFinder(this) { pcFound ->
            val key = pcFound.address
            if (key.isNotEmpty() && key !in found) {
                found[key] = pcFound
                renderFound()
            }
        }.also { it.start() }
        renderFound()
    }

    private fun updateAddressLine(addr: String?) {
        val line = pcAddressLine ?: return
        line.text = if (addr != null) {
            "In your PC's web browser, go to\n$addr/pc\nDownload RetroTV PC Setup and double-click it. " +
                "If Windows says \"Windows protected your PC\", click More info > Run anyway."
        } else {
            "Connect the TV to your home Wi-Fi first - an address for your PC will appear here."
        }
    }

    private fun renderFound() {
        val list = foundList ?: return
        list.removeAllViews()
        searchStatus?.text = if (found.isEmpty()) {
            "Looking for PCs... (make sure the PC setup has finished and the PC is on the same network)"
        } else {
            "Select your PC:"
        }
        var first: View? = null
        for (p in found.values) {
            val t = tile(p.name, p.address) { startPairing(p.address, PcLink.DEFAULT_PORT, p.name) }
            list.addView(t)
            if (first == null) first = t
        }
        if (currentFocus == null) first?.requestFocus()
    }

    private fun askForAddress() {
        val input = EditText(this).apply {
            hint = "e.g. 192.168.1.50"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("PC address")
            .setMessage("Type the address shown in the RetroTV PC Setup window.")
            .setView(box)
            .setPositiveButton("Connect") { _, _ ->
                val raw = input.text.toString().trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
                if (raw.isEmpty()) return@setPositiveButton
                val host = raw.substringBefore(':')
                val port = raw.substringAfter(':', "").toIntOrNull() ?: PcLink.DEFAULT_PORT
                startPairing(host, port, host)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= linking (pairing) =================

    private fun startPairing(address: String, port: Int, label: String) {
        val gen = ++pairGeneration
        val pin = PairingManager.generatePinString()
        showPairingPanel(label, pin, "Checking the PC...")
        lifecycleScope.launch {
            try {
                val probe = withContext(Dispatchers.IO) { PcLink.probe(this@PcStreamActivity, address, port) }
                if (gen != pairGeneration) return@launch
                val login = SunshineLogins.get(this@PcStreamActivity, address)
                val saved = if (login != null) {
                    // RetroTV PC Setup gave the TV the Sunshine login: link with no code.
                    showAutoPanel(probe.name)
                    withContext(Dispatchers.IO) { AutoLink.link(this@PcStreamActivity, address, port, login) }
                } else {
                    showPairingPanel(probe.name, pin, "Waiting for you to type the code on the PC...")
                    withContext(Dispatchers.IO) { PcLink.pair(this@PcStreamActivity, address, port, pin) }
                }
                if (gen != pairGeneration) return@launch
                hideOverlay()
                pc = saved
                finder?.stop()
                finder = null
                Toast.makeText(this@PcStreamActivity, "Linked to ${saved.name}!", Toast.LENGTH_LONG).show()
                showLinked()
            } catch (e: PairException) {
                if (gen != pairGeneration) return@launch
                pairFailed(address, port, label, e.message ?: "Linking failed.")
            } catch (e: Exception) {
                if (gen != pairGeneration) return@launch
                pairFailed(address, port, label,
                    "Couldn't reach the PC at $address. Check it's on, RetroTV PC Setup has finished, " +
                        "and both are on the same network.")
            }
        }
    }

    private fun pairFailed(address: String, port: Int, label: String, msg: String) {
        overlay.removeAllViews()
        val panel = panel()
        panel.addView(text("Not linked", Palette.text, 24f).apply { setTypeface(typeface, Typeface.BOLD) })
        panel.addView(text(msg, Palette.muted, 15f).apply { setPadding(0, dp(8), 0, dp(16)) })
        val retry = button("Try again") { startPairing(address, port, label) }
        panel.addView(retry, matchWidth())
        panel.addView(button("Cancel") { cancelPairing() }, matchWidth().apply { topMargin = dp(8) })
        overlay.addView(panel, FrameLayout.LayoutParams(dp(460), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        overlay.visibility = View.VISIBLE
        retry.requestFocus()
    }

    private fun showPairingPanel(name: String, pin: String, status: String) {
        overlay.removeAllViews()
        val panel = panel()
        panel.addView(text("Linking to $name", Palette.text, 22f).apply { setTypeface(typeface, Typeface.BOLD) })
        panel.addView(text("Type this code into the RetroTV PC Setup window on your PC:", Palette.muted, 15f)
            .apply { setPadding(0, dp(8), 0, dp(8)) })
        panel.addView(text(pin.toCharArray().joinToString(" "), Palette.text, 56f).apply {
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(6))
        })
        panel.addView(text(status, Palette.accent, 14f).apply { gravity = Gravity.CENTER })
        panel.addView(text("Not using the setup file? Enter the code in Sunshine's web page under PIN.",
            Palette.muted, 12f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(12))
        })
        val cancel = button("Cancel") { cancelPairing() }
        panel.addView(cancel, matchWidth())
        overlay.addView(panel, FrameLayout.LayoutParams(dp(500), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        overlay.visibility = View.VISIBLE
        cancel.requestFocus()
    }

    private fun showAutoPanel(name: String) {
        overlay.removeAllViews()
        val panel = panel()
        panel.addView(text("Linking to $name", Palette.text, 22f).apply { setTypeface(typeface, Typeface.BOLD) })
        panel.addView(text("Linking automatically - nothing to type. This takes a few seconds.", Palette.muted, 15f)
            .apply { setPadding(0, dp(8), 0, dp(16)) })
        val cancel = button("Cancel") { cancelPairing() }
        panel.addView(cancel, matchWidth())
        overlay.addView(panel, FrameLayout.LayoutParams(dp(480), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        overlay.visibility = View.VISIBLE
        cancel.requestFocus()
    }

    private fun cancelPairing() {
        pairGeneration++
        hideOverlay()
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
        overlay.removeAllViews()
    }

    // ================= linked: show the PC's games =================

    private fun showLinked() {
        val p = pc ?: return showFinder()
        leftCol.removeAllViews()
        rightCol.removeAllViews()

        leftCol.addView(heading(p.name))
        statusLine = text("Checking if the PC is on...", Palette.muted, 15f).apply { setPadding(0, dp(4), 0, dp(16)) }
        leftCol.addView(statusLine)

        leftCol.addView(button("Refresh games") { refresh(manual = true) }, matchWidth())
        if (p.mac != null) {
            leftCol.addView(button("Wake PC") { wake() }, matchWidth().apply { topMargin = dp(8) })
        }
        leftCol.addView(button("Stream quality") {
            startActivity(Intent(this, com.limelight.preferences.StreamSettings::class.java))
        }, matchWidth().apply { topMargin = dp(8) })
        leftCol.addView(button("Link a different PC") { confirmForget() }, matchWidth().apply { topMargin = dp(8) })
        leftCol.addView(button("Back to games") { finish() }, matchWidth().apply { topMargin = dp(8) })

        leftCol.addView(text(
            "While streaming: hold View + Menu + LB + RB together to stop. " +
                "Hold Menu for a second to use the controller as a mouse for PC menus.",
            Palette.muted, 13f).apply { setPadding(0, dp(16), 0, 0) })

        renderApps(p)
        refresh(manual = false)
    }

    private fun renderApps(p: SavedPc) {
        rightCol.removeAllViews()
        rightCol.addView(text("Games on ${p.name}", Palette.text, 20f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), 0, 0, dp(8))
        })
        if (p.apps.isEmpty()) {
            rightCol.addView(text(
                "No games listed yet. Run RetroTV PC Setup again on the PC to add your emulators, then press Refresh games.",
                Palette.muted, 15f).apply { setPadding(dp(6), 0, dp(6), 0) })
            return
        }
        var first: View? = null
        for (app in p.apps) {
            val t = tile(app.name, PcGames.describe(app.name)) { play(p, app) }
            rightCol.addView(t)
            if (first == null) first = t
        }
        if (currentFocus == null || currentFocus?.parent == rightCol) first?.requestFocus()
    }

    private fun refresh(manual: Boolean) {
        val p = pc ?: return
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { PcLink.refresh(this@PcStreamActivity, p) }
                PcStore.save(this@PcStreamActivity, updated)
                pc = updated
                online = true
                statusLine?.setTextColor(Palette.good)
                statusLine?.text = "● Online · ${updated.address}"
                renderApps(updated)
                if (manual) Toast.makeText(this@PcStreamActivity, "Game list updated", Toast.LENGTH_SHORT).show()
            } catch (e: PairException) {
                online = false
                statusLine?.setTextColor(Palette.bad)
                statusLine?.text = "This TV isn't linked to the PC any more. Choose \"Link a different PC\" to link again."
            } catch (e: Exception) {
                online = false
                statusLine?.setTextColor(Palette.warn)
                statusLine?.text = "● Offline - the PC is off, asleep, or Sunshine isn't running."
            }
        }
    }

    private fun play(p: SavedPc, app: PcApp) {
        if (online == false) {
            Toast.makeText(this, "Your PC looks offline. Trying anyway...", Toast.LENGTH_SHORT).show()
        }
        StreamLog.markStarted(this, "${app.name} on ${p.name}")
        startActivity(PcLink.streamIntent(this, p, app))
    }

    private fun wake() {
        val p = pc ?: return
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    PcLink.wake(p); true
                } catch (_: Exception) {
                    false
                }
            }
            Toast.makeText(this@PcStreamActivity,
                if (ok) "Wake signal sent. Give the PC about 20 seconds, then press Refresh games."
                else "Couldn't send the wake signal.",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmForget() {
        AlertDialog.Builder(this)
            .setTitle("Link a different PC?")
            .setMessage("RetroTV will forget ${pc?.name ?: "this PC"}. You can link it again any time.")
            .setPositiveButton("Forget it") { _, _ ->
                PcStore.forget(this)
                pc = null
                online = null
                showFinder()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ================= small builders =================

    private fun heading(t: String) = text(t, Palette.text, 30f).apply { setTypeface(typeface, Typeface.BOLD) }

    private fun stepCard(parent: LinearLayout, n: Int, where: String, title: String, body: String): TextView {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Palette.panel)
            }
            setPadding(dp(14), dp(12), dp(16), dp(12))
        }
        card.addView(TextView(this).apply {
            text = n.toString()
            textSize = 16f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.text)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Palette.accent)
            }
        }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { rightMargin = dp(14) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(where, Palette.accent, 11f).apply {
            letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(text(title, Palette.text, 16f).apply { setTypeface(typeface, Typeface.BOLD) })
        val bodyView = text(body, Palette.muted, 14f).apply { setPadding(0, dp(3), 0, 0) }
        col.addView(bodyView)
        card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        parent.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(8) })
        return bodyView
    }

    private fun tile(title: String, sub: String, onClick: () -> Unit): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            background = focusTile()
            setPadding(dp(16), dp(12), dp(16), dp(12))
            growOnFocus()
            setOnClickListener { onClick() }
        }
        t.addView(text(title, Palette.text, 16f))
        if (sub.isNotEmpty()) t.addView(text(sub, Palette.muted, 13f).apply { setPadding(0, dp(3), 0, 0) })
        t.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(4), dp(5), dp(4), dp(5)) }
        return t
    }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(28), dp(24), dp(28), dp(24))
        background = GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Palette.panel)
            setStroke(dp(2), Palette.accent)
        }
    }

    private fun text(t: String, color: Int, size: Float) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, 1.15f)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setTextColor(Palette.text)
        background = focusTile()
        setPadding(dp(18), dp(12), dp(18), dp(12))
        growOnFocus()
        setOnClickListener { onClick() }
    }

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

/** Friendly labels for the app tiles the PC setup creates. */
object PcGames {
    fun describe(name: String): String = when (name.lowercase()) {
        "desktop" -> "Your whole PC screen"
        "steam big picture" -> "Steam, made for the TV"
        "ps2" -> "PCSX2"
        "ps3" -> "RPCS3"
        "gamecube / wii", "gamecube", "wii" -> "Dolphin"
        "switch" -> "Switch emulator"
        "wii u" -> "Cemu"
        "3ds" -> "3DS emulator"
        "xbox" -> "xemu"
        "xbox 360" -> "Xenia"
        "ps vita" -> "Vita3K"
        else -> "On your PC"
    }
}
