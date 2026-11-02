package com.biteric.songfixer.match

import com.biteric.songfixer.data.Candidate
import com.biteric.songfixer.data.SongStatus

object Verdict {
    /** Below this, a name match is only shown as an idea, not as a suggestion. */
    const val MIN_SUGGEST_SCORE = 0.6

    /** Is the song already named right, is there something to fix, or was nothing found? */
    fun status(title: String?, artist: String?, album: String?, genre: String?, best: Candidate?): SongStatus {
        if (best == null || best.score < MIN_SUGGEST_SCORE) return SongStatus.NOT_FOUND
        val namesRight = Text.same(title, best.title) && Text.artistSimilarity(artist, best.artist) >= 0.99
        val fillsGaps = (album.isNullOrBlank() && !best.album.isNullOrBlank()) || (genre.isNullOrBlank() && !best.genre.isNullOrBlank())
        return if (namesRight && !fillsGaps) SongStatus.OK else SongStatus.SUGGESTED
    }
}
