/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2026 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package solutions.ormus.logos.voice

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL

/**
 * The voice server over HTTP. https goes through the platform's own client, with its policy.
 *
 * Plain http is the server's contract on the tailnet (WireGuard already encrypts those bytes), and
 * the platform client refuses cleartext to anything but loopback ([the network security config]),
 * so plain http is spoken here on a plain socket, and only to a host [VoiceServer.cleartextAllowed]
 * names: a tailnet, a LAN or loopback. A public name must use https.
 */
class HttpVoiceTransport(
    private val io: CoroutineDispatcher,
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) : VoiceTransport {
    override suspend fun get(request: VoiceRequest): HttpReply = exchange(request, null)

    override suspend fun post(request: VoiceRequest, body: File): HttpReply = exchange(request, body)

    private suspend fun exchange(request: VoiceRequest, body: File?): HttpReply = withContext(io) {
        val uri = try {
            URI(request.url)
        } catch (e: java.net.URISyntaxException) {
            throw IOException("The voice server address is not valid")
        }
        val host = uri.host ?: throw IOException("The voice server address is not valid")
        if (uri.port != -1 && uri.port !in 1..65535) throw IOException("The voice server address is not valid")
        when (uri.scheme) {
            "https" -> viaPlatform(uri, request, body)

            "http" -> {
                if (!VoiceServer.cleartextAllowed(host)) throw IOException("Plain http is sent only inside a tailnet or a LAN: use https for $host")
                viaSocket(uri, host, request, body)
            }

            else -> throw IOException("The voice server address is not valid")
        }
    }

    private fun viaPlatform(uri: URI, request: VoiceRequest, body: File?): HttpReply {
        val conn = URL(uri.toString()).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            if (body == null) {
                conn.requestMethod = "GET"
            } else {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.length())
                request.contentType?.let { conn.setRequestProperty("Content-Type", it) }
                conn.outputStream.use { out -> body.inputStream().use { it.copyTo(out) } }
            }
            val code = conn.responseCode
            val stream = if (code >= HTTP_ERROR) conn.errorStream else conn.inputStream
            return HttpReply(code, stream?.use { String(readCappedBytes(it), Charsets.UTF_8) }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    private fun viaSocket(uri: URI, host: String, request: VoiceRequest, body: File?): HttpReply {
        val port = if (uri.port > 0) uri.port else DEFAULT_HTTP_PORT
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val path = (uri.rawPath.ifEmpty { "/" }) + (uri.rawQuery?.let { "?$it" } ?: "")
            val head = buildString {
                append(if (body == null) "GET " else "POST ").append(path).append(" HTTP/1.1\r\n")
                append("Host: ").append(host).append(':').append(port).append("\r\n")
                request.contentType?.let { append("Content-Type: ").append(it).append("\r\n") }
                if (body != null) append("Content-Length: ").append(body.length()).append("\r\n")
                append("Accept: application/json\r\nConnection: close\r\n\r\n")
            }
            val out = socket.getOutputStream()
            // A server may answer early (too large) and close: its answer is still read after a failed write.
            val writeFailure = try {
                out.write(head.toByteArray(Charsets.US_ASCII))
                body?.inputStream()?.use { it.copyTo(out) }
                out.flush()
                null
            } catch (e: IOException) {
                e
            }
            return try {
                readReply(socket.getInputStream())
            } catch (e: IOException) {
                throw writeFailure ?: e
            }
        }
    }

    private fun readReply(input: InputStream): HttpReply {
        val status = readLine(input) ?: throw IOException("The voice server closed the connection")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("The voice server's answer could not be read")
        var length = -1
        var chunked = false
        while (true) {
            val line = readLine(input) ?: throw IOException("The voice server's answer was cut off")
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim().lowercase()
            val value = line.substringAfter(':', "").trim()
            if (name == "content-length") length = value.toIntOrNull() ?: -1
            if (name == "transfer-encoding" && value.lowercase().contains("chunked")) chunked = true
        }
        val bytes = when {
            chunked -> readChunked(input)
            length >= 0 -> readExactly(input, length)
            else -> readCappedBytes(input)
        }
        return HttpReply(code, String(bytes, Charsets.UTF_8))
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw IOException("The voice server's answer was cut off")
            if (size == 0) break
            if (size < 0 || size > MAX_BODY - out.size()) throw IOException("The voice server's answer is too large")
            out.write(readExactly(input, size))
            readLine(input) // the CRLF after each chunk
        }
        return out.toByteArray()
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        if (length > MAX_BODY) throw IOException("The voice server's answer is too large")
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(bytes, read, length - read)
            if (n < 0) throw IOException("The voice server's answer was cut off")
            read += n
        }
        return bytes
    }

    private fun readCappedBytes(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BODY) throw IOException("The voice server's answer is too large")
        }
        return out.toByteArray()
    }

    /** One header line without its CRLF; null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.isEmpty()) null else line.toString()
            if (b == '\n'.code) return line.toString().trimEnd('\r')
            line.append(b.toChar())
            if (line.length > MAX_LINE) throw IOException("The voice server's answer could not be read")
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000

        // A take is transcribed and rewritten before the answer comes back.
        const val READ_TIMEOUT_MS = 90_000
        const val DEFAULT_HTTP_PORT = 80
        const val HTTP_ERROR = 400
        const val MAX_BODY = 1 shl 20
        const val MAX_LINE = 8 * 1024
        const val BUFFER = 8 * 1024
    }
}
