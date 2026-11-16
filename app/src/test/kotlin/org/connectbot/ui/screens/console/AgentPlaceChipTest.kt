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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import solutions.ormus.logos.terminal.AgentSwipe

/** The chip in a watched console's title: where this terminal is among the agents a swipe moves between. */
@RunWith(AndroidJUnit4::class)
class AgentPlaceChipTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(place: AgentSwipe.Place?) {
        rule.setContent { ConnectBotTheme { if (place != null) AgentPlaceChip(place) } }
        rule.waitForIdle()
    }

    @Test
    fun theChip_saysThePlace_3of9() {
        show(AgentSwipe.Place(index = 2, total = 9, previous = null, next = null))

        rule.onNodeWithTag(AGENT_PLACE_TAG).assertIsDisplayed()
        // The screen reader's node carries the words; the text under it is drawn, not a node of its own.
        rule.onNodeWithContentDescription("Agent 3 of 9").assertIsDisplayed()
    }

    @Test
    fun theChip_isOneNodeForAScreenReader_namedAgent3of9_notABareText() {
        show(AgentSwipe.Place(index = 2, total = 9, previous = null, next = null))

        rule.onAllNodesWithContentDescription("Agent 3 of 9", useUnmergedTree = true).assertCountEquals(1)
        rule.onAllNodesWithText("3 of 9", useUnmergedTree = true).assertCountEquals(0)
    }

    @Config(qualifiers = "+es")
    @Test
    fun inSpanish_theChipIsNamedInSpanish() {
        show(AgentSwipe.Place(index = 2, total = 9, previous = null, next = null))
        rule.onNodeWithContentDescription("Agente 3 de 9").assertExists()
    }

    @Config(qualifiers = "+es")
    @Test
    fun inSpanish_nextAgent() {
        showHint(AgentSwipe.Hint.NEXT, fired = 1)
        rule.onNodeWithText("Siguiente agente", ignoreCase = true).assertIsDisplayed()
    }

    @Config(qualifiers = "+es")
    @Test
    fun inSpanish_previousAgent() {
        showHint(AgentSwipe.Hint.PREVIOUS, fired = 1)
        rule.onNodeWithText("Agente anterior", ignoreCase = true).assertIsDisplayed()
    }

    @Config(qualifiers = "+es")
    @Test
    fun inSpanish_noMoreAgents() {
        showHint(AgentSwipe.Hint.NONE_LEFT, fired = 1)
        rule.onNodeWithText("No hay más agentes", ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun theChip_showsWhenThereAreTwoOrMore_notForALoneTerminal() {
        assertFalse(AgentSwipe.Place(index = 0, total = 1, previous = null, next = null).showsChip)
        assertTrue(AgentSwipe.Place(index = 0, total = 2, previous = null, next = null).showsChip)
    }

    @Test
    fun thePlace_numbersFromOne() {
        assertEquals(1, AgentSwipe.Place(index = 0, total = 2, previous = null, next = null).number)
        assertEquals(9, AgentSwipe.Place(index = 8, total = 9, previous = null, next = null).number)
    }

    private fun showHint(hint: AgentSwipe.Hint?, fired: Int) {
        rule.setContent { ConnectBotTheme { AgentSwipeHint(hint, fired) } }
        rule.waitForIdle()
    }

    @Test
    fun theHint_showsAfterASwipe_inWords() {
        showHint(AgentSwipe.Hint.NEXT, fired = 1)
        rule.onNodeWithTag(AGENT_HINT_TAG).assertIsDisplayed()
        rule.onNodeWithText("Next agent", ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun theHint_saysNoMoreAgentsAtTheEnd() {
        showHint(AgentSwipe.Hint.NONE_LEFT, fired = 1)
        rule.onNodeWithText("No more agents", ignoreCase = true).assertIsDisplayed()
    }

    @Test
    fun theHint_isNotThereBeforeAnySwipe() {
        showHint(null, fired = 0)
        rule.onNodeWithTag(AGENT_HINT_TAG).assertDoesNotExist()
    }
}
