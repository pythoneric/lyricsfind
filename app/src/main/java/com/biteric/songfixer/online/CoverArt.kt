package com.biteric.songfixer.online

/** A cover image: JPEG or PNG bytes. */
class CoverImage(val bytes: ByteArray, val mimeType: String)

/**
 * coverartarchive.org: album covers for MusicBrainz releases, free, no key. Covers are looked up
 * by release group (the album across its editions), so any edition's front cover will do.
 */
class CoverArtApi(private val baseUrl: String = "https://coverartarchive.org", private val userAgent: String) {
    private val throttle = Throttle(300)

    /** The album's front cover, about [size] pixels wide (250, 500 or 1200); null when it has none. */
    suspend fun front(releaseGroupId: String, size: Int = 500): CoverImage? {
        val (bytes, type) = throttle.run { Http.download("$baseUrl/release-group/$releaseGroupId/front-$size", userAgent) } ?: return null
        val mime = when {
            type?.contains("png", ignoreCase = true) == true || isPng(bytes) -> "image/png"
            else -> "image/jpeg"
        }
        return CoverImage(bytes, mime)
    }

    /** The URL of a small preview, for showing the cover before it's written. */
    fun previewUrl(releaseGroupId: String) = "$baseUrl/release-group/$releaseGroupId/front-250"

    private fun isPng(b: ByteArray) = b.size > 4 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte()
}
