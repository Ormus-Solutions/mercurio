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

package org.connectbot.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.voice.HttpReply
import solutions.ormus.logos.voice.TakeRecorder
import solutions.ormus.logos.voice.VoiceClient
import solutions.ormus.logos.voice.VoiceKeyController
import solutions.ormus.logos.voice.VoiceRequest
import solutions.ormus.logos.voice.VoiceStyle
import solutions.ormus.logos.voice.VoiceTransport
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class VoiceKeyUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val recorder = object : TakeRecorder {
        override fun start(file: File, onLevel: (Float) -> Unit, onLimit: () -> Unit, onFail: () -> Unit): Boolean = true.also { file.writeBytes(byteArrayOf(1)) }

        override suspend fun stop() = true

        override fun abort() = Unit
    }
    private val gate = CompletableDeferred<Unit>()
    private var reply = HttpReply(200, """{"text":"spoken words"}""")
    private val transport = object : VoiceTransport {
        override suspend fun get(request: VoiceRequest) = reply

        override suspend fun post(request: VoiceRequest, body: File): HttpReply {
            gate.await()
            return reply
        }
    }
    private val inserted = mutableListOf<String>()

    private fun controller() = VoiceKeyController(
        scope = TestScope(UnconfinedTestDispatcher()),
        recorder = recorder,
        client = VoiceClient(transport),
        takeFile = File(rule.activity.cacheDir, "ui-test-take.wav"),
        micGranted = { true },
        askMic = {},
        server = { "http://box.example:8097" },
        style = { VoiceStyle.SERVER },
        insert = { inserted += it },
    )

    private fun show(c: VoiceKeyController) = rule.setContent {
        ConnectBotTheme {
            androidx.compose.foundation.layout.Column {
                VoiceNoteLine(c)
                VoiceKeyButton(c)
            }
        }
    }

    @Test
    fun theKeyShowsIdleThenListeningThenThinking_andTheTextGoesIn() {
        show(controller())
        rule.onNodeWithContentDescription("Dictate with your voice").assertIsDisplayed()
        rule.onNodeWithTag(VOICE_KEY_TEST_TAG).performClick()
        rule.onNodeWithContentDescription("Listening. Tap to stop and send").assertIsDisplayed()
        rule.onNodeWithTag(VOICE_KEY_TEST_TAG).performClick()
        rule.onNodeWithContentDescription("Turning your voice into text").assertIsDisplayed()
        gate.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Dictate with your voice").assertIsDisplayed()
        assertEquals(listOf("spoken words"), inserted)
    }

    @Test
    fun aJunkTakeShowsAShortNote() {
        reply = HttpReply(200, """{"text":"","dropped":"junk"}""")
        gate.complete(Unit)
        show(controller())
        rule.onNodeWithTag(VOICE_KEY_TEST_TAG).performClick()
        rule.onNodeWithTag(VOICE_KEY_TEST_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Nothing heard, nothing added").assertIsDisplayed()
        assertEquals(emptyList<String>(), inserted)
    }

    @Test
    fun aServerErrorSaysWhyAndOffersAResend() {
        reply = HttpReply(503, """{"error":"busy","message":"two takes run"}""")
        gate.complete(Unit)
        show(controller())
        rule.onNodeWithTag(VOICE_KEY_TEST_TAG).performClick()
        rule.onNodeWithTag(VOICE_KEY_TEST_TAG).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Voice: the server is busy").assertIsDisplayed()
        reply = HttpReply(200, """{"text":"second try"}""")
        rule.onNodeWithText("Send again").performClick()
        rule.waitForIdle()
        assertEquals(listOf("second try"), inserted)
    }
}
