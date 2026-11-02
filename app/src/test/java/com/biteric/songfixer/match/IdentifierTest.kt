package com.biteric.songfixer.match

import com.biteric.songfixer.data.MatchSource
import com.biteric.songfixer.online.AcoustIdApi
import com.biteric.songfixer.online.AcoustIdKeyException
import com.biteric.songfixer.online.LrclibApi
import com.biteric.songfixer.online.MusicBrainzApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/** The whole lookup against a fake AcoustID / MusicBrainz / LRCLIB on localhost. */
class IdentifierTest {
    private lateinit var server: FakeServer
    private val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
    /** path → (status, body) */
    private val routes = mutableMapOf<String, (String) -> Pair<Int, String>>()
    private lateinit var identifier: Identifier

    @Before
    fun setUp() {
        server = FakeServer { path, query ->
            requests += "$path?$query"
            routes[path]?.invoke(query) ?: (404 to "")
        }
        val base = server.baseUrl
        identifier = Identifier(AcoustIdApi(base, "test"), MusicBrainzApi(base, "test"), LrclibApi(base, "test"))
    }

    @After
    fun tearDown() = server.close()

    private val acoustIdHello = """
        {"status":"ok","results":[{"id":"a1","score":0.96,"recordings":[
          {"id":"rec-comp","title":"Hello","duration":295,"artists":[{"id":"art-adele","name":"Adele"}],
           "releasegroups":[{"title":"Now 92","type":"Album","secondarytypes":["Compilation"]}]},
          {"id":"rec-1","title":"Hello","duration":295,"artists":[{"id":"art-adele","name":"Adele"}],
           "releasegroups":[{"id":"rg-25","title":"25","type":"Album"}]},
          {"id":"rec-x","title":"Hello (karaoke)","duration":296,"artists":[{"id":"k","name":"Karaoke Stars"}]}
        ]}]}
    """.trimIndent()

    private val lyricsHello = """[{"id":1,"trackName":"Hello","artistName":"Adele","duration":295.5,"plainLyrics":"x"}]"""

    @Test
    fun unnamedFileRecognizedBySound() = runTest {
        routes["/v2/lookup"] = { 200 to acoustIdHello }
        routes["/ws/2/recording/rec-comp"] = { 200 to """{"genres":[{"name":"soul","count":2},{"name":"pop","count":5}]}""" }
        routes["/api/search"] = { 200 to lyricsHello }

        val r = identifier.identify(SongInfo("track03.mp3", null, null, null, null, 296), "AQAAfp", "key", 296)
        val best = r.candidates.first()
        assertEquals("Hello", best.title)
        assertEquals("Adele", best.artist)
        assertEquals("25", best.album)
        assertEquals("rg-25", best.releaseGroupId)
        assertEquals("Pop", best.genre)
        assertEquals(MatchSource.SOUND, best.source)
        assertTrue(best.sure)
        assertEquals(true, r.lyricsFound)
        assertTrue(requests.first().contains("fingerprint=AQAAfp") && requests.first().contains("client=key"))
    }

    @Test
    fun badKeyStopsTheCheck() = runTest {
        routes["/v2/lookup"] = { 400 to """{"status":"error","error":{"code":4,"message":"invalid API key"}}""" }
        try {
            identifier.identify(SongInfo("a.mp3", null, null, null, null, 200), "AQAA", "bad", 200)
            throw AssertionError("expected AcoustIdKeyException")
        } catch (e: AcoustIdKeyException) {
            assertTrue(e.message!!.contains("invalid API key"))
        }
    }

    @Test
    fun unknownSoundFallsBackToTheName() = runTest {
        routes["/v2/lookup"] = { 200 to """{"status":"ok","results":[]}""" }
        routes["/ws/2/recording"] = {
            200 to """{"recordings":[
              {"id":"r2","score":100,"title":"Hello","length":295000,"artist-credit":[{"name":"Adele","artist":{"id":"art-adele"}}],
               "first-release-date":"2015-10-23","releases":[{"title":"25","status":"Official","release-group":{"id":"rg-25","primary-type":"Album"}}]},
              {"id":"r3","score":90,"title":"Hello","length":250000,"artist-credit":[{"name":"Lionel Richie"}]}
            ]}"""
        }
        routes["/api/search"] = { 200 to lyricsHello }

        val r = identifier.identify(SongInfo("adele_-_hello_(official_video).mp3", null, null, null, null, 296), "AQAA", "key", 296)
        val best = r.candidates.first()
        assertEquals("Hello", best.title)
        assertEquals("Adele", best.artist)
        assertEquals("25", best.album)
        assertEquals("rg-25", best.releaseGroupId)
        assertEquals(2015, best.year)
        assertEquals(MatchSource.NAME, best.source)
        assertTrue(best.sure)
        assertFalse(r.candidates.any { it.artist == "Lionel Richie" && it.score > best.score })
    }

    @Test
    fun withoutKeyOnlyNamesAreUsed() = runTest {
        routes["/ws/2/recording"] = { 200 to """{"recordings":[]}""" }
        routes["/api/search"] = { q -> 200 to if (q.contains("q=")) """[{"id":5,"trackName":"Hello","artistName":"Adele","duration":295}]""" else "[]" }

        val r = identifier.identify(SongInfo("Adele - Hello.mp3", null, null, null, null, 295), null, null, null)
        assertTrue(requests.none { it.startsWith("/v2/lookup") })
        assertEquals("Hello", r.candidates.first().title)
        assertEquals(false, r.lyricsFound)
    }

    @Test
    fun offlineIsAFailureNotANotFound() = runTest {
        routes["/ws/2/recording"] = { 503 to "down" }
        routes["/api/search"] = { 503 to "down" }
        try {
            identifier.identify(SongInfo("Adele - Hello.mp3", null, null, null, null, 295), null, null, null)
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun nothingUsableToSearch() = runTest {
        val r = identifier.identify(SongInfo("track03.mp3", "Track 3", null, null, null, 200), null, null, null)
        assertTrue(r.candidates.isEmpty())
        assertNull(r.lyricsFound)
        assertTrue(requests.isEmpty())
    }
}
