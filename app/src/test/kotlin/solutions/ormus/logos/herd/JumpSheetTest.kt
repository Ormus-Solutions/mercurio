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

package solutions.ormus.logos.herd

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** The Jump sheet's rows, its Recent list kept in the preferences, and its Recent / All / Matches groups. */
@RunWith(AndroidJUnit4::class)
class JumpSheetTest {
    private fun target(session: String, tab: String, space: String, label: String, state: HerdrAgentStatus, agent: String? = null, title: String = "") = JumpTarget(
        session = session,
        tabId = tab,
        tabLabel = label,
        tabNumber = 1,
        workspaceLabel = space,
        workspaceNumber = 1,
        agent = agent,
        state = state,
        focused = false,
        paneId = "$tab:p",
        title = title,
    )

    private val targets = HerdrJump.targets(emptyMap()) + listOf(
        target("default", "t1", "alpha · ONE", "build", HerdrAgentStatus.IDLE),
        target("default", "t2", "alpha · ONE", "review", HerdrAgentStatus.BLOCKED, "claude", "Fix the parser"),
        target("work", "t3", "beta · TWO", "deploy", HerdrAgentStatus.WORKING, "codex"),
    )

    private val items = JumpSheet.items(targets, "box", emptyList())

    private fun keys(section: JumpSheet.Section) = section.items.map { it.key }

    @Test
    fun record_putsTheNewestFirst_andMovesARepeatToTheFront() {
        var recent = emptyList<String>()
        recent = JumpSheet.record(recent, "a")
        recent = JumpSheet.record(recent, "b")
        recent = JumpSheet.record(recent, "a")
        assertEquals(listOf("a", "b"), recent)
    }

    @Test
    fun record_keepsAtMostEight_dropsTheOldest() {
        val recent = (1..10).fold(emptyList<String>()) { acc, i -> JumpSheet.record(acc, "k$i") }
        assertEquals(8, recent.size)
        assertEquals("k10", recent.first())
        assertEquals("k3", recent.last())
    }

    @Test
    fun recents_surviveARestart_inTheSameOrder() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())
        prefs.edit().clear().commit()
        assertEquals(emptyList<String>(), JumpRecents(prefs).load())
        JumpRecents(prefs).record("default/t1/t1:p")
        JumpRecents(prefs).record("work/t3/t3:p")
        assertEquals(listOf("work/t3/t3:p", "default/t1/t1:p"), JumpRecents(PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())).load())
    }

    @Test
    fun emptyQuery_recentFirstThenTheRest_inTheInboxRank() {
        val sections = JumpSheet.sections(items, listOf("work/t3/t3:p", "default/t1/t1:p"), "")
        assertEquals(listOf(JumpSheet.Group.RECENT, JumpSheet.Group.ALL), sections.map { it.group })
        assertEquals(listOf("work/t3/t3:p", "default/t1/t1:p"), keys(sections[0]))
        assertEquals(listOf("default/t2/t2:p"), keys(sections[1]))
    }

    @Test
    fun emptyQuery_withNoRecent_isOneGroupOfAllRows_blockedFirst() {
        val sections = JumpSheet.sections(items, emptyList(), "")
        assertEquals(listOf(JumpSheet.Group.ALL), sections.map { it.group })
        assertEquals("default/t2/t2:p", keys(sections.single()).first())
        assertEquals(3, sections.single().items.size)
    }

    @Test
    fun recent_skipsARowThatNoLongerExists() {
        val sections = JumpSheet.sections(items, listOf("gone/t9/x", "work/t3/t3:p"), "")
        assertEquals(listOf("work/t3/t3:p"), keys(sections[0]))
    }

    @Test
    fun query_isOneMatchesGroup_overSpaceTabTitleAndAgent_inTheInboxRank() {
        assertEquals(listOf("default/t2/t2:p", "default/t1/t1:p"), keys(JumpSheet.sections(items, emptyList(), "alpha").single()))
        assertEquals(listOf("default/t2/t2:p"), keys(JumpSheet.sections(items, emptyList(), "parser").single()))
        assertEquals(listOf("work/t3/t3:p"), keys(JumpSheet.sections(items, emptyList(), "codex").single()))
        assertEquals(listOf("work/t3/t3:p"), keys(JumpSheet.sections(items, emptyList(), "deploy").single()))
        val matches = JumpSheet.sections(items, listOf("work/t3/t3:p"), "alpha")
        assertEquals(listOf(JumpSheet.Group.MATCHES), matches.map { it.group })
    }

    @Test
    fun query_withNoMatch_isNoGroup() {
        assertEquals(emptyList<JumpSheet.Section>(), JumpSheet.sections(items, emptyList(), "zzz"))
    }

    @Test
    fun secondLine_isTheTabThenTheHost() {
        assertEquals("review · box", items.single { it.key == "default/t2/t2:p" }.second)
        assertEquals("1 · box", JumpSheet.items(listOf(target("default", "t5", "alpha · ONE", "", HerdrAgentStatus.IDLE)), "box", emptyList()).single().second)
    }

    @Test
    fun firstLine_isTheSpace_andATitleTheTabLacksIsAThirdLine() {
        val blocked = items.single { it.key == "default/t2/t2:p" }
        assertEquals("alpha · ONE", blocked.space)
        assertEquals("Fix the parser", blocked.third)
        assertNull(items.single { it.key == "work/t3/t3:p" }.third)
    }

    @Test
    fun coreAgents_joinTheRows_withTheirHost_andRankWithThem() {
        val core = Inbox.fromCore(
            7,
            "box",
            CoreSource.Result.Agents(
                listOf(Agent(address = "t9", engine = AgentEngine.CORE, space = "gamma · THREE", tab = "logs", title = "Tail the log", kind = "claude", state = AgentState.NeedsYou())),
            ),
        ).rows
        val all = JumpSheet.items(targets, "box", core)
        val coreItem = all.single { it.core != null }
        assertEquals("7/core/t9", coreItem.key)
        assertEquals("logs · box", coreItem.second)
        assertEquals("Tail the log", coreItem.third)
        assertEquals(4, all.size)
        assertEquals(listOf("7/core/t9", "default/t2/t2:p").sorted(), JumpSheet.sections(all, emptyList(), "").single().items.take(2).map { it.key }.sorted())
        assertEquals(listOf("7/core/t9"), keys(JumpSheet.sections(all, emptyList(), "gamma").single()))
    }

    @Test
    fun coreItem_opensTheTerminalAsHcLsNamedIt() {
        val core = Inbox.fromCore(7, "box", CoreSource.Result.Agents(listOf(Agent(address = "t9", engine = AgentEngine.CORE, space = "gamma", tab = "logs", state = AgentState.Idle)))).rows
        val row = JumpSheet.items(emptyList(), "box", core).single().core!!
        assertEquals("exec hc attach 't9'\n", CoreWatch.target(row.agent)?.let { CoreWatch.attachCommand(it.address) })
    }

    @Test
    fun query_findsAPaneByItsFolder() {
        val withFolder = JumpSheet.items(listOf(target("default", "t1", "alpha · ONE", "build", HerdrAgentStatus.IDLE).copy(folder = "my-project")), "box", emptyList())
        assertEquals(listOf("default/t1/t1:p"), keys(JumpSheet.sections(withFolder, emptyList(), "my-project").single()))
    }

    @Test
    fun coreAgent_withAnAddressThePickCannotOpen_isLeftOut() {
        val bad = Inbox.row(7, "box", Agent(address = "t 9; rm", engine = AgentEngine.CORE, space = "gamma", tab = "logs", state = AgentState.Idle))
        assertEquals(0, JumpSheet.items(emptyList(), "box", listOf(bad)).size)
    }
}
