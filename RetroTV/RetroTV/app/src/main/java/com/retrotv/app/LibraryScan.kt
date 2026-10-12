package com.retrotv.app

import java.net.URI
import java.net.URLDecoder

/**
 * Finds downloadable game files on a web page.
 *
 * Pure Kotlin (no Android) so it can be tested on its own. Works with ordinary pages that link to
 * files and with directory listings (Apache, nginx, NAS servers): sub-folders on the same site are
 * followed a couple of levels deep.
 */
object LibraryScan {

    /** One game found on a page. [companions] are extra files a disc game needs (e.g. .bin tracks for a .cue). */
    data class RemoteGame(
        val url: String,
        val fileName: String,
        val title: String,
        val systemId: String?,
        val companions: List<String> = emptyList(),
    )

    data class Result(val games: List<RemoteGame>, val skippedArchives: Int, val pagesScanned: Int)

    /** Archive types RetroTV can't open; counted so we can tell the user why they're missing. */
    private val unsupported = setOf("7z", "rar", "tar", "gz", "xz")

    /** Disc "index" files and the track files that go with them. */
    private val indexExt = setOf("cue", "gdi", "m3u", "ccd", "toc")
    private val trackExt = setOf("bin", "img", "raw", "sub", "iso")

    private val hrefRegex = Regex("""href\s*=\s*["']([^"'#]+)["']""", RegexOption.IGNORE_CASE)

    /**
     * Scans [startUrl]. [fetch] downloads a page's HTML (or returns null on failure).
     * Follows links into sub-folders under the start address, up to [maxDepth] levels and [maxPages] pages.
     */
    fun scan(startUrl: String, fetch: (String) -> String?, maxDepth: Int = 2, maxPages: Int = 40): Result {
        val start = normalize(startUrl)
        val base = URI(start)
        val prefix = start.substringBeforeLast('/') + "/"
        val seenPages = HashSet<String>()
        val files = LinkedHashMap<String, Pair<String, List<String>>>() // url -> (fileName, folder hints)
        var skipped = 0
        val queue = ArrayDeque(listOf(start to 0))

        while (queue.isNotEmpty() && seenPages.size < maxPages) {
            val (page, depth) = queue.removeFirst()
            if (!seenPages.add(page)) continue
            val html = fetch(page) ?: continue
            val pageUri = try { URI(page) } catch (_: Exception) { continue }
            for (m in hrefRegex.findAll(html)) {
                val raw = m.groupValues[1].trim().replace("&amp;", "&")
                if (raw.startsWith("javascript:", true) || raw.startsWith("mailto:", true) || raw.startsWith("?")) continue
                val abs = try { pageUri.resolve(raw.replace(" ", "%20")).toString() } catch (_: Exception) { continue }
                if (!abs.startsWith("http://") && !abs.startsWith("https://")) continue
                val path = abs.substringBefore('?').substringBefore('#')
                val name = decode(path.substringAfterLast('/'))
                when {
                    path.endsWith("/") -> {
                        // A sub-folder on the same site, below where we started.
                        if (depth < maxDepth && path.startsWith(prefix) && path.length > prefix.length &&
                            sameHost(base, abs) && !name.startsWith(".")
                        ) queue.addLast(path to depth + 1)
                    }
                    else -> {
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in unsupported) { skipped++; continue }
                        if (ext in Systems.knownExtensions || ext in trackExt) {
                            val hints = decode(try { URI(path).rawPath ?: "" } catch (_: Exception) { "" }).split('/')
                                .map { it.lowercase() }.filter { it.isNotEmpty() }.dropLast(1)
                            files.putIfAbsent(path, name to hints)
                        }
                    }
                }
            }
        }

        // Group disc tracks with their .cue/.gdi/.m3u so a disc downloads as one game.
        val byFolder = files.entries.groupBy { it.key.substringBeforeLast('/') }
        val games = mutableListOf<RemoteGame>()
        for ((_, entries) in byFolder) {
            val indexes = entries.filter { it.value.first.substringAfterLast('.').lowercase() in indexExt }
            val claimed = HashSet<String>()
            for (ix in indexes) {
                val base = ix.value.first.substringBeforeLast('.')
                val tracks = entries.filter {
                    it.key != ix.key && it.value.first.substringAfterLast('.').lowercase() in trackExt &&
                        it.value.first.startsWith(base)
                }.map { it.key }
                claimed += tracks
                games += make(ix.key, ix.value.first, ix.value.second, tracks)
            }
            for (e in entries) {
                if (e.key in claimed || e.key in indexes.map { it.key }) continue
                val ext = e.value.first.substringAfterLast('.').lowercase()
                // Loose .bin/.img files only count if they're a known cartridge/disc format on their own.
                if (ext in trackExt && ext !in Systems.knownExtensions) continue
                games += make(e.key, e.value.first, e.value.second, emptyList())
            }
        }
        games.sortWith(compareBy({ Systems.all.indexOfFirst { s -> s.id == it.systemId }.let { i -> if (i < 0) 999 else i } }, { it.title.lowercase() }))
        return Result(games, skipped, seenPages.size)
    }

    private fun make(url: String, name: String, hints: List<String>, companions: List<String>): RemoteGame =
        RemoteGame(url, name, prettyTitle(name), detect(name, hints), companions)

    /** Works out the console from the file name and the folders it's in (no download needed). */
    fun detect(fileName: String, folderHints: List<String>): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        // A folder named after a console wins (".../snes/Game.zip").
        for (h in folderHints.reversed()) {
            val sys = Systems.all.firstOrNull { h in it.folderHints || h.replace('_', ' ') in it.folderHints }
            if (sys != null) return sys.id
        }
        return when (ext) {
            "zip", "iso", "bin", "img", "chd" -> null // can't tell without opening the file
            else -> Systems.all.firstOrNull { ext in it.extensions }?.id
        }
    }

    fun prettyTitle(fileName: String): String {
        val base = fileName.substringBeforeLast('.').replace('_', ' ')
        val clean = base.replace(Regex("""\s*\[[^\]]*\]"""), "").replace(Regex("""\s*\([^)]*\)"""), "")
            .replace(Regex("""\s+"""), " ").trim()
        return clean.ifEmpty { base.trim() }
    }

    fun normalize(url: String): String {
        var u = url.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) {
            // Home servers (192.168.x.x, a :port, a plain name like "nas") usually use plain http.
            val host = u.substringBefore('/').substringBefore(':')
            val local = host.matches(Regex("""(10|127|192\.168|172\.(1[6-9]|2\d|3[01]))\.[\d.]+""")) ||
                !host.contains('.') || host.endsWith(".local") || u.substringBefore('/').contains(':')
            u = (if (local) "http://" else "https://") + u
        }
        // A bare folder address without a trailing slash: treat it as a folder.
        val last = u.substringAfter("://").substringAfter('/', "").substringAfterLast('/')
        if (u.substringAfter("://").count { it == '/' } == 0) u += "/"
        else if (last.isNotEmpty() && !last.contains('.') && !u.endsWith("/")) u += "/"
        return u
    }

    private fun sameHost(a: URI, b: String) = try { URI(b).host.equals(a.host, ignoreCase = true) } catch (_: Exception) { false }

    private fun decode(s: String): String = try { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { s }
}
