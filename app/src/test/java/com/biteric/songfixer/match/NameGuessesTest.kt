package com.biteric.songfixer.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameGuessesTest {
    private fun first(file: String, title: String? = null, artist: String? = null) = NameGuesses.from(file, title, artist).first()

    @Test
    fun artistDashTitle() = assertEquals(NameGuess("Despacito", "Luis Fonsi"), first("Luis Fonsi - Despacito.mp3"))

    @Test
    fun junkAndTrackNumbersRemoved() {
        assertEquals(NameGuess("Despacito", "Luis Fonsi"), first("01 - Luis Fonsi - Despacito (Official Video).mp3"))
        assertEquals(NameGuess("Bohemian Rhapsody", "Queen"), first("03. Queen - Bohemian Rhapsody [HD].mp3"))
        assertEquals(NameGuess("Hello", "Adele"), first("Adele_-_Hello_(Lyrics).mp3"))
        assertEquals(NameGuess("Shape of You", "Ed Sheeran"), first("Ed Sheeran - Shape of You [www.musicdownload.com].mp3"))
    }

    @Test
    fun numberThatIsPartOfTheName() = assertEquals(NameGuess("In da Club", "50 Cent"), first("50 Cent - In da Club.mp3"))

    @Test
    fun alsoTriesTheOtherOrder() {
        val guesses = NameGuesses.from("Despacito - Luis Fonsi.mp3", null, null)
        assertTrue(NameGuess("Despacito", "Luis Fonsi") in guesses)
    }

    @Test
    fun tagsComeFirst() = assertEquals(NameGuess("Hello", "Adele"), first("track03.mp3", "Hello", "Adele"))

    @Test
    fun titleTagHoldingArtistAndTitle() {
        val guesses = NameGuesses.from("audio.mp3", "Adele - Hello", null)
        assertEquals(NameGuess("Hello", "Adele"), guesses.first())
    }

    @Test
    fun wrongArtistTagAlsoTriesTheTitleAlone() {
        val guesses = NameGuesses.from("01 Break Ya Neck.mp3", "Break Ya Neck", "Break Ya Neck")
        assertEquals(NameGuess("Break Ya Neck", "Break Ya Neck"), guesses.first())
        assertTrue(NameGuess("Break Ya Neck", null) in guesses)
    }

    @Test
    fun alsoTheTitleWithoutVersionOrGuests() {
        val ft = NameGuesses.from("Common Emotion ft. MNEK.mp3", "Common Emotion ft. MNEK", "Rudimental")
        assertEquals(NameGuess("Common Emotion ft. MNEK", "Rudimental"), ft[0])
        assertEquals(NameGuess("Common Emotion", "Rudimental"), ft[1])
        val mix = NameGuesses.from("04 Panic Room (CamelPhat Club Mix).mp3", "Panic Room (CamelPhat Club Mix)", "Au/Ra")
        assertEquals(NameGuess("Panic Room", "Au/Ra"), mix[1])
    }

    @Test
    fun aRealArtistTagIsTrusted() {
        // No title-only search, which finds other artists' songs of the same name.
        val guesses = NameGuesses.from("Sexy M.F. (Extended Mix).mp3", "Sexy M.F. (Extended Mix)", "Jackie (IT), AVIV SAB")
        assertTrue(guesses.all { it.artist != null })
    }

    @Test
    fun titleOnlyFileName() = assertEquals(NameGuess("Despacito", null), first("Despacito.mp3"))

    @Test
    fun placeholderNamesGiveNothing() {
        assertTrue(NameGuesses.from("track03.mp3", "Track 3", "Unknown Artist").isEmpty())
        assertTrue(NameGuesses.from("AUD-20240101-WA0003.mp3", null, null).isEmpty())
        assertFalse(NameGuesses.usableTitle("Track 12"))
        assertFalse(NameGuesses.usableTitle("05"))
        assertFalse(NameGuesses.usableArtist("<unknown>"))
        assertTrue(NameGuesses.usableTitle("1999"))
    }
}
