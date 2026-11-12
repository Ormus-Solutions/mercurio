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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.AgentState
import solutions.ormus.logos.herd.CoreSpaces
import solutions.ormus.logos.herd.CoreSpaces.PaneBox
import solutions.ormus.logos.herd.CoreWatchTarget

/** The Spaces list on synthetic spaces: a header per machine, a row per space, a box per tab. */
@RunWith(AndroidJUnit4::class)
class SpacesScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<Pair<Long, CoreWatchTarget>>()

    private fun pane(terminal: String?) = PaneBox.Pane(terminal, 1f)

    private fun tab(id: String, name: String, vararg terminals: String?) = CoreSpaces.CoreTab(id, name, PaneBox.Split(false, terminals.map(::pane), 1f))

    private fun space(name: String, vararg tabs: CoreSpaces.CoreTab) = CoreSpaces.CoreSpace("id-$name", name, "", tabs.toList())

    private fun show(vararg sections: SpacesSection) {
        composeTestRule.setContent {
            ConnectBotTheme {
                SpacesContent(sections = sections.toList(), onOpenTab = { hostId, target -> opened += hostId to target })
            }
        }
    }

    private val box = SpacesSection(
        1L,
        "box",
        CoreSpaces.Result.Spaces(
            listOf(
                space("alpha · ONE", tab("a", "main", "t7"), tab("b", "work", "t8", "t9")),
                space("beta · TWO", tab("c", "", "t10")),
            ),
        ),
    )

    @Test
    fun aHeaderPerMachine_aRowPerSpace_aBoxPerTab() {
        show(box, SpacesSection(2L, "other", CoreSpaces.Result.Spaces(listOf(space("gamma · THREE", tab("d", "x", "t1"))))))

        composeTestRule.onNodeWithText("box").assertIsDisplayed()
        composeTestRule.onNodeWithText("other").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(SPACE_ROW_TAG).assertCountEquals(3)
        composeTestRule.onAllNodesWithTag(TAB_BOX_TAG).assertCountEquals(4)
        composeTestRule.onNodeWithText("alpha · ONE").assertIsDisplayed()
    }

    @Test
    fun theTileCarriesTheGroupLetter() {
        show(box)

        composeTestRule.onNodeWithText("A").assertIsDisplayed()
        composeTestRule.onNodeWithText("B").assertIsDisplayed()
    }

    @Test
    fun aTapOnATabOpensItsFirstTerminalOnThatMachine() {
        show(box)

        composeTestRule.onAllNodesWithTag(TAB_BOX_TAG)[1].performClick()

        assertEquals(listOf(1L to CoreWatchTarget("t8", "alpha · ONE", "work")), opened)
    }

    @Test
    fun aTabWithNoTerminalOpensNothing() {
        show(SpacesSection(1L, "box", CoreSpaces.Result.Spaces(listOf(space("alpha · ONE", tab("a", "mod", null))))))

        composeTestRule.onAllNodesWithTag(TAB_BOX_TAG)[0].performClick()

        assertEquals(emptyList<Pair<Long, CoreWatchTarget>>(), opened)
    }

    @Test
    fun aMachineThatCannotBeReadShowsWhy() {
        show(SpacesSection(1L, "box", CoreSpaces.Result.Unavailable("herdr-core is not running")))

        composeTestRule.onNodeWithText("herdr-core is not running").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(SPACE_ROW_TAG).assertCountEquals(0)
    }

    @Test
    fun aMachineWithNoSpacesSaysSo() {
        show(SpacesSection(1L, "box", CoreSpaces.Result.Spaces(emptyList())))

        composeTestRule.onNodeWithText("No spaces").assertIsDisplayed()
    }

    @Test
    fun noTerminalTextAppears() {
        show(box)

        // The boxes are drawn from the layout alone: nothing of a terminal but its address is in the model.
        composeTestRule.onNodeWithText("t7").assertDoesNotExist()
    }

    @Test
    fun twoMachinesWithOneNicknameBothShow() {
        show(SpacesSection(1L, "box", CoreSpaces.Result.Spaces(emptyList())), SpacesSection(2L, "box", CoreSpaces.Result.Spaces(emptyList())))

        composeTestRule.onAllNodesWithText("No spaces").assertCountEquals(2)
    }

    @Test
    fun aTabWithManyPanesStillDraws() {
        show(SpacesSection(1L, "box", CoreSpaces.Result.Spaces(listOf(space("alpha · ONE", tab("a", "wide", *Array(60) { "t$it" }))))))

        composeTestRule.onAllNodesWithTag(TAB_BOX_TAG).assertCountEquals(1)
    }

    @Test
    fun aTileShowsItsTabName() {
        show(box)

        composeTestRule.onNodeWithText("main", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("work", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aLongNameIsEllipsizedWithinTwoLines() {
        val long = "release-candidate-verification-and-signoff-run for the whole fleet"
        show(SpacesSection(1L, "box", CoreSpaces.Result.Spaces(listOf(space("alpha · ONE", tab("a", long, "t7"))))))

        val results = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithTag(TAB_NAME_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertTrue(results.single().lineCount <= 2)
        assertEquals(2, results.single().layoutInput.maxLines)
        assertEquals(TextOverflow.Ellipsis, results.single().layoutInput.overflow)
    }

    @Test
    fun aTabWithANeedsYouAgentCarriesThatState() {
        val doc = SpacesSection(
            1L,
            "box",
            CoreSpaces.Result.Spaces(listOf(space("alpha · ONE", tab("a", "main", "t7"), tab("b", "work", "t8", "t9")))),
            states = mapOf("t9" to AgentState.NeedsYou(), "t8" to AgentState.Working),
        )
        show(doc)

        val tiles = composeTestRule.onAllNodesWithTag(TAB_BOX_TAG)
        tiles[1].assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Needs you"))
        tiles[1].assert(SemanticsMatcher("description names the state") { n -> n.config[SemanticsProperties.ContentDescription].any { it.endsWith(", Needs you") } })
        assertFalse(tiles[0].fetchSemanticsNode().config.contains(SemanticsProperties.StateDescription))
    }

    @Test
    fun theMostUrgentKnownStateWinsInTheInboxOrder() {
        val one = tab("a", "x", "t1")
        val states = listOf(AgentState.NeedsYou(), AgentState.Done, AgentState.Working, AgentState.Idle)
        // Every pair of the four: the earlier in the list wins.
        for (i in states.indices) {
            for (j in i + 1 until states.size) {
                val two = tab("b", "y", "t1", "t2")
                assertEquals(states[i], tabState(two, mapOf("t1" to states[j], "t2" to states[i])))
                assertEquals(states[i], tabState(two, mapOf("t1" to states[i], "t2" to states[j])))
            }
        }
        assertEquals(AgentState.Idle, tabState(one, mapOf("t1" to AgentState.Idle)))
    }

    @Test
    fun anUnknownOrMissingStateIsSkipped() {
        val two = tab("b", "y", "t1", "t2")
        assertEquals(AgentState.Working, tabState(two, mapOf("t1" to AgentState.Unknown, "t2" to AgentState.Working)))
        assertEquals(null, tabState(two, mapOf("t1" to AgentState.Unknown)))
        assertEquals(null, tabState(two, emptyMap()))
    }

    @Test
    fun everyStateHasItsOwnMark() {
        assertEquals(TabMark.ALERT, tabMark(AgentState.NeedsYou()))
        assertEquals(TabMark.FILLED, tabMark(AgentState.Done))
        assertEquals(TabMark.RING, tabMark(AgentState.Working))
        assertEquals(TabMark.HOLLOW, tabMark(AgentState.Idle))
        assertEquals(4, listOf(AgentState.NeedsYou(), AgentState.Done, AgentState.Working, AgentState.Idle).map(::tabMark).toSet().size)
    }

    @Test
    fun aNeedsYouTileDrawsABangAndANeutralTileDoesNot() {
        show(
            SpacesSection(
                1L,
                "box",
                CoreSpaces.Result.Spaces(listOf(space("alpha · ONE", tab("a", "main", "t7"), tab("b", "work", "t8")))),
                states = mapOf("t7" to AgentState.NeedsYou()),
            ),
        )

        composeTestRule.onAllNodesWithText("!", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun atDoubleFontScaleTheTileGrowsToHoldTheName() {
        val long = "alpha\nbeta"
        composeTestRule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                ConnectBotTheme {
                    SpacesContent(sections = listOf(SpacesSection(1L, "box", CoreSpaces.Result.Spaces(listOf(space("alpha · ONE", tab("a", long, "t7")))))), onOpenTab = { _, _ -> })
                }
            }
        }

        val tile = composeTestRule.onNodeWithTag(TAB_BOX_TAG).fetchSemanticsNode().size.height
        // Two lines at 10sp x 2.0 are 40dp tall; a tile fixed at 38dp would clip them.
        val twoLines = with(composeTestRule.density) { 40.dp.toPx() }
        assertTrue("tile $tile must hold two scaled lines ($twoLines)", tile >= twoLines)
    }
}
