package com.biteric.songfixer.ui

import com.biteric.songfixer.SongRepository
import com.biteric.songfixer.data.Candidate
import com.biteric.songfixer.data.MatchSource
import com.biteric.songfixer.data.SettingsState
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.data.SongStatus
import com.biteric.songfixer.files.NewNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Correcting a wrong year: which songs, what the list says, and that only the year is changed. */
class YearFixTest {
    private val settings = SettingsState(writeTags = true, renameFiles = true, addLyrics = true, addCover = true, normalize = true)

    /** Stop It by Fisher, year [year]; its best match says FISHER (other case), 2017. */
    private fun song(year: Int?, status: SongStatus = SongStatus.SUGGESTED, genre: String? = "Techno", fileName: String = "Stop It.mp3", matchYear: Int? = 2017) = Song(
        uri = "content://x/$fileName", fileName = fileName, folder = "", size = 1, modified = 1,
        title = "Stop It", artist = "Fisher", album = "Oi Oi", genre = genre, year = year, yearRead = true, status = status,
        candidates = listOf(
            Candidate("Stop It", "FISHER", "Oi Oi", "Techno", year = matchYear, source = MatchSource.NAME, score = 1.0, sure = true, releaseGroupId = "rg"),
            Candidate("Stop It - Original Mix", "FISHER", source = MatchSource.NAME, score = 0.81),
        ),
    )

    @Test
    fun chipShowsTheFileYearAndTheRightOne() {
        assertEquals("Year 2021 → 2017", yearChip(song(2021)))
        assertEquals("No year → 2017", yearChip(song(null)))
        assertNull(yearChip(song(2017)))
        assertNull(yearChip(song(2021, matchYear = null)))
    }

    @Test
    fun fixedAndKeptSongsAreLeftAlone() {
        for (status in listOf(SongStatus.FIXED, SongStatus.KEPT, SongStatus.OK, SongStatus.NEW, SongStatus.NOT_FOUND)) {
            val s = song(2021, status = status)
            assertFalse(status.name, SongRepository.isYearOnly(s))
            assertNull(status.name, yearChip(s))
        }
        assertTrue(SongRepository.yearOnly(listOf(song(2021, status = SongStatus.FIXED), song(2021, status = SongStatus.KEPT))).isEmpty())
    }

    @Test
    fun yearOnlySongs() {
        assertTrue(SongRepository.isYearOnly(song(2021)))
        assertTrue(SongRepository.isYearOnly(song(null)))
        // Something else missing too (the genre): a whole fix, not year-only.
        assertFalse(SongRepository.isYearOnly(song(2021, genre = null)))
        // A format without tags can't take a year.
        assertFalse(SongRepository.isYearOnly(song(2021, fileName = "Stop It.opus")))
        assertEquals(listOf(2021, null), SongRepository.yearOnly(listOf(song(2021), song(null), song(2017), song(2021, genre = null))).map { it.year })
    }

    @Test
    fun aYearOnlyFixWritesTheYearAndNothingElse() {
        val plan = SongRepository.yearOnlyPlan(song(2021), settings, extras = false)
        // Blank names are left as they are in the file: only the year is written.
        assertEquals(NewNames("", "", "", "", "2017"), plan.names)
        assertTrue(plan.writeTags)
        assertFalse(plan.rename)
        assertFalse(plan.addLyrics)
        assertFalse(plan.addCover)
        assertFalse(plan.normalize)
    }

    @Test
    fun fixSureOnesCorrectsJustTheYearOfYearOnlySongs() {
        val s = song(2021)
        val plan = SongRepository.surePlan(s, s.best!!, settings)
        assertEquals(NewNames("", "", "", "", "2017"), plan.names)
        assertFalse("no rename, though the settings rename", plan.rename)
        // Lyrics, cover and volume as every fix adds them (cover never replacing one).
        assertTrue(plan.addLyrics && plan.addCover && plan.normalize)
        // With writing tags turned off, it writes nothing.
        assertFalse(SongRepository.surePlan(s, s.best!!, settings.copy(writeTags = false)).writeTags)
    }

    @Test
    fun otherSureFixesStayWhole() {
        val wrong = song(2021).copy(title = "track01", artist = null)
        val plan = SongRepository.surePlan(wrong, wrong.best!!, settings)
        assertEquals(NewNames("Stop It", "FISHER", "Oi Oi", "Techno", "2017"), plan.names)
        assertTrue(plan.rename)
    }

    @Test
    fun sheetStartsAYearOnlyFixFromTheFilesNames() {
        val s = song(2021)
        val start = fixStart(s, s.best, renameSetting = true, coverSetting = true)
        // The file's own spelling ("Fisher", not the match's "FISHER"), no rename, the right year; a cover only where there's none.
        assertEquals(FixStart("Stop It", "Fisher", "Oi Oi", "Techno", "2017", rename = false, addCover = true), start)
        // Its own cover isn't replaced by a year fix.
        assertFalse(fixStart(s.copy(hasCover = true), s.best, renameSetting = true, coverSetting = true).addCover)
        assertFalse(fixStart(s, s.best, renameSetting = true, coverSetting = false).addCover)
        // Another suggestion picked: its names, renamed and with a cover as the settings say.
        val other = fixStart(s.copy(hasCover = true), s.candidates[1], renameSetting = true, coverSetting = true)
        assertEquals("Stop It - Original Mix", other.title)
        assertTrue(other.rename)
        assertTrue(other.addCover)
        // Not year-only: the match's names.
        assertEquals("FISHER", fixStart(song(2021, genre = null), s.best, renameSetting = true, coverSetting = true).artist)
    }
}
