package com.biteric.songfixer.ui

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.biteric.songfixer.data.Song
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/** Lyrics sites the user can look a song up on by hand. */
enum class LyricsSite(val label: String, private val searchUrl: String) {
    GENIUS("Genius", "https://genius.com/search?q="),
    // "?query=" sends visitors to a sign-in page; the path form doesn't.
    MUSIXMATCH("Musixmatch", "https://www.musixmatch.com/search/"),
    AZLYRICS("AZLyrics", "https://www.azlyrics.com/search/?q=");

    // %20, not "+": Musixmatch's query is part of the path.
    fun search(query: String) = searchUrl + URLEncoder.encode(query, "UTF-8").replace("+", "%20")
}

/**
 * The site's search for the song, in a browser inside the app. The user opens the right song,
 * selects the lyrics and takes them with [onText]: only what they selected (or copied) is read,
 * never the page by itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LyricsBrowser(song: Song, site: LyricsSite, onText: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Back goes back in the site first, then closes.
        BackHandler { if (canGoBack) webView?.goBack() else onDismiss() }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(site.label) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") } },
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                        Text(
                            hint ?: "Open the song, then long-press the lyrics and drag the handles to select them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hint != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                webView?.evaluateJavascript("window.getSelection().toString()") { result ->
                                    val text = runCatching { Json.decodeFromString<String>(result) }.getOrNull()?.trim().orEmpty()
                                    if (text.isEmpty()) {
                                        hint = "Nothing selected. Select the lyrics first, or copy them and use \"Paste copied\"."
                                    } else {
                                        onText(text)
                                    }
                                }
                            }) { Text("Use selected text") }
                            // For sites whose selection the page can't read (e.g. inside a frame).
                            OutlinedButton(onClick = {
                                val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                                if (text.isEmpty()) hint = "Nothing copied yet." else onText(text)
                            }) { Text("Paste copied") }
                        }
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx: Context ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                    loading = true
                                    hint = null
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    loading = false
                                    canGoBack = view.canGoBack()
                                }
                            }
                            loadUrl(site.search(lyricsQuery(song)))
                            webView = this
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }
}
