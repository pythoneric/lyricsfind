package com.biteric.songfixer.match

import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * A minimal HTTP server for tests (unit tests compile against android.jar, which has no
 * com.sun.net.httpserver). [handler] gets the path and the decoded query + form body.
 */
class FakeServer(private val handler: (path: String, query: String) -> Pair<Int, String>) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val baseUrl = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { c ->
                    val input = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val requestLine = input.readLine() ?: return@use
                    var length = 0
                    while (true) {
                        val h = input.readLine() ?: break
                        if (h.isEmpty()) break
                        if (h.startsWith("Content-Length:", ignoreCase = true)) length = h.substringAfter(':').trim().toInt()
                    }
                    val body = CharArray(length).also { var n = 0; while (n < length) n += input.read(it, n, length - n) }.concatToString()
                    val target = requestLine.split(' ')[1]
                    val path = target.substringBefore('?')
                    val query = URLDecoder.decode(target.substringAfter('?', "") + "&" + body, "UTF-8")
                    val (code, text) = handler(path, query)
                    val bytes = text.toByteArray()
                    val out = c.getOutputStream()
                    out.write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(bytes)
                    out.flush()
                }
            }
        }
    }

    override fun close() = socket.close()
}
