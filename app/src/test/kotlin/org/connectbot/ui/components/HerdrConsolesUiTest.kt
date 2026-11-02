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
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableStateFlow
import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrCommands
import solutions.ormus.logos.herd.HerdrConsole

/**
 * The consoles of one Herdr host in the session drawer. Synthetic host and workspace
 * names only.
 */
@RunWith(AndroidJUnit4::class)
class HerdrConsolesUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val box = Host(id = 7, nickname = "box", username = "me", hostname = "box.test")

    // An extra console's Herdr side shows its own session from the start, as TerminalBridge's does.
    private fun console(id: Long, target: HerdrConsole?): TerminalBridge {
        val herdSide = mock<HerdSession> { on { attached } doReturn MutableStateFlow(target?.session ?: HerdrCommands.DEFAULT_SESSION) }
        return mock {
            on { host } doReturn box
            on { consoleId } doReturn id
            on { console } doReturn target
            on { herd } doReturn herdSide
            on { activityPreview } doReturn MutableStateFlow("")
        }
    }

    private fun drawer(bridges: List<TerminalBridge>, onSelect: (TerminalBridge) -> Unit = {}) {
        rule.setContent {
            ConnectBotTheme {
                SessionDrawer(
                    bridges = bridges,
                    activeBridge = bridges.first(),
                    onSelect = onSelect,
                    onDisconnect = {},
                    onQuickConnect = {},
                    onOpenHostList = {},
                )
            }
        }
    }

    @Test
    fun drawer_aHostsConsolesAreOneRowEach_namedBySessionAndWorkspace() {
        val first = console(1, null)
        val extra = console(2, HerdrConsole("beta", "w3", "gamma"))
        val selected = mutableListOf<TerminalBridge>()
        drawer(listOf(first, extra), onSelect = { selected += it })

        rule.onNodeWithText("box").assertExists()
        rule.onNodeWithText("box · beta · gamma").performClick()
        assertEquals(listOf(extra), selected)
    }
}
