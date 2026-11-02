package com.biteric.songfixer.ui

import com.biteric.songfixer.SongRepository
import com.biteric.songfixer.data.Song
import com.biteric.songfixer.loudness.GainVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the sheet, the list and the bulk button make of a song's existing volume tags. */
class VolumeTagsTextTest {
    /** Measured −9.58 LUFS (gain −8.42 dB). */
    private fun song(
        verdict: GainVerdict?,
        gain: String? = null,
        peak: String? = null,
        reference: String? = null,
        inFile: Boolean? = null,
        fileName: String = "a.mp3",
        lufs: Double? = -9.58,
    ) = Song(
        uri = "content://x/$fileName", fileName = fileName, folder = "", size = 1, modified = 1,
        loudnessLufs = lufs, trackGainDb = lufs?.let { -18.0 - it }, truePeak = 0.988, loudnessChecked = true,
        replayGainInFile = inFile, existingTrackGain = gain, existingTrackPeak = peak, existingReference = reference, gainVerdict = verdict,
    )

    // ---- The note under "Normalize volume" ----

    @Test
    fun notesSayWhyTagsAreReplaced() {
        assertEquals(
            "Volume tags use ReplayGain 1.0 (89 dB): will be replaced with the −18 LUFS standard",
            volumeTagsNote(song(GainVerdict.RG1, "-3.10 dB", "0.95", "89.0 dB")),
        )
        assertEquals(
            "Volume tags use ReplayGain 1.0 (no reference loudness): will be replaced with the −18 LUFS standard",
            volumeTagsNote(song(GainVerdict.RG1, "-3.10 dB", "0.95", null)),
        )
        assertEquals(
            "Volume tags use EBU R128 (−23 LUFS): will be replaced with the −18 LUFS standard",
            volumeTagsNote(song(GainVerdict.R128_ONLY)),
        )
        assertEquals(
            "Volume tags use a −23 LUFS reference: will be replaced with the −18 LUFS standard",
            volumeTagsNote(song(GainVerdict.OTHER_REFERENCE, "-13.42 dB", "0.95", "-23 LUFS")),
        )
        assertEquals(
            "Volume tags say −3.1 dB, measured −8.4 dB: will be corrected",
            volumeTagsNote(song(GainVerdict.GAIN_OFF, "-3.10 dB", "0.95", "-18.00 LUFS")),
        )
    }

    @Test
    fun unreadableNoteQuotesTheBadValue() {
        assertEquals(
            "Volume tags can't be read (\"loud\"): will be replaced with the −18 LUFS standard",
            volumeTagsNote(song(GainVerdict.UNREADABLE, "loud", "0.95", "-18.00 LUFS")),
        )
        assertEquals(
            "Volume tags can't be read (\"-0.5\"): will be replaced with the −18 LUFS standard",
            volumeTagsNote(song(GainVerdict.UNREADABLE, "-3.10 dB", "-0.5", "-18.00 LUFS")),
        )
        assertEquals(
            "Volume tags can't be read (\"quiet\"): will be replaced with the −18 LUFS standard",
            volumeTagsNote(song(GainVerdict.UNREADABLE, "-8.42 dB", "0.95", "quiet")),
        )
        // A missing value has nothing to quote.
        assertEquals(
            "Volume tags can't be read: will be replaced with the −18 LUFS standard",
            volumeTagsNote(song(GainVerdict.UNREADABLE, null, "0.95", "-18.00 LUFS")),
        )
    }

    @Test
    fun noNoteForStandardOrMissingTags() {
        assertNull(volumeTagsNote(song(GainVerdict.STANDARD, "-8.42 dB", "0.988", "-18.00 LUFS")))
        assertNull(volumeTagsNote(song(GainVerdict.NONE)))
        assertNull(volumeTagsNote(song(null)))
    }

    // ---- List chip ----

    @Test
    fun chipFlagsNonStandardTagsFirst() {
        // Too loud too, but the tags are what gets fixed.
        assertEquals("Volume tags not standard", loudnessChip(song(GainVerdict.RG1, "-3.10 dB")))
        assertEquals("Volume tags not standard", loudnessChip(song(GainVerdict.R128_ONLY)))
        assertEquals("Too loud", loudnessChip(song(GainVerdict.STANDARD)))
        assertEquals("Too loud", loudnessChip(song(GainVerdict.NONE)))
        assertEquals("Too quiet", loudnessChip(song(GainVerdict.NONE, lufs = -24.0)))
        assertNull(loudnessChip(song(GainVerdict.STANDARD, lufs = -17.0)))
    }

    // ---- Standard or not ----

    @Test
    fun whichSongsCountAsStandard() {
        assertTrue(song(GainVerdict.STANDARD).volumeTagsStandard)
        // Tagged by the app before tags were read (database from before v5).
        assertTrue(song(null, inFile = true).volumeTagsStandard)
        assertFalse(song(null).volumeTagsStandard)
        assertFalse(song(GainVerdict.NONE).volumeTagsStandard)
        // A verdict wins over the old flag.
        assertFalse(song(GainVerdict.RG1, inFile = true).volumeTagsStandard)
        for (v in listOf(GainVerdict.RG1, GainVerdict.R128_ONLY, GainVerdict.OTHER_REFERENCE, GainVerdict.UNREADABLE, GainVerdict.GAIN_OFF)) {
            assertTrue(v.name, song(v).volumeTagsNotStandard)
            assertFalse(v.name, song(v).volumeTagsStandard)
        }
        assertFalse(song(GainVerdict.NONE).volumeTagsNotStandard)
        assertFalse(song(null).volumeTagsNotStandard)
    }

    // ---- The sheet's checkbox ----

    @Test
    fun normalizeIsCheckedForNonStandardTagsEvenWithTheSettingOff() {
        assertTrue(normalizeByDefault(song(GainVerdict.RG1), normalizeSetting = true))
        assertTrue(normalizeByDefault(song(GainVerdict.GAIN_OFF), normalizeSetting = false))
        assertFalse(normalizeByDefault(song(GainVerdict.STANDARD), normalizeSetting = true))
        assertFalse(normalizeByDefault(song(null, inFile = true), normalizeSetting = true))
        // No tags: as the setting says.
        assertTrue(normalizeByDefault(song(GainVerdict.NONE), normalizeSetting = true))
        assertFalse(normalizeByDefault(song(GainVerdict.NONE), normalizeSetting = false))
        // A format that can't hold them.
        assertFalse(normalizeByDefault(song(GainVerdict.RG1, fileName = "a.opus"), normalizeSetting = true))
    }

    // ---- "Normalize N songs" ----

    @Test
    fun bulkNormalizeIncludesNonStandardTags() {
        val songs = listOf(
            song(GainVerdict.NONE, fileName = "none.mp3"),
            song(GainVerdict.RG1, fileName = "rg1.mp3"),
            song(GainVerdict.R128_ONLY, fileName = "r128.flac"),
            song(GainVerdict.GAIN_OFF, fileName = "off.m4a"),
            song(GainVerdict.STANDARD, fileName = "standard.mp3"),
            song(null, inFile = true, fileName = "legacy.mp3"),
            song(GainVerdict.RG1, fileName = "unmeasured.mp3", lufs = null),
            song(GainVerdict.RG1, fileName = "cant.opus"),
        )
        assertEquals(listOf("none.mp3", "rg1.mp3", "r128.flac", "off.m4a"), SongRepository.needNormalizing(songs).map { it.fileName })
    }
}
