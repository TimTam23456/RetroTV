package com.retrotv.app

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Web addresses the user added to browse for games. */
object Sources {
    data class Source(val url: String, val name: String)

    private val _list = MutableStateFlow<List<Source>>(emptyList())
    val list: StateFlow<List<Source>> = _list
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("sources", Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Source> {
        if (!loaded) {
            loaded = true
            _list.value = try {
                val arr = JSONArray(prefs(ctx).getString("list", "[]"))
                (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Source(o.getString("url"), o.optString("name")) } }
            } catch (_: Exception) {
                emptyList()
            }
        }
        return _list.value
    }

    fun add(ctx: Context, rawUrl: String, name: String = ""): Source {
        load(ctx)
        val url = LibraryScan.normalize(rawUrl)
        val label = name.trim().ifEmpty { url.substringAfter("://").trimEnd('/') }
        val s = Source(url, label)
        save(ctx, _list.value.filter { it.url != url } + s)
        return s
    }

    fun remove(ctx: Context, url: String) {
        load(ctx)
        save(ctx, _list.value.filter { it.url != url })
        OnlineCache.forget(ctx, url)
    }

    private fun save(ctx: Context, list: List<Source>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("url", it.url).put("name", it.name)) }
        prefs(ctx).edit().putString("list", arr.toString()).apply()
        _list.value = list
    }
}

/** Remembers the last scan of each source, so the Browse screen opens instantly. */
object OnlineCache {
    private fun file(ctx: Context, url: String) = File(File(ctx.cacheDir, "library").apply { mkdirs() }, sha1(url) + ".json")

    fun read(ctx: Context, url: String): LibraryScan.Result? = try {
        val f = file(ctx, url)
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("games")
            LibraryScan.Result((0 until arr.length()).map {
                val g = arr.getJSONObject(it)
                val comp = g.optJSONArray("companions") ?: JSONArray()
                LibraryScan.RemoteGame(g.getString("url"), g.getString("file"), g.getString("title"),
                    g.optString("system").ifEmpty { null }, (0 until comp.length()).map { i -> comp.getString(i) })
            }, o.optInt("skipped"), o.optInt("pages"))
        }
    } catch (_: Exception) {
        null
    }

    fun write(ctx: Context, url: String, r: LibraryScan.Result) {
        val arr = JSONArray()
        r.games.forEach { g ->
            arr.put(JSONObject().put("url", g.url).put("file", g.fileName).put("title", g.title)
                .put("system", g.systemId ?: "").put("companions", JSONArray(g.companions)))
        }
        try {
            file(ctx, url).writeText(JSONObject().put("games", arr).put("skipped", r.skippedArchives).put("pages", r.pagesScanned).toString())
        } catch (_: Exception) {
        }
    }

    fun forget(ctx: Context, url: String) {
        file(ctx, url).delete()
    }

    /** Scans a source on the network (call off the main thread). */
    fun scan(ctx: Context, url: String): LibraryScan.Result {
        // Folders on your own network (a PC or NAS) are fast to read, so look much deeper and wider:
        // e.g. games/snes/<game folder>/<files>, with hundreds of games.
        val r = if (LibraryScan.isLocal(url)) LibraryScan.scan(url, { fetchPage(it) }, maxDepth = 5, maxPages = 3000)
        else LibraryScan.scan(url, { fetchPage(it) })
        if (r.pagesScanned == 0) throw IOException("Couldn't open that page. Check the address and that the site is reachable from the TV.")
        write(ctx, url, r)
        return r
    }

    private fun fetchPage(url: String): String? = try {
        val conn = Net.open(url)
        val type = conn.contentType ?: ""
        if (conn.responseCode != 200 || (type.isNotEmpty() && !type.contains("html") && !type.contains("text"))) null
        else conn.inputStream.use { input ->
            // Pages are text; cap at 8 MB so a huge or binary response can't eat the TV's memory.
            input.readNBytesCompat(8 * 1024 * 1024).toString(Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < max) {
        val n = read(buf, 0, minOf(buf.size, max - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** HTTP helpers that follow redirects (including http -> https). */
object Net {
    fun open(url: String): HttpURLConnection {
        var link = url
        repeat(6) {
            val c = (URL(link).openConnection() as HttpURLConnection).apply {
                connectTimeout = 12_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "RetroTV")
            }
            val code = c.responseCode
            if (code in 300..399) {
                val next = c.getHeaderField("Location") ?: return c
                c.disconnect()
                link = URL(URL(link), next).toString()
            } else {
                return c
            }
        }
        throw IOException("Too many redirects")
    }
}

/**
 * Downloads games into the library in the background (two at a time), with progress the
 * Browse screen can show. Keeps going while you browse elsewhere in RetroTV.
 */
object Downloads {
    enum class Status { QUEUED, DOWNLOADING, DONE, FAILED }
    data class Progress(val status: Status, val percent: Int = 0, val message: String = "")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val _state = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val state: StateFlow<Map<String, Progress>> = _state

    /** Called when a download finishes, so the library can refresh. */
    @Volatile
    var onFinished: ((String) -> Unit)? = null

    private fun set(url: String, p: Progress) {
        _state.value = _state.value + (url to p)
    }

    fun folderFor(ctx: Context, game: LibraryScan.RemoteGame): File =
        File(Library.gamesDir(ctx), game.systemId ?: "downloads").apply { mkdirs() }

    fun target(ctx: Context, game: LibraryScan.RemoteGame) = File(folderFor(ctx, game), safeName(game.fileName))

    /** True if this game is already in the library. */
    fun isInLibrary(ctx: Context, game: LibraryScan.RemoteGame) = target(ctx, game).let { it.exists() && it.length() > 0 }

    fun start(ctx: Context, game: LibraryScan.RemoteGame) {
        val app = ctx.applicationContext
        val current = _state.value[game.url]?.status
        if (current == Status.QUEUED || current == Status.DOWNLOADING) return
        set(game.url, Progress(Status.QUEUED))
        scope.launch {
            gate.withPermit {
                try {
                    val folder = folderFor(app, game)
                    val all = listOf(game.url) + game.companions
                    all.forEachIndexed { i, url ->
                        val name = safeName(java.net.URLDecoder.decode(url.substringAfterLast('/'), "UTF-8"))
                        fetch(url, File(folder, name)) { pct ->
                            // Spread progress across the main file and any disc tracks.
                            set(game.url, Progress(Status.DOWNLOADING, (i * 100 + pct) / all.size))
                        }
                    }
                    set(game.url, Progress(Status.DONE, 100))
                    withContext(Dispatchers.Main) { onFinished?.invoke(game.title) }
                } catch (e: Exception) {
                    set(game.url, Progress(Status.FAILED, 0, e.message ?: "Download failed"))
                }
            }
        }
    }

    private fun fetch(url: String, dest: File, onPct: (Int) -> Unit) {
        val conn = Net.open(url)
        if (conn.responseCode != 200) throw IOException("The site said no (HTTP ${conn.responseCode}).")
        val total = conn.contentLengthLong
        if (total > 0) {
            val free = StatFs(dest.parentFile!!.path).availableBytes
            if (total > free - 200L * 1024 * 1024) throw IOException("Not enough space on the TV for this game.")
        }
        val part = File(dest.path + ".part")
        conn.inputStream.use { input ->
            FileOutputStream(part).use { out ->
                val buf = ByteArray(128 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        if (pct != last) { last = pct; onPct(pct) }
                    }
                }
                if (total > 0 && done < total) throw IOException("The download was cut off. Try again.")
            }
        }
        dest.delete()
        if (!part.renameTo(dest)) throw IOException("Couldn't save the game.")
    }

    private fun safeName(name: String) = name.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("""[^A-Za-z0-9 ._()\[\]&+,'!-]"""), "_").trim().trimStart('.').ifEmpty { "game" }
}
