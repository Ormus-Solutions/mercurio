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

package org.connectbot.ui.screens.console

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.navigation.NavDestinations
import solutions.ormus.logos.herd.AgentEngine
import solutions.ormus.logos.herd.CoreMachines
import solutions.ormus.logos.herd.CoreRead
import solutions.ormus.logos.herd.CoreSource
import solutions.ormus.logos.herd.CoreWatch
import solutions.ormus.logos.herd.CoreWatchTarget
import solutions.ormus.logos.herd.Inbox
import solutions.ormus.logos.herd.InboxRow
import solutions.ormus.logos.terminal.AgentSwipe

/**
 * The rows a watched console's swipe moves between. The Inbox's own rows are not at hand: its view model is gone
 * once the Inbox leaves the back stack, and it polls only while the Inbox shows. The console has the Herd side of
 * every open console, which is what the Inbox reads for a machine with a console open (`InboxViewModel.refresh`):
 * the herdr-core read of the machine. The rows are built from them with the Inbox's own helpers and ordered by
 * [AgentSwipe.order]. Only herdr-core terminals are in the run (a Herdr session's agents are not).
 *
 * Two limits. A machine with no console open has no row here (the Inbox reads it over a login of its own). And the
 * machines are grouped from the hosts of the open consoles, not from every saved host as the Inbox does
 * (`CoreLinks.machines`): a machine with two saved logins, a console open only on the one that is not the Inbox's
 * primary, has its rows on that login, so a terminal the Inbox opens on the primary and the same one a swipe opens
 * are two watch consoles. The same happens on a machine with no plain `herdr` login when its consoles were opened in
 * another order than its saved hosts: `CoreMachines.group` puts a plain `herdr` login first, else keeps the order it
 * meets, and the Inbox meets the saved hosts first.
 */
internal object AgentSwipeSource {
    /** What one open console's Herd side knows: its host and its last herdr-core read. */
    class Read(val host: Host, val core: CoreRead? = null)

    /**
     * The place of the terminal [watch] on [host] among the rows of [reads]. Every open console's host is grouped into
     * machines (hosts of one machine are one machine), so a console whose own read has not come yet is still in its
     * machine; the freshest herdr-core read of a machine that listed agents counts, and a machine with none has no rows.
     * Null when the terminal is in no read.
     */
    fun placeOf(reads: List<Read>, host: Host, watch: CoreWatchTarget): AgentSwipe.Place? {
        val machines = CoreMachines.group(reads.map { it.host })
        val rows = machines.flatMap { machine ->
            val read = reads
                .filter { r -> r.core?.result is CoreSource.Result.Agents && machine.hosts.any { it.id == r.host.id } }
                .maxByOrNull { it.core!!.at }
                ?.core ?: return@flatMap emptyList()
            Inbox.fromCore(machine.primary.id, machine.name, read.result).rows
        }
        val mine = machines.firstOrNull { m -> m.hosts.any { it.id == host.id } } ?: return null
        return AgentSwipe.place(AgentSwipe.order(rows)) { row -> row.hostId == mine.primary.id && row.agent.address == watch.address }
    }

    /** The route a swipe in [direction] from [place] opens, or null: at an end (no wrap) or with no place yet. */
    fun open(place: AgentSwipe.Place?, direction: AgentSwipe.Direction): String? = place?.toward(direction)?.let(::route)

    /** The route that opens [row] as the Inbox opens it: a core terminal watched, a Herdr agent in its session's console. */
    fun route(row: InboxRow): String = NavDestinations.console(row.hostId, row.console, CoreWatch.target(row.agent)?.takeIf { row.agent.engine == AgentEngine.CORE })

    /** The reads of [bridges], again whenever a Herd side changes. */
    fun reads(bridges: List<TerminalBridge>): Flow<List<Read>> {
        val flows = bridges.mapNotNull { bridge ->
            val herd = bridge.herd ?: return@mapNotNull null
            herd.coreRead.map { core -> Read(bridge.host, core) }
        }
        return if (flows.isEmpty()) flowOf(emptyList()) else combine(flows) { it.toList() }
    }

    /** The place of [current] (watching [watch]) among the rows of every open console, again as they change. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun places(allBridges: Flow<List<TerminalBridge>>, current: TerminalBridge, watch: CoreWatchTarget): Flow<AgentSwipe.Place?> = allBridges
        .flatMapLatest { reads(it) }
        .map { placeOf(it, current.host, watch) }
        .distinctUntilChanged()
}
