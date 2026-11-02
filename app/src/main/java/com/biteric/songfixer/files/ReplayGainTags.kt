package com.biteric.songfixer.files

import com.biteric.songfixer.loudness.Loudness
import com.biteric.songfixer.loudness.ReplayGain
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

    /** The tag's track gain text ("-6.42 dB"), or null; null too for formats without these tags. */
    fun trackGain(tag: Tag): String? = read(tag, ReplayGain.TRACK_GAIN)

    fun trackPeak(tag: Tag): String? = read(tag, ReplayGain.TRACK_PEAK)

    fun reference(tag: Tag): String? = read(tag, ReplayGain.REFERENCE_LOUDNESS)

    /** Whether this kind of tag can hold ReplayGain values here. */
    fun supports(tag: Tag) = tag is AbstractID3v2Tag || tag is Mp4Tag || tag is FlacTag || tag is VorbisCommentTag

    private fun read(tag: Tag, name: String): String? = when (tag) {
        is AbstractID3v2Tag -> txxxFrames(tag).firstOrNull { describedAs(it, name) }?.let { (it.body as FrameBodyTXXX).textWithoutTrailingNulls }
        is Mp4Tag -> tag.getFirst(mp4Id(name)).ifEmpty { tag.getFirst("----:$ITUNES:${name.uppercase()}") }.ifEmpty { null }
        is FlacTag -> vorbisRead(tag.vorbisCommentTag, name)
        is VorbisCommentTag -> vorbisRead(tag, name)
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() }

    /** Writes (or replaces) the track gain, peak and reference loudness. False when the format can't hold them. */
    fun write(tag: Tag, loudness: Loudness): Boolean {
        val values = mapOf(
            ReplayGain.TRACK_GAIN to ReplayGain.gainText(loudness.trackGainDb),
            ReplayGain.TRACK_PEAK to ReplayGain.peakText(loudness.truePeak),
            ReplayGain.REFERENCE_LOUDNESS to ReplayGain.referenceText(),
        )
        when (tag) {
            is AbstractID3v2Tag -> {
                // Keep every other TXXX frame; replace ours (setField would replace them all).
                val others = txxxFrames(tag).filterNot { f -> NAMES.any { describedAs(f, it) } }
                val ours = values.map { (name, value) ->
                    tag.createFrame("TXXX").apply { body = FrameBodyTXXX(TextEncoding.ISO_8859_1, name, value) }
                }
                tag.setFrame("TXXX", others + ours)
            }
            is Mp4Tag -> values.forEach { (name, value) ->
                tag.deleteField(mp4Id(name))
                tag.deleteField("----:$ITUNES:${name.uppercase()}")
                tag.setField(Mp4TagReverseDnsField(mp4Id(name), ITUNES, name.lowercase(), value))
            }
            is FlacTag -> vorbisWrite(tag.vorbisCommentTag, values)
            is VorbisCommentTag -> vorbisWrite(tag, values)
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

    private fun vorbisWrite(tag: VorbisCommentTag, values: Map<String, String>) = values.forEach { (name, value) ->
        tag.deleteField(name.lowercase())
        tag.deleteField(name)
        tag.setField(tag.createField(name, value))
    }
}
