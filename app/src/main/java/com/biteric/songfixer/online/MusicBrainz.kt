package com.biteric.songfixer.online

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MbArtistRef(val id: String? = null, val name: String? = null)

@Serializable
data class MbCredit(val name: String, val joinphrase: String? = null, val artist: MbArtistRef? = null)

@Serializable
data class MbReleaseGroup(
    @SerialName("primary-type") val primaryType: String? = null,
    @SerialName("secondary-types") val secondaryTypes: List<String> = emptyList(),
)

@Serializable
data class MbRelease(
    val title: String? = null,
    val status: String? = null,
    @SerialName("release-group") val releaseGroup: MbReleaseGroup? = null,
)

@Serializable
data class MbRecording(
    val id: String,
    val title: String,
    val score: Int? = null,
    /** Milliseconds. */
    val length: Long? = null,
    @SerialName("artist-credit") val artistCredit: List<MbCredit> = emptyList(),
    @SerialName("first-release-date") val firstReleaseDate: String? = null,
    val releases: List<MbRelease> = emptyList(),
) {
    val artist: String get() = artistCredit.mapIndexed { i, c -> c.name + if (i < artistCredit.lastIndex) (c.joinphrase ?: ", ") else "" }.joinToString("")
    val year: Int? get() = firstReleaseDate?.take(4)?.toIntOrNull()
}

@Serializable
data class MbGenre(val name: String, val count: Int = 0)

@Serializable
private data class MbSearch(val recordings: List<MbRecording> = emptyList())

@Serializable
private data class MbWithGenres(val genres: List<MbGenre> = emptyList())

/** musicbrainz.org web service: free, no key, at most one request a second. */
class MusicBrainzApi(private val baseUrl: String = "https://musicbrainz.org", private val userAgent: String) {
    private val json = Json { ignoreUnknownKeys = true }
    private val throttle = Throttle(1100)

    /** Recordings named [title], by [artist] when given, best first. */
    suspend fun searchRecordings(title: String, artist: String?, limit: Int = 10): List<MbRecording> {
        val q = buildString {
            append("recording:\"").append(escape(title)).append('"')
            if (!artist.isNullOrBlank()) append(" AND artist:\"").append(escape(artist)).append('"')
        }
        val body = get("/ws/2/recording", listOf("query" to q, "limit" to limit.toString())) ?: return emptyList()
        return json.decodeFromString<MbSearch>(body).recordings
    }

    /** The most voted genre of a recording, else of its artist; null when MusicBrainz has none. */
    suspend fun genre(recordingId: String?, artistId: String?): String? {
        recordingId?.let { id -> top(get("/ws/2/recording/$id", listOf("inc" to "genres")))?.let { return it } }
        artistId?.let { id -> top(get("/ws/2/artist/$id", listOf("inc" to "genres")))?.let { return it } }
        return null
    }

    private fun top(body: String?): String? {
        if (body == null) return null
        val genres = json.decodeFromString<MbWithGenres>(body).genres
        return genres.maxByOrNull { it.count }?.name?.let(::titleCase)
    }

    private suspend fun get(path: String, params: List<Pair<String, String>>): String? =
        throttle.run { Http.request("$baseUrl$path?" + Http.query(params + ("fmt" to "json")), userAgent) }

    companion object {
        private val luceneSpecial = Regex("""([+\-!(){}\[\]^"~*?:\\/]|&&|\|\|)""")
        fun escape(s: String) = s.replace(luceneSpecial, "\\\\$1")

        /** MusicBrainz genres are lower case ("hip hop"); tags read better as "Hip Hop". */
        fun titleCase(s: String) = s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }
}
