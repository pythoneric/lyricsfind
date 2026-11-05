package com.biteric.songfixer.ui

import com.biteric.songfixer.data.Song
import com.biteric.songfixer.files.SongWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceTextTest {
    private fun song(name: String, lead: Long?, tail: Long?) =
        Song(uri = "content://x/$name", fileName = name, folder = "", size = 1, modified = 1, silenceStartMs = lead, silenceEndMs = tail, silenceChecked = true)

    @Test
    fun chipsSayWhichEnd() {
        assertEquals("Silence at both ends", silenceChip(song("a.mp3", 1800, 3200)))
        assertEquals("Silence at start", silenceChip(song("a.mp3", 1800, 100)))
        assertEquals("Silence at end", silenceChip(song("a.flac", 0, 900)))
        assertNull(silenceChip(song("a.mp3", 300, 400)))
        assertNull(silenceChip(song("a.mp3", null, null)))
    }

    @Test
    fun onlyFormatsThatCanBeCutAreOffered() {
        assertTrue(song("a.wav", 2000, 0).hasSilenceToTrim)
        assertFalse(song("a.m4a", 2000, 2000).hasSilenceToTrim)
        assertFalse(song("a.ogg", 2000, 2000).hasSilenceToTrim)
    }

    @Test
    fun texts() {
        assertEquals("1.8 s at the start · 3.2 s at the end", silenceSummary(song("a.mp3", 1800, 3200)))
        assertEquals("none to cut", silenceSummary(song("a.mp3", 120, 40)))
        assertEquals("3.0 s of silence at the start and 4.0 s at the end", silenceAtEnds(3000, 4000))
        assertEquals("4.0 s of silence at the end", silenceAtEnds(200, 4000))
        assertEquals("cut 2.8 s of silence at the start and 3.8 s at the end", trimmedText(SongWriter.Trimmed(2800, 3800, 100, 150)))
        assertEquals("cut 450 ms of silence at the end", trimmedText(SongWriter.Trimmed(0, 450, 50, 150)))
    }
}
