package com.biteric.songfixer.loudness

/** A file's size and last-modified time, to tell whether it's still the file that was measured. */
data class FileStamp(val size: Long, val modified: Long)

/**
 * Where the sound starts and ends in a song (exactly, in sample frames), and the [stamp] of the file
 * it was measured on. A trim uses it in place of listening to the whole song again, but only while
 * the file is still that file: any other stamp and the song is listened to again.
 */
data class SilenceMark(val silence: Silence, val stamp: FileStamp) {
    fun matches(now: FileStamp?): Boolean = now != null && now == stamp

    /** "rate:total:start:end:size:modified", as saved with the song. */
    fun encode(): String = listOf(silence.sampleRate, silence.totalFrames, silence.soundStart, silence.soundEnd, stamp.size, stamp.modified).joinToString(":")

    companion object {
        fun decode(text: String?): SilenceMark? {
            val p = text?.split(':')?.mapNotNull { it.toLongOrNull() }?.takeIf { it.size == 6 } ?: return null
            val rate = p[0].toInt()
            if (rate <= 0 || p[1] < 0 || p[2] !in 0..p[1] || p[3] !in p[2]..p[1]) return null
            return SilenceMark(Silence(rate, p[1], p[2], p[3]), FileStamp(p[4], p[5]))
        }
    }
}
