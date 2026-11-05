package com.biteric.songfixer.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Reading the year out of ID3v2 tags built byte by byte, as the versions lay them out. */
class Id3YearTest {
    private fun syncsafe(n: Int) = byteArrayOf((n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte())
    private fun int(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())

    /** A text frame's body: encoding byte, then the text (UTF-16 with a byte-order mark for 1). */
    private fun text(value: String, encoding: Int = 0): ByteArray = byteArrayOf(encoding.toByte()) + when (encoding) {
        1 -> value.toByteArray(Charsets.UTF_16)
        2 -> value.toByteArray(Charsets.UTF_16BE)
        3 -> value.toByteArray(Charsets.UTF_8)
        else -> value.toByteArray(Charsets.ISO_8859_1)
    }

    private fun frame(version: Int, id: String, body: ByteArray): ByteArray = when (version) {
        2 -> id.toByteArray() + byteArrayOf((body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body
        3 -> id.toByteArray() + int(body.size) + byteArrayOf(0, 0) + body
        else -> id.toByteArray() + syncsafe(body.size) + byteArrayOf(0, 0) + body
    }

    /** A whole tag (with some padding), followed by a few bytes of "audio". */
    private fun tag(version: Int, vararg frames: ByteArray, flags: Int = 0, extended: ByteArray = ByteArray(0), padding: Int = 64): ByteArray {
        val content = ByteArrayOutputStream().apply {
            write(extended)
            frames.forEach { write(it) }
            write(ByteArray(padding))
        }.toByteArray()
        return "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, flags.toByte()) + syncsafe(content.size) + content + byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 1, 2, 3)
    }

    private fun read(bytes: ByteArray) = Id3Year.read(ByteArrayInputStream(bytes))

    @Test
    fun eachVersionsYearFrame() {
        assertEquals(1998, read(tag(2, frame(2, "TT2", text("Hello")), frame(2, "TYE", text("1998")))))
        assertEquals(2019, read(tag(3, frame(3, "TIT2", text("Hello")), frame(3, "TYER", text("2019")))))
        assertEquals(2021, read(tag(4, frame(4, "TIT2", text("Stop It")), frame(4, "TDRC", text("2021")))))
        // A full date in v2.4, and TDRC as some taggers leave it in v2.3.
        assertEquals(2019, read(tag(4, frame(4, "TDRC", text("2019-05-17T10:00")))))
        assertEquals(2007, read(tag(3, frame(3, "TDRC", text("2007")))))
    }

    @Test
    fun textEncodings() {
        assertEquals(2015, read(tag(3, frame(3, "TYER", text("2015", encoding = 1)))))
        assertEquals(2015, read(tag(4, frame(4, "TDRC", text("2015", encoding = 2)))))
        assertEquals(2015, read(tag(4, frame(4, "TDRC", text("2015", encoding = 3)))))
        // Null-terminated, as many taggers write it.
        assertEquals(2015, read(tag(4, frame(4, "TDRC", text("2015\u0000", encoding = 3)))))
    }

    @Test
    fun skipsBigFramesBeforeTheYear() {
        val cover = frame(4, "APIC", ByteArray(300_000) { 7 })
        assertEquals(2023, read(tag(4, frame(4, "TIT2", text("Hot This Year")), cover, frame(4, "TDRC", text("2023")))))
        // Syncsafe sizes over 127 bytes really are syncsafe in v2.4, and plain in v2.3.
        assertEquals(2023, read(tag(3, frame(3, "APIC", ByteArray(200_000)), frame(3, "TYER", text("2023")))))
    }

    @Test
    fun extendedHeaders() {
        // v2.3: size without its own 4 bytes (6 here: flags + padding size).
        val ext3 = int(6) + ByteArray(6)
        assertEquals(2010, read(tag(3, frame(3, "TYER", text("2010")), flags = 0x40, extended = ext3)))
        // v2.4: syncsafe size including itself (6: size + flag bytes).
        val ext4 = syncsafe(6) + byteArrayOf(1, 0)
        assertEquals(2010, read(tag(4, frame(4, "TDRC", text("2010")), flags = 0x40, extended = ext4)))
    }

    @Test
    fun noYear() {
        assertNull(read(tag(4, frame(4, "TIT2", text("Hello")))))
        assertNull(read(tag(4, frame(4, "TDRC", text("")))))
        assertNull(read(tag(4, frame(4, "TDRC", text("unknown")))))
        // No ID3v2 tag at all, or not even that many bytes.
        assertNull(read(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 1, 2, 3, 4, 5, 6, 7, 8, 9)))
        assertNull(read(ByteArray(0)))
    }

    @Test
    fun brokenOrUnusualTagsGiveNothingRatherThanNonsense() {
        // Cut off in the middle of a frame.
        val whole = tag(4, frame(4, "APIC", ByteArray(5000)), frame(4, "TDRC", text("2021")))
        assertNull(read(whole.copyOf(3000)))
        // A frame claiming more bytes than the tag has.
        val lying = "ID3".toByteArray() + byteArrayOf(4, 0, 0) + syncsafe(20) + "TDRC".toByteArray() + syncsafe(9999) + byteArrayOf(0, 0) + ByteArray(10)
        assertNull(read(lying))
        // Unsynchronized tags, and unknown versions.
        assertNull(read(tag(3, frame(3, "TYER", text("2019")), flags = 0x80)))
        assertNull(read(tag(5, frame(4, "TDRC", text("2019")))))
    }
}
