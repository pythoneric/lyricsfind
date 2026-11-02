package com.biteric.songfixer.ui

import com.biteric.songfixer.data.Song
import com.biteric.songfixer.loudness.ReplayGain
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

/** For the list: "Too loud" / "Too quiet" when more than 3 dB from the reference, else null. */
fun loudnessChip(song: Song): String? {
    val gain = song.trackGainDb ?: return null
    return when {
        gain < -ReplayGain.FAR_DB -> "Too loud"
        gain > ReplayGain.FAR_DB -> "Too quiet"
        else -> null
    }
}
