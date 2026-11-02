package com.biteric.songfixer.match

/** One reading of what a song is called. [artist] is null when nothing hints at it. */
data class NameGuess(val title: String, val artist: String?)

/** Reads possible title/artist pairs out of a song's tags and file name. */
object NameGuesses {
    private val junkWords =
        """official|video|videoclip|audio|lyrics?|letra|hd|hq|4k|1080p|720p|\d+\s*kbps|mp3|visuali[sz]er|music video|clip oficial|video oficial|en vivo oficial|full song|free download|download"""
    /** Brackets with only junk inside: "(Official Video)", "[HD]", "(Lyrics)". */
    private val junkBrackets = Regex("""\s*[(\[{]([^)\]}]*\b($junkWords)\b[^)\]}]*)[)\]}]""", RegexOption.IGNORE_CASE)
    private val sites = Regex("""\s*[(\[]?\b(www\.)?[\w-]+\.(com|net|org|info|me|co|io|ru|to|cc)\b[)\]]?""", RegexOption.IGNORE_CASE)
    /** "01 ", "01. ", "1-03 ", "3) ", "CD1 05 - " but not the "50" of "50 Cent". */
    private val trackNumber = Regex("""^\s*(cd\s*\d+\s*[-.]?\s*)?(\d{1,2}[-.]\d{1,2}\s+|\d{1,3}\s*[-._)]\s*|0\d\s+)""", RegexOption.IGNORE_CASE)
    private val separators = Regex("""\s+[-–—~|]+\s+|\s*[–—]\s*|\s+-|-\s+""")
    private val spaces = Regex("""\s+""")
    /** "Track 3", "Unknown", "05", WhatsApp's "AUD-20240101-WA0003" (but not "1999", a real title). */
    private val placeholders = Regex(
        """^((track|pista|audio|unknown|untitled|sin titulo|sin título|desconocido|new recording|grabaci[oó]n|aud|rec|voice)[\s_-]*\d*|\d{0,3}|(aud|ptt)[-_]\d{8}[-_]wa\d+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val unknownArtists = setOf("#", "unknown", "unknown artist", "<unknown>", "artist", "various", "various artists", "desconocido", "artista desconocido", "va")

    /** Tags like "Track 3", "Unknown" or a bare number say nothing. */
    fun usableTitle(s: String?) = !s.isNullOrBlank() && !placeholders.matches(s.trim()) && Text.normalize(s).isNotEmpty()

    fun usableArtist(s: String?) = !s.isNullOrBlank() && s.trim().lowercase() !in unknownArtists && Text.normalize(s).isNotEmpty()

    /** Strips download-site junk, track numbers and underscores from a name. */
    fun clean(s: String): String {
        var t = s.replace('_', ' ')
        t = t.replace(junkBrackets, " ").replace(sites, " ")
        t = t.replace(Regex("""\b(official\s+(music\s+)?video|official\s+audio|lyric\s+video|with\s+lyrics)\b""", RegexOption.IGNORE_CASE), " ")
        return t.replace(spaces, " ").trim(' ', '-', '.', '|', '~')
    }

    /** Most likely first, at most 4, no duplicates. */
    fun from(fileName: String, title: String?, artist: String?): List<NameGuess> {
        val guesses = mutableListOf<NameGuess>()
        val tagTitle = title?.takeIf(::usableTitle)?.let(::clean)
        val tagArtist = artist?.takeIf(::usableArtist)?.trim()

        val base = clean(fileName.substringBeforeLast('.').let { if ('.' in fileName) it else fileName })
        val noNumber = base.replace(trackNumber, "").trim().ifEmpty { base }
        val parts = noNumber.split(separators).map { it.trim(' ', '-', '.') }.filter { it.isNotEmpty() }

        if (tagTitle != null && tagArtist != null) guesses += NameGuess(tagTitle, tagArtist)
        // A title tag that holds "Artist - Title" (common on downloaded files).
        if (tagTitle != null) {
            val tp = tagTitle.split(separators).map { it.trim() }.filter { it.isNotEmpty() }
            if (tp.size == 2) guesses += NameGuess(tp[1], tp[0])
        }
        if (parts.size >= 2) {
            // "Artist - Title", or "Artist - Album - Title" / "01 - Artist - Title".
            guesses += NameGuess(parts.last(), parts[parts.size - 2])
            if (tagArtist == null) guesses += NameGuess(parts[parts.size - 2], parts.last())
        }
        if (tagTitle != null && tagArtist == null) guesses += NameGuess(tagTitle, parts.takeIf { it.size >= 2 }?.first())
        if (tagArtist != null && parts.isNotEmpty() && tagTitle == null) guesses += NameGuess(parts.last(), tagArtist)
        if (parts.size == 1 && usableTitle(parts[0])) guesses += NameGuess(parts[0], tagArtist)
        if (guesses.isEmpty() && usableTitle(noNumber)) guesses += NameGuess(noNumber, null)
        // Last resort: the artist tag may be the wrong part (e.g. the title copied into it).
        guesses.firstOrNull { it.artist != null }?.let { guesses += NameGuess(it.title, null) }

        return guesses
            .filter { usableTitle(it.title) }
            .distinctBy { Text.normalize(it.title) + "|" + Text.normalize(it.artist) }
            .take(4)
    }
}
