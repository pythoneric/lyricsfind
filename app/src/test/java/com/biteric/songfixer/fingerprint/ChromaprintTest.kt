package com.biteric.songfixer.fingerprint

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values are from Chromaprint 1.5.1's own tests (tests/test_api.cpp). */
class ChromaprintTest {
    private fun loadRaw(name: String): ShortArray {
        val bytes = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()
        return ShortArray(bytes.size / 2) { i -> ((bytes[2 * i + 1].toInt() shl 8) or (bytes[2 * i].toInt() and 0xff)).toShort() }
    }

    @Test
    fun matchesChromaprintOnItsTestAudio() {
        val cp = Chromaprint()
        cp.start(44100, 1)
        cp.feed(loadRaw("test_stereo_44100.raw"))
        cp.finish()
        assertEquals("AQAAC0kkZUqYREkUnFAXHk8uuMZl6EfO4zu-4ABKFGESWIIMEQE", cp.fingerprint())
    }

    @Test
    fun sameResultFedInSmallPieces() {
        val data = loadRaw("test_stereo_44100.raw")
        val cp = Chromaprint()
        cp.start(44100, 1)
        var i = 0
        while (i < data.size) {
            val n = minOf(777, data.size - i)
            cp.feed(data.copyOfRange(i, i + n))
            i += n
        }
        cp.finish()
        assertEquals("AQAAC0kkZUqYREkUnFAXHk8uuMZl6EfO4zu-4ABKFGESWIIMEQE", cp.fingerprint())
    }

    @Test
    fun silence() {
        val cp = Chromaprint()
        cp.start(44100, 1)
        repeat(130) { cp.feed(ShortArray(1024)) }
        cp.finish()
        assertEquals(listOf(627964279, 627964279, 627964279), cp.rawFingerprint().toList())
        assertEquals("AQAAA0mUaEkSRZEGAA", cp.fingerprint())
    }

    @Test
    fun encodesLikeChromaprint() {
        // test_api.cpp TestEncodeFingerprintBase64: {1, 0} with algorithm 55.
        assertEquals("NwAAAkEA", Chromaprint.encode(intArrayOf(1, 0), 55))
    }
}
