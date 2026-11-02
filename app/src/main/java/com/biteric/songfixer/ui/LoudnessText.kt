package com.biteric.songfixer.ui

import com.biteric.songfixer.data.Song
import com.biteric.songfixer.loudness.GainVerdict
import com.biteric.songfixer.loudness.ReplayGain
import com.biteric.songfixer.loudness.ReplayGainCheck
import java.util.Locale
import kotlin.math.abs

/** "−9.8 LUFS" (a real minus sign). */
fun loudnessText(lufs: Double): String = String.format(Locale.US, "%.1f LUFS", lufs).replace('-', '−')

/** "−9.8 LUFS · 8.2 dB too loud", "… · 4.1 dB too quiet", "… · about right"; null when not measured. */
fun loudnessSummary(song: Song): String? {
    val lufs = song.loudnessLufs ?: return null
    val gain = ReplayGain.REFERENCE_LUFS - lufs
    val off = String.format(Locale.US, "%.1f dB", abs(gain))
    return loudnessText(lufs) + " · " + when {
        abs(gain) < 1.0 -> "about right"
        gain < 0 -> "$off too loud"
        else -> "$off too quiet"
    }
}

/** "−3.1 dB" (a real minus sign). */
private fun dbText(db: Double) = String.format(Locale.US, "%.1f dB", db).replace('-', '−')

/**
 * Why the song's volume tags get replaced, for the sheet: "Volume tags use ReplayGain 1.0 (89 dB):
 * will be replaced with the −18 LUFS standard", "Volume tags say −3.1 dB, measured −8.4 dB: will be
 * corrected"; null when they're standard or there are none.
 */
fun volumeTagsNote(song: Song): String? {
    val replaced = ": will be replaced with the −18 LUFS standard"
    return when (song.gainVerdict) {
        GainVerdict.RG1 -> "Volume tags use ReplayGain 1.0 (" + (song.existingReference?.let { "89 dB" } ?: "no reference loudness") + ")" + replaced
        GainVerdict.R128_ONLY -> "Volume tags use EBU R128 (−23 LUFS)$replaced"
        GainVerdict.OTHER_REFERENCE -> "Volume tags use a ${song.existingReference.orEmpty().trim().replace('-', '−')} reference$replaced"
        GainVerdict.UNREADABLE -> "Volume tags can't be read" + (unreadable(song)?.let { " (\"$it\")" } ?: "") + replaced
        GainVerdict.GAIN_OFF -> {
            val said = ReplayGainCheck.gainDb(song.existingTrackGain)
            val measured = song.trackGainDb
            if (said != null && measured != null) "Volume tags say ${dbText(said)}, measured ${dbText(measured)}: will be corrected"
            else "Volume tags don't match the measurement: will be corrected"
        }
        else -> null
    }
}

/** The first value of the song's tags that doesn't parse (missing counts). */
private fun unreadable(song: Song): String? = when {
    ReplayGainCheck.gainDb(song.existingTrackGain) == null -> song.existingTrackGain
    ReplayGainCheck.peak(song.existingTrackPeak) == null -> song.existingTrackPeak
    else -> song.existingReference
}

/**
 * For the list: "Volume tags not standard" when they get replaced; else "Too loud" / "Too quiet"
 * when more than 3 dB from the reference; else null.
 */
fun loudnessChip(song: Song): String? {
    if (song.volumeTagsNotStandard) return "Volume tags not standard"
    val gain = song.trackGainDb ?: return null
    return when {
        gain < -ReplayGain.FAR_DB -> "Too loud"
        gain > ReplayGain.FAR_DB -> "Too quiet"
        else -> null
    }
}
