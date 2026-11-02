package com.biteric.songfixer.match

import com.biteric.songfixer.online.LrclibTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsFinderTest {
    private val plain = LrclibTrack(1, "Hello", "Adele", duration = 295.0, plainLyrics = "plain")
    private val synced = LrclibTrack(2, "Hello", "Adele", duration = 296.0, plainLyrics = "plain", syncedLyrics = "[00:01.00] synced")
    private val otherLength = LrclibTrack(3, "Hello", "Adele", duration = 330.0, syncedLyrics = "[00:01.00] live")
    private val otherSong = LrclibTrack(4, "Hello", "Lionel Richie", duration = 295.0, syncedLyrics = "[00:01.00] other")
    private val empty = LrclibTrack(5, "Hello", "Adele", duration = 295.0)

    @Test
    fun syncedLyricsOfTheSameRecordingWin() {
        val best = LyricsFinder.best(listOf(plain, otherLength, otherSong, synced, empty), "Hello", "Adele", 295)!!
        assertEquals(2L, best.id)
        assertEquals("[00:01.00] synced", LyricsFinder.text(best))
    }

    @Test
    fun plainWhenThatsAllThereIs() = assertEquals("plain", LyricsFinder.text(LyricsFinder.best(listOf(plain, otherSong), "Hello", "Adele", 295)!!))

    @Test
    fun noneForAnotherLengthOrArtist() = assertNull(LyricsFinder.best(listOf(otherLength, otherSong, empty), "Hello", "Adele", 295))

    @Test
    fun searchResultsForPickingByHand() {
        // Any name (the user judges), but only entries with text; synced first, then closest length.
        val ranked = com.biteric.songfixer.SongRepository.rankLyrics(listOf(plain, otherLength, empty, otherSong, synced), 295)
        // otherSong (295 s) is closer than synced (296 s); plain comes last.
        assertEquals(listOf(4L, 2L, 3L, 1L), ranked.map { it.id })
    }

    @Test
    fun instrumentalHasNoText() = assertNull(LyricsFinder.text(LrclibTrack(6, "Intro", "X", instrumental = true)))
}
