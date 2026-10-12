package com.retrotv.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/** After a PC stream, explain what went wrong (if anything) instead of silently dropping back. */
fun showStreamProblemIfAny(activity: androidx.appcompat.app.AppCompatActivity) {
    val ctx = activity.applicationContext
    activity.lifecycleScope.launch {
        val problem = withContext(Dispatchers.IO) { StreamLog.checkAfterStream(ctx) } ?: return@launch
        if (activity.isFinishing) return@launch
        val addr = UploadHub.address.value
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle(problem.title)
            .setMessage(problem.detail +
                "\n\nTo get this fixed, open " + (addr?.let { "$it/log" } ?: "the TV's /log page") +
                " in your PC's browser and copy everything into your chat with Claude.")
            .setPositiveButton("OK", null)
            .show()
    }
}

/** Shared building blocks for the console-style screens. */
object Ui {
    /** Card size in dp. */
    const val CARD = 136

    /** A top-bar tab: plain text, white pill when the remote is on it. */
    fun tab(ctx: android.content.Context, label: String, selected: Boolean, onClick: () -> Unit): android.widget.TextView =
        android.widget.TextView(ctx).apply {
            text = label
            textSize = 15f
            setTypeface(android.graphics.Typeface.create("sans-serif-medium", if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL))
            setPadding(ctx.dp(16), ctx.dp(7), ctx.dp(16), ctx.dp(7))
            isFocusable = true
            isClickable = true
            val normalText = if (selected) Palette.text else Palette.muted
            setTextColor(normalText)
            background = pill(ctx, if (selected) 0x26FFFFFF else Color.TRANSPARENT)
            setOnFocusChangeListener { _, has ->
                setTextColor(if (has) Palette.bg else normalText)
                background = pill(ctx, if (has) Color.WHITE else if (selected) 0x26FFFFFF else Color.TRANSPARENT)
            }
            setOnClickListener { onClick() }
        }

    /** Round icon button; shows its label next to the icon when selected. */
    fun iconButton(ctx: android.content.Context, icon: Icon, label: String, onClick: () -> Unit): android.view.View {
        val iconDrawable = IconDrawable(icon, Palette.text)
        val box = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            contentDescription = label
            setPadding(ctx.dp(9), ctx.dp(8), ctx.dp(9), ctx.dp(8))
            background = pill(ctx, 0x1FFFFFFF)
        }
        box.addView(android.widget.ImageView(ctx).apply { setImageDrawable(iconDrawable) },
            android.widget.LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)))
        val text = android.widget.TextView(ctx).apply {
            this.text = label
            textSize = 14f
            setTextColor(Palette.bg)
            setPadding(ctx.dp(7), 0, ctx.dp(4), 0)
            visibility = android.view.View.GONE
        }
        box.addView(text)
        box.setOnFocusChangeListener { _, has ->
            box.background = pill(ctx, if (has) Color.WHITE else 0x1FFFFFFF)
            iconDrawable.setColor(if (has) Palette.bg else Palette.text)
            text.visibility = if (has) android.view.View.VISIBLE else android.view.View.GONE
        }
        box.setOnClickListener { onClick() }
        box.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = ctx.dp(10) }
        return box
    }

    fun pill(ctx: android.content.Context, color: Int) = GradientDrawable().apply {
        cornerRadius = ctx.dp(100).toFloat()
        setColor(color)
    }

    fun panel(ctx: android.content.Context) = GradientDrawable().apply {
        cornerRadius = ctx.dp(18).toFloat()
        setColor(0xF2181822.toInt())
        setStroke(ctx.dp(1), 0x33FFFFFF)
    }

    /** A pop-up list of choices, styled like the rest of the app. */
    fun actionSheet(activity: android.app.Activity, title: String, options: List<Pair<String, () -> Unit>>) {
        val dialog = android.app.Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = panel(activity)
            setPadding(activity.dp(22), activity.dp(20), activity.dp(22), activity.dp(18))
        }
        box.addView(android.widget.TextView(activity).apply {
            text = title
            textSize = 20f
            maxLines = 2
            setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD))
            setTextColor(Palette.text)
            setPadding(activity.dp(4), 0, 0, activity.dp(10))
        })
        var first: android.view.View? = null
        for ((label, action) in options) {
            val row = listRow(activity, label, null) {
                dialog.dismiss()
                action()
            }
            box.addView(row)
            if (first == null) first = row
        }
        dialog.setContentView(box, android.view.ViewGroup.LayoutParams(activity.dp(420), android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        first?.requestFocus()
    }

    /** A selectable row with a title and optional subtitle (menus, settings). */
    fun listRow(ctx: android.content.Context, title: String, subtitle: String?, value: String? = null, onClick: (() -> Unit)?): android.widget.LinearLayout {
        val row = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            isFocusable = onClick != null
            isClickable = onClick != null
            // Info-only rows have no box, so it's clear they aren't buttons.
            background = if (onClick != null) rowBg(ctx, false) else null
        }
        val col = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        val t = android.widget.TextView(ctx).apply {
            text = title
            textSize = 16f
            setTextColor(Palette.text)
        }
        col.addView(t)
        val sub = if (subtitle != null) android.widget.TextView(ctx).apply {
            text = subtitle
            textSize = 13f
            setTextColor(Palette.muted)
            setPadding(0, ctx.dp(2), 0, 0)
        }.also { col.addView(it) } else null
        row.addView(col, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val v = if (value != null) android.widget.TextView(ctx).apply {
            text = value
            textSize = 14f
            setTextColor(Palette.muted)
            setPadding(ctx.dp(12), 0, 0, 0)
        }.also { row.addView(it) } else null
        if (onClick != null) {
            row.setOnFocusChangeListener { _, has ->
                row.background = rowBg(ctx, has)
                t.setTextColor(if (has) Palette.bg else Palette.text)
                sub?.setTextColor(if (has) 0xFF3A3A48.toInt() else Palette.muted)
                v?.setTextColor(if (has) 0xFF3A3A48.toInt() else Palette.muted)
            }
            row.setOnClickListener { onClick() }
        }
        row.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = ctx.dp(4) }
        return row
    }

    private fun rowBg(ctx: android.content.Context, focused: Boolean) = GradientDrawable().apply {
        cornerRadius = ctx.dp(10).toFloat()
        setColor(if (focused) Color.WHITE else 0x14FFFFFF)
    }
}

/** Background shading that keeps text readable over the game art. */
object Scrims {
    fun home(): android.graphics.drawable.Drawable {
        val side = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xF00E0F13.toInt(), 0xB30E0F13.toInt(), 0x4D0E0F13))
        val bottom = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x000E0F13, 0x660E0F13, 0xF20E0F13.toInt()))
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 900f
            setGradientCenter(0.05f, 0f)
            colors = intArrayOf(0x337C5CFF, 0x007C5CFF)
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(side, bottom, glow))
    }

    /** Plain screens (settings, PC, TV check): dark with a soft colour glow. */
    fun screen(): android.graphics.drawable.Drawable {
        val base = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF15132A.toInt(), Palette.bg, 0xFF0B0C10.toInt()))
        val glow = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 1000f
            setGradientCenter(0.9f, 0f)
            colors = intArrayOf(0x2E7C5CFF, 0x007C5CFF)
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(base, glow))
    }
}

/** Art for the action cards (Add games, TV check...): colour, big icon, title. */
class ActionArt(private val title: String, icon: Icon, private val color: Int) : android.graphics.drawable.Drawable() {
    private val iconDrawable = IconDrawable(icon, Color.WHITE)
    private val bg = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val text = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        bg.shader = android.graphics.LinearGradient(0f, 0f, w, h,
            CoverDrawable.lighten(color, 0.1f), CoverDrawable.darken(color, 0.55f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(b, bg)
        val s = (w * 0.34f).toInt()
        val left = b.left + ((w - s) / 2).toInt()
        val top = b.top + (h * 0.2f).toInt()
        iconDrawable.setBounds(left, top, left + s, top + s)
        iconDrawable.draw(canvas)
        text.textSize = w * 0.105f
        val tw = text.measureText(title)
        canvas.drawText(title, b.left + (w - tw) / 2, b.top + h * 0.82f, text)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
}

/** Connected game controllers. */
object Controllers {
    fun connected(): List<String> = android.view.InputDevice.getDeviceIds().toList()
        .mapNotNull { android.view.InputDevice.getDevice(it) }
        .filter { d ->
            !d.isVirtual &&
                (d.sources and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD ||
                    d.sources and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK)
        }
        .map { it.name }
        .distinct()
}

/** "Add games": a QR code for the phone, plus the address for a PC. */
object AddGames {
    fun show(activity: androidx.appcompat.app.AppCompatActivity) {
        val addr = UploadHub.address.value
        val dialog = android.app.Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            background = Ui.panel(activity)
            setPadding(activity.dp(28), activity.dp(26), activity.dp(28), activity.dp(24))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        if (addr != null) {
            val qr = android.widget.ImageView(activity).apply {
                setImageBitmap(Qr.bitmap(addr, activity.dp(200)))
                background = GradientDrawable().apply { cornerRadius = activity.dp(12).toFloat(); setColor(Color.WHITE) }
                setPadding(activity.dp(10), activity.dp(10), activity.dp(10), activity.dp(10))
            }
            box.addView(qr, android.widget.LinearLayout.LayoutParams(activity.dp(200), activity.dp(200)))
        }
        val col = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(if (addr != null) activity.dp(26) else 0, 0, 0, 0)
        }
        fun line(text: String, size: Float, color: Int, bold: Boolean = false, top: Int = 0) =
            android.widget.TextView(activity).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                if (bold) setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD))
                setPadding(0, activity.dp(top), 0, 0)
                setLineSpacing(0f, 1.15f)
            }
        col.addView(line("Add games", 24f, Palette.text, bold = true))
        if (addr != null) {
            col.addView(line("Scan with your phone's camera, choose the console, and pick your game files.", 15f, Palette.muted, top = 8))
            col.addView(line("On a PC, open:", 13f, Palette.muted, top = 14))
            col.addView(line(addr, 18f, Palette.text, bold = true, top = 2))
        } else {
            col.addView(line("Connect the TV to your home Wi-Fi to send games from your phone or PC.", 15f, Palette.muted, top = 8))
        }
        col.addView(line("Or plug in a USB drive - RetroTV finds games on it automatically.", 13f, Palette.muted, top = 14))
        col.addView(line("Use games you own.", 12f, 0xFF77748A.toInt(), top = 10))
        val done = Ui.listRow(activity, "Done", null) { dialog.dismiss() }
        col.addView(done)
        box.addView(col, android.widget.LinearLayout.LayoutParams(activity.dp(340), android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.setContentView(box)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        done.requestFocus()
    }
}

/** QR codes (ZXing). */
object Qr {
    fun bitmap(text: String, size: Int): android.graphics.Bitmap {
        val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 0)
        val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(m.width * m.height) { i -> if (m.get(i % m.width, i / m.width)) Color.BLACK else Color.WHITE }
        return android.graphics.Bitmap.createBitmap(px, m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
    }
}
