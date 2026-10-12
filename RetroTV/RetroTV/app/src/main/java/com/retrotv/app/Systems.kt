package com.retrotv.app

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/** One emulated console, the libretro core that runs it, and how much TV it needs. */
data class GameSystem(
    val id: String,
    val name: String,
    val core: String,
    val extensions: Set<String>,
    val folderHints: Set<String>,
    /** Systems with real analog sticks get the left stick as an analog stick; the rest get it as a d-pad. */
    val hasAnalog: Boolean = false,
    val note: String? = null,
    /** TV speed score needed to be playable / to run well (see DeviceCheck). */
    val minScore: Double = 1.0,
    val goodScore: Double = 1.5,
    val needs64Bit: Boolean = false,
    /** Minimum OpenGL ES version x10 (30 = 3.0, 32 = 3.2). */
    val minGles: Int = 20,
    /** Extra files the core needs, unpacked into the system folder on first use. */
    val systemAssets: String? = null,
    val heavy: Boolean = false,
    /** Short label and colour used on cards and covers. */
    val short: String = name,
    val color: Int = 0xFF4B5563.toInt(),
    /** Folder name(s) in the libretro thumbnail library (first is the default). */
    val artFolders: List<String> = emptyList(),
)

object Systems {
    val all = listOf(
        GameSystem("nes", "NES", "fceumm", setOf("nes", "fds", "unf", "unif"), setOf("nes", "famicom", "fds"),
            minScore = 0.6, goodScore = 0.9,
            short = "NES", color = 0xFFC0392B.toInt(), artFolders = listOf("Nintendo - Nintendo Entertainment System")),
        GameSystem("snes", "SNES", "snes9x", setOf("sfc", "smc", "swc", "fig"),
            setOf("snes", "sfc", "super nintendo", "superfamicom", "super famicom"),
            minScore = 0.9, goodScore = 1.3,
            short = "SNES", color = 0xFF6C5BB5.toInt(), artFolders = listOf("Nintendo - Super Nintendo Entertainment System")),
        GameSystem("gb", "Game Boy / Color", "mgba", setOf("gb", "gbc", "sgb"),
            setOf("gb", "gbc", "gameboy", "game boy", "gameboy color"),
            minScore = 0.6, goodScore = 0.9,
            short = "GAME BOY", color = 0xFF5E8C31.toInt(), artFolders = listOf("Nintendo - Game Boy", "Nintendo - Game Boy Color")),
        GameSystem("gba", "Game Boy Advance", "mgba", setOf("gba"), setOf("gba", "gameboy advance", "game boy advance"),
            minScore = 0.9, goodScore = 1.3,
            short = "GBA", color = 0xFF4B3CA7.toInt(), artFolders = listOf("Nintendo - Game Boy Advance")),
        GameSystem("genesis", "Sega Genesis / Master System", "genesis_plus_gx", setOf("md", "gen", "smd", "sms", "gg", "sg"),
            setOf("genesis", "megadrive", "mega drive", "md", "sms", "mastersystem", "master system", "gamegear",
                "game gear", "gg", "sega", "segacd", "sega cd"),
            minScore = 0.8, goodScore = 1.2,
            short = "GENESIS", color = 0xFF1F5FBF.toInt(), artFolders = listOf("Sega - Mega Drive - Genesis", "Sega - Master System - Mark III", "Sega - Game Gear")),
        GameSystem("psx", "PlayStation", "pcsx_rearmed", setOf("cue", "chd", "pbp", "m3u", "ccd", "toc"),
            setOf("psx", "ps1", "playstation", "psone", "playstation 1"),
            minScore = 1.2, goodScore = 1.8,
            short = "PS1", color = 0xFF59616E.toInt(), artFolders = listOf("Sony - PlayStation")),
        GameSystem("n64", "Nintendo 64", "mupen64plus_next_gles3", setOf("n64", "z64", "v64"),
            setOf("n64", "nintendo64", "nintendo 64"),
            hasAnalog = true, minScore = 2.0, goodScore = 3.0, minGles = 30,
            short = "N64", color = 0xFF23864F.toInt(), artFolders = listOf("Nintendo - Nintendo 64")),
        GameSystem("nds", "Nintendo DS", "melondsds", setOf("nds"), setOf("nds", "ds"),
            note = "Touch-heavy games are awkward with a controller",
            minScore = 2.2, goodScore = 3.2,
            short = "DS", color = 0xFF0E7C86.toInt(), artFolders = listOf("Nintendo - Nintendo DS")),
        GameSystem("psp", "PSP", "ppsspp", setOf("cso"), setOf("psp"),
            hasAnalog = true, minScore = 2.4, goodScore = 3.6, minGles = 30, systemAssets = "PPSSPP",
            short = "PSP", color = 0xFF263247.toInt(), artFolders = listOf("Sony - PlayStation Portable")),
        GameSystem("dc", "Dreamcast", "flycast", setOf("gdi", "cdi"), setOf("dc", "dreamcast"),
            hasAnalog = true, minScore = 3.0, goodScore = 4.5, minGles = 30,
            short = "DREAMCAST", color = 0xFFE06C1F.toInt(), artFolders = listOf("Sega - Dreamcast")),
        GameSystem("gc", "GameCube / Wii", "dolphin", setOf("gcm", "rvz", "wbfs", "gcz", "ciso", "wad", "dol"),
            setOf("gc", "gcn", "gamecube", "wii", "ngc"),
            hasAnalog = true, minScore = 5.0, goodScore = 7.5, needs64Bit = true, minGles = 30,
            systemAssets = "Dolphin", heavy = true,
            short = "GAMECUBE / WII", color = 0xFF5B3FA8.toInt(), artFolders = listOf("Nintendo - GameCube", "Nintendo - Wii")),
        GameSystem("ps2", "PlayStation 2", "play", setOf(), setOf("ps2", "playstation 2", "playstation2"),
            hasAnalog = true, minScore = 6.0, goodScore = 9.0, needs64Bit = true, minGles = 30, heavy = true,
            short = "PS2", color = 0xFF1E3A8A.toInt(), artFolders = listOf("Sony - PlayStation 2")),
        GameSystem("3ds", "Nintendo 3DS", "citra", setOf("3ds", "cci", "cxi", "3dsx"), setOf("3ds", "n3ds"),
            hasAnalog = true, note = "Touch-screen games are awkward with a controller",
            minScore = 4.5, goodScore = 7.0, needs64Bit = true, minGles = 32, heavy = true,
            short = "3DS", color = 0xFFC81E3A.toInt(), artFolders = listOf("Nintendo - Nintendo 3DS")),
    )

    fun byId(id: String?): GameSystem? = all.firstOrNull { it.id == id }

    /** Extensions that several systems share; a folder name (or the file's contents) decides. */
    private val shared = setOf("bin", "img", "chd", "iso", "cue", "m3u", "pbp", "zip")

    val knownExtensions: Set<String> = all.flatMap { it.extensions }.toSet() + setOf("bin", "img", "zip", "iso")

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
            "iso" -> sniffIso(file)
            "bin" -> if (file.length() > 16L * 1024 * 1024) byId("psx") else byId("genesis")
            "img" -> byId("psx")
            else -> all.firstOrNull { ext in it.extensions }
        }
    }

    /** .iso is used by PSP, PS2, GameCube and Wii; read the disc header to tell them apart. */
    private fun sniffIso(file: File): GameSystem? = try {
        RandomAccessFile(file, "r").use { f ->
            val head = ByteArray(0x20)
            f.readFully(head)
            fun u32(o: Int) = ((head[o].toInt() and 0xFF) shl 24) or ((head[o + 1].toInt() and 0xFF) shl 16) or
                ((head[o + 2].toInt() and 0xFF) shl 8) or (head[o + 3].toInt() and 0xFF)
            when {
                u32(0x1C) == 0xC2339F3D.toInt() -> byId("gc")   // GameCube disc magic
                u32(0x18) == 0x5D1C9EA3 -> byId("gc")          // Wii disc magic
                else -> {
                    val pvd = ByteArray(0x30)
                    f.seek(0x8000)
                    f.readFully(pvd)
                    val sysId = String(pvd, 8, 32, Charsets.US_ASCII).trim()
                    when {
                        sysId.startsWith("PSP GAME") -> byId("psp")
                        sysId.startsWith("PLAYSTATION") -> byId("ps2")
                        else -> null
                    }
                }
            }
        }
    } catch (_: Exception) {
        null
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
