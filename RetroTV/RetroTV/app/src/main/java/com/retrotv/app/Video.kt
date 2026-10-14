package com.retrotv.app

import android.content.Context
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable

/** Picture style, HD rendering for 3D consoles, and rewind settings. */
object Video {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("video", Context.MODE_PRIVATE)

    /** Picture styles, in the order the pause menu cycles through them. */
    val STYLES = listOf(
        "auto" to "Automatic",
        "smooth" to "Smooth HD",
        "crt" to "Retro CRT TV",
        "lcd" to "Handheld screen",
        "sharp" to "Sharp pixels",
        "original" to "Original",
    )
    fun styleName(id: String) = STYLES.firstOrNull { it.first == id }?.second ?: "Automatic"

    fun style(ctx: Context): String = prefs(ctx).getString("style", "auto") ?: "auto"
    fun setStyle(ctx: Context, id: String) = prefs(ctx).edit().putString("style", id).apply()
    fun nextStyle(ctx: Context): String {
        val i = STYLES.indexOfFirst { it.first == style(ctx) }
        val next = STYLES[(i + 1) % STYLES.size].first
        setStyle(ctx, next)
        return next
    }

    private val retro2d = setOf("nes", "snes", "gb", "gba", "genesis")

    /** Automatic: smooth edge-aware upscaling for 2D pixel art (looks clean on a big TV), untouched for 3D. */
    fun shaderFor(ctx: Context, sys: GameSystem): ShaderConfig = when (style(ctx)) {
        "smooth" -> ShaderConfig.CUT3()
        "crt" -> ShaderConfig.CRT
        "lcd" -> ShaderConfig.LCD
        "sharp" -> ShaderConfig.Sharp
        "original" -> ShaderConfig.Default
        else -> if (sys.id in retro2d) ShaderConfig.CUT3() else ShaderConfig.Sharp
    }

    fun hd3d(ctx: Context) = prefs(ctx).getBoolean("hd3d", false)
    fun setHd3d(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("hd3d", on).apply()

    /**
     * Emulator options that render 3D consoles at twice their original resolution - much sharper on a big
     * TV, but it needs a fast TV. Each emulator ignores the options that aren't its own.
     */
    fun coreVariables(ctx: Context, sys: GameSystem): Array<Variable> {
        if (!hd3d(ctx)) return emptyArray()
        return when (sys.id) {
            "n64" -> arrayOf(Variable("mupen64plus-43screensize", "1280x960"), Variable("mupen64plus-169screensize", "1920x1080"))
            "psx" -> arrayOf(Variable("pcsx_rearmed_neon_enhancement_enable", "enabled"), Variable("pcsx_rearmed_neon_enhancement_no_main", "enabled"))
            "psp" -> arrayOf(Variable("ppsspp_internal_resolution", "960x544"))
            "dc" -> arrayOf(Variable("flycast_internal_resolution", "1280x960"), Variable("reicast_internal_resolution", "1280x960"))
            "nds" -> arrayOf(Variable("melonds_render_mode", "opengl"), Variable("melonds_opengl_renderer_resolution", "2"))
            "gc" -> arrayOf(Variable("dolphin_efb_scale", "x2"))
            "ps2" -> arrayOf(Variable("play_res_mult", "2"))
            "3ds" -> arrayOf(Variable("citra_resolution_factor", "2x"))
            else -> emptyArray()
        }
    }

    fun rewind(ctx: Context) = prefs(ctx).getBoolean("rewind", true)
    fun setRewind(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("rewind", on).apply()
}
