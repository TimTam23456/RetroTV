package com.retrotv.app

/**
 * Matches a game file name to a box-art name in the libretro thumbnail library.
 * Pure Kotlin (no Android) so it can be tested on its own.
 *
 * Library names look like "Legend of Zelda, The - A Link to the Past (USA).png";
 * people's files look like "The Legend of Zelda - A Link to the Past.sfc" or "zelda_lttp (U) [!].smc".
 */
object CoverMatch {

    /** Characters libretro replaces with "_" in file names. */
    private val illegal = Regex("""[&*/:`<>?\\|"]""")

    fun libretroSafe(name: String) = name.replace(illegal, "_")

    /** Lowercase words without region tags, punctuation or "the". */
    fun normalize(title: String): String = title
        .lowercase()
        .replace(Regex("""\([^)]*\)|\[[^\]]*]"""), " ")
        .replace("&", " and ")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .split(' ')
        .filter { it.isNotBlank() && it != "the" && it != "a" }
        .mapIndexed { i, w -> if (i > 0) roman[w] ?: w else w }
        .joinToString(" ")

    private val roman = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6",
        "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10", "xi" to "11", "xii" to "12", "xiii" to "13")

    private fun regionScore(name: String): Int {
        val n = name.lowercase()
        var score = when {
            "(usa" in n || ", usa" in n -> 30
            "(world" in n -> 28
            "(europe" in n || "(uk" in n || "(australia" in n -> 25
            "(japan" in n -> 10
            else -> 15
        }
        if ("(beta" in n || "(proto" in n || "(demo" in n || "(sample" in n || "(pirate" in n || "(unl" in n) score -= 20
        if ("(rev" in n) score -= 1
        return score
    }

    private fun tokens(s: String) = s.split(' ').filter { it.isNotEmpty() }.toSet()

    /**
     * Returns the best matching library name (without ".png"), or null if nothing is close enough.
     * [names] are library names without the ".png" extension.
     */
    fun best(fileTitle: String, names: List<String>): String? {
        if (names.isEmpty()) return null
        val safe = libretroSafe(fileTitle)
        names.firstOrNull { it.equals(safe, ignoreCase = true) }?.let { return it }

        val want = normalize(fileTitle)
        if (want.isEmpty()) return null
        val wantTokens = tokens(want)

        var bestName: String? = null
        var bestScore = 0.0
        for (n in names) {
            val norm = normalize(n)
            if (norm.isEmpty()) continue
            val score = if (norm == want) {
                100.0 + regionScore(n)
            } else {
                val have = tokens(norm)
                val common = wantTokens.intersect(have).size
                if (common == 0) continue
                val jaccard = common.toDouble() / wantTokens.union(have).size
                // Small bonus when one title contains the other (e.g. "super mario world" vs
                // "super mario world super mario bros").
                val contains = if (norm.startsWith(want) || want.startsWith(norm)) 0.15 else 0.0
                val base = (jaccard + contains) * 80
                // Every word of a 2+ word title appears in the library name (e.g. "yoshi s island").
                val subset = if (wantTokens.size >= 2 && have.containsAll(wantTokens)) 62.0 + jaccard * 10 else 0.0
                maxOf(base, subset) + regionScore(n) / 10.0
            }
            if (score > bestScore) {
                bestScore = score
                bestName = n
            }
        }
        // 60 ~= at least ~70% of the words in common; below that it's more likely the wrong game.
        return if (bestScore >= 60) bestName else null
    }
}
