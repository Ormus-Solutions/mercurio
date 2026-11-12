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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import solutions.ormus.logos.herd.CoreWatchTarget
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrKeys

/** The bar of a console that watches a core terminal has no key that writes the Herdr TUI prefix. */
@RunWith(AndroidJUnit4::class)
class CoreViewBarTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun bridge(watch: CoreWatchTarget?): TerminalBridge {
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.host).thenReturn(Host(nickname = "box", postLogin = "herdr\n"))
        `when`(bridge.watch).thenReturn(watch)
        val herd = mock(HerdSession::class.java)
        `when`(herd.snapshot).thenReturn(MutableStateFlow(null))
        `when`(herd.result).thenReturn(MutableStateFlow(null))
        `when`(herd.failure).thenReturn(MutableStateFlow(null))
        `when`(herd.sessions).thenReturn(MutableStateFlow(listOf("default")))
        `when`(herd.chosen).thenReturn(MutableStateFlow("default"))
        `when`(herd.others).thenReturn(MutableStateFlow(emptyMap()))
        `when`(herd.attached).thenReturn(MutableStateFlow("default"))
        `when`(herd.keys).thenReturn(MutableStateFlow(HerdrKeys.DEFAULT))
        `when`(bridge.herd).thenReturn(herd)
        return bridge
    }

    private fun setBar(bridge: TerminalBridge) {
        composeTestRule.setContent {
            ConnectBotTheme {
                Box(Modifier.width(411.dp).fillMaxSize()) {
                    TerminalComposeBar(bridge = bridge, modifier = Modifier.align(Alignment.BottomCenter), onRead = {}, onHerdrPanelChange = {})
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun aCoreWatch_hasNoHerdrChatOrNeedsYouKey() {
        setBar(bridge(CoreWatchTarget("box/t1", "alpha · ONE", "build")))

        for (label in listOf("Herdr", "Chat", "Needs you")) {
            assertEquals(label, 0, composeTestRule.onAllNodesWithText(label).fetchSemanticsNodes().size)
        }
        composeTestRule.onNodeWithText("PgUp").assertIsDisplayed()
    }

    @Test
    fun anOrdinaryConsole_keepsThoseKeys() {
        setBar(bridge(null))

        for (label in listOf("Herdr", "Chat", "Needs you")) {
            composeTestRule.onNodeWithText(label).assertIsDisplayed()
        }
    }
}
