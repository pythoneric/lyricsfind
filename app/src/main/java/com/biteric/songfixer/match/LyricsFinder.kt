package com.biteric.songfixer.match

import com.biteric.songfixer.online.LrclibApi
import com.biteric.songfixer.online.LrclibTrack
import kotlin.math.abs

/** Finds a song's lyrics on LRCLIB by its (right) title, artist and length. */
class LyricsFinder(private val lrclib: LrclibApi) {
    /**
     * The best LRCLIB entry with lyrics: same title and artist, length within
     * [DURATION_TOLERANCE_SEC], synced lyrics over plain, then the closest length.
     * Null when LRCLIB has none; throws IOException when it can't be reached.
     */
    suspend fun find(title: String, artist: String, durationSec: Int?): LrclibTrack? =
        best(lrclib.search(title, artist), title, artist, durationSec)

    companion object {
        const val DURATION_TOLERANCE_SEC = 4

        fun best(results: List<LrclibTrack>, title: String, artist: String, durationSec: Int?): LrclibTrack? = results
            .filter { t ->
                t.hasLyrics && Text.same(t.trackName, title) && Text.artistSimilarity(t.artistName, artist) >= 0.99 &&
                    (durationSec == null || t.duration == null || abs(t.duration - durationSec) <= DURATION_TOLERANCE_SEC)
            }
            .sortedWith(
                compareBy<LrclibTrack> { it.syncedLyrics.isNullOrBlank() }
                    .thenBy { if (durationSec == null || it.duration == null) Double.MAX_VALUE else abs(it.duration - durationSec) },
            )
            .firstOrNull()

        /** The text to store: synced (LRC, players that know it scroll along) or else plain; null for instrumentals. */
        fun text(t: LrclibTrack): String? = t.syncedLyrics?.takeIf { it.isNotBlank() } ?: t.plainLyrics?.takeIf { it.isNotBlank() }
    }
}
