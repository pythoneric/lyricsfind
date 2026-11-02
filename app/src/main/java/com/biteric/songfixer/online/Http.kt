package com.biteric.songfixer.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A site answered with an error status. [code] is the HTTP status. */
class HttpException(val code: Int, message: String) : IOException(message)

/** Keeps at least [intervalMs] between requests to one service (MusicBrainz allows 1 a second). */
class Throttle(private val intervalMs: Long) {
    private val mutex = Mutex()
    private var last = 0L

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val wait = last + intervalMs - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            last = System.currentTimeMillis()
        }
    }
}

object Http {
    const val USER_AGENT_CONTACT = "biteric.python@gmail.com"

    fun query(params: List<Pair<String, String>>) =
        params.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

    /**
     * GET (or POST when [form] is set). Returns the body, or null for 404. "Busy" answers
     * (429, 502–504; MusicBrainz sends 503 when it's overloaded) are retried a few times.
     */
    suspend fun request(url: String, userAgent: String, form: String? = null): String? {
        var wait = 1500L
        repeat(3) {
            try {
                return requestOnce(url, userAgent, form)
            } catch (e: HttpException) {
                if (e.code != 429 && e.code !in 502..504) throw e
                delay(wait)
                wait *= 2
            }
        }
        return requestOnce(url, userAgent, form)
    }

    private suspend fun requestOnce(url: String, userAgent: String, form: String?): String? = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            if (form != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(form.toByteArray()) }
            }
            val code = conn.responseCode
            when {
                code == HttpURLConnection.HTTP_OK -> conn.inputStream.use { it.reader().readText() }
                code == HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> {
                    val body = runCatching { conn.errorStream?.use { it.reader().readText() } }.getOrNull()
                    throw HttpException(code, body ?: "HTTP $code")
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
