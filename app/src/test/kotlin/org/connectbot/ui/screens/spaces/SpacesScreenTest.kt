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
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.CoreSpaces
import solutions.ormus.logos.herd.CoreSpaces.PaneBox

/** The Spaces list on synthetic spaces: a header per machine, a row per space, a box per tab. */
@RunWith(AndroidJUnit4::class)
class SpacesScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val opened = mutableListOf<Pair<String, String>>()

    private fun pane(terminal: String?) = PaneBox.Pane(terminal, 1f)

    private fun tab(id: String, name: String, vararg terminals: String?) = CoreSpaces.CoreTab(id, name, PaneBox.Split(false, terminals.map(::pane), 1f))

    private fun space(name: String, vararg tabs: CoreSpaces.CoreTab) = CoreSpaces.CoreSpace("id-$name", name, "", tabs.toList())

    private fun show(vararg sections: SpacesSection) {
        composeTestRule.setContent {
            ConnectBotTheme {
                SpacesContent(sections = sections.toList(), onOpenTab = { machine, terminal -> opened += machine to terminal })
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

        assertEquals(listOf("box" to "t8"), opened)
    }

    @Test
    fun aTabWithNoTerminalOpensNothing() {
        show(SpacesSection(1L, "box", CoreSpaces.Result.Spaces(listOf(space("alpha · ONE", tab("a", "mod", null))))))

        composeTestRule.onAllNodesWithTag(TAB_BOX_TAG)[0].performClick()

        assertEquals(emptyList<Pair<String, String>>(), opened)
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
}
