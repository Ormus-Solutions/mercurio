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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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

/**
 * An Inbox row at the width of a phone (360 dp), with the real text measure (native graphics): the space
 * name keeps its width and the role under it is read, also on a parent row with the mark, the pin, the dot
 * and its helpers. Synthetic rows; the lengths of the roles are the ones measured on a real host
 * (10 to 60 characters, the median 25).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class InboxRowLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val now = 10_000_000L
    private val space = "alpha · MERCURIO"

    private fun core(address: String, role: String?, name: String? = null, parent: String? = null, state: AgentState = AgentState.Working, pinned: Boolean = false, unread: Boolean? = null) = Agent(
        address = address,
        engine = AgentEngine.CORE,
        machine = "box",
        space = space,
        tab = "Dev",
        kind = "claude-code",
        state = state,
        since = now - 12 * 60_000L,
        turn = 3,
        unread = unread,
        pinned = pinned,
        parent = parent,
        name = name,
        role = role,
    )

    /** What one text of the row came to: how wide it is drawn, how wide it would be alone, and whether it was cut. */
    private class Measure(val shownPx: Int, val wholePx: Int, val cut: Boolean)

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.first()
    }

    private fun measure(text: String): Measure {
        val node = rule.onAllNodesWithText(text, useUnmergedTree = true)[0].fetchSemanticsNode()
        val layout = layoutOf(node)
        return Measure(node.size.width, layout.multiParagraph.intrinsics.maxIntrinsicWidth.toInt(), layout.isLineEllipsized(0))
    }

    private fun show(agents: List<Agent>, fontScale: Float = 1f) {
        val rows = Inbox.fromCore(1L, "box", CoreSource.Result.Agents(agents)).rows
        rule.setContent {
            ConnectBotTheme {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                    InboxContent(rows = rows, notes = emptyList(), now = now, onOpen = {}, onNavigateBack = {})
                }
            }
        }
        rule.waitForIdle()
    }

    private fun dp(px: Int) = px / rule.density.density

    /**
     * [text] is drawn at its whole width. For a text that clips (no ellipsis) `cut` is always false, so only the width tells:
     * a mark squeezed to 20 dp has to turn this red.
     */
    private fun assertWhole(text: String) {
        val m = measure(text)
        assertTrue("\"$text\" is drawn ${dp(m.shownPx)} dp of ${dp(m.wholePx)} dp", m.shownPx >= m.wholePx)
    }

    private fun bounds(text: String) = rule.onAllNodesWithText(text, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot

    private fun markBounds() = rule.onAllNodesWithTag(INBOX_ORCHESTRATOR_TAG, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot

    /** The count and the mark do not sit on each other: the count is under the mark, or at its right. */
    private fun assertCountClearOfMark(count: String) {
        val c = bounds(count)
        val m = markBounds()
        assertTrue("the count ($c) sits on the mark ($m)", c.top >= m.bottom - 1f || c.left >= m.right)
    }

    /** [text] is drawn at least [minDp] wide; the message gives what was drawn. */
    private fun assertShown(text: String, minDp: Float) {
        val m = measure(text)
        assertTrue("\"$text\" is drawn ${dp(m.shownPx)} dp of ${dp(m.wholePx)} dp, wanted at least $minDp dp", dp(m.shownPx) >= minDp)
    }

    @Test
    fun aRoleOf28Characters_leavesTheSpaceNameWhole_andIsReadWhole() {
        val role = "Herd: Inbox, Spaces, notices"
        show(listOf(core("t1", role)))
        val spaceName = measure(space)
        assertFalse("space name cut at ${dp(spaceName.shownPx)} of ${dp(spaceName.wholePx)} dp", spaceName.cut)
        val roleName = measure(role)
        assertFalse("role cut at ${dp(roleName.shownPx)} of ${dp(roleName.wholePx)} dp", roleName.cut)
    }

    @Test
    fun theLongestRole_leavesTheSpaceNameWhole_andIsCutOnItsOwnLine() {
        val role = "Console: the terminal, the keys, the palette, the photo and the paste".take(60)
        show(listOf(core("t1", role)))
        assertFalse(measure(space).cut)
        val roleName = measure(role)
        assertTrue(roleName.cut)
        assertTrue("the role keeps a readable width: ${dp(roleName.shownPx)} dp", dp(roleName.shownPx) >= 200f)
    }

    @Test
    fun aParentWithTheMarkAndFiveHelpers_showsItsSpaceNameWhole_itsRole_andTheMarkUnderThem() {
        val helpers = (2..6).map { core("t$it", "Helper $it", parent = "t1") }
        show(listOf(core("t1", "Coordinator Day 2")) + helpers)
        assertFalse(measure(space).cut)
        assertFalse(measure("Coordinator Day 2").cut)
        val roleNode = rule.onAllNodesWithText("Coordinator Day 2", useUnmergedTree = true)[0].fetchSemanticsNode()
        val markNode = rule.onAllNodesWithTag(INBOX_ORCHESTRATOR_TAG, useUnmergedTree = true)[0].fetchSemanticsNode()
        assertTrue("the mark is under the role", markNode.boundsInRoot.top >= roleNode.boundsInRoot.bottom - 1f)
        assertWhole("Orchestrator")
        // The count has the room beside this role, so it stays there, at the right of the role's line.
        val count = bounds("5 helpers")
        assertTrue("the count is beside the role", count.top < roleNode.boundsInRoot.bottom && count.left >= roleNode.boundsInRoot.right)
    }

    @Test
    fun aParentWithAMarkOnItsNameAlone_showsItsSpaceNameWhole_andItsName() {
        val helpers = (2..6).map { core("t$it", null, name = "helper-$it", parent = "t1") }
        show(listOf(core("t1", null, name = "mdev-lead")) + helpers)
        assertFalse(measure(space).cut)
        assertFalse(measure("mdev-lead").cut)
    }

    @Test
    fun aParentThatSaysOrchestrator_withFiveHelpers_showsItsSpaceNameWhole() {
        val helpers = (2..6).map { core("t$it", "Helper $it", parent = "t1") }
        show(listOf(core("t1", "Orchestrator")) + helpers)
        assertFalse(measure(space).cut)
        assertFalse(measure("Orchestrator").cut)
        // A short role leaves the count its place beside it.
        val role = bounds("Orchestrator")
        val count = bounds("5 helpers")
        assertTrue("the count is beside the role", count.top < role.bottom && count.left >= role.right)
    }

    private val worstRole = "Stand-up coordinator"
    private val worstCount = "7 helpers · needs you"

    private fun worstParentRow(): List<Agent> {
        val helpers = (2..8).map { core("t$it", "Helper $it", parent = "t1", state = if (it == 2) AgentState.NeedsYou(null) else AgentState.Working) }
        return listOf(core("t1", worstRole, state = AgentState.Done, pinned = true, unread = true)) + helpers
    }

    @Test
    fun theWorstParentRow_withMarkPinDotAndSevenHelpersThatNeedYou_keepsItsSpaceNameWhole_andReadsItsRoleWhole() {
        // On the base this row drew no space name at all (0 of 142 dp): the dot, the pin and the long helper count took the line.
        // The count leaves the role 45 dp beside it, so it goes under the role: the role is read whole ("Sta…" before).
        show(worstParentRow())
        assertFalse(measure(space).cut)
        assertFalse(measure(worstRole).cut)
        assertWhole(worstRole)
        assertWhole("Orchestrator")
        assertWhole(worstCount)
        assertTrue("the count is under the role", bounds(worstCount).top >= bounds(worstRole).bottom - 1f)
        // The mark (96 dp) and the count (138.5 dp) do not fit one line of 187 dp: the count has a line of its own.
        assertCountClearOfMark(worstCount)
        assertTrue("the count is under the mark", bounds(worstCount).top >= markBounds().bottom - 1f)
    }

    @Test
    fun theWorstParentRow_atALargeFont_givesItsRoleTheWholeLine_andKeepsItsMark_andItsCountWhole() {
        // Round 2 of the review (R1): at font scale 1.3 the count is 174 dp wide in a 181 dp column, so beside the role it left
        // the role 0 dp. It goes under the role, and under the mark when the two do not fit on a line. On the base the role (188 dp)
        // was wider than the column and was cut at the line's end (180.5 dp). With the star in the place of the 16 dp pin icon the
        // column has room: the role is whole (188.0 dp, measured by the gate lane). The threshold stays the roles PR's.
        show(worstParentRow(), fontScale = 1.3f)
        assertShown(worstRole, 175f)
        assertWhole("Orchestrator")
        assertWhole(worstCount)
        assertTrue("the count is under the role", bounds(worstCount).top >= bounds(worstRole).bottom - 1f)
        assertCountClearOfMark(worstCount)
        // On the base the name was cut a little by the unread dot and the pin icon (164.5 of 176 dp). With the star it is whole
        // (176.0 dp, measured by the gate lane); the threshold stays the roles PR's, and it must not get worse.
        assertShown(space, 160f)
    }

    @Test
    fun theWorstParentRow_atFontScale20_cutsItsCountWithAnEllipsis_notInSilence() {
        // Round 3 (S1): at a large font the count is wider than the column. Clipped, "needs you" went with no sign of a cut. It was font 1.5 before the
        // star: the worst row is pinned, and on the base its count was cut there (159.5 of 182.0 dp). The star replaces the 16 dp pin icon, so at 1.5 the
        // count fits (182.0 dp); at 2.0 it is 243 dp, wider than the column.
        show(worstParentRow(), fontScale = 2.0f)
        val count = measure(worstCount)
        assertTrue("the count is wider than its line: ${dp(count.wholePx)} dp", count.wholePx > count.shownPx)
        assertTrue("the cut shows: an ellipsis", count.cut)
        assertTrue("the count stays inside the row", bounds(worstCount).right <= bounds(space).let { rule.onAllNodesWithTag(INBOX_ROW_TAG, useUnmergedTree = true)[0].fetchSemanticsNode().boundsInRoot.right })
    }

    @Test
    fun aParentWhoseCountWouldCutItsRole_putsTheCountOnTheMarksLine() {
        // A role of 28 characters and "5 helpers" do not fit on a line: the role stays whole and the count goes to the mark's line, at its right.
        val role = "Herd: Inbox, Spaces, notices"
        val helpers = (2..6).map { core("t$it", "Helper $it", parent = "t1") }
        show(listOf(core("t1", role)) + helpers)
        assertWhole(role)
        val count = bounds("5 helpers")
        val mark = markBounds()
        assertTrue("the count is under the role", count.top >= bounds(role).bottom - 1f)
        assertTrue("the count is on the mark's line, at its right", count.left >= mark.right && count.top < mark.bottom && count.bottom > mark.top)
    }

    @Test
    fun atALargeFont_aRoleOf28Characters_leavesTheSpaceNameWholeOnItsOwnLine() {
        val role = "Herd: Inbox, Spaces, notices"
        show(listOf(core("t1", role)), fontScale = 1.3f)
        assertFalse(measure(space).cut)
        assertTrue(dp(measure(role).shownPx) >= 100f)
    }

    @Test
    fun theRoleSitsUnderTheSpaceName_notBesideIt() {
        val role = "Herd: Inbox, Spaces, notices"
        show(listOf(core("t1", role)))
        val spaceNode = rule.onAllNodesWithText(space, useUnmergedTree = true)[0].fetchSemanticsNode()
        val roleNode = rule.onAllNodesWithText(role, useUnmergedTree = true)[0].fetchSemanticsNode()
        assertTrue("role under the name", roleNode.boundsInRoot.top >= spaceNode.boundsInRoot.bottom - 1f)
        assertEquals("same left edge", spaceNode.boundsInRoot.left, roleNode.boundsInRoot.left, 1f)
    }

    @Test
    fun aPlainHerdrRow_isDrawnAsBefore_itsTabBesideTheSpaceName() {
        val tui = Agent("a", AgentEngine.TUI, null, space, "work", null, null, AgentState.Idle, null)
        val rows = listOf(Inbox.row(1L, "box", tui))
        rule.setContent { ConnectBotTheme { InboxContent(rows = rows, notes = emptyList(), now = now, onOpen = {}, onNavigateBack = {}) } }
        rule.waitForIdle()
        val spaceNode = rule.onAllNodesWithText(space, useUnmergedTree = true)[0].fetchSemanticsNode()
        val tabNode = rule.onAllNodesWithText("work", useUnmergedTree = true)[0].fetchSemanticsNode()
        assertTrue("tab beside the name", tabNode.boundsInRoot.left >= spaceNode.boundsInRoot.right - 1f)
    }
}
