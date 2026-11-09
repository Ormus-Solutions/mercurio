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

package solutions.ormus.logos.home

import org.connectbot.data.entity.Host
import solutions.ormus.logos.herd.HerdrOpen
import solutions.ormus.logos.herd.HerdrSessions
import solutions.ormus.logos.herd.HerdrWorkspacePicker
import java.net.URI
import java.net.URISyntaxException

/**
 * The host a workboard's Save key saves: a row named after the board whose post-login attaches
 * the board's Herdr session on its machine, with the account and keys of the host saved there.
 * Pure: the home shows a key per [Shortcut] and the host list saves what a tap hands it.
 */
object HomeShortcuts {
    /** A machine of the feed and a Herdr session on it. */
    data class Place(val machine: String, val session: String)

    /** A host a Save key would make, and the place it attaches. */
    data class Shortcut(val place: Place, val host: Host)

    /**
     * Where the board lives. Its own place when the feed gives it a machine and a session; else
     * one place per machine its named workspaces stand on, in the order the workspaces name
     * them. The feed leaves a board unplaced when its workspaces are spread (another machine
     * runs a session of the same name): then the board is the session named like it, on every
     * machine that runs one, and a machine without it gives no place. With no such session
     * anywhere, a machine with one session is a place; one with several is none. Names match
     * exactly, trimmed.
     */
    fun places(board: HomeBoard): List<Place> {
        val own = board.machine.named()?.let { machine -> board.session.named()?.let { Place(machine, it) } }
        if (own != null) return listOf(own)
        val name = board.name.trim()
        val byMachine = board.workspaces.filter { it.name.isNotBlank() }
            .mapNotNull { workspace -> (workspace.machine.named() ?: board.machine.named())?.let { machine -> (workspace.session.named() ?: board.session.named())?.let { Place(machine, it) } } }
            .distinct()
            .groupBy { it.machine }
            .values
        val named = byMachine.mapNotNull { there -> there.firstOrNull { it.session == name } }
        if (byMachine.size > 1 && named.isNotEmpty()) return named
        return byMachine.mapNotNull { there -> there.singleOrNull() ?: there.firstOrNull { it.session == name } }
    }

    /** The hosts the board's Save keys would make: one per place of the board that no saved host attaches yet. */
    fun shortcuts(board: HomeBoard, feed: HomeBoards, hosts: List<Host>): List<Shortcut> = places(board).filter { saved(it, feed, hosts) == null }.mapNotNull { place -> newHost(board, place, feed, hosts)?.let { Shortcut(place, it) } }

    /**
     * The saved host on the place's machine whose post-login attaches its session; the one
     * connected last when several do. Two feed aliases for one machine are taken as two machines.
     */
    fun saved(place: Place, feed: HomeBoards, hosts: List<Host>): Host? = there(place, feed, hosts).filter { HerdrOpen.attached(it.postLogin) == place.session }.maxByOrNull { it.lastConnect }

    /**
     * The host a Save makes for the board at [place]: named after the board (a name free among
     * [hosts]), on the account, address, port and key settings of the SSH host connected last
     * among those on the machine with an account, with a post-login that attaches the session. With no saved host there, the feed's
     * SSH link for the machine gives the account and address, if that address is one of the
     * feed's names for it. Null when the session is not one Herdr could have given, the board has
     * no name, or no account is known.
     */
    fun newHost(board: HomeBoard, place: Place, feed: HomeBoards, hosts: List<Host>): Host? {
        if (!HerdrSessions.isName(place.session)) return null
        val name = board.name.trim().takeIf { it.isNotEmpty() } ?: return null
        val nickname = HerdrOpen.freeNickname(hosts, name)
        val postLogin = HerdrWorkspacePicker.attachPostLogin(HerdrOpen.PLAIN_LAUNCH, place.session)
        there(place, feed, hosts).filter { it.username.isNotBlank() }.maxByOrNull { it.lastConnect }?.let { sibling ->
            return Host(
                nickname = nickname,
                protocol = "ssh",
                username = sibling.username,
                hostname = sibling.hostname,
                port = sibling.port,
                useKeys = sibling.useKeys,
                useAuthAgent = sibling.useAuthAgent,
                postLogin = postLogin,
                pubkeyId = sibling.pubkeyId,
                jumpHostId = sibling.jumpHostId,
                profileId = sibling.profileId,
            )
        }
        val link = feed.links[place.machine]?.let(::link) ?: return null
        val hostname = link.host.removeSurrounding("[", "]")
        if (HerdrOpen.canon(hostname) !in names(place, feed)) return null
        // An account only: a password in a link (`user:secret@`) is never a username.
        return Host(nickname = nickname, protocol = "ssh", username = link.userInfo.substringBefore(':'), hostname = hostname, port = if (link.port > 0) link.port else 22, postLogin = postLogin)
    }

    /** The feed's names for the place's machine, as the host rows are matched. */
    private fun names(place: Place, feed: HomeBoards): Set<String> = HerdrOpen.names(feed.hosts[place.machine]?.joinToString(","), null)

    /** The saved SSH hosts whose address is one of the feed's names for the place's machine; none when the feed names none. */
    private fun there(place: Place, feed: HomeBoards, hosts: List<Host>): List<Host> {
        val names = names(place, feed)
        if (names.isEmpty()) return emptyList()
        return hosts.filter { it.protocol == "ssh" && HerdrOpen.canon(it.hostname) in names }
    }

    /** The link as an SSH URI with an account and a host, else null. */
    private fun link(value: String): URI? {
        val uri = try {
            URI(value.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        if (uri.scheme != "ssh" || uri.userInfo?.substringBefore(':').isNullOrBlank() || uri.host.isNullOrBlank()) return null
        return uri
    }

    private fun String?.named(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
