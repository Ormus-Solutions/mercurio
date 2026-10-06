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

package solutions.ormus.logos.commands

import org.connectbot.ui.commands.AgentChoice
import org.connectbot.ui.commands.chooseAgent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import solutions.ormus.logos.herd.parseHerdrSnapshot

internal fun commandsFixture(name: String): String = checkNotNull(AgentKindTest::class.java.getResourceAsStream("/$name")) { "missing fixture $name" }
    .readBytes()
    .decodeToString()

internal fun snapshotFixture(name: String): HerdrSessionSnapshot = checkNotNull(parseHerdrSnapshot(commandsFixture(name))) { "$name should parse" }

/** Which agent the slash menu shows, from Herdr's snapshot and the per-host memory. */
class AgentKindTest {
    // Recorded on Sun: focus on the plain shell pane w1:p1, grok agents in the other panes.
    private val mixed = snapshotFixture("herdr/snapshot-sun-mixed.json")

    // The same recording with focus on w1:pB running claude in ~/projects/demo-app.
    private val claudeFocused = snapshotFixture("commands/snapshot-focus-claude.json")

    @Test
    fun focusedClaudePane_isClaude() {
        assertEquals(AgentKind.CLAUDE, claudeFocused.focusedAgentKind())
        assertEquals("/home/alice/projects/demo-app", claudeFocused.focusedCwd())
    }

    @Test
    fun focusedGrokPane_isGrok() {
        assertEquals(AgentKind.GROK, mixed.copy(focusedPaneId = "w1:pC").focusedAgentKind())
    }

    @Test
    fun focusedShellPane_hasNoAgent_butHasItsCwd() {
        assertNull(mixed.focusedAgentKind())
        assertEquals("/home/alice", mixed.focusedCwd())
    }

    @Test
    fun nothingFocused_hasNoAgent() {
        assertNull(mixed.copy(focusedPaneId = null).focusedAgentKind())
        assertNull(mixed.copy(focusedPaneId = "").focusedCwd())
    }

    @Test
    fun herdrNames_mapToKindsWithCatalogs() {
        assertEquals(AgentKind.CLAUDE, AgentKind.fromHerdr("claude"))
        assertEquals(AgentKind.CLAUDE, AgentKind.fromHerdr(" Claude "))
        assertEquals(AgentKind.CODEX, AgentKind.fromHerdr("codex"))
        assertEquals(AgentKind.GROK, AgentKind.fromHerdr("grok"))
        assertNull(AgentKind.fromHerdr("pi"))
        assertNull(AgentKind.fromHerdr("claudette"))
        assertNull(AgentKind.fromHerdr(""))
        assertNull(AgentKind.fromHerdr(null))
    }

    @Test
    fun choice_pickedThenDetectedThenRememberedThenClaude() {
        assertEquals(AgentChoice(AgentKind.CODEX, detected = false), chooseAgent(AgentKind.CODEX, AgentKind.GROK, AgentKind.CLAUDE))
        assertEquals(AgentChoice(AgentKind.GROK, detected = true), chooseAgent(AgentKind.GROK, AgentKind.GROK, null))
        assertEquals(AgentChoice(AgentKind.GROK, detected = true), chooseAgent(null, AgentKind.GROK, AgentKind.CODEX))
        assertEquals(AgentChoice(AgentKind.CODEX, detected = false), chooseAgent(null, null, AgentKind.CODEX))
        assertEquals(AgentChoice(AgentKind.CLAUDE, detected = false), chooseAgent(null, null, null))
    }
}
