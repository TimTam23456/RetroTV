package com.retrotv.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings: categories on the left, their options on the right (console style). */
class SettingsActivity : RetroActivity() {

    override val tabIndex = 3

    private data class Category(val id: String, val title: String, val icon: Icon)

    private val categories = listOf(
        Category("controllers", "Controllers", Icon.GAMEPAD),
        Category("games", "Games & cover art", Icon.FOLDER),
        Category("sound", "Sound", Icon.SOUND),
        Category("pc", "PC streaming", Icon.PC),
        Category("tv", "This TV", Icon.TV),
        Category("about", "About", Icon.INFO),
    )

    private lateinit var detail: LinearLayout
    private lateinit var detailTitle: TextView
    private val catViews = mutableListOf<LinearLayout>()
    private var current = "controllers"

    private val prefs get() = getSharedPreferences("settings", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Scrims.screen()
        }
        page.addView(TabBar.build(this, 3))
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(48), dp(14), dp(48), dp(24))
        }
        page.addView(root, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- left: categories
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@SettingsActivity).apply { setImageDrawable(IconDrawable(Icon.SETTINGS, Palette.text)) },
                LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(TextView(this@SettingsActivity).apply {
                text = "Settings"
                textSize = 22f
                setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
                setTextColor(Palette.text)
                setPadding(dp(12), 0, 0, 0)
            })
            setPadding(0, 0, 0, dp(12))
        })
        for (c in categories) {
            val v = categoryRow(c)
            catViews += v
            left.addView(v)
        }
        root.addView(left, LinearLayout.LayoutParams(dp(280), ViewGroup.LayoutParams.MATCH_PARENT))

        // ---- right: options
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(36), dp(4), 0, 0)
        }
        detailTitle = TextView(this).apply {
            textSize = 22f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(Palette.text)
            setPadding(dp(4), dp(4), 0, dp(12))
        }
        right.addView(detailTitle)
        detail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(8), dp(40))
        }
        right.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(detail)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        setContentView(page)

        catViews.first().requestFocus()
    }

    override fun onResume() {
        super.onResume()
        show(current) // values may have changed (e.g. PC linked)
    }

    private fun categoryRow(c: Category): LinearLayout {
        val icon = IconDrawable(c.icon, Palette.muted)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            id = View.generateViewId()
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        row.addView(ImageView(this).apply { setImageDrawable(icon) }, LinearLayout.LayoutParams(dp(22), dp(22)))
        val label = TextView(this).apply {
            text = c.title
            textSize = 16f
            setTextColor(Palette.muted)
            setPadding(dp(14), 0, 0, 0)
        }
        row.addView(label)
        fun style(focused: Boolean, selected: Boolean) {
            row.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(when { focused -> 0xFFFFFFFF.toInt(); selected -> 0x1FFFFFFF; else -> 0 })
            }
            val col = when { focused -> Palette.bg; selected -> Palette.text; else -> Palette.muted }
            label.setTextColor(col)
            icon.setColor(col)
        }
        row.tag = { selected: Boolean -> style(row.isFocused, selected) }
        row.setOnFocusChangeListener { _, has ->
            if (has && current != c.id) show(c.id)
            style(has, current == c.id)
        }
        row.setOnClickListener { detail.getChildAt(0)?.let { focusFirstFocusable(detail) } }
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4) }
        style(false, current == c.id)
        return row
    }

    private fun focusFirstFocusable(group: ViewGroup) {
        for (i in 0 until group.childCount) {
            val v = group.getChildAt(i)
            if (v.isFocusable) { v.requestFocus(); return }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun show(id: String) {
        current = id
        categories.forEachIndexed { i, c -> (catViews[i].tag as (Boolean) -> Unit)(c.id == id) }
        detailTitle.text = categories.first { it.id == id }.title
        detail.removeAllViews()
        when (id) {
            "controllers" -> controllers()
            "games" -> games()
            "sound" -> sound()
            "pc" -> pc()
            "tv" -> tv()
            "about" -> about()
        }
    }

    // ------------------------------------------------------------------ categories

    private fun section(text: String) {
        detail.addView(TextView(this).apply {
            this.text = text.uppercase()
            textSize = 11f
            letterSpacing = 0.14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Palette.accent)
            setPadding(dp(4), dp(16), 0, dp(2))
        })
    }

    private fun row(title: String, subtitle: String? = null, value: String? = null, onClick: (() -> Unit)? = null) {
        val v = Ui.listRow(this, title, subtitle, value, onClick)
        // Pressing left goes back to this category, not whichever one happens to be level with the row.
        v.nextFocusLeftId = catViews[categories.indexOfFirst { it.id == current }].id
        detail.addView(v)
    }

    private fun controllers() {
        val pads = Controllers.connected()
        section("Connected")
        if (pads.isEmpty()) row("No controller connected", "Pair one using the steps below.")
        else pads.forEachIndexed { i, name -> row(name, "Player ${i + 1}", "✓") }
        section("Help")
        row("Pair a Bluetooth controller", "TV Settings > Remotes & Accessories > Pair accessory") {
            info("Pair a controller",
                "1. On the TV: Settings > Remotes & Accessories > Pair accessory.\n\n" +
                    "2. Put the controller in pairing mode:\n" +
                    "   - Xbox: hold the small pair button on top until the logo flashes fast.\n" +
                    "   - PlayStation: hold Share (or Create) + PS until the light flashes.\n\n" +
                    "3. Pick it from the list on the TV. Up to 4 controllers work at once.")
        }
        row("In-game menu", "Press Select + Start together (View + Menu on Xbox), or Back on the remote.")
        row("While streaming from your PC", "Hold View + Menu + LB + RB to stop. Hold Menu for mouse mode.")
    }

    private fun games() {
        section("Library")
        row("Add games", "Scan a code with your phone, or send from a PC") { AddGames.show(this) }
        row("Rescan games", "Look again for new games on the TV and USB drives") {
            lifecycleScope.launch {
                val n = withContext(Dispatchers.IO) { Library.scan(this@SettingsActivity).size }
                Toast.makeText(this@SettingsActivity, "Found $n game" + if (n == 1) "" else "s", Toast.LENGTH_SHORT).show()
            }
        }
        row("Online library", "Browse and download games from web pages you add") { goToTab(1) }
        row("Where RetroTV looks", "Games you send to the TV, the TV's Download folder, and any USB drive.")
        section("Cover art")
        val on = prefs.getBoolean("cover_art", true)
        row("Download cover art", "Box art from the free libretro thumbnail library", if (on) "On" else "Off") {
            prefs.edit().putBoolean("cover_art", !on).apply()
            show("games")
            focusRowWithTitle("Download cover art")
        }
        row("Refresh cover art", "Clear saved covers and look them up again") {
            Covers.clearCache(this)
            Toast.makeText(this, "Cover art will reload", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sound() {
        val ctx = this
        section("Music")
        val musicOn = SoundPrefs.musicOn(ctx)
        row("Background music", "Original RetroTV soundtrack in the menus. Stops while you play.", if (musicOn) "On" else "Off") {
            SoundPrefs.setMusicOn(ctx, !musicOn)
            Music.settingsChanged()
            show("sound"); focusRowWithTitle("Background music")
        }
        val level = SoundPrefs.musicLevel(ctx)
        row("Music volume", "Low, Medium or High", SoundPrefs.levelNames[level]) {
            SoundPrefs.setMusicLevel(ctx, (level + 1) % 3)
            Music.settingsChanged()
            show("sound"); focusRowWithTitle("Music volume")
        }
        section("Menu sounds")
        val sfxOn = SoundPrefs.sfxOn(ctx)
        row("Menu sounds", "Clicks and chimes when you move around and select", if (sfxOn) "On" else "Off") {
            SoundPrefs.setSfxOn(ctx, !sfxOn)
            show("sound"); focusRowWithTitle("Menu sounds")
        }
        row("Switching tabs", "Press LB / RB on the controller to move between Games, PC and Settings.")
    }

    private fun pc() {
        val pc = PcStore.get(this)
        section("Your PC")
        if (pc == null) {
            row("No PC linked", "Stream PS2, PS3, GameCube, Switch and more from your Windows PC.")
            row("Set up PC streaming", "One file on your PC - the TV links itself") {
                startActivity(Intent(this, PcStreamActivity::class.java))
            }
        } else {
            row(pc.name, "${pc.apps.size} game tiles · ${pc.address}")
            row("Open PC games", "See the games on ${pc.name}") { startActivity(Intent(this, PcStreamActivity::class.java)) }
            row("Stream quality", "Resolution, frame rate and bitrate (default 1080p, 60 fps)") {
                startActivity(Intent(this, com.limelight.preferences.StreamSettings::class.java))
            }
            row("Link a different PC", "Forget ${pc.name}") {
                AlertDialog.Builder(this)
                    .setTitle("Forget ${pc.name}?")
                    .setMessage("You can link it again any time - it's automatic.")
                    .setPositiveButton("Forget") { _, _ -> PcStore.forget(this); show("pc") }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        section("Setup file")
        val addr = UploadHub.address.value
        row("Get RetroTV PC Setup", if (addr != null) "On your PC, open $addr/pc" else "Connect the TV to Wi-Fi first")
    }

    private fun tv() {
        lifecycleScope.launch {
            val info = withContext(Dispatchers.Default) { DeviceCheck.info(applicationContext) }
            if (current != "tv") return@launch
            detail.removeAllViews()
            section("Performance")
            row(DeviceCheck.summary(this@SettingsActivity), "Speed score ${"%.1f".format(info.score)} · ${info.chip}")
            row("Open TV check", "Details for every console") { startActivity(Intent(this@SettingsActivity, TvCheckActivity::class.java)) }
            row("Reset speed history", "Forget slow-downs RetroTV measured while you played") {
                getSharedPreferences("speed", Context.MODE_PRIVATE).edit().clear().apply()
                Toast.makeText(this@SettingsActivity, "Speed history cleared", Toast.LENGTH_SHORT).show()
            }
            section("Network")
            val addr = UploadHub.address.value
            row("Address for sending games", addr ?: "Not connected to Wi-Fi")
        }
    }

    private fun about() {
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        section("RetroTV")
        row("Version", null, version)
        row("Emulation", "LibretroDroid with libretro emulator cores")
        row("PC streaming", "Moonlight engine (built in), Sunshine on your PC")
        row("Cover art", "libretro thumbnail library")
        row("Licence", "GPL-3.0 - free and open source")
        section("Help")
        val addr = UploadHub.address.value
        row("Report a problem", if (addr != null) "Open $addr/log on your PC and share what it shows" else "Connect the TV to Wi-Fi")
    }

    private fun info(title: String, text: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("OK", null).show()
    }

    private fun focusRowWithTitle(title: String) {
        detail.post {
            for (i in 0 until detail.childCount) {
                val row = detail.getChildAt(i) as? LinearLayout ?: continue
                val col = row.getChildAt(0) as? LinearLayout ?: continue
                if ((col.getChildAt(0) as? TextView)?.text == title) { row.requestFocus(); return@post }
            }
        }
    }
}
