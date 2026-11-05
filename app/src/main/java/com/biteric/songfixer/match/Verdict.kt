package com.biteric.songfixer.match

import com.biteric.songfixer.data.Candidate
import com.biteric.songfixer.data.SongStatus

object Verdict {
    /** Below this, a name match is only shown as an idea, not as a suggestion. */
    const val MIN_SUGGEST_SCORE = 0.6

    /**
     * Is the song already named right, is there something to fix, or was nothing found?
     * [candidates] best first. With the names right, a missing album or genre the match knows,
     * or a [year] missing or different from the match's (see [correctYear]), is something to fix.
     */
    fun status(title: String?, artist: String?, album: String?, genre: String?, candidates: List<Candidate>, year: Int? = null): SongStatus {
        val best = candidates.firstOrNull()
        // Tags naming another version of a song that exists ("Panic Room (CamelPhat Club Mix)"
        // when only "Panic Room" is known, at another length): the names are right as they are.
        val versionOfKnown = candidates.any { Text.sameSong(title, it.title) && Text.artistSimilarity(artist, it.artist) >= 0.99 }
        if (best == null || best.score < MIN_SUGGEST_SCORE) return if (versionOfKnown) SongStatus.OK else SongStatus.NOT_FOUND
        val namesRight = namesRight(title, artist, best)
        val fillsGaps = (album.isNullOrBlank() && !best.album.isNullOrBlank()) || (genre.isNullOrBlank() && !best.genre.isNullOrBlank()) ||
            (best.year != null && year != best.year)
        return when {
            namesRight && !fillsGaps -> SongStatus.OK
            // The best match is the original of a version the user has: keep their names.
            !namesRight && versionOfKnown && Text.sameSong(title, best.title) -> SongStatus.OK
            else -> SongStatus.SUGGESTED
        }
    }

    /**
     * The year a song should have: its best match's (MusicBrainz first release of the recording),
     * when that match is good and names the song the same way (title and artist), and the file's
     * [year] is missing or different. Null when there's nothing to correct or nothing to go by.
     */
    fun correctYear(title: String?, artist: String?, year: Int?, candidates: List<Candidate>): Int? {
        val best = candidates.firstOrNull() ?: return null
        val right = best.year ?: return null
        if (best.score < MIN_SUGGEST_SCORE || right == year || !namesRight(title, artist, best)) return null
        return right
    }

    /** The year is all that's wrong: with [correctYear] written, the song would be named right. */
    fun onlyYearWrong(title: String?, artist: String?, album: String?, genre: String?, year: Int?, candidates: List<Candidate>): Boolean {
        val right = correctYear(title, artist, year, candidates) ?: return false
        return status(title, artist, album, genre, candidates, year = right) == SongStatus.OK
    }

    private fun namesRight(title: String?, artist: String?, best: Candidate) =
        Text.same(title, best.title) && Text.artistSimilarity(artist, best.artist) >= 0.99

    /** One candidate (or none): see [status]. */
    fun status(title: String?, artist: String?, album: String?, genre: String?, best: Candidate?, year: Int? = null): SongStatus =
        status(title, artist, album, genre, listOfNotNull(best), year)
}
