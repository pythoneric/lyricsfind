package com.biteric.songfixer.files

import com.biteric.songfixer.loudness.Loudness
import com.biteric.songfixer.loudness.ReplayGain
import com.biteric.songfixer.loudness.VolumeTags
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.AbstractID3v2Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.id3.valuepair.TextEncoding
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag

/**
 * ReplayGain track tags, where each format keeps them:
 *  - MP3 (ID3v2): TXXX frames described REPLAYGAIN_TRACK_GAIN etc.
 *  - M4A: freeform atoms ----:com.apple.iTunes:replaygain_track_gain etc.
 *  - FLAC, Ogg Vorbis: Vorbis comments REPLAYGAIN_TRACK_GAIN etc.
 * Names are matched without regard to case when reading (both spellings are common).
 */
object ReplayGainTags {
    private val NAMES = listOf(ReplayGain.TRACK_GAIN, ReplayGain.TRACK_PEAK, ReplayGain.REFERENCE_LOUDNESS)
    private const val ITUNES = "com.apple.iTunes"
    /** EBU R128 track gain (Opus, some taggers): replaced only when it's the file's only volume tag. */
    const val R128_TRACK_GAIN = "R128_TRACK_GAIN"

    /** The tag's track gain text ("-6.42 dB"), or null; null too for formats without these tags. */
    fun trackGain(tag: Tag): String? = read(tag, ReplayGain.TRACK_GAIN)

    fun trackPeak(tag: Tag): String? = read(tag, ReplayGain.TRACK_PEAK)

    fun reference(tag: Tag): String? = read(tag, ReplayGain.REFERENCE_LOUDNESS)

    fun r128TrackGain(tag: Tag): String? = read(tag, R128_TRACK_GAIN)

    /** All of the track's volume tags, as written. */
    fun volumeTags(tag: Tag) = VolumeTags(trackGain(tag), trackPeak(tag), reference(tag), r128TrackGain(tag))

    /** Whether this kind of tag can hold ReplayGain values here. */
    fun supports(tag: Tag) = tag is AbstractID3v2Tag || tag is Mp4Tag || tag is FlacTag || tag is VorbisCommentTag

    private fun read(tag: Tag, name: String): String? = when (tag) {
        is AbstractID3v2Tag -> txxxFrames(tag).firstOrNull { describedAs(it, name) }?.let { (it.body as FrameBodyTXXX).textWithoutTrailingNulls }
        is Mp4Tag -> tag.getFirst(mp4Id(name)).ifEmpty { tag.getFirst("----:$ITUNES:${name.uppercase()}") }.ifEmpty { null }
        is FlacTag -> vorbisRead(tag.vorbisCommentTag, name)
        is VorbisCommentTag -> vorbisRead(tag, name)
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Writes (or replaces) the track gain, peak and reference loudness; album gain and every other
     * tag stay. [removeR128] also drops R128_TRACK_GAIN (when these tags replace it).
     * False when the format can't hold them.
     */
    fun write(tag: Tag, loudness: Loudness, removeR128: Boolean = false): Boolean {
        val values = mapOf(
            ReplayGain.TRACK_GAIN to ReplayGain.gainText(loudness.trackGainDb),
            ReplayGain.TRACK_PEAK to ReplayGain.peakText(loudness.truePeak),
            ReplayGain.REFERENCE_LOUDNESS to ReplayGain.referenceText(),
        )
        val replaced = if (removeR128) NAMES + R128_TRACK_GAIN else NAMES
        when (tag) {
            is AbstractID3v2Tag -> {
                // Keep every other TXXX frame; replace ours (setField would replace them all).
                val others = txxxFrames(tag).filterNot { f -> replaced.any { describedAs(f, it) } }
                val ours = values.map { (name, value) ->
                    tag.createFrame("TXXX").apply { body = FrameBodyTXXX(TextEncoding.ISO_8859_1, name, value) }
                }
                tag.setFrame("TXXX", others + ours)
            }
            is Mp4Tag -> {
                replaced.forEach { name ->
                    tag.deleteField(mp4Id(name))
                    tag.deleteField("----:$ITUNES:${name.uppercase()}")
                }
                values.forEach { (name, value) -> tag.setField(Mp4TagReverseDnsField(mp4Id(name), ITUNES, name.lowercase(), value)) }
            }
            is FlacTag -> vorbisWrite(tag.vorbisCommentTag, values, replaced)
            is VorbisCommentTag -> vorbisWrite(tag, values, replaced)
            else -> return false
        }
        return true
    }

    private fun txxxFrames(tag: AbstractID3v2Tag): List<AbstractID3v2Frame> =
        runCatching { tag.getFields("TXXX") }.getOrDefault(emptyList()).filterIsInstance<AbstractID3v2Frame>().filter { it.body is FrameBodyTXXX }

    private fun describedAs(frame: AbstractID3v2Frame, name: String) =
        (frame.body as FrameBodyTXXX).description.equals(name, ignoreCase = true)

    private fun mp4Id(name: String) = "----:$ITUNES:${name.lowercase()}"

    private fun vorbisRead(tag: VorbisCommentTag, name: String): String? =
        tag.getFirst(name).ifEmpty { tag.getFirst(name.lowercase()) }.ifEmpty { null }

    private fun vorbisWrite(tag: VorbisCommentTag, values: Map<String, String>, replaced: List<String>) {
        replaced.forEach { name ->
            tag.deleteField(name.lowercase())
            tag.deleteField(name)
        }
        values.forEach { (name, value) -> tag.setField(tag.createField(name, value)) }
    }
}
