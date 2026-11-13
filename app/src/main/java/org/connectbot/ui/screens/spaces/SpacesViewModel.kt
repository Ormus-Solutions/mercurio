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

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalManager
import org.connectbot.transport.CommandOutput
import org.connectbot.util.PreferenceConstants
import solutions.ormus.logos.herd.AgentState
import solutions.ormus.logos.herd.CoreLinks
import solutions.ormus.logos.herd.CoreMachines
import solutions.ormus.logos.herd.CoreSource
import solutions.ormus.logos.herd.CoreSpaces
import solutions.ormus.logos.home.HomeStart
import javax.inject.Inject

/**
 * One machine's spaces ([hostId] tells two hosts of one nickname apart), or why they could not be read; what [SpacesContent] draws under the machine's header.
 * [states] is what is known of the agents there, by terminal id; a tab whose terminals are not in it draws the neutral box.
 */
data class SpacesSection(
    val hostId: Long,
    val machine: String,
    val result: CoreSpaces.Result,
    val states: Map<String, AgentState> = emptyMap(),
)

/**
 * Backs the Spaces list: the spaces document of every saved machine (hosts of one hostname, port and
 * user are one), one read per machine at a time, only while the herdr-core setting is on. A machine
 * with a console open is read over it, any other over one headless login held while the list shows.
 * Each read also runs `hc ls` for the agents' states. Read only; nothing is written back. [SpacesSection.hostId] is the machine's primary host.
 */
@HiltViewModel
class SpacesViewModel @Inject constructor(
    private val prefs: SharedPreferences,
    private val dispatchers: CoroutineDispatchers,
    private val links: CoreLinks,
) : ViewModel() {
    private val _sections = MutableStateFlow<List<SpacesSection>>(emptyList())
    val sections: StateFlow<List<SpacesSection>> = _sections.asStateFlow()

    private var manager: TerminalManager? = null

    // What each host's last read gave, by host id, and the hosts being read now. Main thread only.
    private val read = LinkedHashMap<Long, SpacesSection>()
    private val reading = HashSet<Long>()
    private val lastStates = HashMap<Long, Map<String, AgentState>>()
    private var current: Set<Long> = emptySet()

    fun setTerminalManager(value: TerminalManager) {
        manager = value
    }

    /** Read now and again every [PERIOD_MS], for as long as the caller stays: the screen while it shows. */
    suspend fun watch() {
        links.reopen()
        try {
            while (true) {
                refresh()
                delay(PERIOD_MS)
            }
        } finally {
            links.close()
        }
    }

    override fun onCleared() {
        links.close()
    }

    internal suspend fun refresh() {
        val bridges = manager?.bridgesFlow?.value ?: return
        val enabled = HomeStart.coreOn(prefs)
        if (!enabled) {
            read.clear()
            lastStates.clear()
            links.close()
            _sections.value = emptyList()
            return
        }
        val machines = withContext(dispatchers.io) { links.machines(CoreMachines.liveHosts(bridges)) }
        current = machines.map { it.primary.id }.toSet()
        read.keys.retainAll(current)
        lastStates.keys.retainAll(current)
        _sections.value = read.values.toList()
        for (machine in machines) {
            val id = machine.primary.id
            if (!reading.add(id)) continue
            val open = bridges.firstOrNull { b -> b.herd != null && machine.hosts.any { it.id == b.host.id } }?.herd?.let { it::exec }
            viewModelScope.launch {
                try {
                    val answer: Pair<CoreSpaces.Result, Map<String, AgentState>?> = try {
                        withContext(dispatchers.io) {
                            when (val link = links.link(machine, open)) {
                                is CoreLinks.Link.Down -> CoreSpaces.Result.Unavailable(link.reason) to null

                                is CoreLinks.Link.Up -> {
                                    val spaces = CoreSpaces.read(true, link.run)
                                    spaces to if (spaces is CoreSpaces.Result.Spaces) agentStates(link.run) else null
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        CoreSpaces.Result.Unavailable(e.message ?: e::class.simpleName.orEmpty()) to null
                    }
                    val (result, fresh) = answer
                    // A read that failed keeps the last states for one refresh, so a tile does not flicker.
                    val states = fresh?.also { lastStates[id] = it } ?: lastStates.remove(id).orEmpty()
                    val left = (result as? CoreSpaces.Result.Unavailable)?.reason == CoreLinks.LEFT
                    if (!left && id in current) read[id] = SpacesSection(id, machine.name, result, states)
                } finally {
                    reading.remove(id)
                    _sections.value = read.values.toList()
                }
            }
        }
    }

    /** The agents' states over the same link (one command, two when an unread one needs its label); null when the read fails, and the spaces still show. */
    private fun agentStates(run: (String) -> CommandOutput?): Map<String, AgentState>? = try {
        CoreSource().states(run)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    companion object {
        /** How long the Spaces list waits between two reads while it shows. */
        const val PERIOD_MS = 10_000L
    }
}
