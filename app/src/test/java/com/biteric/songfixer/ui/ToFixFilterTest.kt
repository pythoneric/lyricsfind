package com.biteric.songfixer.ui

import com.biteric.songfixer.data.SettingsState
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.data.SongStatus
import com.biteric.songfixer.loudness.GainVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "To fix" holds every song with something to do, not only better names; "Done" the rest of the right ones. */
class ToFixFilterTest {
    private val all = SettingsState()

    private fun song(status: SongStatus, name: String = "a.mp3", change: (Song) -> Song = { it }) =
        change(Song(uri = "content://x/$name", fileName = name, folder = "", size = 1, modified = 1, status = status))

    /** Named right, measured, with standard volume tags, no silence, lyrics in the file: nothing to do. */
    private fun clean(status: SongStatus = SongStatus.OK, name: String = "a.mp3") = song(status, name) {
        it.copy(
            loudnessLufs = -18.0, loudnessChecked = true, gainVerdict = GainVerdict.STANDARD,
            silenceStartMs = 0, silenceEndMs = 100, silenceChecked = true, lyricsFound = true, lyricsInFile = true,
        )
    }

    private fun toFix(s: Song, settings: SettingsState = all) = SongFilter.TO_FIX.matches(s, settings)
    private fun done(s: Song, settings: SettingsState = all) = SongFilter.DONE.matches(s, settings)

    @Test
    fun betterNamesAreToFix() = assertTrue(toFix(song(SongStatus.SUGGESTED)))

    @Test
    fun aCleanSongIsDone() {
        assertFalse(toFix(clean()))
        assertTrue(done(clean()))
    }

    @Test
    fun volumeTagsSilenceAndLyricsAreToFixToo() {
        val noTags = clean().copy(gainVerdict = GainVerdict.NONE)
        val silent = clean().copy(silenceStartMs = 2000)
        val lyrics = clean().copy(lyricsInFile = false)
        for (s in listOf(noTags, silent, lyrics)) {
            assertTrue(s.toString(), toFix(s))
            assertFalse(s.toString(), done(s))
        }
        // Not found by name, but its volume and silence can still be fixed.
        assertTrue(toFix(clean(SongStatus.NOT_FOUND).copy(silenceEndMs = 3000)))
        assertTrue(SongFilter.NOT_FOUND.matches(clean(SongStatus.NOT_FOUND).copy(silenceEndMs = 3000), all))
    }

    @Test
    fun theSettingsDecide() {
        assertFalse(toFix(clean().copy(gainVerdict = GainVerdict.NONE), all.copy(normalize = false)))
        assertFalse(toFix(clean().copy(silenceStartMs = 2000), all.copy(trimSilence = false)))
        assertFalse(toFix(clean().copy(lyricsInFile = false), all.copy(addLyrics = false)))
        assertTrue(done(clean().copy(silenceStartMs = 2000), all.copy(trimSilence = false)))
    }

    @Test
    fun formatsThatCantTakeItArentToFix() {
        assertFalse(toFix(clean(name = "a.ogg").copy(silenceStartMs = 2000)))
        assertFalse(toFix(clean(name = "a.opus").copy(gainVerdict = GainVerdict.NONE)))
        // WAV tags can't hold ReplayGain values: never stuck in "To fix" for volume tags (silence still counts).
        assertFalse(toFix(clean(name = "a.wav").copy(gainVerdict = GainVerdict.NONE)))
        assertTrue(toFix(clean(name = "a.wav").copy(gainVerdict = GainVerdict.NONE, silenceStartMs = 2000)))
    }

    @Test
    fun songsNotCheckedYetWait() {
        assertFalse(toFix(clean(SongStatus.NEW).copy(silenceStartMs = 2000, gainVerdict = GainVerdict.NONE)))
        assertEquals(false, done(clean(SongStatus.NEW)))
    }
}
