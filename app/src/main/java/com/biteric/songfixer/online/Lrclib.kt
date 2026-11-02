package com.biteric.songfixer.online

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A song on LRCLIB; [duration] is in seconds. Only whether lyrics exist matters here. */
@Serializable
data class LrclibTrack(
    val id: Long,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
) {
    val hasLyrics: Boolean get() = instrumental || !plainLyrics.isNullOrBlank() || !syncedLyrics.isNullOrBlank()
}

/** lrclib.net: free lyrics database, the names lyrics apps look songs up by. */
class LrclibApi(private val baseUrl: String = "https://lrclib.net", private val userAgent: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val throttle = Throttle(250)

    /** Songs matching free text, best first (at most 20). */
    suspend fun search(query: String): List<LrclibTrack> = request(listOf("q" to query))

    /** Songs with this title and artist. */
    suspend fun search(title: String, artist: String): List<LrclibTrack> =
        request(listOf("track_name" to title, "artist_name" to artist))

    private suspend fun request(params: List<Pair<String, String>>): List<LrclibTrack> {
        val body = throttle.run { Http.request("$baseUrl/api/search?" + Http.query(params), userAgent) } ?: return emptyList()
        return json.decodeFromString<List<LrclibTrack>>(body)
    }
}
