package com.retrotv.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Box art for the library.
 *
 * Covers come from the free libretro thumbnail library (thumbnails.libretro.com). For each console we
 * download the list of available covers once, match the game's file name against it (see CoverMatch),
 * then download and cache the image. Anything without a match gets a designed cover instead.
 */
object Covers {

    private const val BASE = "https://thumbnails.libretro.com/"
    private const val INDEX_MAX_AGE = 30L * 24 * 3600 * 1000
    private const val MISS_RETRY = 7L * 24 * 3600 * 1000

    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(4)
    private val jobs = WeakHashMap<ImageView, Job>()
    private val folderLocks = ConcurrentHashMap<String, Mutex>()
    private val indexes = ConcurrentHashMap<String, List<String>>()

    fun enabled(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("cover_art", true)

    private fun dir(ctx: Context, sub: String) = File(ctx.cacheDir, "art/$sub").apply { mkdirs() }

    // ---------------------------------------------------------------- binding to views

    /** Shows the game's cover in [view]: the designed cover straight away, the real one when ready. */
    fun bindGame(view: ImageView, game: Game) {
        val key = "g:" + game.file.absolutePath
        jobs.remove(view)?.cancel()
        view.tag = key
        mem.get(key)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(CoverDrawable(game.title, game.system.short, game.system.color))
        if (!enabled(view.context)) return
        val ctx = view.context.applicationContext
        jobs[view] = scope.launch {
            val bmp = withContext(io) { coverFile(ctx, game)?.let { decode(it, 420) } } ?: return@launch
            mem.put(key, bmp)
            if (view.tag == key) {
                view.setImageBitmap(bmp)
                view.alpha = 0f
                view.animate().alpha(1f).setDuration(180).start()
            }
        }
    }

    /** Small blurred version of the cover for the full-screen backdrop, or null if there's no art. */
    suspend fun backdrop(ctx: Context, game: Game): Bitmap? = withContext(io) {
        val key = "b:" + game.file.absolutePath
        mem.get(key) ?: run {
            if (!enabled(ctx)) return@run null
            val f = coverFile(ctx, game) ?: return@run null
            val small = decode(f, 64) ?: return@run null
            blur(small).also { mem.put(key, it) }
        }
    }

    fun clearCache(ctx: Context) {
        mem.evictAll()
        indexes.clear()
        File(ctx.cacheDir, "art").deleteRecursively()
        ctx.getSharedPreferences("art", Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ---------------------------------------------------------------- finding the cover

    private fun foldersFor(game: Game): List<String> {
        val all = game.system.artFolders
        if (all.size < 2) return all
        val ext = game.file.extension.lowercase()
        val preferred = when {
            ext == "gbc" -> all.firstOrNull { it.endsWith("Game Boy Color") }
            ext == "sms" -> all.firstOrNull { "Master System" in it }
            ext == "gg" -> all.firstOrNull { it.endsWith("Game Gear") }
            ext == "wbfs" || ext == "wad" -> all.firstOrNull { it.endsWith("Wii") }
            else -> null
        }
        return listOfNotNull(preferred) + all.filter { it != preferred }
    }

    /** The cached cover image file for a game, downloading it if needed. */
    private suspend fun coverFile(ctx: Context, game: Game): File? {
        val url = resolveUrl(ctx, game) ?: return null
        val file = File(dir(ctx, "img"), sha1(url) + ".png")
        if (file.exists() && file.length() > 0) return file
        return if (download(url, file)) file else null
    }

    private suspend fun resolveUrl(ctx: Context, game: Game): String? {
        val prefs = ctx.getSharedPreferences("art", Context.MODE_PRIVATE)
        val key = game.file.absolutePath
        prefs.getString(key, null)?.let { saved ->
            if (saved.startsWith("http")) return saved
            val missAt = saved.removePrefix("-").toLongOrNull() ?: 0
            if (System.currentTimeMillis() - missAt < MISS_RETRY) return null
        }
        val title = game.file.nameWithoutExtension
        for (folder in foldersFor(game)) {
            val names = index(ctx, folder)
            val match = CoverMatch.best(title, names) ?: continue
            val url = BASE + enc(folder) + "/Named_Boxarts/" + enc(match) + ".png"
            prefs.edit().putString(key, url).apply()
            return url
        }
        // Only remember a miss if we actually had the lists to look in.
        if (foldersFor(game).all { indexes[it]?.isNotEmpty() == true }) {
            prefs.edit().putString(key, "-" + System.currentTimeMillis()).apply()
        }
        return null
    }

    /** Names of every cover available for one console (cached for a month). */
    private suspend fun index(ctx: Context, folder: String): List<String> {
        indexes[folder]?.let { return it }
        return folderLocks.getOrPut(folder) { Mutex() }.withLock {
            indexes[folder]?.let { return@withLock it }
            val file = File(dir(ctx, "index"), sha1(folder) + ".txt")
            val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < INDEX_MAX_AGE
            val names = if (fresh) {
                file.readLines()
            } else {
                val fetched = fetchIndex(folder)
                if (fetched.isNotEmpty()) file.writeText(fetched.joinToString("\n"))
                fetched.ifEmpty { if (file.exists()) file.readLines() else emptyList() }
            }
            if (names.isNotEmpty()) indexes[folder] = names
            names
        }
    }

    private fun fetchIndex(folder: String): List<String> = try {
        val conn = open(BASE + enc(folder) + "/Named_Boxarts/")
        if (conn.responseCode != 200) emptyList() else {
            val html = conn.inputStream.bufferedReader().readText()
            Regex("""href="([^"?/][^"]*?)\.png"""").findAll(html)
                .map { URLDecoder.decode(it.groupValues[1].replace("+", "%2B"), "UTF-8") }
                .toList()
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun download(url: String, dest: File): Boolean = try {
        val conn = open(url)
        if (conn.responseCode != 200) false else {
            val part = File(dest.path + ".part")
            conn.inputStream.use { input -> FileOutputStream(part).use { input.copyTo(it) } }
            part.renameTo(dest)
        }
    } catch (_: Exception) {
        false
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "RetroTV")
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- bitmaps

    /** Decodes an image scaled down so its longest side is about [maxSide] pixels. */
    fun decode(file: File, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: Throwable) {
        null
    }

    /** Fast blur for a small bitmap (three box-blur passes). */
    private fun blur(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            boxPass(px, tmp, w, h, 2, horizontal = true)
            boxPass(tmp, px, w, h, 2, horizontal = false)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val outer = if (horizontal) h else w
        val inner = if (horizontal) w else h
        for (o in 0 until outer) {
            for (i in 0 until inner) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, inner - 1)
                    val c = if (horizontal) src[o * w + j] else src[j * w + o]
                    a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                val out = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
                if (horizontal) dst[o * w + i] = out else dst[i * w + o] = out
            }
        }
    }
}

/**
 * A designed cover for games without box art (and for PC tiles): the console's colour,
 * its name across the top, and the game's title.
 */
class CoverDrawable(
    private val title: String,
    private val label: String,
    private val color: Int,
    private val footer: String? = null,
) : Drawable() {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.18f
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }
    private val footPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x99FFFFFF.toInt()
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.1f
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        if (w <= 0 || h <= 0) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())

        // Background: console colour fading to near-black.
        bgPaint.shader = LinearGradient(0f, 0f, w * 0.4f, h, lighten(color, 0.12f), darken(color, 0.62f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // Soft diagonal light streaks for depth.
        linePaint.strokeWidth = w * 0.18f
        linePaint.color = 0x14FFFFFF
        canvas.drawLine(-w * 0.2f, h * 0.55f, w * 1.2f, h * 0.15f, linePaint)
        linePaint.strokeWidth = w * 0.06f
        linePaint.color = 0x10FFFFFF
        canvas.drawLine(-w * 0.2f, h * 0.78f, w * 1.2f, h * 0.38f, linePaint)

        // Console name across the top, with an accent bar.
        val pad = w * 0.09f
        labelPaint.textSize = w * 0.075f
        bgPaint.shader = null
        bgPaint.color = lighten(color, 0.35f)
        canvas.drawRect(pad, pad, pad + w * 0.14f, pad + w * 0.022f, bgPaint)
        canvas.drawText(label, pad, pad + w * 0.022f + labelPaint.textSize * 1.35f, labelPaint)

        // Title, as large as fits in up to 4 lines.
        val textW = (w - pad * 2).toInt()
        var size = w * 0.15f
        var layout: StaticLayout
        do {
            titlePaint.textSize = size
            layout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, textW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 0.95f)
                .setMaxLines(4)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
            size *= 0.9f
        } while ((layout.height > h * 0.5f || layout.getEllipsisCount(layout.lineCount - 1) > 0) && size > w * 0.07f)
        canvas.save()
        canvas.translate(pad, h - pad - layout.height - (if (footer != null) w * 0.12f else 0f))
        layout.draw(canvas)
        canvas.restore()

        if (footer != null) {
            footPaint.textSize = w * 0.065f
            canvas.drawText(footer, pad, h - pad, footPaint)
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    companion object {
        fun lighten(c: Int, f: Float): Int = mix(c, 0xFFFFFFFF.toInt(), f)
        fun darken(c: Int, f: Float): Int = mix(c, 0xFF000000.toInt(), f)
        private fun mix(a: Int, b: Int, f: Float): Int {
            fun ch(s: Int) = (((a shr s) and 0xFF) * (1 - f) + ((b shr s) and 0xFF) * f).toInt() and 0xFF
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/** Colours for the PC tiles that RetroTV PC Setup creates. */
object PcArt {
    fun colorFor(name: String): Int = when (name.lowercase()) {
        "ps2" -> 0xFF1E3A8A.toInt()
        "ps3" -> 0xFF2B2F36.toInt()
        "gamecube / wii", "gamecube", "wii" -> 0xFF5B3FA8.toInt()
        "switch" -> 0xFFC8102E.toInt()
        "wii u" -> 0xFF0B7FAB.toInt()
        "3ds" -> 0xFFC81E3A.toInt()
        "xbox", "xbox 360" -> 0xFF1F7A1F.toInt()
        "ps vita" -> 0xFF1D4ED8.toInt()
        "steam big picture" -> 0xFF1B2838.toInt()
        "desktop" -> 0xFF374151.toInt()
        else -> 0xFF4B5563.toInt()
    }

    fun drawable(app: PcApp) = CoverDrawable(app.name, "VIA PC", colorFor(app.name), PcGames.describe(app.name).uppercase())
}
