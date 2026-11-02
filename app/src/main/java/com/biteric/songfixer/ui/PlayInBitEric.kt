package com.biteric.songfixer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.biteric.songfixer.data.Song

private const val BITERIC = "com.biteric.sounds"

/**
 * Opens the song in BitEric, which plays it and shows Now Playing (names, cover, lyrics), so the
 * fix can be checked by ear and eye. Back returns here. Without BitEric, any music player.
 */
fun playInBitEric(context: Context, song: Song) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(song.uri), mimeOf(song.extension))
        // The song's folder permission is ours; lend BitEric read access to this one file.
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent(view).setPackage(BITERIC))
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(Intent.createChooser(view, "Play with"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No music player can open this file", Toast.LENGTH_LONG).show()
        }
    }
}

private fun mimeOf(extension: String) = when (extension) {
    "mp3" -> "audio/mpeg"
    "m4a", "aac" -> "audio/mp4"
    "flac" -> "audio/flac"
    "ogg", "oga" -> "audio/ogg"
    "opus" -> "audio/opus"
    "wav" -> "audio/wav"
    else -> "audio/*"
}
