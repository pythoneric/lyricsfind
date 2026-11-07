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
import org.jaudiotagger.audio.mp3.MP3File
import org.jaudiotagger.tag.TagOptionSingleton
import org.jaudiotagger.tag.id3.ID3v23Tag
import org.jaudiotagger.tag.id3.ID3v24Tag
import org.jaudiotagger.tag.reference.ID3V2Version
import org.jaudiotagger.tag.images.AndroidArtwork
import org.jaudiotagger.tag.reference.PictureTypes
import android.graphics.BitmapFactory
import com.biteric.songfixer.online.CoverImage
import com.biteric.songfixer.loudness.Loudness
import com.biteric.songfixer.loudness.GainVerdict
import com.biteric.songfixer.loudness.ReplayGainCheck
import com.biteric.songfixer.loudness.VolumeTags
import com.biteric.songfixer.loudness.Silence
import com.biteric.songfixer.fingerprint.PcmDecoder
import com.biteric.songfixer.fingerprint.PcmSink
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
            // New MP3 tags as ID3v2.3: see [asId3v23].
            setID3V2Version(ID3V2Version.ID3_V23)
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
            var namesChanged = false
            fun set(key: FieldKey, value: String) {
                if (value.isNotBlank() && tag.getFirst(key) != value.trim()) {
                    tag.setField(key, value.trim())
                    namesChanged = true
                }
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
            // ReplayGain: tags only, the audio is untouched. Tags that already follow ReplayGain 2.0
            // stay unless re-measured; any others (RG 1.0, R128, another reference, wrong values) are replaced.
            val existing = runCatching { ReplayGainTags.volumeTags(tag) }.getOrDefault(VolumeTags())
            val gainResult = when {
                replayGain == null -> null
                !ReplayGainTags.supports(tag) -> EmbedResult.UNSUPPORTED
                !replaceReplayGain && ReplayGainCheck.verdict(existing, replayGain.lufs) == GainVerdict.STANDARD -> EmbedResult.KEPT_EXISTING
                // R128_TRACK_GAIN goes only when it's what these tags replace.
                runCatching { ReplayGainTags.write(tag, replayGain, removeR128 = existing.trackGain == null && existing.r128TrackGain != null) }
                    .getOrDefault(false) -> EmbedResult.ADDED
                else -> EmbedResult.UNSUPPORTED
            }
            // Nothing to change: leave the file as it is (no rewrite, no new tag version).
            if (!namesChanged && EmbedResult.ADDED !in listOf(embed, coverResult, gainResult)) {
                return@withContext Written(embed, coverResult, gainResult)
            }
            asId3v23(audio)
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

    /**
     * MP3s are saved with an ID3v2.3 tag: Android's music library (and so its players) reads the
     * v2.3 year (TYER) but skips the v2.4 one (TDRC). Every other frame carries over.
     */
    private fun asId3v23(audio: org.jaudiotagger.audio.AudioFile) {
        val mp3 = audio as? MP3File ?: return
        val v24 = mp3.getID3v2Tag() as? ID3v24Tag ?: return
        mp3.setID3v2Tag(ID3v23Tag(v24))
    }

    /**
     * The song's volume tags as they are, read from a copy in the app's cache; empty when the
     * format has none or they can't be read.
     */
    suspend fun readVolumeTags(uri: Uri, extension: String): VolumeTags = withContext(Dispatchers.IO) {
        if (extension !in TAGGABLE) return@withContext VolumeTags()
        val dir = File(context.cacheDir, "tag-read").apply { mkdirs() }
        // Its own file: the background check reads while a fix may be writing "edit.*".
        val copy = File(dir, "read.$extension")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: return@withContext VolumeTags()
            runCatching { AudioFileIO.read(copy).tag?.let(ReplayGainTags::volumeTags) }.getOrNull() ?: VolumeTags()
        } finally {
            copy.delete()
        }
    }

    /** How much silence was cut off each end, and how much is left there (the margins kept). */
    data class Trimmed(val startMs: Long, val endMs: Long, val leadLeftMs: Long, val tailLeftMs: Long)

    /**
     * Cuts the silence off the song's start and end (see [SilenceTrimmer]), on a copy in the app's
     * cache: [findSilence] listens to that copy, the cut copy must decode and read back, and only
     * then is it copied over the song. Nothing is cut (0, 0) when there's too little silence to bother.
     */
    suspend fun trimSilence(uri: Uri, extension: String, findSilence: suspend (Uri) -> Silence): Trimmed = withContext(Dispatchers.IO) {
        if (extension !in SilenceTrimmer.FORMATS) throw Failed(".$extension files can't be trimmed here")
        val dir = File(context.cacheDir, "trim").apply { mkdirs() }
        val copy = File(dir, "trim.$extension")
        val cut = File(dir, "cut.$extension")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
                ?: throw Failed("The song's file can't be read")
            val silence = findSilence(Uri.fromFile(copy))
            // Straight from file to file: long mixes (100+ MB) don't fit in the app's memory.
            val result = try {
                cut.outputStream().buffered(256 * 1024).use { out -> SilenceTrimmer.cut(copy, extension, silence, out) }
            } catch (e: SilenceTrimmer.Unsupported) {
                throw Failed(e.message ?: "This file can't be trimmed")
            } ?: return@withContext Trimmed(0, 0, silence.leadMs, silence.tailMs)
            // Never copy a damaged file over the song: it must decode, and its tags still read.
            runCatching { PcmDecoder(context).decode(Uri.fromFile(cut), object : PcmSink {
                override fun start(sampleRate: Int, channels: Int) {}
                override fun pcm(samples: FloatArray, frames: Int) {}
            }, maxSeconds = 2) }.getOrElse { throw Failed("The trimmed file didn't play back; the song was left as it was") }
            val before = runCatching { AudioFileIO.read(copy) }.getOrNull()
            if (before != null && runCatching { AudioFileIO.read(cut) }.isFailure) {
                throw Failed("The trimmed file's tags didn't read back; the song was left as it was")
            }
            context.contentResolver.openOutputStream(uri, "wt")?.use { out -> cut.inputStream().use { it.copyTo(out) } }
                ?: throw Failed("The song's file can't be changed")
            Trimmed(
                result.startMs, result.endMs,
                (silence.leadMs - result.startMs).coerceAtLeast(0), (silence.tailMs - result.endMs).coerceAtLeast(0),
            )
        } finally {
            copy.delete()
            cut.delete()
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

        /** Formats whose tags can hold ReplayGain values here (ID3v2, MP4, FLAC/Vorbis comments); not WAV, WMA or AIFF. */
        val VOLUME_TAGGABLE = setOf("mp3", "m4a", "mp4", "flac", "ogg")

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
