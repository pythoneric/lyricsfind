package com.biteric.songfixer.loudness

import kotlin.math.abs

/**
 * Where the sound starts and ends in a song: [soundStart] is the first frame of the first window
 * louder than [SilenceDetector.THRESHOLD], [soundEnd] the frame after the last such window
 * (both in sample frames from the start of the decoded audio). A song with no sound at all has
 * [soundStart] == [soundEnd] == 0.
 */
data class Silence(val sampleRate: Int, val totalFrames: Long, val soundStart: Long, val soundEnd: Long) {
    val leadMs: Long get() = soundStart * 1000 / sampleRate
    val tailMs: Long get() = (totalFrames - soundEnd) * 1000 / sampleRate
    val silent: Boolean get() = soundEnd <= soundStart
}

/**
 * Finds the silence at the start and end of a song, fed with its decoded audio. A window of
 * [WINDOW_MS] counts as sound when any sample in any channel goes above [THRESHOLD]: a vinyl
 * rip's hiss or a quiet room stays below it, the first note doesn't.
 */
class SilenceDetector(private val sampleRate: Int, private val channels: Int) {
    private val window = (sampleRate * WINDOW_MS / 1000).coerceAtLeast(1)
    private var frames = 0L
    private var windowStart = 0L
    private var windowPeak = 0f
    private var inWindow = 0
    private var firstLoud = -1L
    private var lastLoudEnd = 0L

    fun feed(samples: FloatArray, count: Int) {
        for (f in 0 until count) {
            var peak = windowPeak
            val base = f * channels
            for (c in 0 until channels) {
                val v = abs(samples[base + c])
                if (v > peak) peak = v
            }
            windowPeak = peak
            frames++
            if (++inWindow == window) closeWindow()
        }
    }

    private fun closeWindow() {
        if (windowPeak > THRESHOLD) {
            if (firstLoud < 0) firstLoud = windowStart
            lastLoudEnd = frames
        }
        windowStart = frames
        windowPeak = 0f
        inWindow = 0
    }

    fun result(): Silence {
        if (inWindow > 0) closeWindow()
        return if (firstLoud < 0) Silence(sampleRate, frames, 0, 0) else Silence(sampleRate, frames, firstLoud, lastLoudEnd)
    }

    companion object {
        const val WINDOW_MS = 10
        /** −50 dBFS. */
        const val THRESHOLD = 0.00316f
        /** Less than this at an end isn't worth cutting. */
        const val MIN_TRIM_MS = 500L
    }
}
