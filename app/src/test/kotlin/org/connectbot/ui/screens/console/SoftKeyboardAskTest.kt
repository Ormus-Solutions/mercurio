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

package org.connectbot.ui.screens.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.components.TerminalComposeBar
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * An agent console opens to watch: the soft keyboard stays down until the person taps the terminal
 * or the compose field, or a control asks for typing (K-20). A plain shell keeps the old open.
 */
@RunWith(AndroidJUnit4::class)
class SoftKeyboardAskTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val plain = KeyboardSetting(hardwareKeyboard = false, agentConsole = false)
    private val agent = KeyboardSetting(hardwareKeyboard = false, agentConsole = true)
    private val withHardware = KeyboardSetting(hardwareKeyboard = true, agentConsole = false)

    @Test
    fun anAgentConsoleOpensWithTheKeyboardDown() {
        assertFalse(raisesKeyboard(KeyboardEvent.OPEN, agent))
    }

    @Test
    fun aPlainShellKeepsRaisingTheKeyboardOnOpen() {
        assertTrue(raisesKeyboard(KeyboardEvent.OPEN, plain))
    }

    @Test
    fun aHardwareKeyboardNeverRaisesTheSoftOne() {
        for (event in KeyboardEvent.entries) {
            assertFalse("$event", raisesKeyboard(event, withHardware))
            assertFalse("$event", raisesKeyboard(event, withHardware.copy(agentConsole = true)))
        }
    }

    @Test
    fun aTapAndBiometricCompletionRaiseTheKeyboardOnAgentConsolesToo() {
        assertTrue(raisesKeyboard(KeyboardEvent.TERMINAL_TAP, agent))
        assertTrue(raisesKeyboard(KeyboardEvent.TERMINAL_TAP, plain))
        assertTrue(raisesKeyboard(KeyboardEvent.BIOMETRIC_DONE, agent))
    }

    @Test
    fun aNewAskStartsWithNoRequest() {
        val ask = SoftKeyboardAsk()
        assertFalse(ask.wanted)
        assertEquals(0, ask.requests)
    }

    @Test
    fun twoTapsWithTheKeyboardNeverShownMakeTwoRequests() {
        val ask = SoftKeyboardAsk()
        ask.on(KeyboardEvent.TERMINAL_TAP, agent)
        ask.on(KeyboardEvent.TERMINAL_TAP, agent)
        assertEquals(2, ask.requests)
        assertTrue(ask.wanted)
    }

    @Test
    fun anEventThatDoesNotRaiseMakesNoRequest() {
        val ask = SoftKeyboardAsk()
        ask.on(KeyboardEvent.OPEN, agent)
        ask.on(KeyboardEvent.TERMINAL_TAP, withHardware)
        assertEquals(0, ask.requests)
        assertFalse(ask.wanted)
    }

    @Test
    fun theKeyboardGoingDownWithBackWithdrawsTheAsk() {
        val ask = SoftKeyboardAsk()
        ask.on(KeyboardEvent.TERMINAL_TAP, agent)
        ask.onKeyboardDismissed()
        assertFalse(ask.wanted)
    }

    @Test
    fun theComposeFieldTakesFocusOnlyOnATap() {
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.host).thenReturn(Host(nickname = "devbox"))
        composeTestRule.setContent {
            ConnectBotTheme { TerminalComposeBar(bridge = bridge) }
        }
        composeTestRule.waitForIdle()
        val field = composeTestRule.activity.getString(R.string.terminal_text_input_dialog_label)

        composeTestRule.onNodeWithText(field).assertIsNotFocused()
        composeTestRule.onNodeWithText(field).performClick()
        composeTestRule.onNodeWithText(field).assertIsFocused()
    }
}
