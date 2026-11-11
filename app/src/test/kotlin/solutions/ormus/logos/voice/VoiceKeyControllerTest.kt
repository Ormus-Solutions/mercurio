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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class VoiceKeyControllerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeRecorder : TakeRecorder {
        var started = 0
        var stopped = 0
        var canOpen = true
        var level: ((Float) -> Unit)? = null
        var limit: (() -> Unit)? = null
        var fail: (() -> Unit)? = null
        var stopOk = true
        var file: File? = null

        override fun start(file: File, onLevel: (Float) -> Unit, onLimit: () -> Unit, onFail: () -> Unit): Boolean {
            if (!canOpen) return false
            started++
            this.file = file
            file.writeBytes(byteArrayOf(9, 9))
            level = onLevel
            limit = onLimit
            fail = onFail
            return true
        }

        override suspend fun stop(): Boolean {
            stopped++
            return stopOk
        }

        var aborted = 0

        override fun abort() {
            aborted++
        }
    }

    private class FakeTransport : VoiceTransport {
        val posts = mutableListOf<Pair<VoiceRequest, Int>>()
        var next: () -> HttpReply = { HttpReply(200, """{"text":"hello world"}""") }
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun get(request: VoiceRequest): HttpReply = next()

        override suspend fun post(request: VoiceRequest, body: File): HttpReply {
            posts += request to body.readBytes().size
            gate?.await()
            return next()
        }
    }

    private inner class Rig(
        var granted: Boolean = true,
        var server: String? = "http://box.example:8097",
        var style: VoiceStyle = VoiceStyle.SERVER,
    ) {
        val recorder = FakeRecorder()
        val transport = FakeTransport()
        val inserted = mutableListOf<String>()
        var asked = 0
        val take = File(tmp.root, "voice-take.wav")
        val scope = TestScope(UnconfinedTestDispatcher())
        val controller = VoiceKeyController(
            scope = scope,
            recorder = recorder,
            client = VoiceClient(transport),
            takeFile = take,
            micGranted = { granted },
            askMic = { asked++ },
            server = { server },
            style = { style },
            insert = { inserted += it },
        )
    }

    @Test
    fun tapRecordsThenTapStopsThinksAndInsertsTheText() {
        val rig = Rig()
        assertEquals(VoiceState.IDLE, rig.controller.state.value)
        rig.controller.tap()
        assertEquals(VoiceState.LISTENING, rig.controller.state.value)
        assertEquals(1, rig.recorder.started)

        val gate = CompletableDeferred<Unit>().also { rig.transport.gate = it }
        rig.controller.tap()
        assertEquals(VoiceState.THINKING, rig.controller.state.value)
        assertEquals(1, rig.recorder.stopped)
        assertTrue(rig.inserted.isEmpty())

        gate.complete(Unit)
        assertEquals(VoiceState.IDLE, rig.controller.state.value)
        assertEquals(listOf("hello world"), rig.inserted)
        assertNull(rig.controller.note.value)
    }

    @Test
    fun theTakeIsSentWithTheStyleOfTheSetting() {
        val rig = Rig(style = VoiceStyle.FORMAL)
        rig.controller.tap()
        rig.controller.tap()
        assertEquals("http://box.example:8097/v1/dictate?style=formal", rig.transport.posts.single().first.url)
        assertEquals(2, rig.transport.posts.single().second)
    }

    @Test
    fun aTapWhileThinkingDoesNothing() {
        val rig = Rig()
        rig.controller.tap()
        rig.transport.gate = CompletableDeferred()
        rig.controller.tap()
        rig.controller.tap()
        assertEquals(VoiceState.THINKING, rig.controller.state.value)
        assertEquals(1, rig.recorder.started)
        assertEquals(1, rig.transport.posts.size)
    }

    @Test
    fun noServerSaysSoAndNeverRecords() {
        val rig = Rig(server = null)
        rig.controller.tap()
        assertEquals(VoiceState.IDLE, rig.controller.state.value)
        assertEquals(VoiceNote.NoServer, rig.controller.note.value)
        assertEquals(0, rig.recorder.started)
        assertEquals(0, rig.asked)
    }

    @Test
    fun theMicIsAskedForOnTheFirstTapOnly_andRecordingStartsOnceItIsGranted() {
        val rig = Rig(granted = false)
        rig.controller.tap()
        assertEquals(1, rig.asked)
        assertEquals(VoiceState.IDLE, rig.controller.state.value)
        assertEquals(0, rig.recorder.started)

        rig.granted = true
        rig.controller.onMicResult(true)
        assertEquals(VoiceState.LISTENING, rig.controller.state.value)
        assertEquals(1, rig.recorder.started)
        assertEquals(1, rig.asked)
    }

    @Test
    fun aDeniedMicSaysSo_andTheNextTapAsksAgain() {
        val rig = Rig(granted = false)
        rig.controller.tap()
        rig.controller.onMicResult(false)
        assertEquals(VoiceState.IDLE, rig.controller.state.value)
        assertEquals(VoiceNote.MicDenied, rig.controller.note.value)
        assertEquals(0, rig.recorder.started)
        assertTrue(rig.inserted.isEmpty())

        rig.controller.tap()
        assertEquals(2, rig.asked)
        assertNull(rig.controller.note.value)
    }

    @Test
    fun aMicThatWillNotOpenSaysSo() {
        val rig = Rig()
        rig.recorder.canOpen = false
        rig.controller.tap()
        assertEquals(VoiceState.IDLE, rig.controller.state.value)
        assertEquals(VoiceNote.RecordFailed, rig.controller.note.value)
    }

    @Test
    fun aJunkTakeInsertsNothingAndSaysSo() {
        val rig = Rig()
        rig.transport.next = { HttpReply(200, """{"text":"","dropped":"junk"}""") }
        rig.controller.tap()
        rig.controller.tap()
        assertTrue(rig.inserted.isEmpty())
        assertEquals(VoiceNote.Junk, rig.controller.note.value)
        assertEquals(VoiceState.IDLE, rig.controller.state.value)
    }

    @Test
    fun aFailedRewriteInsertsTheRawTextAndSaysSo() {
        val rig = Rig()
        rig.transport.next = { HttpReply(200, """{"text":"raw words","rewrite":"failed"}""") }
        rig.controller.tap()
        rig.controller.tap()
        assertEquals(listOf("raw words"), rig.inserted)
        assertEquals(VoiceNote.RewriteFailed, rig.controller.note.value)
    }

    @Test
    fun aServerErrorKeepsTheTake_saysWhy_andRetrySendsTheSameTake() {
        val rig = Rig()
        rig.transport.next = { HttpReply(503, """{"error":"no_engine","message":"engine down"}""") }
        rig.controller.tap()
        rig.controller.tap()
        assertTrue(rig.inserted.isEmpty())
        assertTrue(rig.take.exists())
        assertEquals(VoiceNote.Failed("no_engine", "engine down", canRetry = true), rig.controller.note.value)

        rig.transport.next = { HttpReply(200, """{"text":"second time"}""") }
        rig.controller.retry()
        assertEquals(listOf("second time"), rig.inserted)
        assertEquals(2, rig.transport.posts.size)
        assertEquals(rig.transport.posts[0].second, rig.transport.posts[1].second)
        assertNull(rig.controller.note.value)
        assertEquals(1, rig.recorder.started)
    }

    @Test
    fun anUnreachableServerIsRetryable() {
        val rig = Rig()
        rig.transport.next = { throw IOException("no route") }
        rig.controller.tap()
        rig.controller.tap()
        val note = rig.controller.note.value as VoiceNote.Failed
        assertEquals("unreachable", note.kind)
        assertTrue(note.canRetry)
        assertTrue(rig.take.exists())
    }

    @Test
    fun badAudioCannotBeRetried() {
        val rig = Rig()
        rig.transport.next = { HttpReply(400, """{"error":"bad_audio","message":"x"}""") }
        rig.controller.tap()
        rig.controller.tap()
        assertFalse((rig.controller.note.value as VoiceNote.Failed).canRetry)
    }

    @Test
    fun aGoodTakeIsDeletedAfterItsTextIsIn() {
        val rig = Rig()
        rig.controller.tap()
        rig.controller.tap()
        assertFalse(rig.take.exists())
    }

    @Test
    fun aNewTakeReplacesTheKeptOneAndClearsTheNote() {
        val rig = Rig()
        rig.transport.next = { HttpReply(503, """{"error":"no_engine","message":"x"}""") }
        rig.controller.tap()
        rig.controller.tap()
        rig.transport.next = { HttpReply(200, """{"text":"fresh"}""") }
        rig.controller.tap()
        assertNull(rig.controller.note.value)
        assertEquals(2, rig.recorder.started)
        rig.controller.tap()
        assertEquals(listOf("fresh"), rig.inserted)
    }

    @Test
    fun retryWithNothingKeptDoesNothing() {
        val rig = Rig()
        rig.controller.retry()
        assertTrue(rig.transport.posts.isEmpty())
    }

    @Test
    fun levelsFollowTheRecorderWhileListeningAndAreClamped() {
        val rig = Rig()
        rig.controller.tap()
        rig.recorder.level!!(0.25f)
        rig.recorder.level!!(2f)
        assertEquals(listOf(0.25f, 1f), rig.controller.levels.value.takeLast(2))
        rig.controller.tap()
        assertTrue(rig.controller.levels.value.isEmpty())
    }

    @Test
    fun theRecordersTimeLimitEndsTheTakeLikeATap() {
        val rig = Rig()
        rig.controller.tap()
        rig.recorder.limit!!()
        assertEquals(listOf("hello world"), rig.inserted)
        assertEquals(VoiceState.IDLE, rig.controller.state.value)
    }

    @Test
    fun cancelWhileListeningDropsTheTakeAndSendsNothing() {
        val rig = Rig()
        rig.controller.tap()
        rig.controller.cancel()
        assertEquals(1, rig.recorder.aborted)
        assertEquals(VoiceState.IDLE, rig.controller.state.value)
        assertFalse(rig.take.exists())
        assertTrue(rig.transport.posts.isEmpty())
    }

    @Test
    fun cancelWhileIdleDoesNothing() {
        val rig = Rig()
        rig.controller.cancel()
        assertEquals(0, rig.recorder.aborted)
    }

    @Test
    fun aRecorderThatFailsMidTakeDropsTheTakeAndSaysSo() {
        val rig = Rig()
        rig.controller.tap()
        rig.recorder.fail!!()
        assertEquals(VoiceState.IDLE, rig.controller.state.value)
        assertEquals(VoiceNote.RecordFailed, rig.controller.note.value)
        assertFalse(rig.take.exists())
        assertTrue(rig.transport.posts.isEmpty())
    }

    @Test
    fun aTakeThatCouldNotBeFinishedIsNotSent() {
        val rig = Rig()
        rig.recorder.stopOk = false
        rig.controller.tap()
        rig.controller.tap()
        assertEquals(VoiceNote.RecordFailed, rig.controller.note.value)
        assertTrue(rig.transport.posts.isEmpty())
        assertEquals(VoiceState.IDLE, rig.controller.state.value)
    }
}
