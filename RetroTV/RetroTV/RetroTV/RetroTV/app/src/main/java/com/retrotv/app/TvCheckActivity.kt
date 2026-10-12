package com.retrotv.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Is my TV good enough?" screen: shows the TV's hardware and a verdict for every console. */
class TvCheckActivity : AppCompatActivity() {

    private lateinit var infoBox: LinearLayout
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.bg)
            setPadding(dp(48), dp(32), dp(48), dp(24))
        }

        infoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(32), 0)
        }
        infoBox.addView(heading("TV check"))
        infoBox.addView(text("Checking your TV...", Palette.muted, 15f))
        root.addView(infoBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(32))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(listBox)
        }
        root.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.6f))
        setContentView(root)

        lifecycleScope.launch {
            val info = withContext(Dispatchers.Default) { DeviceCheck.info(applicationContext) }
            show(info)
        }
    }

    private fun show(info: DeviceCheck.Info) {
        infoBox.removeAllViews()
        infoBox.addView(heading("TV check"))
        infoBox.addView(text(info.tvName, Palette.muted, 15f).apply { setPadding(0, dp(2), 0, dp(18)) })

        fun row(label: String, value: String) {
            infoBox.addView(text(label.uppercase(), Palette.accent, 11f).apply {
                letterSpacing = 0.12f
                setTypeface(typeface, Typeface.BOLD)
            })
            infoBox.addView(text(value, Palette.text, 15f).apply { setPadding(0, dp(1), 0, dp(10)) })
        }
        row("Chip", info.chip)
        row("Processor", "${info.cores} cores, best is ${info.coreName} at ${"%.1f".format(info.maxGhz)} GHz")
        row("Graphics", "${info.gpu} (OpenGL ES ${info.gles / 10}.${info.gles % 10})")
        row("Memory", "${"%.1f".format(info.ramGb)} GB")
        row("Apps run as", if (info.is64Bit) "64-bit" else "32-bit (limits newer consoles)")
        row("Speed score", "%.1f".format(info.score) + "   (Shield TV is about 4.5)")

        infoBox.addView(text(
            "Ratings also update from real play: if a game runs slow, that console gets marked down.",
            Palette.muted, 13f).apply { setPadding(0, dp(6), 0, dp(16)) })

        val pc = Button(this).apply {
            text = "Play newer consoles from your PC"
            isAllCaps = false
            textSize = 15f
            setTextColor(Palette.text)
            background = focusTile()
            setPadding(dp(18), dp(12), dp(18), dp(12))
            growOnFocus()
            setOnClickListener { startActivity(Intent(context, PcStreamActivity::class.java)) }
        }
        infoBox.addView(pc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        listBox.removeAllViews()
        listBox.addView(text("What this TV can play", Palette.text, 20f).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), 0, 0, dp(10))
        })
        for (sys in Systems.all) {
            val v = DeviceCheck.verdict(this, sys)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true
                background = focusTile()
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            top.addView(text(sys.name, Palette.text, 16f),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(text(v.rating.label, v.rating.color, 14f).apply { gravity = Gravity.END })
            card.addView(top)
            val detail = listOfNotNull(v.reason.ifEmpty { null }, sys.note).joinToString(" ")
            if (detail.isNotEmpty()) card.addView(text(detail, Palette.muted, 13f).apply { setPadding(0, dp(3), 0, 0) })
            if (!v.playable && sys.heavy) {
                card.addView(text("Tip: stream it from your PC instead.", Palette.accent, 13f).apply { setPadding(0, dp(3), 0, 0) })
                card.isClickable = true
                card.setOnClickListener { startActivity(Intent(this, PcStreamActivity::class.java)) }
            }
            listBox.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(dp(4), dp(5), dp(4), dp(5)) })
        }
        pc.requestFocus()
    }

    private fun heading(t: String) = text(t, Palette.text, 32f).apply { setTypeface(typeface, Typeface.BOLD) }

    private fun text(t: String, color: Int, size: Float) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        setLineSpacing(0f, 1.15f)
    }
}
