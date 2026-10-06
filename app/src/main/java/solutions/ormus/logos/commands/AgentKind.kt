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

import solutions.ormus.logos.herd.HerdrSessionSnapshot

/**
 * A coding agent whose slash commands the slash menu knows. [id] is the name
 * Herdr reports in a pane's `agent` field and the value remembered per host.
 */
enum class AgentKind(val id: String) {
    CLAUDE("claude"),
    CODEX("codex"),
    GROK("grok"),
    ;

    companion object {
        fun fromId(id: String?): AgentKind? = entries.firstOrNull { it.id == id }

        /**
         * Herdr's detected agent name ("claude", "codex", "grok", "pi", ...) as a
         * kind with a catalog, or null for agents the menu has no catalog for.
         */
        fun fromHerdr(name: String?): AgentKind? {
            val agent = name?.trim()?.lowercase()?.ifEmpty { null } ?: return null
            return entries.firstOrNull { agent == it.id || agent.startsWith(it.id + "-") }
        }
    }
}

/** The agent running in the focused pane, or null when it runs none the menu knows. */
fun HerdrSessionSnapshot.focusedAgentKind(): AgentKind? {
    val paneId = focusedPaneId?.ifBlank { null } ?: return null
    val name = agents.firstOrNull { it.paneId == paneId }?.agent
        ?: panes.firstOrNull { it.paneId == paneId }?.agent
    return AgentKind.fromHerdr(name)
}

/** The focused pane's working directory, where the agent finds project commands. */
fun HerdrSessionSnapshot.focusedCwd(): String? {
    val paneId = focusedPaneId?.ifBlank { null } ?: return null
    return (
        panes.firstOrNull { it.paneId == paneId }?.cwd
            ?: agents.firstOrNull { it.paneId == paneId }?.cwd
        )?.ifBlank { null }
}
