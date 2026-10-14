package com.retrotv.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

/** One cheat: a name and a code (Game Genie, Action Replay, GameShark... whatever the console uses). */
data class Cheat(val desc: String, val code: String, val custom: Boolean = false)

/**
 * Cheats for the game being played.
 *  - The cheat list comes from libretro's free cheat database (the same one RetroArch uses), downloaded
 *    once as a zip and matched to the game by name.
 *  - Players can also type in their own codes.
 *  - Which cheats are switched on is remembered per game.
 */
object Cheats {
    private const val DB_URL = "https://buildbot.libretro.com/assets/frontend/cheats.zip"

    fun dbFile(ctx: Context) = File(ctx.filesDir, "cheats.zip")
    fun hasDb(ctx: Context) = dbFile(ctx).let { it.exists() && it.length() > 1000 }

    /** Downloads the cheat database (about 10-20 MB). Call off the main thread. */
    fun download(ctx: Context, onProgress: (Int) -> Unit = {}) {
        val conn = Net.open(DB_URL)
        if (conn.responseCode != 200) throw IOException("The cheat library said ${conn.responseCode}. Try again later.")
        val total = conn.contentLengthLong
        val part = File(ctx.filesDir, "cheats.zip.part")
        conn.inputStream.use { input ->
            FileOutputStream(part).use { out ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done * 100 / total).toInt())
                }
            }
        }
        ZipFile(part).close() // throws if the download is broken
        dbFile(ctx).delete()
        part.renameTo(dbFile(ctx))
    }

    /** Finds this game's cheat file in the database, by console folder and closest title. */
    fun findInDb(ctx: Context, game: Game): List<Cheat> {
        if (!hasDb(ctx)) return emptyList()
        ZipFile(dbFile(ctx)).use { zip ->
            val folders = game.system.artFolders.map { it.lowercase() }
            val byName = HashMap<String, String>() // name without .cht -> entry path
            for (e in zip.entries()) {
                if (e.isDirectory || !e.name.endsWith(".cht", true)) continue
                val folder = e.name.substringBeforeLast('/').substringAfterLast('/').lowercase()
                if (folders.isNotEmpty() && folder !in folders) continue
                byName[e.name.substringAfterLast('/').removeSuffix(".cht")] = e.name
            }
            val best = CoverMatch.best(game.file.nameWithoutExtension, byName.keys.toList())
                ?: CoverMatch.best(game.title, byName.keys.toList()) ?: return emptyList()
            val entry = zip.getEntry(byName[best]) ?: return emptyList()
            return zip.getInputStream(entry).bufferedReader().use { parse(it.readText()) }
        }
    }

    /** Reads RetroArch's .cht format: cheatN_desc / cheatN_code lines. */
    fun parse(text: String): List<Cheat> {
        val values = HashMap<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            val i = line.indexOf('=')
            if (i <= 0) continue
            values[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim().trim('"')
        }
        val count = values["cheats"]?.toIntOrNull() ?: 0
        return (0 until count).mapNotNull { n ->
            val code = values["cheat${n}_code"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Cheat(values["cheat${n}_desc"]?.ifBlank { null } ?: "Cheat ${n + 1}", code)
        }
    }

    // ---------------------------------------------------------------- per-game choices
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cheats", Context.MODE_PRIVATE)
    private fun key(game: Game) = MessageDigest.getInstance("SHA-1").digest(game.file.absolutePath.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    data class State(val enabled: Set<String>, val custom: List<Cheat>, val version: Int)

    fun state(ctx: Context, game: Game): State {
        val o = try { JSONObject(prefs(ctx).getString(key(game), "{}")!!) } catch (_: Exception) { JSONObject() }
        val en = o.optJSONArray("on") ?: JSONArray()
        val cu = o.optJSONArray("custom") ?: JSONArray()
        return State(
            (0 until en.length()).map { en.getString(it) }.toSet(),
            (0 until cu.length()).map { cu.getJSONObject(it).let { c -> Cheat(c.getString("d"), c.getString("c"), true) } },
            o.optInt("v"),
        )
    }

    private fun save(ctx: Context, game: Game, s: State) {
        val o = JSONObject().put("on", JSONArray(s.enabled.toList())).put("v", s.version + 1)
            .put("custom", JSONArray().apply { s.custom.forEach { put(JSONObject().put("d", it.desc).put("c", it.code)) } })
        prefs(ctx).edit().putString(key(game), o.toString()).apply()
    }

    /** Cheats are remembered by their code, so the list can be updated without losing choices. */
    fun idOf(c: Cheat) = (if (c.custom) "u:" else "d:") + c.code

    fun toggle(ctx: Context, game: Game, c: Cheat): Boolean {
        val s = state(ctx, game)
        val on = idOf(c) !in s.enabled
        save(ctx, game, s.copy(enabled = if (on) s.enabled + idOf(c) else s.enabled - idOf(c)))
        return on
    }

    fun addCustom(ctx: Context, game: Game, desc: String, code: String) {
        val s = state(ctx, game)
        val c = Cheat(desc.ifBlank { "My cheat" }, code.trim(), true)
        save(ctx, game, s.copy(custom = s.custom + c, enabled = s.enabled + idOf(c)))
    }

    fun removeCustom(ctx: Context, game: Game, c: Cheat) {
        val s = state(ctx, game)
        save(ctx, game, s.copy(custom = s.custom.filter { it != c }, enabled = s.enabled - idOf(c)))
    }

    fun turnAllOff(ctx: Context, game: Game) { val s = state(ctx, game); save(ctx, game, s.copy(enabled = emptySet())) }

    /** The codes that should be active right now, in a stable order. */
    fun activeCodes(ctx: Context, game: Game, dbCheats: List<Cheat>): List<String> {
        val s = state(ctx, game)
        return (dbCheats + s.custom).filter { idOf(it) in s.enabled }.map { it.code }
    }
}
