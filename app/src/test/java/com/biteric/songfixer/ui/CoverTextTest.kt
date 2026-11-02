package com.biteric.songfixer.ui

import com.biteric.songfixer.SongRepository.LyricsOutcome
import com.biteric.songfixer.data.Song
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the sheet and the messages say about the cover in the file and the new one. */
class CoverTextTest {
    private fun song(hasCover: Boolean?, fileName: String = "a.mp3") =
        Song(uri = "content://x/$fileName", fileName = fileName, folder = "", size = 1, modified = 1, hasCover = hasCover)

    @Test
    fun detailSaysWhatTickingDoes() {
        assertEquals("The new cover replaces the one in the file", coverDetail(song(true), addCover = true))
        assertEquals("The file keeps its cover", coverDetail(song(true), addCover = false))
        assertEquals("The album's front cover, from the Cover Art Archive", coverDetail(song(false), addCover = true))
        assertEquals("The album's front cover, from the Cover Art Archive", coverDetail(song(null), addCover = false))
        assertEquals(".opus files can't hold a cover here", coverDetail(song(true, "a.opus"), addCover = true))
    }

    @Test
    fun buttonSaysReplaceWhenThereIsACover() {
        assertEquals("Replace cover only", coverOnlyLabel(song(true)))
        assertEquals("Add cover only", coverOnlyLabel(song(false)))
        assertEquals("Add cover only", coverOnlyLabel(song(null)))
    }

    @Test
    fun fixMessageSaysReplacedOrAdded() {
        assertEquals(" · cover replaced", coverNote(LyricsOutcome.ADDED, hadCover = true))
        assertEquals(" · cover added", coverNote(LyricsOutcome.ADDED, hadCover = false))
        assertEquals(" · no new cover found, kept the old one", coverNote(LyricsOutcome.NOT_ONLINE, hadCover = true))
        assertEquals(" · no cover found", coverNote(LyricsOutcome.NOT_ONLINE, hadCover = false))
        assertEquals(" · cover site unreachable", coverNote(LyricsOutcome.OFFLINE, hadCover = true))
        assertEquals("", coverNote(LyricsOutcome.ALREADY_THERE, hadCover = true))
        assertEquals("", coverNote(LyricsOutcome.NOT_ASKED, hadCover = false))
    }

    @Test
    fun coverOnlyMessages() {
        assertEquals("Cover replaced", coverOnlyMessage(LyricsOutcome.ADDED, hadCover = true))
        assertEquals("Cover added", coverOnlyMessage(LyricsOutcome.ADDED, hadCover = false))
        assertEquals("No new cover found; the old one stays", coverOnlyMessage(LyricsOutcome.NOT_ONLINE, hadCover = true))
        assertEquals("No cover found for its album", coverOnlyMessage(LyricsOutcome.NOT_ONLINE, hadCover = false))
        assertEquals("This song already has a cover", coverOnlyMessage(LyricsOutcome.ALREADY_THERE, hadCover = true))
        assertEquals("The cover site can't be reached, try again later", coverOnlyMessage(LyricsOutcome.OFFLINE, hadCover = false))
    }
}
