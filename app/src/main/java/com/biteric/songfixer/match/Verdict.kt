package com.biteric.songfixer.match

import com.biteric.songfixer.data.Candidate
import com.biteric.songfixer.data.SongStatus

object Verdict {
    /** Below this, a name match is only shown as an idea, not as a suggestion. */
    const val MIN_SUGGEST_SCORE = 0.6

    /**
     * Is the song already named right, is there something to fix, or was nothing found?
     * [candidates] best first. A missing album, genre or [year] the match knows is something to
     * fix; a different year isn't (remasters and compilations carry their own).
     */
    fun status(title: String?, artist: String?, album: String?, genre: String?, candidates: List<Candidate>, year: Int? = null): SongStatus {
        val best = candidates.firstOrNull()
        // Tags naming another version of a song that exists ("Panic Room (CamelPhat Club Mix)"
        // when only "Panic Room" is known, at another length): the names are right as they are.
        val versionOfKnown = candidates.any { Text.sameSong(title, it.title) && Text.artistSimilarity(artist, it.artist) >= 0.99 }
        if (best == null || best.score < MIN_SUGGEST_SCORE) return if (versionOfKnown) SongStatus.OK else SongStatus.NOT_FOUND
        val namesRight = Text.same(title, best.title) && Text.artistSimilarity(artist, best.artist) >= 0.99
        val fillsGaps = (album.isNullOrBlank() && !best.album.isNullOrBlank()) || (genre.isNullOrBlank() && !best.genre.isNullOrBlank()) ||
            (year == null && best.year != null)
        return when {
            namesRight && !fillsGaps -> SongStatus.OK
            // The best match is the original of a version the user has: keep their names.
            !namesRight && versionOfKnown && Text.sameSong(title, best.title) -> SongStatus.OK
            else -> SongStatus.SUGGESTED
        }
    }

    /** One candidate (or none): see [status]. */
    fun status(title: String?, artist: String?, album: String?, genre: String?, best: Candidate?, year: Int? = null): SongStatus =
        status(title, artist, album, genre, listOfNotNull(best), year)
}
