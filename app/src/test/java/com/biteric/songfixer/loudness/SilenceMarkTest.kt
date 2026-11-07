package com.biteric.songfixer.loudness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceMarkTest {
    private val mark = SilenceMark(Silence(44_100, 10_000_000, 88_200, 9_800_000), FileStamp(4_100_861, 1_762_500_000_000))

    @Test
    fun comesBackAsSaved() = assertEquals(mark, SilenceMark.decode(mark.encode()))

    @Test
    fun onlyTheSameFileMatches() {
        assertTrue(mark.matches(FileStamp(4_100_861, 1_762_500_000_000)))
        // Any other size or time: the song is listened to again.
        assertFalse(mark.matches(FileStamp(4_100_862, 1_762_500_000_000)))
        assertFalse(mark.matches(FileStamp(4_100_861, 1_762_500_000_001)))
        assertFalse(mark.matches(null))
    }

    @Test
    fun nonsenseIsNoMark() {
        assertNull(SilenceMark.decode(null))
        assertNull(SilenceMark.decode("44100:100:50"))
        assertNull(SilenceMark.decode("0:100:10:90:1:2"))
        // Sound ending before it starts, or past the end.
        assertNull(SilenceMark.decode("44100:100:60:50:1:2"))
        assertNull(SilenceMark.decode("44100:100:10:200:1:2"))
    }
}
