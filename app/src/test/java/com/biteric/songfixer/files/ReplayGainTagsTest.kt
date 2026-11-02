package com.biteric.songfixer.files

import com.biteric.songfixer.loudness.Loudness
import com.biteric.songfixer.loudness.VolumeTags
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.flac.FlacTag
import org.jaudiotagger.tag.id3.AbstractID3v2Frame
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.id3.framebody.FrameBodyTXXX
import org.jaudiotagger.tag.id3.valuepair.TextEncoding
import org.jaudiotagger.tag.mp4.Mp4Tag
import org.jaudiotagger.tag.mp4.field.Mp4TagReverseDnsField
import org.jaudiotagger.tag.vorbiscomment.VorbisCommentTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the volume tags already in a file and replacing only the ReplayGain track tags, in each
 * format's own place: ID3 TXXX frames, MP4 freeform atoms, Vorbis comments (FLAC, Ogg).
 */
class ReplayGainTagsTest {
    private val loud = Loudness(lufs = -9.58, truePeak = 0.988)
    private val written = VolumeTags("-8.42 dB", "0.988000", "-18.00 LUFS")

    // ---- ID3 (MP3) ----

    private fun id3(vararg frames: Pair<String, String>, tag: org.jaudiotagger.tag.id3.AbstractID3v2Tag = ID3v24Tag()) = tag.apply {
        setFrame("TXXX", frames.map { (d, v) -> createFrame("TXXX").apply { body = FrameBodyTXXX(TextEncoding.ISO_8859_1, d, v) } })
    }

    private fun txxx(tag: org.jaudiotagger.tag.id3.AbstractID3v2Tag): Map<String, String> =
        tag.getFields("TXXX").map { (it as AbstractID3v2Frame).body as FrameBodyTXXX }.associate { it.description to it.textWithoutTrailingNulls }

    @Test
    fun readsId3VolumeTagsWhateverTheCase() {
        val tag = id3(
            "replaygain_track_gain" to "-3.10 dB",
            "Replaygain_Track_Peak" to "0.950000",
            "REPLAYGAIN_REFERENCE_LOUDNESS" to " 89.0 dB ",
            "r128_track_gain" to "-1536",
        )
        assertEquals(VolumeTags("-3.10 dB", "0.950000", "89.0 dB", "-1536"), ReplayGainTags.volumeTags(tag))
    }

    @Test
    fun noVolumeTagsReadAsEmpty() {
        assertEquals(VolumeTags(), ReplayGainTags.volumeTags(ID3v24Tag()))
        assertEquals(VolumeTags(), ReplayGainTags.volumeTags(id3("MusicBrainz Album Id" to "abc")))
        // Blank values count as missing.
        assertNull(ReplayGainTags.trackGain(id3("REPLAYGAIN_TRACK_GAIN" to "  ")))
    }

    @Test
    fun replacingId3TrackTagsKeepsAlbumGainAndOtherFrames() {
        val tag = id3(
            "MusicBrainz Album Id" to "abc",
            "replaygain_track_gain" to "-3.10 dB",
            "replaygain_track_peak" to "0.950000",
            "REPLAYGAIN_REFERENCE_LOUDNESS" to "89.0 dB",
            "REPLAYGAIN_ALBUM_GAIN" to "-2.50 dB",
            "REPLAYGAIN_ALBUM_PEAK" to "0.990000",
            "R128_TRACK_GAIN" to "-1536",
        )
        assertTrue(ReplayGainTags.write(tag, loud))
        assertEquals(
            mapOf(
                "MusicBrainz Album Id" to "abc",
                "REPLAYGAIN_ALBUM_GAIN" to "-2.50 dB",
                "REPLAYGAIN_ALBUM_PEAK" to "0.990000",
                "R128_TRACK_GAIN" to "-1536",
                "REPLAYGAIN_TRACK_GAIN" to "-8.42 dB",
                "REPLAYGAIN_TRACK_PEAK" to "0.988000",
                "REPLAYGAIN_REFERENCE_LOUDNESS" to "-18.00 LUFS",
            ),
            txxx(tag),
        )
    }

    @Test
    fun id3R128GoesOnlyWhenAsked() {
        val tag = id3("MusicBrainz Album Id" to "abc", "R128_TRACK_GAIN" to "-1536")
        assertTrue(ReplayGainTags.write(tag, loud, removeR128 = true))
        assertEquals(setOf("MusicBrainz Album Id", "REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_REFERENCE_LOUDNESS"), txxx(tag).keys)
        assertNull(ReplayGainTags.r128TrackGain(tag))
    }

    @Test
    fun id3v23TagsWorkToo() {
        val tag = id3("replaygain_track_gain" to "-3.10 dB", tag = ID3v23Tag())
        assertTrue(ReplayGainTags.write(tag, loud))
        assertEquals(written, ReplayGainTags.volumeTags(tag))
    }

    // ---- MP4 (M4A) ----

    private fun mp4Field(name: String, value: String) = Mp4TagReverseDnsField("----:com.apple.iTunes:$name", "com.apple.iTunes", name, value)

    @Test
    fun replacingMp4TrackTagsKeepsAlbumGain() {
        val tag = Mp4Tag().apply {
            setField(mp4Field("replaygain_track_gain", "-3.10 dB"))
            setField(mp4Field("replaygain_reference_loudness", "89.0 dB"))
            setField(mp4Field("replaygain_album_gain", "-2.50 dB"))
            setField(mp4Field("R128_TRACK_GAIN", "-1536"))
        }
        assertEquals(VolumeTags("-3.10 dB", null, "89.0 dB", "-1536"), ReplayGainTags.volumeTags(tag))
        assertTrue(ReplayGainTags.write(tag, loud))
        assertEquals(written.copy(r128TrackGain = "-1536"), ReplayGainTags.volumeTags(tag))
        assertEquals("-2.50 dB", tag.getFirst("----:com.apple.iTunes:replaygain_album_gain"))
        // One of each, not the old one next to the new.
        assertEquals(1, tag.getFields("----:com.apple.iTunes:replaygain_track_gain").size)
    }

    @Test
    fun mp4R128GoesOnlyWhenAsked() {
        val tag = Mp4Tag().apply { setField(mp4Field("R128_TRACK_GAIN", "-1536")) }
        assertTrue(ReplayGainTags.write(tag, loud, removeR128 = true))
        assertEquals(written, ReplayGainTags.volumeTags(tag))
    }

    // ---- Vorbis comments (FLAC, Ogg) ----

    private fun vorbis(vararg fields: Pair<String, String>) = VorbisCommentTag.createNewTag().apply {
        fields.forEach { (k, v) -> addField(createField(k, v)) }
    }

    @Test
    fun replacingVorbisTrackTagsKeepsAlbumGain() {
        val tag = vorbis(
            "replaygain_track_gain" to "-3.10 dB",
            "REPLAYGAIN_TRACK_PEAK" to "0.950000",
            "REPLAYGAIN_ALBUM_GAIN" to "-2.50 dB",
            "R128_TRACK_GAIN" to "-1536",
            "ARTIST" to "Fisher",
        )
        assertEquals(VolumeTags("-3.10 dB", "0.950000", null, "-1536"), ReplayGainTags.volumeTags(tag))
        assertTrue(ReplayGainTags.write(tag, loud))
        assertEquals(written.copy(r128TrackGain = "-1536"), ReplayGainTags.volumeTags(tag))
        assertEquals("-2.50 dB", tag.getFirst("REPLAYGAIN_ALBUM_GAIN"))
        assertEquals("Fisher", tag.getFirst("ARTIST"))
        assertTrue(tag.getFields("replaygain_track_gain").isEmpty())
        assertEquals(1, tag.getFields("REPLAYGAIN_TRACK_GAIN").size)
    }

    @Test
    fun flacR128GoesOnlyWhenAsked() {
        val tag = FlacTag(vorbis("R128_TRACK_GAIN" to "-1536"), ArrayList())
        assertTrue(ReplayGainTags.supports(tag))
        assertTrue(ReplayGainTags.write(tag, loud, removeR128 = true))
        assertEquals(written, ReplayGainTags.volumeTags(tag))
    }

    @Test
    fun otherTagKindsCantHoldThem() {
        val other: Tag = org.jaudiotagger.tag.id3.ID3v1Tag()
        assertFalse(ReplayGainTags.supports(other))
        assertFalse(ReplayGainTags.write(other, loud))
        assertEquals(VolumeTags(), ReplayGainTags.volumeTags(other))
    }
}
