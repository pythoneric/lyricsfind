package com.biteric.songfixer.files

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A music file found in the folder. */
data class FoundFile(val uri: Uri, val name: String, val folder: String, val size: Long, val modified: Long)

/** Tags as Android reads them, plus the length. */
data class ReadTags(val title: String?, val artist: String?, val album: String?, val genre: String?, val durationMs: Long?)

/** The folder the user picked (Storage Access Framework tree): lists songs and reads their tags. */
class MusicFolder(private val context: Context) {
    /** Every music file under [tree], subfolders included. */
    suspend fun list(tree: Uri): List<FoundFile> = withContext(Dispatchers.IO) {
        val out = mutableListOf<FoundFile>()
        val root = DocumentsContract.getTreeDocumentId(tree)
        val pending = ArrayDeque(listOf(root to ""))
        while (pending.isNotEmpty()) {
            val (docId, path) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    if (mime == Document.MIME_TYPE_DIR) {
                        // Hidden folders hold app caches and thumbnails, not music.
                        if (!name.startsWith(".")) pending += id to (if (path.isEmpty()) name else "$path/$name")
                    } else if (isMusic(name, mime)) {
                        out += FoundFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, path, c.getLong(3), c.getLong(4))
                    }
                }
            }
        }
        out
    }

    suspend fun readTags(uri: Uri): ReadTags = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            fun get(key: Int) = r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            ReadTags(
                title = get(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = get(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = get(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                genre = get(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let(::genreName),
                durationMs = get(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
            )
        } finally {
            r.release()
        }
    }

    companion object {
        val EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "aif", "aiff")

        /** By extension: providers report odd MIME types for music (e.g. application/octet-stream). */
        @Suppress("UNUSED_PARAMETER")
        fun isMusic(name: String, mime: String): Boolean = name.substringAfterLast('.', "").lowercase() in EXTENSIONS

        /** ID3v1 genre numbers ("(17)", "17") as some files store them; Android passes those through. */
        fun genreName(raw: String): String? {
            val n = Regex("""^\(?(\d{1,3})\)?$""").find(raw.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return raw
            return ID3_GENRES.getOrNull(n)
        }

        private val ID3_GENRES = listOf(
            "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz", "Metal", "New Age", "Oldies",
            "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska", "Death Metal", "Pranks",
            "Soundtrack", "Euro-Techno", "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical", "Instrumental",
            "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise", "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative",
            "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk",
            "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap", "Pop/Funk", "Jungle",
            "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk",
            "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll", "Hard Rock", "Folk", "Folk-Rock", "National Folk", "Swing",
            "Fast Fusion", "Bebop", "Latin", "Revival", "Celtic", "Bluegrass", "Avantgarde", "Gothic Rock", "Progressive Rock",
            "Psychedelic Rock", "Symphonic Rock", "Slow Rock", "Big Band", "Chorus", "Easy Listening", "Acoustic", "Humour", "Speech",
            "Chanson", "Opera", "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus", "Porn Groove", "Satire", "Slow Jam",
            "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul", "Freestyle", "Duet", "Punk Rock",
            "Drum Solo", "A Cappella", "Euro-House", "Dance Hall", "Goa", "Drum & Bass", "Club-House", "Hardcore", "Terror", "Indie",
            "BritPop", "Negerpunk", "Polsk Punk", "Beat", "Christian Gangsta Rap", "Heavy Metal", "Black Metal", "Crossover",
            "Contemporary Christian", "Christian Rock", "Merengue", "Salsa", "Thrash Metal", "Anime", "JPop", "Synthpop",
        )
    }
}
