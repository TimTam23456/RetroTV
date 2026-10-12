package com.retrotv.app

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Sound settings (Settings > Sound). */
object SoundPrefs {
    private fun p(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun musicOn(ctx: Context) = p(ctx).getBoolean("music_on", true)
    fun sfxOn(ctx: Context) = p(ctx).getBoolean("sfx_on", true)
    /** 0 = low, 1 = medium, 2 = high */
    fun musicLevel(ctx: Context) = p(ctx).getInt("music_level", 1)
    fun setMusicOn(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("music_on", on).apply()
    fun setSfxOn(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("sfx_on", on).apply()
    fun setMusicLevel(ctx: Context, level: Int) = p(ctx).edit().putInt("music_level", level).apply()
    val levelNames = listOf("Low", "Medium", "High")
}

/** Menu sound effects (all original, synthesized for RetroTV). */
object Sfx {
    enum class S { MOVE, SELECT, BACK, TAB, LAUNCH }

    private var pool: SoundPool? = null
    private val ids = HashMap<S, Int>()
    private val last = HashMap<S, Long>()
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val sp = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
        ids[S.MOVE] = sp.load(app, R.raw.sfx_move, 1)
        ids[S.SELECT] = sp.load(app, R.raw.sfx_select, 1)
        ids[S.BACK] = sp.load(app, R.raw.sfx_back, 1)
        ids[S.TAB] = sp.load(app, R.raw.sfx_tab, 1)
        ids[S.LAUNCH] = sp.load(app, R.raw.sfx_launch, 1)
        pool = sp
    }

    fun play(s: S) {
        val sp = pool ?: return
        if (!SoundPrefs.sfxOn(app)) return
        val now = SystemClock.uptimeMillis()
        // Don't machine-gun the same sound (holding a direction, or two listeners firing at once).
        if (now - (last[s] ?: 0) < (if (s == S.MOVE) 45 else 120)) return
        last[s] = now
        val vol = if (s == S.MOVE) 0.55f else 0.8f
        sp.play(ids[s] ?: return, vol, vol, 1, 0, 1f)
    }
}

/**
 * Background music for the menus. Fades in when a menu screen is showing and fades out when a game
 * or a PC stream starts (or the app goes to the background).
 */
object Music {
    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var menusShowing = 0
    private var volume = 0f
    private var target = 0f
    private lateinit var app: Context

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    private fun levelVolume() = when (SoundPrefs.musicLevel(app)) {
        0 -> 0.15f
        2 -> 0.55f
        else -> 0.32f
    }

    fun menuShown() {
        menusShowing++
        handler.removeCallbacks(stopIfHidden)
        update()
    }

    fun menuHidden() {
        menusShowing = maxOf(0, menusShowing - 1)
        // Wait a moment: moving between two menu screens hides one just before showing the next.
        handler.removeCallbacks(stopIfHidden)
        handler.postDelayed(stopIfHidden, 450)
    }

    /** Call after the sound settings change. */
    fun settingsChanged() = update()

    private val stopIfHidden = Runnable { update() }

    private fun update() {
        val shouldPlay = menusShowing > 0 && SoundPrefs.musicOn(app)
        if (shouldPlay) {
            val p = player ?: create() ?: return
            if (!p.isPlaying) p.start()
            fadeTo(levelVolume(), 1500)
        } else {
            fadeTo(0f, 600)
        }
    }

    private fun create(): MediaPlayer? = try {
        MediaPlayer.create(app, R.raw.menu_music)?.apply {
            isLooping = true
            setVolume(0f, 0f)
            volume = 0f
            player = this
        }
    } catch (_: Exception) {
        null
    }

    private var fadeStart = 0L
    private var fadeFrom = 0f
    private var fadeMs = 1L

    private fun fadeTo(v: Float, ms: Long) {
        target = v
        fadeFrom = volume
        fadeStart = SystemClock.uptimeMillis()
        fadeMs = ms
        handler.removeCallbacks(fadeStep)
        handler.post(fadeStep)
    }

    private val fadeStep = object : Runnable {
        override fun run() {
            val p = player ?: return
            val t = ((SystemClock.uptimeMillis() - fadeStart).toFloat() / fadeMs).coerceIn(0f, 1f)
            volume = fadeFrom + (target - fadeFrom) * t
            try {
                p.setVolume(volume, volume)
                if (t < 1f) {
                    handler.postDelayed(this, 30)
                } else if (target == 0f && p.isPlaying) {
                    p.pause()
                }
            } catch (_: IllegalStateException) {
                player = null
            }
        }
    }
}
