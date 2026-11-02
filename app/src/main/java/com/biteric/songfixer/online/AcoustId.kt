package com.biteric.songfixer.online

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException

@Serializable
data class AcoustIdArtist(val id: String? = null, val name: String, val joinphrase: String? = null)

@Serializable
data class AcoustIdReleaseGroup(
    val id: String? = null,
    val title: String? = null,
    val type: String? = null,
    val secondarytypes: List<String> = emptyList(),
)

@Serializable
data class AcoustIdRecording(
    val id: String,
    val title: String? = null,
    val duration: Double? = null,
    val artists: List<AcoustIdArtist> = emptyList(),
    val releasegroups: List<AcoustIdReleaseGroup> = emptyList(),
)

@Serializable
data class AcoustIdResult(val id: String, val score: Double, val recordings: List<AcoustIdRecording> = emptyList())

@Serializable
data class AcoustIdError(val code: Int = 0, val message: String = "")

@Serializable
data class AcoustIdResponse(
    val status: String,
    val results: List<AcoustIdResult> = emptyList(),
    val error: AcoustIdError? = null,
)

/** The key was refused: the user has to fix it in Settings, so the scan stops asking. */
class AcoustIdKeyException(message: String) : IOException(message)

/** acoustid.org: finds MusicBrainz recordings by audio fingerprint. Needs a free application key. */
class AcoustIdApi(private val baseUrl: String = "https://api.acoustid.org", private val userAgent: String) {
    private val json = Json { ignoreUnknownKeys = true }
    // The service allows 3 requests a second.
    private val throttle = Throttle(350)

    suspend fun lookup(key: String, fingerprint: String, durationSec: Int): List<AcoustIdResult> {
        val form = Http.query(
            listOf(
                "client" to key,
                "duration" to durationSec.toString(),
                "fingerprint" to fingerprint,
                "meta" to "recordings releasegroups compress",
                "format" to "json",
            ),
        )
        val body = try {
            throttle.run { Http.request("$baseUrl/v2/lookup", userAgent, form) } ?: return emptyList()
        } catch (e: HttpException) {
            // Errors come back as JSON with a 400 status.
            parse(e.message ?: throw e)?.let { return check(it) }
            throw e
        }
        return check(parse(body) ?: throw IOException("AcoustID sent something unexpected"))
    }

    private fun parse(body: String): AcoustIdResponse? = runCatching { json.decodeFromString<AcoustIdResponse>(body) }.getOrNull()

    private fun check(r: AcoustIdResponse): List<AcoustIdResult> {
        if (r.status == "ok") return r.results
        val e = r.error ?: AcoustIdError(message = "unknown error")
        // 4 = invalid API key (see acoustid.org/webservice).
        if (e.code == 4 || e.message.contains("API key", ignoreCase = true)) throw AcoustIdKeyException("AcoustID key not accepted: ${e.message}")
        throw IOException("AcoustID: ${e.message}")
    }

    companion object {
        /** "Artist A feat. Artist B", as MusicBrainz credits it. */
        fun credit(artists: List<AcoustIdArtist>): String =
            artists.mapIndexed { i, a -> a.name + if (i < artists.lastIndex) (a.joinphrase ?: ", ") else "" }.joinToString("")
    }
}
