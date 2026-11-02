package com.biteric.songfixer.loudness

import kotlin.math.abs

/** The volume tags a file has, as written (null = not there). */
data class VolumeTags(
    val trackGain: String? = null,
    val trackPeak: String? = null,
    val reference: String? = null,
    /** EBU R128 track gain (R128_TRACK_GAIN, −23 LUFS reference). */
    val r128TrackGain: String? = null,
)

/** What a file's existing volume tags are worth. */
enum class GainVerdict {
    /** No volume tags at all. */
    NONE,
    /** ReplayGain 2.0 at −18 LUFS, agreeing with the measurement: left alone. */
    STANDARD,
    /** ReplayGain 1.0: an 89 dB reference, or no reference tag at all. */
    RG1,
    /** Only R128_TRACK_GAIN (EBU R128, −23 LUFS). */
    R128_ONLY,
    /** A reference other than −18 LUFS (−14, −23 LUFS…). */
    OTHER_REFERENCE,
    /** A value that doesn't parse. */
    UNREADABLE,
    /** The gain is more than [ReplayGainCheck.MAX_OFF_DB] from the measurement. */
    GAIN_OFF;

    /** The file has tags, and they get replaced with standard ones. */
    val notStandard: Boolean get() = this != NONE && this != STANDARD
}

/** Whether existing ReplayGain tags follow ReplayGain 2.0 (−18 LUFS, BS.1770). */
object ReplayGainCheck {
    /** A gain further than this from the app's own measurement is corrected. */
    const val MAX_OFF_DB = 1.0

    private val REFERENCE_18 = Regex("""-18(\.0+)?\s*LUFS""", RegexOption.IGNORE_CASE)
    private val REFERENCE_89 = Regex("""\+?89(\.0+)?\s*dB""", RegexOption.IGNORE_CASE)
    /** Some other loudness reference that reads fine ("-14 LUFS", "-23.00 LUFS", "83 dB"). */
    private val REFERENCE_OTHER = Regex("""[+-]?\d+(\.\d+)?\s*(LUFS|LKFS|LU|dB)""", RegexOption.IGNORE_CASE)
    private val GAIN = Regex("""([+-]?\d+(\.\d+)?)\s*(dB)?""", RegexOption.IGNORE_CASE)

    /**
     * [measuredLufs] is the app's BS.1770 measurement; when it's null (the song couldn't be
     * measured) the gain can't be compared and only the form of the tags counts.
     */
    fun verdict(tags: VolumeTags, measuredLufs: Double?): GainVerdict {
        val (gain, peak, reference) = tags
        if (gain == null && peak == null && reference == null) {
            return if (tags.r128TrackGain != null) GainVerdict.R128_ONLY else GainVerdict.NONE
        }
        when {
            reference == null || REFERENCE_89.matches(reference.trim()) -> return GainVerdict.RG1
            REFERENCE_18.matches(reference.trim()) -> {}
            REFERENCE_OTHER.matches(reference.trim()) -> return GainVerdict.OTHER_REFERENCE
            else -> return GainVerdict.UNREADABLE
        }
        val gainDb = gainDb(gain) ?: return GainVerdict.UNREADABLE
        if (peak(peak) == null) return GainVerdict.UNREADABLE
        if (measuredLufs != null && abs(gainDb - (ReplayGain.REFERENCE_LUFS - measuredLufs)) > MAX_OFF_DB) return GainVerdict.GAIN_OFF
        return GainVerdict.STANDARD
    }

    /** "-6.42 dB" → −6.42; null when it isn't a number in dB. */
    fun gainDb(text: String?): Double? =
        text?.trim()?.let { GAIN.matchEntire(it) }?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it.isFinite() }

    /** "0.988000" → 0.988; null unless a positive number. */
    fun peak(text: String?): Double? =
        text?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
}
