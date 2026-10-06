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

package org.connectbot.ui.screens.herd

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.parseHerdrSnapshot

/**
 * The Herd screen rendered from `herdr api snapshot` recorded on Sun
 * (snapshot-sun-mixed.json: the recording with five agents' states changed to
 * cover blocked, done and working).
 */
@RunWith(AndroidJUnit4::class)
class HerdScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val snapshot = checkNotNull(
        parseHerdrSnapshot(
            checkNotNull(javaClass.getResourceAsStream("/herdr/snapshot-sun-mixed.json")).readBytes().decodeToString(),
        ),
    )

    @Test
    fun cards_readInOrderBlockedDoneWorkingIdle_newestFirst() {
        composeTestRule.setContent {
            HerdScreen(snapshot = snapshot, onOpenAgent = {}, onRefresh = {}, onOpenCommands = {}, onClose = {})
        }

        val cards = composeTestRule.onAllNodesWithTag(HERD_CARD_TAG)
        val expected = listOf(
            "Needs you" to "Three flaky tests in the parser suite wi", // w1:pJ, seq 10
            "Needs you" to "Lint setup, two config files for the CI", // w1:pC, seq 9
            "Done" to "New theme created but not applied on the", // w1:pH, seq 11
            "Working" to "Billing export review and CSV header fix", // w1:pK, seq 13
            "Working" to "Docs site build, broken links in the nav", // w1:pB, seq 12
            "Idle" to "Login form layout pass and its PR review", // w1:pF, seq 14
        )
        cards.fetchSemanticsNodes().let { assertEquals(expected.size, it.size) }
        expected.forEachIndexed { index, (status, title) ->
            cards[index]
                .assertTextContains(status)
                .assertTextContains(title, substring = true)
                .assertTextContains("grok")
                .assertTextContains("~Demo / ", substring = true)
        }
    }

    @Test
    fun header_countsAgentsPerState() {
        composeTestRule.setContent {
            HerdScreen(snapshot = snapshot, onOpenAgent = {}, onRefresh = {}, onOpenCommands = {}, onClose = {})
        }

        composeTestRule.onNodeWithTag(HERD_COUNTS_TAG)
            .assertTextContains("2 need you · 1 done · 2 working · 1 idle")
    }

    @Test
    fun tappingACard_opensThatAgent() {
        var opened: String? = null
        val logged = mutableListOf<String>()
        composeTestRule.setContent {
            CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                HerdScreen(
                    snapshot = snapshot,
                    onOpenAgent = { opened = it.paneId },
                    onRefresh = {},
                    onOpenCommands = {},
                    onClose = {},
                )
            }
        }

        composeTestRule.onAllNodesWithTag(HERD_CARD_TAG)[2].performClick()

        assertEquals("w1:pH", opened)
        assertEquals(listOf(UsageActions.HERD_CARD), logged)
    }

    @Test
    fun beforeTheFirstPoll_saysItIsReading() {
        composeTestRule.setContent {
            HerdScreen(snapshot = null, onOpenAgent = {}, onRefresh = {}, onOpenCommands = {}, onClose = {})
        }

        composeTestRule.onNodeWithTag(HERD_COUNTS_TAG).assertTextContains("Reading agents from Herdr…")
        assertEquals(0, composeTestRule.onAllNodesWithTag(HERD_CARD_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun withoutAPushApp_saysToInstallNtfy_andOtherwiseSaysNothing() {
        var pushOff by mutableStateOf(true)
        composeTestRule.setContent {
            HerdScreen(snapshot = snapshot, onOpenAgent = {}, onRefresh = {}, onOpenCommands = {}, onClose = {}, pushOff = pushOff)
        }

        composeTestRule.onNodeWithTag(HERD_PUSH_OFF_TAG).assertTextContains("Install ntfy to get alerts while Mercurio sleeps.")
        pushOff = false
        composeTestRule.onNodeWithTag(HERD_PUSH_OFF_TAG).assertDoesNotExist()
    }
}
