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

import org.assertj.core.api.Assertions.assertThat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The built-in slash command catalogs per agent. */
class SlashCatalogsTest {
    private fun names(agent: AgentKind) = SlashCatalogs.forAgent(agent).map { it.name }

    @Test
    fun everyCatalog_hasUniqueBareNamesAndDescriptions() {
        for (agent in AgentKind.entries) {
            val catalog = SlashCatalogs.forAgent(agent)
            assertTrue("$agent catalog is empty", catalog.size >= 40)
            assertThat(names(agent)).doesNotHaveDuplicates()
            catalog.forEach { command ->
                assertTrue("$agent ${command.name}", Regex("[a-z0-9-]+").matches(command.name))
                assertTrue("$agent ${command.name} has no description", command.description.isNotBlank())
                assertEquals(SlashSource.BUILT_IN, command.source)
            }
        }
    }

    @Test
    fun claudeCode_hasTheCommandsOrmusNamed() {
        assertThat(names(AgentKind.CLAUDE)).contains(
            "clear", "compact", "model", "resume", "review", "init", "memory", "config", "cost", "status",
            "help", "agents", "mcp", "permissions", "add-dir", "doctor", "export", "hooks", "rewind",
        )
        // 2.1.289 has no /todos; its task list is /tasks.
        assertThat(names(AgentKind.CLAUDE)).doesNotContain("todos").contains("tasks")
        // Most used first.
        assertEquals("clear", names(AgentKind.CLAUDE).first())
    }

    @Test
    fun codex_hasItsCoreCommands() {
        assertThat(names(AgentKind.CODEX)).contains("model", "new", "compact", "review", "diff", "init", "status", "resume", "mcp", "quit")
    }

    @Test
    fun grok_hasItsCoreCommands() {
        assertThat(names(AgentKind.GROK)).contains("new", "compact", "model", "resume", "context", "rewind", "help", "skills", "quit")
    }

    @Test
    fun bareCommandsSend_commandsThatTakeMoreGoToTheField() {
        val claude = SlashCatalogs.CLAUDE.associateBy { it.name }
        assertTrue(claude.getValue("clear").sendsAtOnce)
        assertTrue(claude.getValue("compact").sendsAtOnce)
        assertFalse(claude.getValue("add-dir").sendsAtOnce)
        assertEquals("<path>", claude.getValue("add-dir").argumentHint)
        assertFalse(SlashCatalogs.GROK.first { it.name == "rename" }.sendsAtOnce)
    }

    @Test
    fun ownCommandsAlwaysGoToTheField() {
        assertFalse(SlashCommand("mplan", "plan", source = SlashSource.COMMAND).sendsAtOnce)
        assertFalse(SlashCommand("brain", "brain", source = SlashSource.SKILL).sendsAtOnce)
        assertEquals("/mplan", SlashCommand("mplan", "").text)
    }
}
