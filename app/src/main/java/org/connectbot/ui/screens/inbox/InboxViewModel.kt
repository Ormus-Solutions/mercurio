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
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.TerminalManager
import org.connectbot.ui.screens.herd.boardSnapshots
import org.connectbot.ui.screens.panel.FeedbackText
import org.connectbot.util.PreferenceConstants
import solutions.ormus.logos.herd.AgentEngine
import solutions.ormus.logos.herd.AgentsBoard
import solutions.ormus.logos.herd.CoreLinks
import solutions.ormus.logos.herd.CoreMachines
import solutions.ormus.logos.herd.CoreRead
import solutions.ormus.logos.herd.CoreSource
import solutions.ormus.logos.herd.HerdSession
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
    /**
     * What the Inbox shows: the rows, and a line for each host whose herdr-core could not be read.
     * [loaded] is false until every machine has answered once, with rows or with a failure; an empty
     * Inbox that is not loaded is still loading, not empty.
     */
    data class State(
        val rows: List<InboxRow> = emptyList(),
        val notes: List<String> = emptyList(),
        val loaded: Boolean = false,
        /** Core reads are on and no Herdr machine is known: the screen says why the Inbox may be empty. */
        val noHerdrMachine: Boolean = false,
    )

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

    // True once a poll has listed the machines; false again when the Inbox is opened. Main thread only.
    private var listed = false

    // Counts the writes of [act], at their start and at their patch. A read that began under an older
    // count may hold the row as it was before the write, so it is dropped. Main thread only.
    private var writes = 0

    // Counts the visits ([watch] calls). A read of an earlier visit ends without touching the new one's state.
    private var visit = 0

    // Polls in a row that found no terminal manager, and the line shown once there were [UNBOUND_POLLS].
    private var unboundPolls = 0
    private var unboundNote: String? = null

    /** How long a machine's read may take before the Inbox gives up on it and says so. */
    internal var readTimeoutMs = READ_TIMEOUT_MS

    fun setTerminalManager(value: TerminalManager) {
        manager = value
    }

    /** Read now and again every [PERIOD_MS], for as long as the caller stays: the Inbox while it shows. */
    suspend fun watch() {
        // Rows kept from an earlier visit are old; they go before the first read of this one.
        _state.value = State()
        coreRows.clear()
        // Opening the Inbox asks a host that said "not installed" again.
        cores.values.forEach { it.recheck() }
        machines = emptyList()
        listed = false
        failure = null
        failureHost = null
        unboundPolls = 0
        unboundNote = null
        // A read still out from an earlier visit must not keep its host from being read again.
        visit++
        reading.clear()
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

    // True when core reads are on and no Herdr machine is known: why the Inbox may be empty.
    private var noHerdrMachine = false

    // The machines of the last poll, by primary host id.
    private var machines: List<CoreMachines.Machine> = emptyList()

    /**
     * Take the Herdr rows of every connection now, and start a herdr-core read for each host that
     * has none under way. A read runs on its own, so a host that is slow holds back only itself.
     */
    internal suspend fun refresh() {
        val bridges = manager?.bridgesFlow?.value
        if (bridges == null) {
            // The screen binds the manager before it shows; a manager that never comes is said, not waited on.
            if (++unboundPolls >= UNBOUND_POLLS) {
                listed = true
                unboundNote = "Inbox: no terminal service to read from"
                publish()
            }
            return
        }
        unboundPolls = 0
        unboundNote = null
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
            machines = emptyList()
            noHerdrMachine = false
            listed = true
            dropFailureUnless(emptySet())
            publish()
            return
        }
        val liveHosts = CoreMachines.liveHosts(bridges)
        val liveKeys = liveHosts.map(CoreMachines::keyOf).toSet()
        val all = withContext(dispatchers.io) { links.machines(liveHosts) }
        noHerdrMachine = all.isEmpty() && herdrRows.isEmpty()
        machines = all
        listed = true
        val ids = all.map { it.primary.id }.toSet()
        cores.keys.retainAll(ids)
        coreRows.keys.retainAll(ids)
        probes.keys.retainAll(ids)
        dropFailureUnless(ids)
        publish()
        for (machine in all) {
            val id = machine.primary.id
            if (!reading.add(id)) continue
            val source = cores.getOrPut(id) { CoreSource() }
            // A console to the machine is open and its Herd session just read herdr-core: that is the read, not a second poll.
            val shared = sessionRead(machine, bridges)
            if (shared != null) {
                val (herd, read) = shared
                probes[id] = read.result
                HomeStart.noteProbes(prefs, probes.values)
                noteVersion(herd.coreVersion)
                coreRows[id] = Inbox.fromCore(id, machine.name, read.result)
                if (failureHost == id) failure = null
                reading.remove(id)
                publish()
                continue
            }
            val open = openExec(machine, bridges)
            val startedAt = writes
            val startedIn = visit
            viewModelScope.launch {
                try {
                    val result = try {
                        // The read runs on its own, so a deadline can leave it behind; its late answer is dropped.
                        val work = viewModelScope.async(dispatchers.io) {
                            when (val link = links.link(machine, open)) {
                                is CoreLinks.Link.Down -> CoreSource.Result.Unavailable(link.reason)
                                is CoreLinks.Link.Up -> source.read(true, machine.name, System.currentTimeMillis(), link.run)
                            }
                        }
                        val read = withContext(dispatchers.io) { withTimeoutOrNull(readTimeoutMs) { work.await() } }
                            ?: return@launch timedOut(id, machine.name, startedIn)
                        probes[id] = read
                        // A machine with a console that found Herdr is not forgotten: it would be learned again at the next refresh.
                        if (read is CoreSource.Result.Unavailable && read.reason == CoreSource.NOT_INSTALLED && machine.key !in liveKeys) links.forget(machine)
                        HomeStart.noteProbes(prefs, probes.values)
                        noteVersion(source.version)
                        Inbox.fromCore(id, machine.name, read)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Inbox.CoreRows(emptyList(), "${machine.name}: ${e.message ?: e::class.simpleName}")
                    }
                    // A read cut short by the screen leaving says nothing about the machine.
                    // A read begun before the latest write may predate it, so it is dropped; the next poll reads again.
                    if (startedIn == visit && id in cores && !leftBy(result) && startedAt == writes) {
                        coreRows[id] = result
                        if (result.note == null && failureHost == id) failure = null
                    }
                } finally {
                    if (startedIn == visit) {
                        reading.remove(id)
                        publish()
                    }
                }
            }
        }
    }

    /**
     * The herdr-core agents read of the Herd session of a console open to [machine], when it began after the last
     * write (a read that began before one may predate it) and no longer ago than [SESSION_READ_FRESH_MS]; else null.
     * Any other answer of the session (not installed, no reach, no answer) is not used: its own source may hold
     * a "not installed" for minutes, and the Inbox asks the host again when it opens.
     */
    private fun sessionRead(machine: CoreMachines.Machine, bridges: List<org.connectbot.service.TerminalBridge>): Pair<HerdSession, CoreRead>? {
        val now = System.currentTimeMillis()
        return bridges.asSequence()
            .filter { b -> machine.hosts.any { it.id == b.host.id } }
            .mapNotNull { b -> b.herd?.let { herd -> herd.coreRead.value?.let { herd to it } } }
            .filter { (_, read) -> read.result is CoreSource.Result.Agents && now - read.at in 0..SESSION_READ_FRESH_MS && read.at > lastWriteAt }
            .maxByOrNull { (_, read) -> read.at }
    }

    private fun noteVersion(version: String?) {
        FeedbackText.coreVersionOf(version.orEmpty())?.let { v ->
            if (prefs.getString(PreferenceConstants.HERDR_CORE_VERSION, null) != v) prefs.edit { putString(PreferenceConstants.HERDR_CORE_VERSION, v) }
        }
    }

    /** A read past its deadline counts as an answer: a note for the machine, so the Inbox is no longer loading. */
    private fun timedOut(id: Long, name: String, startedIn: Int) {
        if (startedIn == visit && id in cores) coreRows[id] = Inbox.CoreRows(emptyList(), "$name: no answer")
    }

    // A failed write is about one machine; when that machine is gone from the poll, its line goes too.
    private fun dropFailureUnless(ids: Set<Long>) {
        if (failureHost !in ids) {
            failure = null
            failureHost = null
        }
    }

    /** The exec of a console already open to [machine], or null: that connection is used before a login is made. */
    private fun openExec(machine: CoreMachines.Machine, bridges: List<org.connectbot.service.TerminalBridge>): ((String) -> org.connectbot.transport.CommandOutput?)? = bridges
        .firstOrNull { b -> b.herd != null && machine.hosts.any { it.id == b.host.id } }?.herd?.let { it::exec }

    // When the last write ended: a Herd session read that began before it is not the Inbox's read.
    @Volatile private var lastWriteAt = 0L

    // The last write that failed, as a line for the user, and the machine it was for; the next write,
    // the next visit or the next good read of that machine clears it.
    private var failure: String? = null
    private var failureHost: Long? = null

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
        writes++
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
            failureHost = row.hostId
            // A read already out may hold the row as it was; it must neither undo the patch nor clear the line.
            writes++
            lastWriteAt = System.currentTimeMillis()
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
        val loaded = listed && machines.all { it.primary.id in coreRows }
        _state.value = State(herdrRows + coreRows.values.flatMap { it.rows }, coreRows.values.mapNotNull { it.note } + listOfNotNull(failure, unboundNote), loaded, noHerdrMachine)
    }

    companion object {
        /** How long the Inbox waits between two reads while it shows. */
        const val PERIOD_MS = 5_000L

        /**
         * How long a read of a Herd session serves the Inbox: a little over its 4 s poll, so each of the Inbox's 5 s
         * refreshes finds one while a console is open and its session polls.
         */
        const val SESSION_READ_FRESH_MS = 6_000L

        /** How long one machine's read may take: the exec timeout of a command, with room for a login. */
        const val READ_TIMEOUT_MS = 15_000L

        /** Polls without a terminal manager after which the Inbox stops saying it is loading. */
        const val UNBOUND_POLLS = 3
    }
}
