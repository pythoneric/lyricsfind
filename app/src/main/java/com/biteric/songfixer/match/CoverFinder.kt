package com.biteric.songfixer.match

import com.biteric.songfixer.online.CoverArtApi
import com.biteric.songfixer.online.CoverImage
import com.biteric.songfixer.online.MbReleaseGroup
import com.biteric.songfixer.online.MusicBrainzApi

/** Finds a song's album cover on the Cover Art Archive. */
class CoverFinder(private val musicBrainz: MusicBrainzApi, private val coverArt: CoverArtApi) {
    /**
     * The album's release group: [knownId] when the match carried one, else a MusicBrainz album
     * with this name by this artist. Null when there's no album to go by.
     */
    suspend fun releaseGroup(knownId: String?, album: String?, artist: String?): String? {
        if (knownId != null) return knownId
        if (album.isNullOrBlank() || artist.isNullOrBlank()) return null
        return best(musicBrainz.searchReleaseGroups(album, artist), album, artist)?.id
    }

    /** The front cover, or null when the album (or its cover) isn't known. Throws IOException offline. */
    suspend fun find(knownId: String?, album: String?, artist: String?): CoverImage? {
        val id = releaseGroup(knownId, album, artist) ?: return null
        return coverArt.front(id)
    }

    companion object {
        /** Same album name and artist; a confident search hit. */
        fun best(groups: List<MbReleaseGroup>, album: String, artist: String): MbReleaseGroup? = groups
            .filter { g ->
                g.id != null && Text.same(g.title, album) && (g.score ?: 100) >= 80 &&
                    Text.artistSimilarity(g.artistCredit.joinToString("") { it.name + (it.joinphrase ?: "") }, artist) >= 0.99
            }
            // Albums over singles and compilations of the same name.
            .sortedBy { if (it.primaryType == "Album" && it.secondaryTypes.isEmpty()) 0 else 1 }
            .firstOrNull()
    }
}
