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

package org.connectbot.ui.commands

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalBridge
import org.connectbot.transport.CommandOutput
import org.connectbot.transport.SSH
import solutions.ormus.logos.commands.AgentKind
import solutions.ormus.logos.commands.LineSender
import solutions.ormus.logos.commands.OwnCommandsState
import solutions.ormus.logos.commands.SlashCatalogs
import solutions.ormus.logos.commands.SlashCommand
import solutions.ormus.logos.commands.SlashCommandRepository
import solutions.ormus.logos.commands.focusedAgentKind
import solutions.ormus.logos.commands.focusedCwd
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import javax.inject.Inject

/**
 * The session a slash command goes to.
 *
 * @param snapshot Herdr's live snapshot on a Herdr host (the focused pane says
 * which agent runs), null on a plain host
 * @param runner runs one command over an SSH exec channel and blocks; null when
 * no SSH session is up
 * @param inject writes bytes to the session's PTY
 */
class SlashTarget(
    val hostId: Long,
    val snapshot: StateFlow<HerdrSessionSnapshot?>?,
    val runner: (String) -> CommandOutput?,
    val inject: (String) -> Unit,
)

/** The slash target for [this] session. */
fun TerminalBridge.slashTarget(): SlashTarget = SlashTarget(
    hostId = host.id,
    snapshot = herd?.snapshot,
    runner = { command -> (transport as? SSH)?.takeIf { it.isConnected() }?.execDetailed(command) },
    inject = { injectString(it) },
)

/** Which agent's commands show, and why. */
data class AgentChoice(val agent: AgentKind, val detected: Boolean)

/**
 * The agent whose commands the menu shows: one picked in this menu wins, then
 * the agent Herdr sees in the focused pane, then the one last picked on this
 * host, then Claude Code.
 */
fun chooseAgent(picked: AgentKind?, detected: AgentKind?, remembered: AgentKind?): AgentChoice = when {
    picked != null -> AgentChoice(picked, detected = picked == detected)
    detected != null -> AgentChoice(detected, detected = true)
    else -> AgentChoice(remembered ?: AgentKind.CLAUDE, detected = false)
}

/** Keep commands whose name or description holds [query]; names that start with it first. */
fun filterCommands(commands: List<SlashCommand>, query: String): List<SlashCommand> {
    val q = query.trim().removePrefix("/").lowercase()
    if (q.isEmpty()) return commands
    val hits = commands.filter { it.name.lowercase().contains(q) || it.description.lowercase().contains(q) }
    return hits.sortedBy {
        if (it.name.lowercase().startsWith(q)) {
            0
        } else if (it.name.lowercase().contains(q)) {
            1
        } else {
            2
        }
    }
}

data class SlashMenuUiState(
    val agent: AgentKind = AgentKind.CLAUDE,
    /** The agent came from Herdr's focused pane. */
    val detected: Boolean = false,
    val query: String = "",
    val builtIns: List<SlashCommand> = emptyList(),
    val own: List<SlashCommand> = emptyList(),
    val refreshing: Boolean = false,
    val failed: Boolean = false,
    /** Epoch millis of the last good read of the host's own commands; 0 when never. */
    val fetchedAt: Long = 0L,
)

/**
 * Backs the slash menu: the current agent's built-in catalog and Ormus's own
 * commands for the host (cached, read again on demand), filtered by the search
 * box. A tap either sends the command at once or hands "/name " to the
 * dictation field; see [SlashCommand.sendsAtOnce].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SlashMenuViewModel @Inject constructor(
    private val repository: SlashCommandRepository,
    dispatchers: CoroutineDispatchers,
) : ViewModel() {
    private val target = MutableStateFlow<SlashTarget?>(null)
    private val picked = MutableStateFlow<AgentKind?>(null)
    private val query = MutableStateFlow("")

    private val snapshot = target.flatMapLatest { it?.snapshot ?: flowOf(null) }
    private val own = target.flatMapLatest { it?.let { t -> repository.state(t.hostId) } ?: flowOf(OwnCommandsState()) }

    val uiState: StateFlow<SlashMenuUiState> = combine(target, picked, query, snapshot, own) { target, picked, query, snapshot, own ->
        val remembered = target?.let { repository.rememberedAgent(it.hostId) }
        val choice = chooseAgent(picked, snapshot?.focusedAgentKind(), remembered)
        SlashMenuUiState(
            agent = choice.agent,
            detected = choice.detected,
            query = query,
            builtIns = filterCommands(SlashCatalogs.forAgent(choice.agent), query),
            own = filterCommands(own.forAgent(choice.agent).sortedBy { it.name.lowercase() }, query),
            refreshing = own.refreshing,
            failed = own.failed,
            fetchedAt = own.fetchedAt,
        )
    }.flowOn(dispatchers.default).stateIn(viewModelScope, SharingStarted.Eagerly, SlashMenuUiState())

    /**
     * The menu opened on [target]: clear the search and this menu's pick, show
     * the cached list, and read it from the host when there is none yet or the
     * focused pane moved to another project.
     */
    fun open(target: SlashTarget) {
        this.target.value = target
        picked.value = null
        query.value = ""
        viewModelScope.launch {
            repository.load(target.hostId)
            val cached = repository.state(target.hostId).value
            val cwd = target.snapshot?.value?.focusedCwd()
            if (cached.fetchedAt == 0L || (cwd != null && cwd != cached.cwd)) {
                repository.refresh(target.hostId, cwd ?: cached.cwd, target.runner)
            }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun pickAgent(agent: AgentKind) {
        picked.value = agent
        target.value?.let { repository.rememberAgent(it.hostId, agent) }
    }

    /** Read the host's own commands again. */
    fun refresh() {
        val target = target.value ?: return
        viewModelScope.launch {
            val cwd = target.snapshot?.value?.focusedCwd() ?: repository.state(target.hostId).value.cwd
            repository.refresh(target.hostId, cwd, target.runner)
        }
    }

    /**
     * Run [command]: send "/name" and a discrete Enter, or, for a command that
     * takes more (or with [insert] forced by a long press), hand "/name " to
     * [toField]. Returns true when it was sent.
     */
    fun run(command: SlashCommand, toField: (String) -> Unit, insert: Boolean = false): Boolean {
        val target = target.value ?: return false
        return runSlashCommand(command, target.inject, toField, insert) { send -> viewModelScope.launch { send() } }
    }
}

/**
 * Send [command] to the PTY through [inject] ("/name", then Enter on its own),
 * or put "/name " in the dictation field through [toField]. [launch] runs the
 * send, which pauses between the text and the Enter. Returns true when sent.
 */
fun runSlashCommand(
    command: SlashCommand,
    inject: (String) -> Unit,
    toField: (String) -> Unit,
    insert: Boolean = false,
    launch: (suspend () -> Unit) -> Unit,
): Boolean = if (command.sendsAtOnce && !insert) {
    launch { LineSender.send(inject, command.text) }
    true
} else {
    toField(command.text + " ")
    false
}
