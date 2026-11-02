package com.biteric.songfixer.match

import java.text.Normalizer

/** Comparing song names the way a person would: case, accents, "feat." and punctuation don't count. */
object Text {
    private val extras = Regex(
        """\s*[(\[][^)\]]*\b(remaster(ed)?|explicit|clean|radio edit|single version|album version|mono|stereo|feat\.?|ft\.?|featuring|with)\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val featuring = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val marks = Regex("""\p{Mn}+""")
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    fun normalize(s: String?): String {
        if (s == null) return ""
        val plain = Normalizer.normalize(s.replace(extras, "").replace(featuring, ""), Normalizer.Form.NFD).replace(marks, "")
        return plain.lowercase().replace("&", " and ").replace(nonWord, " ").trim()
    }

    fun same(a: String?, b: String?) = normalize(a).let { it.isNotEmpty() && it == normalize(b) }

    /**
     * 0..1: how alike two names are. The best of word overlap (handles reordered or extra words)
     * and letter-pair overlap (handles typos and run-together words).
     */
    fun similarity(a: String?, b: String?): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val wx = x.split(' ').toSet()
        val wy = y.split(' ').toSet()
        val words = (wx intersect wy).size.toDouble() / (wx union wy).size
        // A name inside a longer one ("Despacito" in "Despacito Remix") is still close.
        val contained = if (wx.containsAll(wy) || wy.containsAll(wx)) minOf(wx.size, wy.size).toDouble() / maxOf(wx.size, wy.size) * 0.3 + 0.6 else 0.0
        return maxOf(words, dice(x.replace(" ", ""), y.replace(" ", "")), contained)
    }

    /** Artists match when one credit contains the other ("Shakira" vs "Shakira feat. Wyclef"). */
    fun artistSimilarity(a: String?, b: String?): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y || x.startsWith("$y ") || y.startsWith("$x ")) return 1.0
        return similarity(a, b)
    }

    private fun dice(x: String, y: String): Double {
        if (x.length < 2 || y.length < 2) return if (x == y) 1.0 else 0.0
        val px = x.windowed(2).groupingBy { it }.eachCount().toMutableMap()
        var common = 0
        for (p in y.windowed(2)) {
            val n = px[p] ?: 0
            if (n > 0) {
                common++
                px[p] = n - 1
            }
        }
        return 2.0 * common / (x.length - 1 + y.length - 1)
    }
}
