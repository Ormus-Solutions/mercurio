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

package org.connectbot.ui.screens.inbox

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import solutions.ormus.logos.herd.Agent
import solutions.ormus.logos.herd.AgentEngine
import solutions.ormus.logos.herd.AgentState
import solutions.ormus.logos.herd.CoreSource
import solutions.ormus.logos.herd.Inbox
import solutions.ormus.logos.herd.InboxRow
import solutions.ormus.logos.herd.RowAction

/**
 * The star of an Inbox row at the width of a phone (360 dp), with the real text measure (native graphics): the touch
 * target is a 48 dp square (a tap in its corners pins and opens nothing), and the star costs the text of the row at most
 * 9 dp. Synthetic rows. The widths below were measured on `main` 50badc7d (the base, no star) and on the star's head,
 * same rows, tab "Dev", an age of 12 minutes (`~/work/.b2-tmp/herd-star/widths-summary.txt`): a 16-character space name is
 * 141.5 dp whole in both, at font 1.3 176.0 dp whole in both; a 30-character one is cut at 215.0 dp on the base and 206.0 dp
 * on the head at font 1.0, and at 208.5 and 206.0 dp at font 1.3. The thresholds sit close to the head's widths, so a loss
 * of more than a few dp fails.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class InboxRowStarLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = 10_000_000L
    private val space = "alpha · MERCURIO"

    private fun core(address: String, space: String = this.space, tab: String = "Dev", parent: String? = null, state: AgentState = AgentState.Working, pinned: Boolean = false, unread: Boolean? = null) = Agent(
        address = address,
        engine = AgentEngine.CORE,
        machine = "box",
        space = space,
        tab = tab,
        kind = "claude-code",
        state = state,
        since = now - 12 * 60_000L,
        turn = 3,
        unread = unread,
        pinned = pinned,
        parent = parent,
    )

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.first()
    }

    private val opened = mutableListOf<InboxRow>()
    private val acted = mutableListOf<Pair<InboxRow, RowAction>>()

    private fun show(agents: List<Agent>, fontScale: Float = 1f) {
        val rows = Inbox.fromCore(1L, "box", CoreSource.Result.Agents(agents)).rows
        rule.setContent {
            ConnectBotTheme {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                    InboxContent(rows = rows, notes = emptyList(), now = now, onOpen = { opened += it }, onAction = { r, a -> acted += r to a })
                }
            }
        }
        rule.waitForIdle()
    }

    private fun dp(px: Number) = px.toFloat() / rule.density.density

    private fun shownDp(text: String): Float = dp(rule.onAllNodesWithText(text, useUnmergedTree = true)[0].fetchSemanticsNode().size.width)

    private fun cut(text: String): Boolean = layoutOf(rule.onAllNodesWithText(text, useUnmergedTree = true)[0].fetchSemanticsNode()).isLineEllipsized(0)

    private fun star(i: Int = 0) = rule.onAllNodesWithTag(INBOX_STAR_TAG, useUnmergedTree = true)[i].fetchSemanticsNode()

    private fun row(i: Int = 0) = rule.onAllNodesWithTag(INBOX_ROW_TAG)[i].fetchSemanticsNode()

    @Test
    fun theStar_isAtLeast48dpSquare_andSitsAtTheRightEdgeOfTheRow() {
        show(listOf(core("t1")))
        val s = star()
        assertTrue("star width ${dp(s.size.width)} dp", dp(s.size.width) >= 47.9f)
        assertTrue("star height ${dp(s.size.height)} dp", dp(s.size.height) >= 47.9f)
        val r = row()
        assertEquals("the star ends at the row's edge", r.boundsInRoot.right, s.boundsInRoot.right, 1f)
        assertTrue("the star is inside the row", s.boundsInRoot.top >= r.boundsInRoot.top - 1f && s.boundsInRoot.bottom <= r.boundsInRoot.bottom + 1f)
    }

    @Test
    fun theRow_growsByThreeDpAtMost_forTheTarget() {
        show(listOf(core("t1")))
        assertTrue("row height ${dp(row().size.height)} dp (61 dp before)", dp(row().size.height) <= 64.5f)
    }

    @Test
    fun aSpaceNameOf16Characters_stillReadsWhole_besideTheStar() {
        show(listOf(core("t1")))
        assertFalse("space cut at ${shownDp(space)} dp", cut(space))
        assertTrue("space shown ${shownDp(space)} dp (141.5 on the base)", shownDp(space) >= 141f)
    }

    @Test
    fun aSpaceNameOf30Characters_keepsAtLeast200dp() {
        val long = "ormus · MERCURIO DEVELOPMENT"
        show(listOf(core("t1", space = long)))
        assertTrue("space shown ${shownDp(long)} dp (215.0 on the base, 206.0 with the star)", shownDp(long) >= 200f)
    }

    @Test
    fun aSpaceNameOf30Characters_atFont13_keepsAtLeast200dp() {
        val long = "ormus · MERCURIO DEVELOPMENT"
        show(listOf(core("t1", space = long)), fontScale = 1.3f)
        assertTrue("space shown ${shownDp(long)} dp (208.5 on the base, 206.0 with the star)", shownDp(long) >= 200f)
    }

    @Test
    fun aTabOf11Characters_leavesTheSpaceNameWhole() {
        show(listOf(core("t1", tab = "Gate: tests")))
        assertFalse("space cut at ${shownDp(space)} dp", cut(space))
        assertTrue("space shown ${shownDp(space)} dp (141.5 on the base)", shownDp(space) >= 141f)
        assertFalse("the tab is drawn whole", cut("Gate: tests"))
    }

    @Test
    fun aParentWithFiveHelpers_keepsItsSpaceNameWhole() {
        show(listOf(core("t1")) + (2..6).map { core("t$it", tab = "H$it", parent = "t1") })
        assertFalse("space cut at ${shownDp(space)} dp", cut(space))
        assertTrue("space shown ${shownDp(space)} dp (141.5 on the base)", shownDp(space) >= 141f)
    }

    @Test
    fun aPinnedUnreadRow_showsAFilledStarInPlaceOfTheOldPin_andKeepsItsSpaceNameWhole() {
        show(listOf(core("t1", pinned = true, unread = true)))
        assertEquals(1, rule.onAllNodesWithTag(INBOX_PINNED_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertFalse("space cut at ${shownDp(space)} dp", cut(space))
        assertTrue("space shown ${shownDp(space)} dp (141.5 on the base, with the old 16 dp pin)", shownDp(space) >= 141f)
    }

    @Test
    fun atALargeFont_theTargetIsStill48dp_andTheSpaceNameStaysWhole() {
        show(listOf(core("t1")), fontScale = 1.3f)
        assertTrue("star ${dp(star().size.width)} x ${dp(star().size.height)} dp", dp(star().size.width) >= 47.9f && dp(star().size.height) >= 47.9f)
        assertFalse("space cut at ${shownDp(space)} dp", cut(space))
        assertTrue("space shown ${shownDp(space)} dp (176.0 on the base)", shownDp(space) >= 175f)
    }

    @Test
    fun aPinnedUnreadRow_atFont13_keepsItsSpaceNameWhole() {
        show(listOf(core("t1", pinned = true, unread = true)), fontScale = 1.3f)
        assertFalse("space cut at ${shownDp(space)} dp (164.5 dp, cut, on the base)", cut(space))
    }

    // L1 of the gate lane's review: the star was an IconButton, whose touch area is a circle, so the corners of its 48 dp
    // square fell to the row and opened the terminal.
    @Test
    fun aTapInAnyCornerOfTheStar_pinsTheRow_andOpensNothing() {
        show(listOf(core("t1")))
        val target = star().boundsInRoot
        val d = rule.density.density
        listOf(2f to 2f, 46f to 2f, 2f to 46f, 46f to 46f).forEach { (dx, dy) ->
            rule.onRoot().performTouchInput { click(Offset(target.left + dx * d, target.top + dy * d)) }
            rule.waitForIdle()
        }
        assertEquals("the four taps pinned: acted=${acted.map { it.second }}, opened=${opened.size}", List(4) { RowAction.PIN }, acted.map { it.second })
        assertEquals(emptyList<InboxRow>(), opened)
    }

    // L4: at a large font the age under the glyph met the star's lower points and reached the row's edge.
    private fun theAge_isClearOfTheStar_andOfTheRowsEdge(fontScale: Float) {
        show(listOf(core("t1", pinned = true)), fontScale = fontScale)
        val glyph = rule.onAllNodesWithTag(INBOX_PINNED_TAG, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val age = rule.onAllNodesWithText("12m", useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot
        val edge = row().boundsInRoot.right
        assertTrue("font $fontScale: the age's top ${dp(age.top)} dp is above the glyph's bottom ${dp(glyph.bottom)} dp", age.top >= glyph.bottom - 0.5f)
        assertTrue("font $fontScale: the age reaches ${dp(edge - age.right)} dp from the row's edge", edge - age.right >= 3f * rule.density.density)
        val target = star()
        assertTrue("font $fontScale: the star is ${dp(target.size.width)} x ${dp(target.size.height)} dp", dp(target.size.width) >= 47.9f && dp(target.size.height) >= 47.9f)
        assertTrue("font $fontScale: the age is inside the star's area", age.bottom <= target.boundsInRoot.bottom + 0.5f && age.top >= target.boundsInRoot.top - 0.5f)
    }

    @Test
    fun atFont13_theAgeIsClearOfTheStar_andOfTheRowsEdge() = theAge_isClearOfTheStar_andOfTheRowsEdge(1.3f)

    @Test
    fun atFont15_theAgeIsClearOfTheStar_andOfTheRowsEdge() = theAge_isClearOfTheStar_andOfTheRowsEdge(1.5f)

    @Test
    fun atFont20_theAgeIsClearOfTheStar_andOfTheRowsEdge() = theAge_isClearOfTheStar_andOfTheRowsEdge(2.0f)

    @Test
    fun aTuiRow_hasNoStar_andDrawsAsBefore() {
        val tui = Agent("a", AgentEngine.TUI, null, space, "Dev", null, null, AgentState.Idle, null)
        rule.setContent { ConnectBotTheme { InboxContent(rows = listOf(Inbox.row(1L, "box", tui)), notes = emptyList(), now = now, onOpen = {}) } }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodesWithTag(INBOX_STAR_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)
        assertFalse(cut(space))
        assertEquals("the row is 61 dp as before", 61f, dp(row().size.height), 0.6f)
    }
}
