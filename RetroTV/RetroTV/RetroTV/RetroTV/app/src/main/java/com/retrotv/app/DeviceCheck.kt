package com.retrotv.app

import android.app.ActivityManager
import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import android.os.Process
import java.io.File

/**
 * Works out how powerful the TV is and which consoles it can handle.
 *
 * Score = (how fast the best CPU core is per GHz) x (its top speed in GHz) x (graphics chip factor).
 * Rough reference points: budget TV ~1.5, Chromecast with Google TV ~2.2,
 * recent Sony Bravia ~2.5-3, Nvidia Shield ~4.5, 2018 flagship phone ~7.
 */
object DeviceCheck {

    data class Info(
        val tvName: String,
        val chip: String,
        val coreName: String,
        val cores: Int,
        val maxGhz: Double,
        val is64Bit: Boolean,
        val ramGb: Double,
        val gpu: String,
        val gles: Int,
        val score: Double,
    )

    enum class Rating(val label: String, val color: Int) {
        GREAT("Runs great", Palette.good),
        OK("Playable - some games may slow down", Palette.warn),
        SLOW("Too slow on this TV", Palette.bad),
        UNSUPPORTED("Not supported on this TV", Palette.bad),
    }

    data class Verdict(val rating: Rating, val reason: String) {
        val playable get() = rating == Rating.GREAT || rating == Rating.OK
    }

    // Relative speed per GHz of common ARM cores (Cortex-A53 = 1.0).
    private val coreSpeed = mapOf(
        0xd04 to ("Cortex-A35" to 0.9),
        0xd03 to ("Cortex-A53" to 1.0),
        0xd05 to ("Cortex-A55" to 1.15),
        0xd46 to ("Cortex-A510" to 1.3),
        0xd07 to ("Cortex-A57" to 1.65),
        0xd08 to ("Cortex-A72" to 1.9),
        0xd09 to ("Cortex-A73" to 2.0),
        0xd0a to ("Cortex-A75" to 2.4),
        0xd0b to ("Cortex-A76" to 3.0),
        0xd0d to ("Cortex-A77" to 3.4),
        0xd41 to ("Cortex-A78" to 3.8),
        0xd47 to ("Cortex-A710" to 4.1),
        0xd4d to ("Cortex-A715" to 4.4),
        0xd44 to ("Cortex-X1" to 4.6),
        0xd48 to ("Cortex-X2" to 5.0),
        0xd4e to ("Cortex-X3" to 5.6),
        0xc0f to ("Cortex-A15" to 1.4),
        0xc0d to ("Cortex-A17" to 1.3),
        0xc09 to ("Cortex-A9" to 0.8),
        0xc07 to ("Cortex-A7" to 0.6),
        0x800 to ("Kryo 2xx/3xx" to 2.4),
        0x801 to ("Kryo silver" to 1.0),
        0x802 to ("Kryo 385 Gold" to 2.4),
        0x803 to ("Kryo 385 Silver" to 1.15),
        0x804 to ("Kryo 485 Gold" to 3.0),
        0x805 to ("Kryo 485 Silver" to 1.15),
    )

    @Volatile
    private var cached: Info? = null

    fun info(ctx: Context): Info = cached ?: measure(ctx).also { cached = it }

    fun rate(sys: GameSystem, info: Info): Verdict {
        if (sys.needs64Bit && !info.is64Bit) {
            return Verdict(Rating.UNSUPPORTED, "Needs a 64-bit TV system; this TV runs 32-bit apps.")
        }
        if (info.gles < sys.minGles) {
            return Verdict(Rating.UNSUPPORTED,
                "Needs OpenGL ES ${sys.minGles / 10}.${sys.minGles % 10}; this TV has ${info.gles / 10}.${info.gles % 10}.")
        }
        return when {
            info.score >= sys.goodScore -> Verdict(Rating.GREAT, "")
            info.score >= sys.minScore -> Verdict(Rating.OK, "Lighter games run fine; demanding ones may stutter.")
            else -> Verdict(Rating.SLOW,
                "Needs about ${"%.1f".format(sys.minScore)} speed; this TV scores ${"%.1f".format(info.score)}.")
        }
    }

    /** Combines the hardware check with real speed seen while playing. */
    fun verdict(ctx: Context, sys: GameSystem): Verdict {
        val base = rate(sys, info(ctx))
        val measured = SpeedLog.get(ctx, sys)
        if (measured != null && measured < 0.85 && base.playable) {
            return Verdict(Rating.SLOW, "Ran at ${(measured * 100).toInt()}% speed last time you played.")
        }
        return base
    }

    /** One-line summary for the home screen, e.g. "Good up to PSP". */
    fun summary(ctx: Context): String {
        val best = Systems.all.lastOrNull { rate(it, info(ctx)).rating == Rating.GREAT }
        val ok = Systems.all.lastOrNull { rate(it, info(ctx)).playable }
        return when {
            best == null && ok == null -> "Retro consoles only"
            best != null && ok != null && ok != best -> "Great up to ${best.name}, playable up to ${ok.name}"
            else -> "Great up to ${(best ?: ok)!!.name}"
        }
    }

    // ---------- measuring ----------

    private fun measure(ctx: Context): Info {
        val tvName = listOf(Build.MANUFACTURER, Build.MODEL)
            .map { it.trim() }.distinct()
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

        val chip = if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.isNotBlank() && Build.SOC_MODEL != Build.UNKNOWN) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim()
        } else {
            Build.HARDWARE.uppercase()
        }

        val parts = readCpuParts()
        val best = parts.mapNotNull { coreSpeed[it] }.maxByOrNull { it.second }
        val ghz = readMaxGhz()
        val perGhz = best?.second ?: 1.1 // unknown core: assume a modest one
        val coreName = best?.first ?: "Unknown CPU"

        val (gpu, gles) = readGpu(ctx)
        val gpuFactor = gpuFactor(gpu)

        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val ramGb = mi.totalMem / 1_073_741_824.0
        // Under 1.5 GB of RAM, heavy systems run out of memory regardless of speed.
        val ramFactor = when {
            ramGb < 1.2 -> 0.75
            ramGb < 1.7 -> 0.9
            else -> 1.0
        }

        return Info(
            tvName = tvName,
            chip = chip,
            coreName = coreName,
            cores = Runtime.getRuntime().availableProcessors(),
            maxGhz = ghz,
            // What matters is the app process: many TVs have 64-bit chips but only run 32-bit apps.
            is64Bit = Process.is64Bit(),
            ramGb = ramGb,
            gpu = gpu,
            gles = gles,
            score = perGhz * ghz * gpuFactor * ramFactor,
        )
    }

    private fun readCpuParts(): Set<Int> = try {
        File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("CPU part") }
            .mapNotNull { it.substringAfter(':').trim().removePrefix("0x").toIntOrNull(16) }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }

    private fun readMaxGhz(): Double {
        val khz = (0 until 16).mapNotNull { i ->
            try {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull()
            } catch (_: Exception) {
                null
            }
        }.maxOrNull()
        return if (khz != null && khz > 0) khz / 1_000_000.0 else 1.4
    }

    private fun gpuFactor(gpu: String): Double {
        val g = gpu.lowercase()
        return when {
            "nvidia" in g || "tegra" in g -> 1.35
            "adreno" in g -> 1.15
            "mali-g51" in g || "mali-g52" in g || "mali-g57" in g -> 0.9
            "mali-g31" in g || "mali-4" in g || "mali-t" in g || "ge8" in g || "sgx" in g -> 0.7
            "mali-g" in g -> 1.0
            else -> 0.9
        }
    }

    /** Opens a tiny invisible OpenGL context just to ask the graphics chip its name and version. */
    private fun readGpu(ctx: Context): Pair<String, Int> {
        val reqGles = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .deviceConfigurationInfo.reqGlEsVersion
        val fallbackGles = (reqGles shr 16) * 10 + (reqGles and 0xFFFF)
        return try {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            EGL14.eglInitialize(display, ver, 0, ver, 1)
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0)
            val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, surface, surface, context)
            val renderer = GLES20.glGetString(GLES20.GL_RENDERER) ?: "Unknown GPU"
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglTerminate(display)
            renderer to fallbackGles
        } catch (_: Exception) {
            "Unknown GPU" to fallbackGles
        }
    }
}

/** Remembers how fast each console actually ran last time (1.0 = full speed). */
object SpeedLog {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("speed", Context.MODE_PRIVATE)

    fun get(ctx: Context, sys: GameSystem): Double? =
        prefs(ctx).getFloat(sys.id, -1f).takeIf { it > 0 }?.toDouble()

    fun put(ctx: Context, sys: GameSystem, speed: Double) {
        prefs(ctx).edit().putFloat(sys.id, speed.toFloat()).apply()
    }
}
