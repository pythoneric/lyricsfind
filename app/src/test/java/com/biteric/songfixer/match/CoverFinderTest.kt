package com.biteric.songfixer.match

import com.biteric.songfixer.online.MbCredit
import com.biteric.songfixer.online.MbReleaseGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverFinderTest {
    private fun group(id: String, title: String, artist: String, type: String = "Album", secondary: List<String> = emptyList(), score: Int = 100) =
        MbReleaseGroup(id = id, title = title, score = score, artistCredit = listOf(MbCredit(artist)), primaryType = type, secondaryTypes = secondary)

    @Test
    fun theAlbumOfThatName() {
        val groups = listOf(
            group("single", "25", "Adele", type = "Single"),
            group("other", "25", "Somebody Else"),
            group("album", "25", "Adele"),
        )
        assertEquals("album", CoverFinder.best(groups, "25", "Adele")?.id)
    }

    @Test
    fun noGuessing() {
        assertNull(CoverFinder.best(listOf(group("x", "25 Live", "Adele")), "25", "Adele"))
        assertNull(CoverFinder.best(listOf(group("x", "25", "Adele", score = 40)), "25", "Adele"))
    }
}
