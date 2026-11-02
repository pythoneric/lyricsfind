package com.biteric.songfixer.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.biteric.songfixer.BuildConfig
import com.biteric.songfixer.online.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface Cover {
    data object Loading : Cover
    data object None : Cover
    class Loaded(val image: ImageBitmap) : Cover
}

/**
 * The cover a fix would add, loaded from [url] (resolved by [findUrl], which may search for the
 * album). A placeholder when there's none.
 */
@Composable
fun CoverPreview(key: Any?, findUrl: suspend () -> String?, size: Dp = 96.dp) {
    val cover by produceState<Cover>(Cover.Loading, key) {
        value = try {
            val url = findUrl()
            val bytes = url?.let { Http.download(it, "SongFixer/${BuildConfig.VERSION_NAME} ( ${Http.USER_AGENT_CONTACT} )")?.first }
            val bitmap = bytes?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) } }
            if (bitmap != null) Cover.Loaded(bitmap.asImageBitmap()) else Cover.None
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Cover.None
        }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when (val c = cover) {
            Cover.Loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            Cover.None -> Icon(Icons.Default.Album, contentDescription = "No cover found", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            is Cover.Loaded -> Image(c.image, contentDescription = "Album cover", contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        }
    }
}
