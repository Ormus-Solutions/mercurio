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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.service.PromptRequest
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.components.InlinePrompt
import org.connectbot.ui.components.TRAY_TEST_TAG
import org.connectbot.ui.components.TerminalComposeBar
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * While an inline prompt is up it takes the compose bar's place, so its text
 * and answers are fully on screen and no key row sits under them (#37), on
 * Herdr sessions (extra key row) and plain sessions alike, including the short
 * console heights seen with the soft keyboard up (460dp) and in landscape
 * (200dp).
 */
@RunWith(AndroidJUnit4::class)
class ComposeBarOrPromptTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    /** Last bar height the layout reported, the value the terminal pads by. */
    private var barHeightPx = 0

    private fun bridge(postLogin: String?): TerminalBridge {
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.host).thenReturn(Host(nickname = "Sun", postLogin = postLogin))
        return bridge
    }

    private fun setContent(
        bridge: TerminalBridge,
        prompt: MutableState<PromptRequest?>,
        consoleHeight: Dp,
    ) {
        composeTestRule.setContent {
            ConnectBotTheme {
                var reportedHeight by remember { mutableIntStateOf(0) }
                barHeightPx = reportedHeight
                Box(Modifier.size(width = 400.dp, height = consoleHeight).testTag(CONSOLE)) {
                    ComposeBarOrPrompt(
                        promptActive = prompt.value != null,
                        onComposeBarHeightChange = { reportedHeight = it },
                        composeBar = { m -> TerminalComposeBar(bridge = bridge, modifier = m.testTag(BAR)) },
                        prompt = { m ->
                            InlinePrompt(
                                promptRequest = prompt.value,
                                onResponse = {},
                                onCancel = {},
                                modifier = m,
                            )
                        },
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun showPrompt(postLogin: String?, prompt: PromptRequest, consoleHeight: Dp) {
        setContent(bridge(postLogin), mutableStateOf(prompt), consoleHeight)
    }

    private fun string(id: Int) = composeTestRule.activity.getString(id)

    /**
     * The compose bar and its key rows are gone, and every named prompt node is
     * displayed with a real height inside the console.
     */
    private fun assertPromptOnScreenWithoutBar(vararg texts: String) {
        composeTestRule.onNodeWithTag(BAR).assertDoesNotExist()
        composeTestRule.onNodeWithText("Esc").assertDoesNotExist()
        val console = composeTestRule.onNodeWithTag(CONSOLE).fetchSemanticsNode().boundsInRoot
        for (text in texts) {
            val b = composeTestRule.onNodeWithText(text).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("'$text' has height ${b.height}", b.height > 0f)
            assertTrue("'$text' top ${b.top} inside console ${console.top}", b.top >= console.top - 0.5f)
            assertTrue("'$text' bottom ${b.bottom} inside console ${console.bottom}", b.bottom <= console.bottom + 0.5f)
        }
    }

    private val hostKeyPrompt = PromptRequest.HostKeyFingerprintPrompt(
        hostname = "100.100.1.1",
        keyType = "Ed25519",
        keySize = 256,
        serverHostKey = byteArrayOf(),
        randomArt = "art",
        bubblebabble = "babble",
        sha256 = "SHA256:abc",
        md5 = "md5",
    )

    private val passwordPrompt = PromptRequest.StringPrompt(
        instructions = null,
        hint = "Password",
        isPassword = true,
    )

    @Test
    fun hostKeyPrompt_herdr_keyboardUp_fitsWithoutBar() {
        showPrompt("herdr\n", hostKeyPrompt, KEYBOARD_UP)
        assertPromptOnScreenWithoutBar(
            string(R.string.prompt_continue_connecting),
            string(R.string.button_yes),
            string(R.string.button_no),
        )
    }

    @Test
    fun hostKeyPrompt_plain_keyboardUp_fitsWithoutBar() {
        showPrompt(null, hostKeyPrompt, KEYBOARD_UP)
        assertPromptOnScreenWithoutBar(
            string(R.string.prompt_continue_connecting),
            string(R.string.button_yes),
            string(R.string.button_no),
        )
    }

    @Test
    fun hostKeyPrompt_landscape_answersStayOnScreen_questionScrollsIntoView() {
        showPrompt("herdr\n", hostKeyPrompt, LANDSCAPE)
        assertPromptOnScreenWithoutBar(string(R.string.button_yes), string(R.string.button_no))
        composeTestRule.onNodeWithText(string(R.string.prompt_continue_connecting)).performScrollTo()
        assertPromptOnScreenWithoutBar(
            string(R.string.prompt_continue_connecting),
            string(R.string.button_yes),
            string(R.string.button_no),
        )
    }

    @Test
    fun passwordPrompt_plain_keyboardUp_fitsWithoutBar() {
        showPrompt(null, passwordPrompt, KEYBOARD_UP)
        assertPromptOnScreenWithoutBar("Password", string(R.string.button_ok), string(R.string.delete_neg))
    }

    @Test
    fun passwordPrompt_herdr_landscape_fitsWithoutBar() {
        showPrompt("herdr\n", passwordPrompt, LANDSCAPE)
        assertPromptOnScreenWithoutBar("Password", string(R.string.button_ok), string(R.string.delete_neg))
    }

    @Test
    fun yesNoPrompt_herdr_keyboardUp_fitsWithoutBar() {
        showPrompt("herdr\n", PromptRequest.BooleanPrompt(null, "Accept?"), KEYBOARD_UP)
        assertPromptOnScreenWithoutBar("Accept?", string(R.string.button_yes), string(R.string.button_no))
    }

    @Test
    fun barReturnsAfterPrompt_andTerminalPaddingNeverChanges() {
        val prompt = mutableStateOf<PromptRequest?>(null)
        setContent(bridge("herdr\n"), prompt, KEYBOARD_UP)
        composeTestRule.onNodeWithTag(BAR).assertIsDisplayed()
        val measured = barHeightPx
        assertTrue("bar height measured", measured > 0)

        prompt.value = PromptRequest.BooleanPrompt(null, "Accept?")
        composeTestRule.waitForIdle()
        assertPromptOnScreenWithoutBar("Accept?")
        assertEquals("terminal keeps the bar's height while a prompt is up", measured, barHeightPx)

        prompt.value = null
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(BAR).assertIsDisplayed()
        composeTestRule.onNodeWithText("Esc").assertIsDisplayed()
        assertEquals(measured, barHeightPx)
    }

    @Test
    fun trayOpen_terminalPaddingGrowsByTheTray_soTheTerminalMovesUp() {
        setContent(bridge("herdr\n"), mutableStateOf(null), 891.dp)
        val measured = barHeightPx
        assertTrue("bar height measured", measured > 0)

        composeTestRule.onNodeWithContentDescription("More keys").performClick()
        composeTestRule.waitForIdle()

        val tray = composeTestRule.onNodeWithTag(TRAY_TEST_TAG).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val bar = composeTestRule.onNodeWithTag(BAR).fetchSemanticsNode().boundsInRoot
        assertEquals("terminal padding grows by the tray", measured + tray.height, barHeightPx.toFloat(), 1f)
        assertEquals("tray is the top of the bar", bar.top, tray.top, 0.5f)
        composeTestRule.onNodeWithText("Mode").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("More keys").performClick()
        composeTestRule.waitForIdle()
        assertEquals("closing gives the height back", measured, barHeightPx)
    }

    @Test
    fun twoRowBar_isShorterThanTheFourRowBar() {
        setContent(bridge("herdr\n"), mutableStateOf(null), 891.dp)
        val heightDp = barHeightPx / composeTestRule.density.density
        // Before the tray the Herdr bar stacked four rows (about 245dp on a 411dp phone).
        assertTrue("two-row bar is $heightDp dp", heightDp < 130f)
    }

    private companion object {
        const val BAR = "composeBar"
        const val CONSOLE = "console"

        /** Console height on the emulator with the soft keyboard up (PR #40 dump 01). */
        val KEYBOARD_UP = 460.dp

        /** A landscape console, shorter than the host-key prompt's full content. */
        val LANDSCAPE = 200.dp
    }
}
