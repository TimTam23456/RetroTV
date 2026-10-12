package com.retrotv.app

import android.content.Context
import android.os.Environment
import java.io.File

data class Game(val file: File, val system: GameSystem, val title: String)

/** Finds games on the TV: uploaded games, the Download folder and any USB drive. */
object Library {

    fun gamesDir(ctx: Context): File = File(ctx.filesDir, "games").apply { mkdirs() }

    private val skipDirs = setOf(
        "android", "dcim", "movies", "music", "pictures", "podcasts", "ringtones",
        "alarms", "notifications", "lost.dir", "system volume information", "\$recycle.bin",
    )

    fun roots(ctx: Context): List<File> {
        val roots = mutableListOf(gamesDir(ctx))
        try {
            roots += Environment.getExternalStorageDirectory()
        } catch (_: Exception) {
        }
        // USB drives and SD cards show up as /storage/XXXX-XXXX
        File("/storage").listFiles()?.forEach { f ->
            val n = f.name.lowercase()
            if (f.isDirectory && n != "emulated" && n != "self" && f.canRead()) roots += f
        }
        return roots.distinctBy { safeCanonical(it) }
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

    private fun prettyTitle(f: File): String =
        f.nameWithoutExtension
            .replace('_', ' ')
            .replace(Regex("\\s*\\[[^\\]]*\\]"), "")
            .trim()
            .ifEmpty { f.name }

    private fun safeCanonical(f: File): String = try {
        f.canonicalPath
    } catch (_: Exception) {
        f.absolutePath
    }
}
