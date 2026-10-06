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

package org.connectbot.usage

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.components.TerminalComposeBar
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File

/**
 * End to end through the real compose bar and tracker: taps land as action ids,
 * and what is typed or dictated never reaches the usage store.
 */
@RunWith(AndroidJUnit4::class)
class ComposeBarUsageTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun bridge(postLogin: String?): TerminalBridge {
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.host).thenReturn(
            Host(nickname = "Sun", username = "alice", hostname = "100.100.1.1", postLogin = postLogin),
        )
        return bridge
    }

    @Test
    fun tapsAreRecordedAndTypedTextNeverIs() {
        val prefs = context.getSharedPreferences("compose-usage", Context.MODE_PRIVATE).apply { edit(commit = true) { clear() } }
        val dir = File(temp.root, "usage")
        val store = UsageStore(dir)
        val tracker = UsageTracker(store, prefs, CoroutineDispatchers(dispatcher, dispatcher, dispatcher)) { 1L }
        tracker.setScreen("console/{hostId}")
        tracker.setHerdr(true)
        val secret = "my password is hunter2 and the plan is ls -la"

        composeTestRule.setContent {
            CompositionLocalProvider(LocalUsageLog provides tracker) {
                TerminalComposeBar(bridge = bridge("herdr\n"), onRead = {})
            }
        }
        composeTestRule.onNodeWithText("↑").performClick()
        composeTestRule.onNodeWithText("↑").performClick()
        composeTestRule.onNodeWithText("Esc").performClick()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.compose_bar_more_keys)).performClick()
        composeTestRule.onNodeWithText("Mode").performClick()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.reader_open)).performClick()
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.compose_bar_more_keys)).performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput(secret)
        composeTestRule.onNodeWithContentDescription(context.getString(R.string.button_send)).performClick()
        composeTestRule.onNode(hasSetTextAction()).performTextInput(secret)
        composeTestRule.onNode(hasSetTextAction()).performImeAction()
        composeTestRule.waitForIdle()
        scheduler.advanceUntilIdle()

        val actions = store.snapshot().events.map { it.action }
        assertThat(actions).containsExactly(
            // Row 1's ↑ pages up.
            UsageActions.HERDR_PGUP,
            UsageActions.HERDR_PGUP,
            UsageActions.KEY_ESC,
            UsageActions.BAR_TRAY_OPEN,
            UsageActions.KEY_MODE,
            UsageActions.READER_OPEN,
            UsageActions.BAR_TRAY_CLOSE,
            UsageActions.SEND,
            UsageActions.SEND_IME,
        )
        assertThat(store.snapshot().events.all { it.screen == "console" && it.herdr == true }).isTrue()

        val onDisk = dir.listFiles()!!.filter { it.isFile }.joinToString("\n") { it.readText() }
        listOf("hunter2", "password", "ls -la", "alice", "100.100.1.1").forEach {
            assertThat(onDisk).describedAs("leaked $it").doesNotContain(it)
        }
    }
}
