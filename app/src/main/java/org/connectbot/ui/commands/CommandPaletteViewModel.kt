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

import android.content.SharedPreferences
import androidx.core.content.edit
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
import kotlinx.coroutines.withContext
import org.connectbot.data.HostRepository
import org.connectbot.data.TailscaleDevice
import org.connectbot.data.entity.Host
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.usage.UsageStore
import solutions.ormus.logos.commands.AgentKind
import solutions.ormus.logos.commands.OwnCommandsState
import solutions.ormus.logos.commands.PaletteAction
import solutions.ormus.logos.commands.PaletteEntry
import solutions.ormus.logos.commands.PaletteScreen
import solutions.ormus.logos.commands.PaletteSearch
import solutions.ormus.logos.commands.SlashCatalogs
import solutions.ormus.logos.commands.SlashCommand
import solutions.ormus.logos.commands.SlashCommandRepository
import solutions.ormus.logos.commands.focusedAgentKind
import solutions.ormus.logos.herd.HerdrAction
import javax.inject.Inject

/** Where palette results land. The console implements it; tests use a fake. */
interface PaletteTarget {
    fun sendKey(sequence: String)

    /** Run a Herdr sheet action; [text] is the name a rename asked for. */
    fun herdr(action: HerdrAction, text: String?)

    fun slash(command: SlashCommand)

    fun connectHost(hostId: Long)

    fun switchSession(hostId: Long)

    fun connectMachine(device: TailscaleDevice)

    fun open(screen: PaletteScreen)

    fun navigate(route: String)
}

/** Run [action] on [target]. [text] is the name an inline rename step asked for. */
fun runPaletteAction(action: PaletteAction, target: PaletteTarget, text: String? = null) {
    when (action) {
        is PaletteAction.Key -> target.sendKey(action.sequence)
        is PaletteAction.Herdr -> target.herdr(action.action, text)
        is PaletteAction.Slash -> target.slash(action.command)
        is PaletteAction.ConnectHost -> target.connectHost(action.hostId)
        is PaletteAction.SwitchSession -> target.switchSession(action.hostId)
        is PaletteAction.ConnectMachine -> target.connectMachine(action.device)
        is PaletteAction.Open -> target.open(action.screen)
        is PaletteAction.Navigate -> target.navigate(action.route)
    }
}

/** The current agent and its slash commands, built-ins first. */
data class PaletteSlash(val agent: AgentKind = AgentKind.CLAUDE, val commands: List<SlashCommand> = emptyList())

/**
 * Backs the command palette: one query over every entry the console passes in
 * ([setEntries]), ranked by [PaletteSearch] with the recent list (this phone
 * only, in preferences) and the usage log's counts per control. Ranking runs off
 * the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CommandPaletteViewModel @Inject constructor(
    hostRepository: HostRepository,
    private val slashRepository: SlashCommandRepository,
    private val usageStore: UsageStore,
    private val prefs: SharedPreferences,
    private val dispatchers: CoroutineDispatchers,
) : ViewModel() {
    val hosts: StateFlow<List<Host>> = hostRepository.observeHosts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val target = MutableStateFlow<SlashTarget?>(null)
    private val query = MutableStateFlow("")
    private val entries = MutableStateFlow<List<PaletteEntry>>(emptyList())
    private val recents = MutableStateFlow<List<String>>(emptyList())
    private val counts = MutableStateFlow<Map<String, Long>>(emptyMap())

    private val snapshot = target.flatMapLatest { it?.snapshot ?: flowOf(null) }
    private val own = target.flatMapLatest { it?.let { t -> slashRepository.state(t.hostId) } ?: flowOf(OwnCommandsState()) }

    /** The agent in the session and its commands, as the slash menu would show them. */
    val slash: StateFlow<PaletteSlash> = combine(target, snapshot, own) { target, snapshot, own ->
        if (target == null) return@combine PaletteSlash()
        val agent = chooseAgent(null, snapshot?.focusedAgentKind(), slashRepository.rememberedAgent(target.hostId)).agent
        PaletteSlash(agent, SlashCatalogs.forAgent(agent) + own.forAgent(agent).sortedBy { it.name.lowercase() })
    }.flowOn(dispatchers.default).stateIn(viewModelScope, SharingStarted.Eagerly, PaletteSlash())

    val results: StateFlow<List<PaletteEntry>> = combine(entries, query, recents, counts) { entries, query, recents, counts ->
        PaletteSearch.rank(entries, query, recents, counts)
    }.flowOn(dispatchers.default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The palette opened on [target] (null with no session): clear the search, read recents and counts. */
    fun open(target: SlashTarget?) {
        this.target.value = target
        query.value = ""
        recents.value = loadRecents()
        viewModelScope.launch {
            target?.let { slashRepository.load(it.hostId) }
            counts.value = withContext(dispatchers.io) { usageCounts() }
        }
    }

    fun setQuery(value: String) {
        query.value = value
    }

    fun setEntries(value: List<PaletteEntry>) {
        entries.value = value
    }

    /** Put [entry] at the top of the recent list. */
    fun recordRun(entry: PaletteEntry) {
        val updated = (listOf(entry.key) + recents.value.filterNot { it == entry.key }).take(MAX_RECENTS)
        recents.value = updated
        prefs.edit { putString(RECENTS_KEY, updated.joinToString(SEPARATOR)) }
    }

    private fun loadRecents(): List<String> = prefs.getString(RECENTS_KEY, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() }.orEmpty()

    // Action ids only: the usage log never holds what was typed.
    private fun usageCounts(): Map<String, Long> {
        val snapshot = usageStore.snapshot()
        val counts = HashMap<String, Long>()
        snapshot.events.forEach { counts[it.action] = (counts[it.action] ?: 0) + 1 }
        snapshot.dailies.forEach { counts[it.action] = (counts[it.action] ?: 0) + it.count }
        return counts
    }

    companion object {
        /** Recently run palette results (entry keys), on this phone only. */
        const val RECENTS_KEY = "paletteRecents"
        private const val SEPARATOR = "\n"
        private const val MAX_RECENTS = 20
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
