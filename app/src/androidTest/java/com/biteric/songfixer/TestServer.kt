package com.biteric.songfixer

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A minimal HTTP server on this device for device tests: [handler] gets the request path and
 * answers with a status, a content type and the body bytes (images too). Records [paths] asked for.
 */
class TestServer(private val handler: (path: String) -> Triple<Int, String, ByteArray>) : AutoCloseable {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val baseUrl = "http://127.0.0.1:${socket.localPort}"
    val paths: MutableList<String> = CopyOnWriteArrayList()

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { c ->
                    val input = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val requestLine = input.readLine() ?: return@use
                    while (true) {
                        val h = input.readLine() ?: break
                        if (h.isEmpty()) break
                    }
                    val path = requestLine.split(' ')[1].substringBefore('?')
                    paths += path
                    val (code, type, body) = handler(path)
                    val out = c.getOutputStream()
                    out.write("HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                    out.flush()
                }
            }
        }
    }

    override fun close() = socket.close()
}
