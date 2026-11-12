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

package org.connectbot.ui.screens.spaces

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.CoreSpaces
import solutions.ormus.logos.herd.CoreSpaces.PaneBox

/** A tab opened in Spaces logs its usage id. */
@RunWith(AndroidJUnit4::class)
class SpacesUsageTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val logged = mutableListOf<String>()
    private val opened = mutableListOf<Long>()

    private fun tab(id: String, terminal: String?) = CoreSpaces.CoreTab(id, "main", PaneBox.Split(false, listOf(PaneBox.Pane(terminal, 1f)), 1f))

    private fun show(vararg tabs: CoreSpaces.CoreTab) {
        val section = SpacesSection(1L, "devbox", CoreSpaces.Result.Spaces(listOf(CoreSpaces.CoreSpace("id-a", "alpha · ONE", "", tabs.toList()))))
        composeTestRule.setContent {
            ConnectBotTheme {
                CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                    SpacesContent(sections = listOf(section), onOpenTab = { hostId, _ -> opened += hostId })
                }
            }
        }
    }

    @Test
    fun aTabTap_logsOpenTab_andStillOpensIt() {
        show(tab("a", "t7"))
        composeTestRule.onAllNodesWithTag(TAB_BOX_TAG)[0].performClick()
        assertEquals(listOf(UsageActions.SPACES_OPEN_TAB), logged)
        assertEquals(listOf(1L), opened)
    }
}
