package com.retrotv.app

/**
 * Forgiving game search: word order doesn't matter, partial words count ("mar kart" finds Mario Kart),
 * and small typos are tolerated ("zelad" finds Zelda). Pure Kotlin so it can be tested on its own.
 */
object Search {
    fun norm(s: String): String = s.lowercase()
        .replace(Regex("""\([^)]*\)|\[[^\]]*\]"""), " ")
        .replace('&', ' ').replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun words(s: String) = norm(s).split(' ').filter { it.isNotEmpty() }

    /** 0 = no match; higher is better. */
    fun score(query: String, title: String, extra: String = ""): Int {
        val q = words(query)
        if (q.isEmpty()) return 0
        val t = words(title)
        val x = words(extra)
        val all = t + x
        var total = 0
        for (w in q) {
            val best = all.maxOfOrNull { word(w, it) } ?: 0
            if (best == 0) return 0
            total += best
        }
        val joined = norm(title)
        if (joined.startsWith(norm(query))) total += 30
        if (joined == norm(query)) total += 50
        return total
    }

    private fun word(q: String, w: String): Int = when {
        w == q -> 20
        w.startsWith(q) -> 14
        q.length >= 3 && w.contains(q) -> 9
        q.length >= 4 && (editDistance(q, w.take(q.length)) <= 1 || editDistance(q, w.take(q.length + 1)) <= 1) -> 7
        q.length >= 6 && editDistance(q, w) <= 2 -> 5
        // "mk" or "smb": initials of the title words
        else -> 0
    }

    fun initialsMatch(query: String, title: String): Boolean {
        val q = norm(query).replace(" ", "")
        if (q.length < 2 || q.length > 6) return false
        return words(title).joinToString("") { it.take(1) }.startsWith(q)
    }

    /** Edit distance where swapping two neighbouring letters ("zelad" vs "zelda") counts as one typo. */
    private fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }

    /** Ranks [items] by how well [title] (and [extra], e.g. the console) match [query]. */
    fun <T> rank(query: String, items: List<T>, title: (T) -> String, extra: (T) -> String = { "" }): List<T> =
        items.map { it to (score(query, title(it), extra(it)).let { s -> if (s == 0 && initialsMatch(query, title(it))) 12 else s }) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
}
