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
import org.connectbot.util.PreferenceConstants
import solutions.ormus.logos.herd.AgentsBoard
import solutions.ormus.logos.herd.CoreSource
import solutions.ormus.logos.herd.Inbox
import solutions.ormus.logos.herd.InboxRow
import javax.inject.Inject

/**
 * Backs the Inbox: the agents of every connected host, from the Herdr side each connection
 * already polls and, when the herdr-core setting is on, from one herdr-core read per host.
 * A host has one herdr-core read at a time; a poll that finds one still running leaves it be.
 */
@HiltViewModel
class InboxViewModel @Inject constructor(
    private val prefs: SharedPreferences,
    private val dispatchers: CoroutineDispatchers,
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
    private var herdrRows: List<InboxRow> = emptyList()

    fun setTerminalManager(value: TerminalManager) {
        manager = value
    }

    /** Read now and again every [PERIOD_MS], for as long as the caller stays: the Inbox while it shows. */
    suspend fun watch() {
        // Rows kept from an earlier visit are old; they go before the first read of this one.
        _state.value = State()
        coreRows.clear()
        while (true) {
            refresh()
            delay(PERIOD_MS)
        }
    }

    /**
     * Take the Herdr rows of every connection now, and start a herdr-core read for each host that
     * has none under way. A read runs on its own, so a host that is slow holds back only itself.
     */
    internal suspend fun refresh() {
        val bridges = manager?.bridgesFlow?.value ?: return
        val core = prefs.getBoolean(PreferenceConstants.HERDR_CORE, false)
        herdrRows = bridges.flatMap { bridge ->
            val herd = bridge.herd ?: return@flatMap emptyList()
            val host = bridge.host
            Inbox.fromCards(host.id, host.nickname, AgentsBoard.cards(boardSnapshots(herd.snapshot.value, herd.others.value, herd.sessions.value), herd.since::of))
        }
        val ids = bridges.map { it.host.id }.toSet()
        cores.keys.retainAll(ids)
        coreRows.keys.retainAll(ids)
        if (!core) coreRows.clear()
        publish()
        if (!core) return
        for (bridge in bridges) {
            val herd = bridge.herd ?: continue
            val host = bridge.host
            if (!reading.add(host.id)) continue
            val source = cores.getOrPut(host.id) { CoreSource() }
            viewModelScope.launch {
                try {
                    val result = try {
                        Inbox.fromCore(host.id, host.nickname, withContext(dispatchers.io) { source.read(true, host.nickname, System.currentTimeMillis(), herd::exec) })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Inbox.CoreRows(emptyList(), "${host.nickname}: ${e.message ?: e::class.simpleName}")
                    }
                    if (host.id in cores) coreRows[host.id] = result
                } finally {
                    reading.remove(host.id)
                    publish()
                }
            }
        }
    }

    private fun publish() {
        _state.value = State(herdrRows + coreRows.values.flatMap { it.rows }, coreRows.values.mapNotNull { it.note })
    }

    companion object {
        /** How long the Inbox waits between two reads while it shows. */
        const val PERIOD_MS = 5_000L
    }
}
