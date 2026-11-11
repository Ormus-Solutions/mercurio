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

import android.content.SharedPreferences
import androidx.core.content.edit
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
import org.connectbot.ui.screens.herd.boardSnapshots
import org.connectbot.ui.screens.panel.FeedbackText
import org.connectbot.util.PreferenceConstants
import solutions.ormus.logos.herd.AgentEngine
import solutions.ormus.logos.herd.AgentsBoard
import solutions.ormus.logos.herd.CoreLinks
import solutions.ormus.logos.herd.CoreMachines
import solutions.ormus.logos.herd.CoreSource
import solutions.ormus.logos.herd.Inbox
import solutions.ormus.logos.herd.InboxRow
import solutions.ormus.logos.herd.RowAction
import solutions.ormus.logos.home.HomeStart
import javax.inject.Inject

/**
 * Backs the Inbox: the Herdr agents of every connected host, and, when the herdr-core setting is
 * on, the herdr-core terminals of every saved machine (hosts of one hostname, port and user are one
 * machine), with or without a console open: a machine with a console open is read over it, any other
 * over one held headless login that closes when the Inbox leaves. A machine has one read at a time;
 * a poll that finds one still running leaves it be. Keys below are the machine's primary host id.
 */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val prefs: SharedPreferences,
    private val dispatchers: CoroutineDispatchers,
    private val links: CoreLinks,
) : ViewModel() {
    /** What the Inbox shows: the rows, and a line for each host whose herdr-core could not be read. */
    data class State(val rows: List<InboxRow> = emptyList(), val notes: List<String> = emptyList())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var manager: TerminalManager? = null

    // One CoreSource per host: it remembers when each terminal entered its state.
    private val cores = HashMap<Long, CoreSource>()

    // What each host's last herdr-core read gave, and the hosts being read now. Main thread only.
    private val coreRows = HashMap<Long, Inbox.CoreRows>()
    private val reading = HashSet<Long>()

    // What each host's last herdr-core read said of the probe, for the start destination. Main thread only.
    private val probes = HashMap<Long, CoreSource.Result>()
    private var herdrRows: List<InboxRow> = emptyList()

    fun setTerminalManager(value: TerminalManager) {
        manager = value
    }

    /** Read now and again every [PERIOD_MS], for as long as the caller stays: the Inbox while it shows. */
    suspend fun watch() {
        // Rows kept from an earlier visit are old; they go before the first read of this one.
        _state.value = State()
        coreRows.clear()
        failure = null
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

    // The machines of the last poll, by primary host id.
    private var machines: List<CoreMachines.Machine> = emptyList()

    /**
     * Take the Herdr rows of every connection now, and start a herdr-core read for each host that
     * has none under way. A read runs on its own, so a host that is slow holds back only itself.
     */
    internal suspend fun refresh() {
        val bridges = manager?.bridgesFlow?.value ?: return
        val core = HomeStart.coreOn(prefs)
        herdrRows = bridges.flatMap { bridge ->
            val herd = bridge.herd ?: return@flatMap emptyList()
            val host = bridge.host
            Inbox.fromCards(host.id, host.nickname, AgentsBoard.cards(boardSnapshots(herd.snapshot.value, herd.others.value, herd.sessions.value), herd.since::of))
        }
        if (!core) {
            coreRows.clear()
            cores.clear()
            probes.clear()
            links.close()
            publish()
            return
        }
        val all = withContext(dispatchers.io) { links.machines(bridges.map { it.host }) }
        machines = all
        val ids = all.map { it.primary.id }.toSet()
        cores.keys.retainAll(ids)
        coreRows.keys.retainAll(ids)
        probes.keys.retainAll(ids)
        publish()
        for (machine in all) {
            val id = machine.primary.id
            if (!reading.add(id)) continue
            val source = cores.getOrPut(id) { CoreSource() }
            val open = openExec(machine, bridges)
            viewModelScope.launch {
                try {
                    val result = try {
                        val read = withContext(dispatchers.io) {
                            when (val link = links.link(machine, open)) {
                                is CoreLinks.Link.Down -> CoreSource.Result.Unavailable(link.reason)
                                is CoreLinks.Link.Up -> source.read(true, machine.name, System.currentTimeMillis(), link.run)
                            }
                        }
                        probes[id] = read
                        HomeStart.noteProbes(prefs, probes.values)
                        FeedbackText.coreVersionOf(source.version.orEmpty())?.let { v ->
                            if (prefs.getString(PreferenceConstants.HERDR_CORE_VERSION, null) != v) prefs.edit { putString(PreferenceConstants.HERDR_CORE_VERSION, v) }
                        }
                        Inbox.fromCore(id, machine.name, read)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Inbox.CoreRows(emptyList(), "${machine.name}: ${e.message ?: e::class.simpleName}")
                    }
                    // A read cut short by the screen leaving says nothing about the machine.
                    if (id in cores && !leftBy(result)) coreRows[id] = result
                } finally {
                    reading.remove(id)
                    publish()
                }
            }
        }
    }

    /** The exec of a console already open to [machine], or null: that connection is used before a login is made. */
    private fun openExec(machine: CoreMachines.Machine, bridges: List<org.connectbot.service.TerminalBridge>): ((String) -> org.connectbot.transport.CommandOutput?)? = bridges
        .firstOrNull { b -> b.herd != null && machine.hosts.any { it.id == b.host.id } }?.herd?.let { it::exec }

    // The last write that failed, as a line for the user; the next write or visit clears it.
    private var failure: String? = null

    /**
     * Write [action] on [row]'s terminal through its host's connection. A herdr-core row only: a TUI
     * agent has no labels and no Esc to send here. On success the row is patched at once, and the
     * next poll confirms it; on failure the row stays and a line says so.
     */
    fun act(row: InboxRow, action: RowAction) {
        val agent = row.agent
        if (agent.engine != AgentEngine.CORE) return
        val machine = machines.firstOrNull { it.primary.id == row.hostId } ?: return
        val open = openExec(machine, manager?.bridgesFlow?.value.orEmpty())
        viewModelScope.launch {
            val ok = try {
                withContext(dispatchers.io) {
                    (links.link(machine, open) as? CoreLinks.Link.Up)?.let { CoreSource.write(action, agent.address, agent.turn, it.run) } ?: false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            failure = if (ok) null else "${row.host}: could not write to ${agent.address}"
            if (ok) {
                coreRows[row.hostId]?.let { held ->
                    coreRows[row.hostId] = held.copy(rows = Inbox.patched(held.rows, agent, action))
                }
            }
            publish()
        }
    }

    private fun leftBy(rows: Inbox.CoreRows) = rows.note?.endsWith(": ${CoreLinks.LEFT}") == true

    private fun publish() {
        _state.value = State(herdrRows + coreRows.values.flatMap { it.rows }, coreRows.values.mapNotNull { it.note } + listOfNotNull(failure))
    }

    companion object {
        /** How long the Inbox waits between two reads while it shows. */
        const val PERIOD_MS = 5_000L
    }
}
