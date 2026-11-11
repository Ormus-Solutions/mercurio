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

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class HttpVoiceTransportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val transport = HttpVoiceTransport(connectTimeoutMs = 2_000, readTimeoutMs = 5_000)

    @Before
    fun up() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun down() = server.shutdown()

    private fun base() = "http://127.0.0.1:${server.port}"

    @Test
    fun get_readsStatusAndBody() = runTest {
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        val reply = transport.get(VoiceRequest.health(base()))
        assertEquals(200, reply.code)
        assertEquals("""{"status":"ok"}""", reply.body)
        assertEquals("/v1/health", server.takeRequest().path)
    }

    @Test
    fun post_sendsTheFileWithItsLengthAndContentType() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"hi"}"""))
        val file = tmp.newFile("t.wav").also { it.writeBytes(ByteArray(70_000) { i -> (i % 7).toByte() }) }
        val reply = transport.post(VoiceRequest.dictate(base(), VoiceStyle.PROMPT), file)
        assertEquals("""{"text":"hi"}""", reply.body)
        val got = server.takeRequest()
        assertEquals("POST", got.method)
        assertEquals("/v1/dictate?style=prompt", got.path)
        assertEquals("audio/wav", got.getHeader("Content-Type"))
        assertEquals(70_000L, got.bodySize)
        assertEquals(file.readBytes().toList(), got.body.readByteArray().toList())
    }

    @Test
    fun anErrorStatusIsAReplyNotAThrow() = runTest {
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"no_engine","message":"down"}"""))
        val reply = transport.get(VoiceRequest.health(base()))
        assertEquals(503, reply.code)
        assertTrue(reply.body.contains("no_engine"))
    }

    @Test
    fun aChunkedReplyIsReadWhole() = runTest {
        server.enqueue(MockResponse().setChunkedBody("""{"text":"a long answer split in pieces"}""", 5))
        assertEquals("""{"text":"a long answer split in pieces"}""", transport.get(VoiceRequest.health(base())).body)
    }

    @Test
    fun aRefusedConnectionThrowsIOException() = runTest {
        val url = base()
        server.shutdown()
        try {
            transport.get(VoiceRequest.health(url))
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun plainHttpToAPublicNameIsRefusedBeforeAnyConnection() = runTest {
        try {
            transport.get(VoiceRequest.health("http://box.example:8097"))
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    /** A one-shot server that answers with raw [reply] bytes, then closes. */
    private fun rawServer(reply: ByteArray): Int {
        val socket = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            socket.use { ss ->
                ss.accept().use { c ->
                    val input = c.getInputStream()
                    var last4 = 0
                    while (last4 != 0x0d0a0d0a) last4 = (last4 shl 8) or input.read().also { if (it < 0) return@use }
                    c.getOutputStream().write(reply)
                }
            }
        }.also { it.isDaemon = true }.start()
        return socket.localPort
    }

    @Test
    fun aPortOutOfRangeIsAnIOException() = runTest {
        try {
            transport.get(VoiceRequest.health("http://127.0.0.1:99999"))
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun aNegativeChunkSizeIsAnIOException() = runTest {
        val port = rawServer("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n-1\r\nx\r\n".toByteArray())
        try {
            transport.get(VoiceRequest.health("http://127.0.0.1:$port"))
            fail("expected IOException")
        } catch (_: IOException) {
        }
    }

    @Test
    fun aReplyEndedByTheCloseKeepsItsNonAsciiText() = runTest {
        val port = rawServer("HTTP/1.0 200 OK\r\n\r\n".toByteArray() + """{"text":"caf\u00e9 ma\u00f1ana"}""".toByteArray(Charsets.UTF_8))
        assertEquals("""{"text":"caf\u00e9 ma\u00f1ana"}""", transport.get(VoiceRequest.health("http://127.0.0.1:$port")).body)
    }
}
