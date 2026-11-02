package com.biteric.songfixer.files

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream

/**
 * The year in an MP3's ID3v2 tag, read straight from the start of the file. Android's own reader
 * skips the ID3v2.4 year (TDRC), so a song tagged that way looks yearless to it (and to players).
 * Looks at TYE (v2.2), TYER (v2.3) and TDRC (v2.4, also seen in v2.3 files); big frames such as
 * covers are skipped, not read.
 */
object Id3Year {
    private val YEAR_FRAMES = setOf("TYE", "TYER", "TDRC")

    /** Null when there's no ID3v2 tag, no year frame, or the tag can't be read. */
    fun read(input: InputStream): Int? = runCatching { readOrThrow(DataInputStream(input)) }.getOrNull()

    private fun readOrThrow(input: DataInputStream): Int? {
        val header = ByteArray(10).also(input::readFully)
        if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) return null
        val version = header[3].toInt()
        if (version !in 2..4) return null
        val flags = header[5].toInt()
        // Unsynchronized tags (rare) would need undoing first; leave them to other readers.
        if (flags and 0x80 != 0) return null
        var left = syncsafe(header, 6)
        if (version >= 3 && flags and 0x40 != 0) {
            // Extended header: v2.3 gives its size without these 4 bytes, v2.4 (syncsafe) with them.
            val sizeBytes = ByteArray(4).also(input::readFully)
            val extra = if (version == 3) int(sizeBytes, 0) else syncsafe(sizeBytes, 0) - 4
            skip(input, extra)
            left -= 4 + extra
        }
        val idLength = if (version == 2) 3 else 4
        val headerLength = if (version == 2) 6 else 10
        while (left >= headerLength) {
            val frame = ByteArray(headerLength).also(input::readFully)
            left -= headerLength
            if (frame[0].toInt() == 0) return null // padding: no more frames
            val id = String(frame, 0, idLength, Charsets.ISO_8859_1)
            val size = when (version) {
                2 -> ((frame[3].toInt() and 0xFF) shl 16) or ((frame[4].toInt() and 0xFF) shl 8) or (frame[5].toInt() and 0xFF)
                3 -> int(frame, 4)
                else -> syncsafe(frame, 4)
            }
            if (size < 0 || size > left) return null
            left -= size
            if (id in YEAR_FRAMES && size in 2..64) {
                val body = ByteArray(size).also(input::readFully)
                MusicFolder.parseYear(text(body))?.let { return it }
            } else {
                skip(input, size)
            }
        }
        return null
    }

    /** A text frame's value: first byte is the encoding. */
    private fun text(body: ByteArray): String {
        val charset = when (body[0].toInt()) {
            1 -> Charsets.UTF_16 // with a byte-order mark
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(body, 1, body.size - 1, charset).trimEnd('\u0000').trim()
    }

    private fun syncsafe(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun int(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun skip(input: InputStream, n: Int) {
        var left = n.toLong()
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                if (input.read() < 0) throw EOFException()
                left--
            } else {
                left -= skipped
            }
        }
    }
}
