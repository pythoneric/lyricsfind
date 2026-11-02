package com.biteric.songfixer.loudness

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which existing volume tags follow ReplayGain 2.0 and stay, and which get replaced. */
class ReplayGainCheckTest {
    /** The app measured −9.58 LUFS, so the right gain is −8.42 dB. */
    private val measured = -9.58

    private fun verdict(gain: String? = null, peak: String? = null, reference: String? = null, r128: String? = null, lufs: Double? = measured) =
        ReplayGainCheck.verdict(VolumeTags(gain, peak, reference, r128), lufs)

    @Test
    fun rg2At18LufsIsStandard() {
        assertEquals(GainVerdict.STANDARD, verdict("-8.42 dB", "0.988000", "-18.00 LUFS"))
        assertEquals(GainVerdict.STANDARD, verdict("-8.42 dB", "0.988000", "-18 LUFS"))
        assertEquals(GainVerdict.STANDARD, verdict("-8.42 DB", "0.988", "-18 lufs"))
        assertEquals(GainVerdict.STANDARD, verdict(" -8.0 dB ", "1.2", " -18.0 LUFS "))
    }

    @Test
    fun rg1At89DbIsNotStandard() {
        assertEquals(GainVerdict.RG1, verdict("-8.42 dB", "0.988000", "89 dB"))
        assertEquals(GainVerdict.RG1, verdict("-8.42 dB", "0.988000", "89.0 dB"))
    }

    @Test
    fun noReferenceTagIsRg1() {
        assertEquals(GainVerdict.RG1, verdict("-8.42 dB", "0.988000", null))
        assertEquals(GainVerdict.RG1, verdict("-3.1 dB", null, null))
    }

    @Test
    fun r128OnlyIsNotStandard() {
        assertEquals(GainVerdict.R128_ONLY, verdict(r128 = "-1234"))
        assertEquals(GainVerdict.R128_ONLY, verdict(r128 = "-1234", lufs = null))
    }

    @Test
    fun otherReferencesAreNotStandard() {
        assertEquals(GainVerdict.OTHER_REFERENCE, verdict("-13.42 dB", "0.988000", "-23 LUFS"))
        assertEquals(GainVerdict.OTHER_REFERENCE, verdict("-13.42 dB", "0.988000", "-23.00 LUFS"))
        assertEquals(GainVerdict.OTHER_REFERENCE, verdict("-4.42 dB", "0.988000", "-14 LUFS"))
    }

    @Test
    fun gainMoreThan1DbOffIsCorrected() {
        assertEquals(GainVerdict.GAIN_OFF, verdict("-6.42 dB", "0.988000", "-18.00 LUFS"))
        assertEquals(GainVerdict.GAIN_OFF, verdict("-10.42 dB", "0.988000", "-18.00 LUFS"))
        // Within 1 dB stays.
        assertEquals(GainVerdict.STANDARD, verdict("-7.50 dB", "0.988000", "-18.00 LUFS"))
        assertEquals(GainVerdict.STANDARD, verdict("-9.40 dB", "0.988000", "-18.00 LUFS"))
        // Not measured: only the form counts.
        assertEquals(GainVerdict.STANDARD, verdict("-2.00 dB", "0.988000", "-18.00 LUFS", lufs = null))
    }

    @Test
    fun malformedValuesAreUnreadable() {
        assertEquals(GainVerdict.UNREADABLE, verdict("loud", "0.988000", "-18.00 LUFS"))
        assertEquals(GainVerdict.UNREADABLE, verdict("-8,42 dB", "0.988000", "-18.00 LUFS"))
        assertEquals(GainVerdict.UNREADABLE, verdict("-8.42 LUFS", "0.988000", "-18.00 LUFS"))
        assertEquals(GainVerdict.UNREADABLE, verdict(null, "0.988000", "-18.00 LUFS"))
        assertEquals(GainVerdict.UNREADABLE, verdict("-8.42 dB", "0", "-18.00 LUFS"))
        assertEquals(GainVerdict.UNREADABLE, verdict("-8.42 dB", "-0.5", "-18.00 LUFS"))
        assertEquals(GainVerdict.UNREADABLE, verdict("-8.42 dB", "NaN", "-18.00 LUFS"))
        assertEquals(GainVerdict.UNREADABLE, verdict("-8.42 dB", null, "-18.00 LUFS"))
        assertEquals(GainVerdict.UNREADABLE, verdict("-8.42 dB", "0.988000", "quiet"))
    }

    @Test
    fun noTagsAtAll() {
        assertEquals(GainVerdict.NONE, verdict())
        assertEquals(false, GainVerdict.NONE.notStandard)
        assertEquals(false, GainVerdict.STANDARD.notStandard)
    }
}
