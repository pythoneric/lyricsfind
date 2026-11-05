package com.biteric.songfixer.ui

import com.biteric.songfixer.data.Candidate
import com.biteric.songfixer.data.MatchSource
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.files.MusicFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The year: read from files as Android reports it, and the sheet's Year box. */
class YearTextTest {
    private fun song(year: Int?) = Song(uri = "content://x/a.mp3", fileName = "a.mp3", folder = "", size = 1, modified = 1, year = year)
    private fun candidate(year: Int?) = Candidate("Hello", "Adele", source = MatchSource.NAME, score = 1.0, year = year)

    @Test
    fun yearsAsAndroidReportsThem() {
        assertEquals(2019, MusicFolder.parseYear("2019"))
        assertEquals(2019, MusicFolder.parseYear(" 2019 "))
        assertEquals(2019, MusicFolder.parseYear("2019-05-17"))
        assertEquals(2019, MusicFolder.parseYear("20190517T000000.000Z"))
        assertEquals(1998, MusicFolder.parseYear("1998/2004"))
        assertNull(MusicFolder.parseYear(null))
        assertNull(MusicFolder.parseYear(""))
        assertNull(MusicFolder.parseYear("0"))
        assertNull(MusicFolder.parseYear("0000"))
        assertNull(MusicFolder.parseYear("19"))
        assertNull(MusicFolder.parseYear("unknown"))
    }

    @Test
    fun yearBoxStartsFromTheMatchThenTheFile() {
        assertEquals("2015", startYear(candidate(2015), song(2021)))
        assertEquals("2021", startYear(candidate(null), song(2021)))
        assertEquals("2021", startYear(null, song(2021)))
        assertEquals("", startYear(candidate(null), song(null)))
        assertEquals("", startYear(null, song(null)))
    }

    @Test
    fun yearBoxTakesOnlyRealYears() {
        assertNull(yearProblem("", thisYear = 2026))
        assertNull(yearProblem("  ", thisYear = 2026))
        assertNull(yearProblem("2019", thisYear = 2026))
        assertNull(yearProblem("1958", thisYear = 2026))
        // Next year's release date is announced ahead.
        assertNull(yearProblem("2027", thisYear = 2026))
        assertEquals("Four digits, like 2019", yearProblem("19", thisYear = 2026))
        assertEquals("Four digits, like 2019", yearProblem("20195", thisYear = 2026))
        assertEquals("Four digits, like 2019", yearProblem("abcd", thisYear = 2026))
        assertEquals("Not a year a song could be from", yearProblem("2099", thisYear = 2026))
        assertEquals("Not a year a song could be from", yearProblem("0999", thisYear = 2026))
    }
}
