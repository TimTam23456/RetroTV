package com.retrotv.app

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Base for RetroTV's menu screens:
 *  - menu sounds (move / select / back) for the remote and controller
 *  - background music while the screen is showing
 *  - LB / RB to switch between the Games, PC and Settings tabs
 */
abstract class RetroActivity : AppCompatActivity() {

    /** Which top tab this screen is (0 Games, 1 Browse, 2 PC, 3 Settings), or null if it isn't a tab. */
    open val tabIndex: Int? = null

    /** Menus play music; the in-game pause menu doesn't. */
    open val playsMusic = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { old, new ->
            // Only real moves between items (not focus being set up when a screen opens).
            if (old != null && new != null && old !== new) Sfx.play(Sfx.S.MOVE)
        }
    }

    override fun onResume() {
        super.onResume()
        if (playsMusic) Music.menuShown()
    }

    override fun onPause() {
        super.onPause()
        if (playsMusic) Music.menuHidden()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_L1 -> if (tabIndex != null) { switchTab(-1); return true }
                KeyEvent.KEYCODE_BUTTON_R1 -> if (tabIndex != null) { switchTab(+1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    val f = currentFocus
                    // Game cards play their own "launch" sound.
                    if (f != null && f.isClickable && f !is CardView) Sfx.play(Sfx.S.SELECT)
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> Sfx.play(Sfx.S.BACK)
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Moves one tab left (-1) or right (+1). */
    fun switchTab(dir: Int) {
        val from = tabIndex ?: return
        goToTab((from + dir).coerceIn(0, TABS.size - 1))
    }

    fun goToTab(to: Int) {
        val from = tabIndex ?: return
        if (Kids.on(this)) return // kids stay on their games
        if (to == from) return
        Sfx.play(Sfx.S.TAB)
        if (to == 0) {
            // Games is the home screen at the bottom of the stack: just go back to it.
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } else {
            startActivity(Intent(this, TABS[to]))
        }
        if (from != 0) finish()
        @Suppress("DEPRECATION")
        if (to > from) overridePendingTransition(R.anim.tab_in_right, R.anim.tab_out_left)
        else overridePendingTransition(R.anim.tab_in_left, R.anim.tab_out_right)
    }

    companion object {
        val TABS = listOf(MainActivity::class.java, LibraryActivity::class.java, PcStreamActivity::class.java, SettingsActivity::class.java)
        val TAB_NAMES = listOf("Games", "Browse", "PC", "Settings")
    }
}

/** The top bar shared by the tab screens: logo, LB [Games PC Settings] RB, extras, clock. */
object TabBar {
    fun build(activity: RetroActivity, selected: Int, extras: List<View> = emptyList()): LinearLayout {
        val ctx = activity
        val kids = Kids.on(ctx)
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(48), ctx.dp(22), ctx.dp(40), ctx.dp(6))
        }
        bar.addView(ImageView(ctx).apply {
            setImageDrawable(IconDrawable(Icon.GAMEPAD, Color.WHITE))
            background = GradientDrawable().apply { cornerRadius = ctx.dp(9).toFloat(); setColor(Palette.accent) }
            setPadding(ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5))
        }, LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32)))
        bar.addView(TextView(ctx).apply {
            text = "RetroTV"
            textSize = 20f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setTextColor(Palette.text)
            setPadding(ctx.dp(10), 0, ctx.dp(22), 0)
        })
        if (kids) {
            bar.addView(TextView(ctx).apply { text = "Kids"; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFF8FC0.toInt()) })
        } else {
            bar.addView(shoulderHint(ctx, "LB"))
            RetroActivity.TAB_NAMES.forEachIndexed { i, name ->
                bar.addView(Ui.tab(ctx, name, selected = i == selected) { activity.goToTab(i) })
            }
            bar.addView(shoulderHint(ctx, "RB"))
        }
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        extras.forEach { bar.addView(it) }
        bar.addView(TextClock(ctx).apply {
            format12Hour = "h:mm"
            format24Hour = "HH:mm"
            textSize = 18f
            setTextColor(Palette.text)
            setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL))
            setPadding(ctx.dp(18), 0, 0, 0)
        })
        return bar
    }

    /** Small "LB" / "RB" label showing which buttons switch tabs. */
    fun shoulderHint(ctx: android.content.Context, label: String) = TextView(ctx).apply {
        text = label
        textSize = 10f
        letterSpacing = 0.05f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Palette.muted)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(5).toFloat()
            setStroke(ctx.dp(1), 0x66A9A6B8)
        }
        setPadding(ctx.dp(6), ctx.dp(2), ctx.dp(6), ctx.dp(2))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(ctx.dp(6), 0, ctx.dp(6), 0) }
    }
}
