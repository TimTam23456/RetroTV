package com.retrotv.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

data class Game(val file: File, val system: GameSystem, val title: String)

/** Finds games on the TV: uploaded games, the Download folder and any USB drive. */
object Library {

    fun gamesDir(ctx: Context): File = File(ctx.filesDir, "games").apply { mkdirs() }

    private val skipDirs = setOf(
        "android", "dcim", "movies", "music", "pictures", "podcasts", "ringtones",
        "alarms", "notifications", "lost.dir", "system volume information", "\$recycle.bin",
    )

    fun roots(ctx: Context): List<File> = candidates(ctx).filter { it.isDirectory && it.canRead() }.distinctBy { safeCanonical(it) }

    /** Every place a USB stick or SD card might appear. TVs differ, so we ask Android several ways. */
    fun candidates(ctx: Context): List<File> {
        val out = mutableListOf(gamesDir(ctx))
        try { out += Environment.getExternalStorageDirectory() } catch (_: Exception) { }
        // 1. The storage volumes Android reports (USB drives, card readers, SD cards).
        try {
            val sm = ctx.getSystemService(StorageManager::class.java)
            for (v in sm?.storageVolumes.orEmpty()) {
                val dir: File? = if (Build.VERSION.SDK_INT >= 30) v.directory else try {
                    v.javaClass.getMethod("getPathFile").invoke(v) as? File
                } catch (_: Exception) { null }
                if (dir != null) out += dir
            }
        } catch (_: Exception) { }
        // 2. Each volume's app folder (always visible) tells us where that volume is mounted.
        try {
            ctx.getExternalFilesDirs(null).filterNotNull().forEach { f ->
                val p = f.absolutePath
                val i = p.indexOf("/Android/")
                if (i > 0) out += File(p.substring(0, i))
            }
        } catch (_: Exception) { }
        // 3. The usual mount points: /storage/XXXX-XXXX, plus a few TV-maker specials.
        File("/storage").listFiles()?.forEach { f ->
            val n = f.name.lowercase()
            if (f.isDirectory && n != "emulated" && n != "self") out += f
        }
        listOf("/mnt/usb", "/mnt/usb_storage", "/storage/usb", "/mnt/media_rw", "/mnt/sdcard/usbStorage", "/mnt/extSdCard").forEach { p ->
            val f = File(p)
            if (f.isDirectory) { out += f; f.listFiles()?.filter { it.isDirectory }?.forEach { out += it } }
        }
        return out.distinctBy { safeCanonical(it) }
    }

    fun hasStoragePermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    data class DriveReport(val path: String, val readable: Boolean, val items: Int, val games: Int, val internal: Boolean)

    /** What RetroTV can see on each drive - for the "Check USB drive" screen. */
    fun diagnose(ctx: Context): List<DriveReport> = candidates(ctx).map { root ->
        val readable = root.isDirectory && root.canRead()
        val items = if (readable) root.listFiles()?.size ?: -1 else -1
        val games = if (readable && items >= 0) LinkedHashMap<String, Game>().also { walk(root, 0, it) }.size else 0
        val internal = safeCanonical(root).startsWith(safeCanonical(ctx.filesDir)) || root.absolutePath.contains("/emulated")
        DriveReport(root.absolutePath, readable && items >= 0, items, games, internal)
    }

    fun scan(ctx: Context): List<Game> {
        val found = LinkedHashMap<String, Game>()
        for (root in roots(ctx)) walk(root, 0, found)
        return found.values.sortedWith(
            compareBy<Game> { Systems.all.indexOf(it.system) }.thenBy { it.title.lowercase() }
        )
    }

    private fun walk(dir: File, depth: Int, out: MutableMap<String, Game>) {
        if (depth > 5) return
        val entries = dir.listFiles() ?: return
        val files = entries.filter { it.isFile }
        val exts = files.map { it.extension.lowercase() }.toSet()

        // Multi-file discs: show only the "index" file (.m3u, .cue, .gdi), hide the tracks.
        val hasIndex = exts.any { it == "cue" || it == "gdi" || it == "m3u" || it == "ccd" || it == "toc" }
        val hasM3u = "m3u" in exts

        for (f in files) {
            val ext = f.extension.lowercase()
            if (f.name.startsWith(".")) continue
            if (hasIndex && (ext == "bin" || ext == "raw" || ext == "img" || ext == "sub")) continue
            if (hasM3u && (ext == "cue" || ext == "chd" || ext == "ccd")) continue
            val system = Systems.detect(f) ?: continue
            val key = safeCanonical(f)
            if (key !in out) out[key] = Game(f, system, prettyTitle(f))
        }
        for (d in entries) {
            if (!d.isDirectory || d.name.startsWith(".")) continue
            if (d.name.lowercase() in skipDirs) continue
            walk(d, depth + 1, out)
        }
    }

    /** Only games sent to the TV (not ones on a USB drive) can be deleted from RetroTV. */
    fun isDeletable(ctx: Context, game: Game): Boolean =
        safeCanonical(game.file).startsWith(safeCanonical(gamesDir(ctx)) + File.separator)

    fun delete(ctx: Context, game: Game) {
        if (!isDeletable(ctx, game)) return
        val f = game.file
        // Multi-file discs: also remove the track files that share the name (Game.cue + Game (Track 1).bin ...).
        if (f.extension.lowercase() in setOf("cue", "gdi", "m3u", "ccd", "toc")) {
            f.parentFile?.listFiles()?.filter { it != f && it.name.startsWith(f.nameWithoutExtension) }?.forEach { it.delete() }
        }
        f.delete()
    }

    /** "Super_Mario_World (USA) [!].sfc" -> "Super Mario World". */
    private fun prettyTitle(f: File): String {
        val base = f.nameWithoutExtension.replace('_', ' ')
        val clean = base
            .replace(Regex("""\s*\[[^\]]*\]"""), "")
            .replace(Regex("""\s*\([^)]*\)"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return clean.ifEmpty { base.trim() }.ifEmpty { f.name }
    }

    private fun safeCanonical(f: File): String = try {
        f.canonicalPath
    } catch (_: Exception) {
        f.absolutePath
    }
}
