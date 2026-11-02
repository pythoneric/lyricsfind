package com.biteric.songfixer.files

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.reference.PictureTypes
import android.graphics.BitmapFactory
import com.biteric.songfixer.online.CoverImage
import com.biteric.songfixer.loudness.Loudness
import java.io.File

/** The names to give a song. Blank fields are left as they are in the file. */
data class NewNames(val title: String, val artist: String, val album: String, val genre: String, val year: String = "")

/** Writes tags into a song and renames it, through the folder permission the user gave. */
class SongWriter(private val context: Context) {
    class Failed(message: String) : Exception(message)

    init {
        // Genres as typed: by default a genre the library knows ("Salsa") is stored as a number,
        // which some players show as "143".
        TagOptionSingleton.getInstance().apply {
            isWriteMp3GenresAsText = true
            isWriteMp4GenresAsText = true
        }
    }

    /**
     * Writes the tags into a copy in the app's cache, reads the copy back to make sure it's sound,
     * and only then copies it over the song (tag libraries write files next to the original,
     * which a folder permission doesn't always allow).
     *
     * [lyrics] go into the lyrics tag unless the file already has lyrics (or [replaceLyrics]). Returns what happened
     * to them: [EmbedResult.ADDED], [EmbedResult.KEPT_EXISTING], or [EmbedResult.UNSUPPORTED] when
     * this format has no lyrics tag (null when no lyrics were given). [cover] is the front cover,
     * kept out the same way when the file has one (unless [replaceCover]).
     */
    suspend fun writeTags(
        uri: Uri,
        extension: String,
        names: NewNames,
        lyrics: String? = null,
        replaceLyrics: Boolean = false,
        cover: CoverImage? = null,
        replaceCover: Boolean = false,
        replayGain: Loudness? = null,
        replaceReplayGain: Boolean = false,
    ): Written = withContext(Dispatchers.IO) {
        if (extension !in TAGGABLE) throw Failed("Tags of .$extension files can't be written")
        val dir = File(context.cacheDir, "tag-edit").apply { mkdirs() }
        val copy = File(dir, "edit.$extension")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: throw Failed("The song's file can't be read")
            val audio = runCatching { AudioFileIO.read(copy) }.getOrElse { throw Failed("This file's tags can't be read: ${it.message}") }
            val tag = audio.tagOrCreateAndSetDefault
            fun set(key: FieldKey, value: String) {
                if (value.isNotBlank() && tag.getFirst(key) != value.trim()) tag.setField(key, value.trim())
            }
            set(FieldKey.TITLE, names.title)
            set(FieldKey.ARTIST, names.artist)
            set(FieldKey.ALBUM, names.album)
            set(FieldKey.GENRE, names.genre)
            set(FieldKey.YEAR, names.year)
            val embed = when {
                lyrics.isNullOrBlank() -> null
                // Lyrics already there were put there by someone on purpose: keep them, unless the
                // user picked new ones themselves.
                !replaceLyrics && runCatching { tag.getFirst(FieldKey.LYRICS) }.getOrNull()?.isNotBlank() == true -> EmbedResult.KEPT_EXISTING
                runCatching { tag.setField(FieldKey.LYRICS, lyrics) }.isSuccess -> EmbedResult.ADDED
                else -> EmbedResult.UNSUPPORTED
            }
            val coverResult = when {
                cover == null -> null
                !replaceCover && runCatching { tag.firstArtwork }.getOrNull() != null -> EmbedResult.KEPT_EXISTING
                else -> runCatching {
                    val art = AndroidArtwork().apply {
                        binaryData = cover.bytes
                        mimeType = cover.mimeType
                        pictureType = PictureTypes.DEFAULT_ID // front cover
                        // FLAC stores the size; read it from the image header.
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(cover.bytes, 0, cover.bytes.size, bounds)
                        width = bounds.outWidth.coerceAtLeast(0)
                        height = bounds.outHeight.coerceAtLeast(0)
                    }
                    tag.deleteArtworkField()
                    tag.setField(art)
                }.fold({ EmbedResult.ADDED }, { EmbedResult.UNSUPPORTED })
            }
            // ReplayGain: tags only, the audio is untouched. Existing values stay unless re-measured.
            val gainResult = when {
                replayGain == null -> null
                !ReplayGainTags.supports(tag) -> EmbedResult.UNSUPPORTED
                !replaceReplayGain && ReplayGainTags.trackGain(tag) != null -> EmbedResult.KEPT_EXISTING
                runCatching { ReplayGainTags.write(tag, replayGain) }.getOrDefault(false) -> EmbedResult.ADDED
                else -> EmbedResult.UNSUPPORTED
            }
            runCatching { audio.commit() }.getOrElse { throw Failed("The tags couldn't be written: ${it.message}") }
            // Never copy a damaged file over the song.
            val check = runCatching { AudioFileIO.read(copy) }.getOrNull()
            val titleKept = names.title.isBlank() || check?.tag?.getFirst(FieldKey.TITLE) == names.title.trim()
            if (check == null || !titleKept || (check.audioHeader.trackLength <= 0 && audio.audioHeader.trackLength > 0)) {
                throw Failed("The edited file didn't check out; the song was left as it was")
            }
            // "wt": replace the whole file (plain "w" doesn't shorten it on some Android versions).
            context.contentResolver.openOutputStream(uri, "wt")?.use { out -> copy.inputStream().use { it.copyTo(out) } }
                ?: throw Failed("The song's file can't be changed")
            Written(embed, coverResult, gainResult)
        } finally {
            copy.delete()
        }
    }

    /** ADDED; KEPT_EXISTING = the file already had one and kept it (lyrics or cover); UNSUPPORTED = no room in this format. */
    enum class EmbedResult { ADDED, KEPT_EXISTING, UNSUPPORTED }

    /** What happened to the lyrics and the cover (null = none given). */
    data class Written(val lyrics: EmbedResult?, val cover: EmbedResult?, val replayGain: EmbedResult? = null)

    /** Renames the file (and its .lrc lyrics file, if there is one); returns its new URI. */
    suspend fun rename(uri: Uri, currentName: String, newName: String): Uri = withContext(Dispatchers.IO) {
        if (newName == currentName) return@withContext uri
        val newUri = runCatching { DocumentsContract.renameDocument(context.contentResolver, uri, newName) }
            .getOrElse { throw Failed("The file couldn't be renamed: ${it.message}") }
            ?: uri
        sibling(uri, lrcName(currentName))?.takeIf(::exists)?.let { lrc ->
            runCatching { DocumentsContract.renameDocument(context.contentResolver, lrc, lrcName(newName)) }
        }
        newUri
    }

    /**
     * Saves lyrics as "<song name>.lrc" next to the song (replacing an older one), the file most
     * players look for synced lyrics in.
     */
    suspend fun writeLrc(songUri: Uri, songFileName: String, text: String) = withContext(Dispatchers.IO) {
        val name = lrcName(songFileName)
        val existing = sibling(songUri, name)?.takeIf(::exists)
        val target = existing ?: run {
            val parent = parentUri(songUri) ?: throw Failed("The lyrics file couldn't be created")
            // octet-stream: the provider keeps the ".lrc" name as given.
            runCatching { DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", name) }.getOrNull()
                ?: throw Failed("The lyrics file couldn't be created")
        }
        context.contentResolver.openOutputStream(target, "wt")?.use { it.write(text.toByteArray()) }
            ?: throw Failed("The lyrics file couldn't be written")
    }

    private fun exists(uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    /** Tells Android's music library about the change now, so players show the new names. */
    suspend fun notifyMediaStore(uri: Uri) = withContext(Dispatchers.IO) {
        val path = localPath(uri) ?: return@withContext
        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
    }

    companion object {
        /** Formats jaudiotagger can write. */
        val TAGGABLE = setOf("mp3", "m4a", "mp4", "flac", "ogg", "wav", "wma", "aif", "aiff")

        fun lrcName(songFileName: String) = songFileName.substringBeforeLast('.') + ".lrc"

        /** "primary:Music/a.mp3" → "primary:Music" (the root's id is "primary:"). */
        fun parentDocumentId(docId: String): String =
            if ('/' in docId) docId.substringBeforeLast('/') else docId.substringBefore(':') + ":"

        /** The folder holding a document, as a document URI under the same folder permission. */
        fun parentUri(songUri: Uri): Uri? = runCatching {
            DocumentsContract.buildDocumentUriUsingTree(songUri, parentDocumentId(DocumentsContract.getDocumentId(songUri)))
        }.getOrNull()

        /** A file named [name] in the same folder as [songUri]. */
        fun sibling(songUri: Uri, name: String): Uri? = runCatching {
            val parent = parentDocumentId(DocumentsContract.getDocumentId(songUri))
            DocumentsContract.buildDocumentUriUsingTree(songUri, if (parent.endsWith(":")) "$parent$name" else "$parent/$name")
        }.getOrNull()

        /** "Artist - Title.mp3", without characters file systems refuse. */
        fun fileName(artist: String, title: String, extension: String): String {
            val base = "${artist.trim()} - ${title.trim()}"
                .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim(' ', '.')
                .take(150)
            return "$base.$extension"
        }

        /** "primary:Music/a.mp3" → /storage/emulated/0/Music/a.mp3 (other volumes are skipped). */
        @Suppress("DEPRECATION")
        fun localPath(uri: Uri): String? {
            val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
            val (volume, path) = id.split(':', limit = 2).takeIf { it.size == 2 } ?: return null
            return if (volume == "primary") "${Environment.getExternalStorageDirectory()}/$path" else "/storage/$volume/$path"
        }
    }
}
