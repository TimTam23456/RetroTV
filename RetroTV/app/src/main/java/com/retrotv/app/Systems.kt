package com.retrotv.app

import java.io.File
import java.util.zip.ZipFile

/** One emulated console and the libretro core that runs it. */
data class GameSystem(
    val id: String,
    val name: String,
    val core: String,
    val extensions: Set<String>,
    val folderHints: Set<String>,
    /** Systems with real analog sticks get the left stick as an analog stick; the rest get it as a d-pad. */
    val hasAnalog: Boolean = false,
    val note: String? = null,
)

object Systems {
    val all = listOf(
        GameSystem("nes", "NES", "fceumm", setOf("nes", "fds", "unf", "unif"), setOf("nes", "famicom", "fds")),
        GameSystem("snes", "SNES", "snes9x", setOf("sfc", "smc", "swc", "fig"), setOf("snes", "sfc", "super nintendo", "superfamicom", "super famicom")),
        GameSystem("gb", "Game Boy / Color", "mgba", setOf("gb", "gbc", "sgb"), setOf("gb", "gbc", "gameboy", "game boy", "gameboy color")),
        GameSystem("gba", "Game Boy Advance", "mgba", setOf("gba"), setOf("gba", "gameboy advance", "game boy advance")),
        GameSystem("genesis", "Sega Genesis / Master System", "genesis_plus_gx", setOf("md", "gen", "smd", "sms", "gg", "sg"),
            setOf("genesis", "megadrive", "mega drive", "md", "sms", "mastersystem", "master system", "gamegear", "game gear", "gg", "sega", "segacd", "sega cd")),
        GameSystem("psx", "PlayStation", "pcsx_rearmed", setOf("cue", "chd", "pbp", "m3u", "ccd", "toc"),
            setOf("psx", "ps1", "playstation", "psone", "playstation 1")),
        GameSystem("n64", "Nintendo 64", "mupen64plus_next_gles3", setOf("n64", "z64", "v64"), setOf("n64", "nintendo64", "nintendo 64"),
            hasAnalog = true, note = "Needs a mid-range or better TV chip"),
        GameSystem("nds", "Nintendo DS", "melondsds", setOf("nds"), setOf("nds", "ds"),
            note = "Touch-heavy games are awkward with a controller"),
        GameSystem("psp", "PSP", "ppsspp", setOf("iso", "cso"), setOf("psp"),
            hasAnalog = true, note = "Needs a mid-range or better TV chip"),
        GameSystem("dc", "Dreamcast", "flycast", setOf("gdi", "cdi"), setOf("dc", "dreamcast"),
            hasAnalog = true, note = "Experimental - needs a fast TV chip"),
    )

    fun byId(id: String?): GameSystem? = all.firstOrNull { it.id == id }

    /** Extensions that several systems share; a folder name decides which system they belong to. */
    private val shared = setOf("bin", "img", "chd", "iso", "cue", "m3u", "pbp", "zip")

    val knownExtensions: Set<String> = all.flatMap { it.extensions }.toSet() + setOf("bin", "img", "zip")

    /** Cartridge formats that can sit inside a .zip (disc games must be unzipped). */
    private val zippable = setOf("nes", "fds", "unf", "sfc", "smc", "swc", "fig", "gb", "gbc", "sgb", "gba",
        "md", "gen", "smd", "sms", "gg", "sg", "bin", "n64", "z64", "v64", "nds")

    fun detect(file: File): GameSystem? {
        val ext = file.extension.lowercase()
        if (ext !in knownExtensions) return null

        // 1. A parent folder named after a console wins (e.g. "ROMs/psx/Game.chd").
        var dir = file.parentFile
        var depth = 0
        while (dir != null && depth < 3) {
            val n = dir.name.lowercase()
            val hit = all.firstOrNull { n in it.folderHints && (ext in it.extensions || ext in shared) }
            if (hit != null) return if (ext == "zip") zipSystem(file, hit) else hit
            dir = dir.parentFile
            depth++
        }

        // 2. Otherwise, guess from the file itself.
        return when (ext) {
            "zip" -> zipSystem(file, null)
            "bin" -> if (file.length() > 16L * 1024 * 1024) byId("psx") else byId("genesis")
            "img" -> byId("psx")
            else -> all.firstOrNull { ext in it.extensions }
        }
    }

    /** Returns the name of the playable file inside a zip, or null. */
    fun zipEntry(file: File): String? = try {
        ZipFile(file).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory }
                .map { it.name }
                .firstOrNull { it.substringAfterLast('.', "").lowercase() in zippable }
        }
    } catch (e: Exception) {
        null
    }

    private fun zipSystem(file: File, hint: GameSystem?): GameSystem? {
        val inner = zipEntry(file) ?: return null
        val ext = inner.substringAfterLast('.', "").lowercase()
        if (hint != null) return hint
        if (ext == "bin") return byId("genesis")
        return all.firstOrNull { ext in it.extensions }
    }
}
