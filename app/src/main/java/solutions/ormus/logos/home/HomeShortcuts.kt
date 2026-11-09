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
 * A workboard's shortcut: a saved host whose post-login attaches the board's Herdr session on
 * its machine. The host list, the board's keys, a launcher's tap and the Needs you notices all
 * meet on such a row; the Save key on a board makes it when none exists. Pure.
 */
object HomeShortcuts {
    /** Where a board lives: the feed's alias of its machine and its Herdr session. */
    data class Place(val machine: String, val session: String)

    /**
     * The place of [board]: its own machine and session (the board's session, even where a
     * workspace of it names another), else the one place all its named, placed workspaces share
     * (a workspace's machine or session is the board's unless it says otherwise). Null when
     * nothing places it, or its workspaces live in two places. A blank name is no name.
     */
    fun place(board: HomeBoard): Place? {
        val own = board.machine.named()?.let { machine -> board.session.named()?.let { Place(machine, it) } }
        if (own != null) return own
        return board.workspaces.filter { it.name.isNotBlank() }.mapNotNull { workspace ->
            (workspace.machine ?: board.machine).named()?.let { machine -> (workspace.session ?: board.session).named()?.let { Place(machine, it) } }
        }.toSet().singleOrNull()
    }

    /**
     * The saved SSH host on [board]'s machine (as [feed] names it) whose post-login attaches the
     * board's session, the one connected last among several; null when there is none: the Save
     * key then shows.
     */
    fun saved(board: HomeBoard, feed: HomeBoards, hosts: List<Host>): Host? {
        val place = place(board) ?: return null
        return there(place, feed, hosts).filter { HerdrOpen.attached(it.postLogin) == place.session }.maxByOrNull { it.lastConnect }
    }

    /**
     * The host a Save on [board] makes: named after the board (a free name among [hosts]), with a
     * post-login that attaches its session. The saved SSH host on its machine with an account,
     * connected last, lends its account, address, port and key settings (the phone can log in
     * there); with none, the feed's SSH link for the machine gives the account, the address and
     * the port, when that address is one of the feed's names for the machine (else the row would
     * never be found again: not by [saved], not by the board's keys). Null when nothing places
     * the board, its session is not a name Herdr gives, the board has no name, or neither a host
     * nor a link gives an account.
     */
    fun newHost(board: HomeBoard, feed: HomeBoards, hosts: List<Host>): Host? {
        val place = place(board) ?: return null
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

    /** The feed's names for the place's machine, canon ([HerdrOpen.names]). */
    private fun names(place: Place, feed: HomeBoards): Set<String> = HerdrOpen.names(feed.hosts[place.machine]?.joinToString(","), null)

    /** The saved SSH hosts whose hostname is one of the feed's names for the place's machine. */
    private fun there(place: Place, feed: HomeBoards, hosts: List<Host>): List<Host> {
        val names = names(place, feed)
        if (names.isEmpty()) return emptyList()
        return hosts.filter { it.protocol == "ssh" && HerdrOpen.canon(it.hostname) in names }
    }

    /** [value] as an SSH link with an account and a host (`ssh://user@host[:port]`); null for anything else. */
    private fun link(value: String): URI? {
        val uri = try {
            URI(value.trim())
        } catch (e: URISyntaxException) {
            return null
        }
        if (uri.scheme != "ssh" || uri.userInfo?.substringBefore(':').isNullOrBlank() || uri.host.isNullOrBlank()) return null
        return uri
    }

    /** This when it says something (an alias is a key of the feed's maps, so it is kept as it is); null for null or blank. */
    private fun String?.named(): String? = this?.takeIf { it.isNotBlank() }
}
