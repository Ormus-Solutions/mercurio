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

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class VoiceReplyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun ok_isTheText() {
        val r = VoiceReply.parseDictation(200, """{"text":"hello there","raw":"hello there","style":"prompt","engine":"e","ms":{"asr":300}}""")
        assertEquals(Dictation.Text("hello there"), r)
    }

    @Test
    fun junk_isJunk() {
        assertEquals(Dictation.Junk, VoiceReply.parseDictation(200, """{"text":"","raw":"","dropped":"junk"}"""))
    }

    @Test
    fun emptyTextWithoutDropped_isJunkToo() {
        assertEquals(Dictation.Junk, VoiceReply.parseDictation(200, """{"text":"  "}"""))
    }

    @Test
    fun rewriteFailed_keepsTheSubstitutedTextAndSaysSo() {
        val r = VoiceReply.parseDictation(200, """{"text":"the raw words","raw":"the raw words","rewrite":"failed"}""")
        assertEquals(Dictation.Text("the raw words", rewriteFailed = true), r)
    }

    @Test
    fun errorBody_isFailedWithItsKindAndMessage() {
        val r = VoiceReply.parseDictation(400, """{"error":"bad_audio","message":"not a wav"}""")
        assertEquals(Dictation.Failed("bad_audio", "not a wav"), r)
        assertEquals(Dictation.Failed("too_large", "big"), VoiceReply.parseDictation(413, """{"error":"too_large","message":"big"}"""))
        assertEquals(Dictation.Failed("no_engine", "down"), VoiceReply.parseDictation(503, """{"error":"no_engine","message":"down"}"""))
    }

    @Test
    fun errorWithoutAJsonBody_isAServerFailureWithTheCode() {
        val r = VoiceReply.parseDictation(502, "<html>bad gateway</html>") as Dictation.Failed
        assertEquals("server", r.kind)
        assertTrue(r.message.contains("502"))
    }

    @Test
    fun okWithAnUnreadableBody_isABadReply() {
        assertEquals("bad_reply", (VoiceReply.parseDictation(200, "nope") as Dictation.Failed).kind)
        assertEquals("bad_reply", (VoiceReply.parseDictation(200, """{"engine":"e"}""") as Dictation.Failed).kind)
    }

    @Test
    fun retryable_isTrueForServerAndEngineFailuresNotForBadOrLargeAudio() {
        assertTrue(Dictation.Failed("no_engine", "").retryable)
        assertTrue(Dictation.Failed("server", "").retryable)
        assertTrue(Dictation.Failed("unreachable", "").retryable)
        assertTrue(Dictation.Failed("bad_reply", "").retryable)
        assertFalse(Dictation.Failed("bad_audio", "").retryable)
        assertFalse(Dictation.Failed("too_large", "").retryable)
    }

    @Test
    fun busyIsRetryable_badRequestIsNot() {
        assertEquals(Dictation.Failed("busy", "two takes run"), VoiceReply.parseDictation(503, """{"error":"busy","message":"two takes run"}"""))
        assertTrue(Dictation.Failed("busy", "").retryable)
        assertEquals("bad_request", (VoiceReply.parseDictation(400, """{"error":"bad_request"}""") as Dictation.Failed).kind)
        assertFalse(Dictation.Failed("bad_request", "").retryable)
        assertEquals("bad_audio", (VoiceReply.parseDictation(415, """{"error":"bad_audio"}""") as Dictation.Failed).kind)
    }

    @Test
    fun health_isOkOnlyOn200WithStatusOk() {
        assertTrue(VoiceReply.parseHealth(200, """{"status":"ok","engine":"e"}"""))
        assertFalse(VoiceReply.parseHealth(200, """{"status":"degraded"}"""))
        assertFalse(VoiceReply.parseHealth(503, """{"status":"ok"}"""))
        assertFalse(VoiceReply.parseHealth(200, "nope"))
    }

    private class FakeTransport(val reply: HttpReply? = null, val fail: IOException? = null) : VoiceTransport {
        var sent: Pair<VoiceRequest, ByteArray>? = null

        override suspend fun get(request: VoiceRequest): HttpReply = fail?.let { throw it } ?: reply!!

        override suspend fun post(request: VoiceRequest, body: File): HttpReply {
            sent = request to body.readBytes()
            return fail?.let { throw it } ?: reply!!
        }
    }

    @Test
    fun client_postsTheTakeToTheRightUrlAndParsesTheReply() = runTest {
        val take = tmp.newFile("t.wav").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
        val transport = FakeTransport(HttpReply(200, """{"text":"hi"}"""))
        val r = VoiceClient(transport).dictate("http://box.example:8097", VoiceStyle.CASUAL, take)
        assertEquals(Dictation.Text("hi"), r)
        assertEquals("http://box.example:8097/v1/dictate?style=casual", transport.sent!!.first.url)
        assertEquals("audio/wav", transport.sent!!.first.contentType)
        assertEquals(listOf<Byte>(1, 2, 3), transport.sent!!.second.toList())
    }

    @Test
    fun client_turnsAnUnreachableServerIntoAFailureNotAThrow() = runTest {
        val take = tmp.newFile("t.wav")
        val r = VoiceClient(FakeTransport(fail = IOException("no route"))).dictate("http://box.example:8097", VoiceStyle.SERVER, take)
        r as Dictation.Failed
        assertEquals("unreachable", r.kind)
        assertTrue(r.retryable)
    }

    @Test
    fun client_turnsAnUnexpectedExceptionIntoAFailureToo() = runTest {
        val take = tmp.newFile("t.wav")
        val boom = object : VoiceTransport {
            override suspend fun get(request: VoiceRequest): HttpReply = throw IllegalArgumentException("port out of range")

            override suspend fun post(request: VoiceRequest, body: File): HttpReply = throw IllegalArgumentException("port out of range")
        }
        assertEquals("unreachable", (VoiceClient(boom).dictate("http://box.example:8097", VoiceStyle.SERVER, take) as Dictation.Failed).kind)
        assertFalse(VoiceClient(boom).health("http://box.example:8097"))
    }

    @Test
    fun client_health() = runTest {
        assertTrue(VoiceClient(FakeTransport(HttpReply(200, """{"status":"ok"}"""))).health("http://box.example:8097"))
        assertFalse(VoiceClient(FakeTransport(fail = IOException("x"))).health("http://box.example:8097"))
    }
}
