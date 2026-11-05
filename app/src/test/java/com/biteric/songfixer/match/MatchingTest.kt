package com.biteric.songfixer.match

import com.biteric.songfixer.data.Candidate
import com.biteric.songfixer.data.MatchSource
import com.biteric.songfixer.data.SongStatus
import com.biteric.songfixer.files.SongWriter
import com.biteric.songfixer.online.AcoustIdReleaseGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchingTest {
    @Test
    fun textComparisonIgnoresCaseAccentsAndFeaturing() {
        assertTrue(Text.same("Canción", "cancion"))
        assertTrue(Text.same("Hello (Remastered 2011)", "hello"))
        assertTrue(Text.same("Taki Taki feat. Selena Gomez", "Taki Taki"))
        assertEquals(1.0, Text.artistSimilarity("Shakira feat. Wyclef Jean", "Shakira"), 0.0)
        assertTrue(Text.similarity("Despasito", "Despacito") > 0.7)
        assertTrue(Text.similarity("Bohemian Rhapsody", "Hotel California") < 0.3)
    }

    @Test
    fun lengthDecidesBetweenRecordings() {
        val guess = NameGuess("Hello", "Adele")
        val same = Identifier.scoreByName("Hello", "Adele", guess, 295, 296)
        val live = Identifier.scoreByName("Hello", "Adele", guess, 340, 296)
        assertTrue(same >= Identifier.SURE_NAME_SCORE)
        assertTrue(live < Verdict.MIN_SUGGEST_SCORE + 0.2)
        assertTrue(same > live)
        // A perfect name alone isn't sure, and a confirmed length beats an unknown one.
        val unknown = Identifier.scoreByName("Hello", "Adele", guess, null, 296)
        assertTrue(unknown < Identifier.SURE_NAME_SCORE)
        assertTrue(same > unknown)
        // A 17 s longer live version isn't sure either.
        assertTrue(Identifier.scoreByName("Hello", "Adele", guess, 313, 296) < Identifier.SURE_NAME_SCORE)
    }

    @Test
    fun picksTheOriginalAlbum() {
        val groups = listOf(
            AcoustIdReleaseGroup(title = "Now 95", type = "Album", secondarytypes = listOf("Compilation")),
            AcoustIdReleaseGroup(title = "Hello", type = "Single"),
            AcoustIdReleaseGroup(title = "25", type = "Album"),
        )
        assertEquals("25", Identifier.pickAlbum(groups, null))
        assertEquals("Hello", Identifier.pickAlbum(groups, "hello"))
        assertEquals("Hello", Identifier.pickAlbum(groups.take(2), null))
    }

    @Test
    fun longMixesGetALengthShare() {
        val guess = NameGuess("Ghosts N Stuff", "deadmau5")
        // 9 s off on a 6-minute mix still counts as the same recording.
        assertTrue(Identifier.scoreByName("Ghosts 'n' Stuff", "deadmau5", guess, 361, 370) > Identifier.scoreByName("Ghosts 'n' Stuff", "deadmau5", guess, 340, 370))
    }

    @Test
    fun carelessNamesLose() {
        assertTrue(Identifier.sloppiness(c("juicy", "notorious big")) > 0)
        assertTrue(Identifier.sloppiness(c("notorious_big-juicy", "notorious big")) > Identifier.sloppiness(c("juicy", "notorious big")))
        assertEquals(0.0, Identifier.sloppiness(c("Juicy", "The Notorious B.I.G.")), 0.0)
        assertEquals(0.0, Identifier.sloppiness(c("Ghosts 'n' Stuff", "deadmau5 feat. Rob Swire")), 0.0)
        assertEquals(0.0, Identifier.sloppiness(c("bad guy", "Billie Eilish")), 0.0)
    }

    private fun c(title: String, artist: String, album: String? = null, genre: String? = null, score: Double = 0.95) =
        Candidate(title, artist, album, genre, source = MatchSource.SOUND, score = score)

    @Test
    fun verdict() {
        // Right already.
        assertEquals(SongStatus.OK, Verdict.status("Hello", "Adele", "25", "Pop", c("Hello", "Adele", "25", "Pop")))
        assertEquals(SongStatus.OK, Verdict.status("hello", "ADELE", "25", null, c("Hello", "Adele", "25")))
        // Wrong or missing names.
        assertEquals(SongStatus.SUGGESTED, Verdict.status("Track 1", null, null, null, c("Hello", "Adele")))
        assertEquals(SongStatus.SUGGESTED, Verdict.status("Hello (Official Video)", "Adele", "25", "Pop", c("Hello", "Adele", "25", "Pop")))
        // Names right but the genre is empty and one was found.
        assertEquals(SongStatus.SUGGESTED, Verdict.status("Hello", "Adele", "25", null, c("Hello", "Adele", "25", "Pop")))
        // Nothing, or nothing good enough.
        assertEquals(SongStatus.NOT_FOUND, Verdict.status("x", "y", null, null, null))
        assertEquals(SongStatus.NOT_FOUND, Verdict.status("x", "y", null, null, c("Hello", "Adele", score = 0.5)))
    }

    @Test
    fun theYearRule() {
        val match = c("Hello", "Adele", "25", "Pop").copy(year = 2015)
        // Same year: right.
        assertEquals(SongStatus.OK, Verdict.status("Hello", "Adele", "25", "Pop", match, year = 2015))
        assertNull(Verdict.correctYear("Hello", "Adele", 2015, listOf(match)))
        // A different year: to fix, with the match's year.
        assertEquals(SongStatus.SUGGESTED, Verdict.status("Hello", "Adele", "25", "Pop", match, year = 2021))
        assertEquals(2015, Verdict.correctYear("Hello", "Adele", 2021, listOf(match)))
        // No year: to fix.
        assertEquals(SongStatus.SUGGESTED, Verdict.status("Hello", "Adele", "25", "Pop", match))
        assertEquals(2015, Verdict.correctYear("Hello", "Adele", null, listOf(match)))
        // A match without a year: right, whatever the file says.
        val noYear = c("Hello", "Adele", "25", "Pop")
        assertEquals(SongStatus.OK, Verdict.status("Hello", "Adele", "25", "Pop", noYear, year = 2021))
        assertEquals(SongStatus.OK, Verdict.status("Hello", "Adele", "25", "Pop", noYear))
        assertNull(Verdict.correctYear("Hello", "Adele", 2021, listOf(noYear)))
    }

    @Test
    fun weakOrOtherMatchesNeverFlagAYear() {
        // Weak: not found, and no year to correct.
        val weak = c("Hello", "Adele", "25", "Pop", score = 0.5).copy(year = 2015)
        assertEquals(SongStatus.NOT_FOUND, Verdict.status("x", "y", null, null, weak))
        assertNull(Verdict.correctYear("Hello", "Adele", 2021, listOf(weak)))
        // Another song or artist: the year isn't this song's.
        val other = c("Someone Like You", "Adele").copy(year = 2011)
        assertNull(Verdict.correctYear("Hello", "Adele", 2021, listOf(other)))
        assertNull(Verdict.correctYear("Hello", "Lionel Richie", 1984, listOf(c("Hello", "Adele").copy(year = 2015))))
        // A version of a known song (names not the same): its year is its own.
        val original = c("Panic Room", "Au/Ra", score = 0.5).copy(year = 2018)
        assertEquals(SongStatus.OK, Verdict.status("Panic Room (CamelPhat Club Mix)", "Au/Ra", "#", "Deep House", listOf(original), year = 2019))
        assertNull(Verdict.correctYear("Panic Room (CamelPhat Club Mix)", "Au/Ra", 2019, listOf(original)))
        // Same names in other spelling (case) count as the same names.
        assertEquals(2017, Verdict.correctYear("Stop It", "Fisher", 2021, listOf(c("Stop It", "FISHER").copy(year = 2017))))
    }

    @Test
    fun onlyTheYearWrong() {
        val match = c("Hello", "Adele", "25", "Pop").copy(year = 2015)
        assertTrue(Verdict.onlyYearWrong("Hello", "Adele", "25", "Pop", 2021, listOf(match)))
        assertTrue(Verdict.onlyYearWrong("Hello", "Adele", "25", "Pop", null, listOf(match)))
        // Right already, or more than the year missing (the genre), or the names wrong: not year-only.
        assertTrue(!Verdict.onlyYearWrong("Hello", "Adele", "25", "Pop", 2015, listOf(match)))
        assertTrue(!Verdict.onlyYearWrong("Hello", "Adele", "25", null, 2021, listOf(match)))
        assertTrue(!Verdict.onlyYearWrong("Track 1", null, null, null, 2021, listOf(match)))
    }

    @Test
    fun aVersionOfAKnownSongIsNamedRight() {
        // The club mix (7 min) when only the 4-minute original is known: names right as they are.
        val original = c("Panic Room", "Au/Ra", score = 0.5)
        assertEquals(SongStatus.OK, Verdict.status("Panic Room (CamelPhat Club Mix)", "Au/Ra", "#", "Deep House", listOf(original)))
        // Guests in the title, credited as artists in the database.
        assertEquals(SongStatus.OK, Verdict.status("Common Emotion ft. MNEK", "Rudimental", "#", "Deep House", listOf(c("Common Emotion", "Rudimental feat. MNEK", "Common Emotion"))))
        // Another artist's song of the same name is not.
        assertEquals(SongStatus.NOT_FOUND, Verdict.status("Panic Room (Club Mix)", "Au/Ra", "#", "House", listOf(c("Panic Room", "Somebody Else", score = 0.5))))
    }

    @Test
    fun fileNames() {
        assertEquals("AC_DC - Back in Black.mp3".replace("_", " "), SongWriter.fileName("AC/DC", "Back in Black", "mp3"))
        assertEquals("Artist - What.flac", SongWriter.fileName("Artist", "What?", "flac"))
        assertEquals("Beyoncé - Halo.m4a", SongWriter.fileName(" Beyoncé ", "Halo ", "m4a"))
    }
}
