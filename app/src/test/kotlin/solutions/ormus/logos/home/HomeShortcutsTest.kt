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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import solutions.ormus.logos.herd.HerdrOpen

/** The saved host a workboard's Save key makes: where the board lives, what the host copies, when there is none to make. */
class HomeShortcutsTest {
    private val names = mapOf("box" to listOf("box", "box.example", "192.0.2.10"), "far" to listOf("far.example"), "bare" to emptyList())
    private val links = mapOf("far" to "ssh://ops@far.example:2222", "bare" to "https://bare.example/")
    private val plain = Host(id = 1L, nickname = "Box", protocol = "ssh", username = "me", hostname = "192.0.2.10", postLogin = "herdr\n", lastConnect = 30L, pubkeyId = 7L, useAuthAgent = "confirm", profileId = 3L, jumpHostId = 9L)
    private val copy = Host(id = 2L, nickname = "Box work", protocol = "ssh", username = "me", hostname = "box.example", postLogin = "herdr session attach work\n", lastConnect = 5L)
    private val shell = Host(id = 3L, nickname = "Box shell", protocol = "ssh", username = "other", hostname = "box", port = 2200, lastConnect = 20L)
    private val feed = HomeBoards(hosts = names, links = links)

    private fun board(name: String, vararg workspaces: HomeWorkspace, machine: String? = "box", session: String? = name) = HomeBoard(name = name, machine = machine, session = session, workspaces = workspaces.toList())

    @Test
    fun place_isTheBoards_elseTheOnePlaceItsWorkspacesShare() {
        assertEquals(HomeShortcuts.Place("box", "work"), HomeShortcuts.place(board("work")))
        // A board the feed places nowhere lives where its placed workspaces agree; one with no place says nothing.
        val agree = board("work", HomeWorkspace("a", machine = "box", session = "work"), HomeWorkspace("b"), HomeWorkspace("c", machine = "box", session = "work"), machine = null, session = null)
        assertEquals(HomeShortcuts.Place("box", "work"), HomeShortcuts.place(agree))
        // A board's own machine with its workspaces' session is still one place.
        assertEquals(HomeShortcuts.Place("box", "work"), HomeShortcuts.place(board("work", HomeWorkspace("a", session = "work"), session = null)))
        // Two places, or none, give none.
        assertNull(HomeShortcuts.place(board("work", HomeWorkspace("a", machine = "box", session = "work"), HomeWorkspace("b", machine = "far", session = "work"), machine = null, session = null)))
        assertNull(HomeShortcuts.place(board("work", HomeWorkspace("a"), machine = null, session = null)))
        assertNull(HomeShortcuts.place(board("work", session = null)))
        // The board's own place wins over a workspace that names another session: the Save key is about the board's session.
        assertEquals(HomeShortcuts.Place("box", "work"), HomeShortcuts.place(board("work", HomeWorkspace("a", session = "other"))))
        // A workspace with no name is no key, so it places nothing; a blank session or machine says nothing.
        assertEquals(HomeShortcuts.Place("box", "work"), HomeShortcuts.place(board("work", HomeWorkspace("a", machine = "box", session = "work"), HomeWorkspace(" ", machine = "box", session = "other"), machine = null, session = null)))
        assertEquals(HomeShortcuts.Place("box", "work"), HomeShortcuts.place(board("work", HomeWorkspace("a", session = "work"), session = " ")))
        assertNull(HomeShortcuts.place(board("work", machine = " ")))
    }

    @Test
    fun saved_isTheHostOnTheMachineWhosePostLoginAttachesTheSession() {
        assertEquals(copy, HomeShortcuts.saved(board("work"), feed, listOf(plain, copy, shell)))
        // A plain `herdr` attaches the default session and no other.
        assertEquals(plain, HomeShortcuts.saved(board("sun", session = "default"), feed, listOf(plain, copy)))
        assertNull(HomeShortcuts.saved(board("work"), feed, listOf(plain, shell)))
        // Another machine's copy does not count.
        assertNull(HomeShortcuts.saved(board("work", machine = "far"), feed, listOf(copy)))
        assertNull(HomeShortcuts.saved(board("work", machine = null, session = null), feed, listOf(copy)))
    }

    @Test
    fun newHost_copiesTheAccountAndKeysOfTheHostOnTheMachine_andAttachesTheSession() {
        val host = HomeShortcuts.newHost(board("work"), feed, listOf(shell, plain))!!

        // The host connected last on the machine lends its account, address, port and key settings; the session is the board's.
        assertEquals(
            Host(nickname = "work", protocol = "ssh", username = "me", hostname = "192.0.2.10", port = 22, postLogin = "herdr session attach 'work'\n", pubkeyId = 7L, useAuthAgent = "confirm", profileId = 3L, jumpHostId = 9L),
            host,
        )
        assertEquals("work", HerdrOpen.attached(host.postLogin))
        // Saved, it is what the board's keys and a launcher's tap open, and what a second Save finds.
        assertEquals(HerdrOpen.Target(host, null), HerdrOpen.resolve(listOf(plain, host), "work", HerdrOpen.names(names.getValue("box").joinToString(","), null)))
        assertEquals(host, HomeShortcuts.saved(board("work"), feed, listOf(plain, host)))
        // The default session is a plain `herdr`; a host on another port keeps it.
        assertEquals("herdr\n", HomeShortcuts.newHost(board("sun", session = "default"), feed, listOf(copy))!!.postLogin)
        assertEquals(2200, HomeShortcuts.newHost(board("work"), feed, listOf(shell))!!.port)
        // A host with no account, however recent, lends nothing: the next one does.
        assertEquals("me", HomeShortcuts.newHost(board("work"), feed, listOf(shell.copy(username = " ", lastConnect = 99L), plain))!!.username)
    }

    @Test
    fun newHost_takesTheFeedsLink_whenTheMachineHasNoSavedHost() {
        assertEquals(
            Host(nickname = "edge", protocol = "ssh", username = "ops", hostname = "far.example", port = 2222, postLogin = "herdr session attach 'edge'\n"),
            HomeShortcuts.newHost(board("edge", machine = "far"), feed, listOf(plain)),
        )
        // A link with no port is port 22; a password in a link is never part of the account.
        assertEquals(22, HomeShortcuts.newHost(board("edge", machine = "far"), HomeBoards(hosts = names, links = mapOf("far" to "ssh://ops@far.example")), emptyList())!!.port)
        assertEquals("ops", HomeShortcuts.newHost(board("edge", machine = "far"), HomeBoards(hosts = names, links = mapOf("far" to "ssh://ops:secret@far.example")), emptyList())!!.username)
        // A host with no account on the machine leaves the link its say.
        assertEquals("ops", HomeShortcuts.newHost(board("edge", machine = "far"), feed, listOf(Host(id = 6L, protocol = "ssh", username = "", hostname = "far.example", lastConnect = 9L)))!!.username)
        // Saved, the link's row is the one the board finds again.
        val saved = HomeShortcuts.newHost(board("edge", machine = "far"), feed, emptyList())!!
        assertEquals(saved, HomeShortcuts.saved(board("edge", machine = "far"), feed, listOf(saved)))
        // A link whose address is none of the feed's names for the machine makes no row: it would never be found again.
        assertNull(HomeShortcuts.newHost(board("edge", machine = "far"), HomeBoards(hosts = names, links = mapOf("far" to "ssh://ops@far-other.example")), emptyList()))
        assertNull(HomeShortcuts.newHost(board("edge", machine = "new"), HomeBoards(hosts = names, links = mapOf("new" to "ssh://ops@new.example")), emptyList()))
    }

    @Test
    fun newHost_isNamedAfterTheBoard_freeAmongTheSavedNames() {
        val taken = listOf(plain, Host(id = 4L, nickname = "work", hostname = "elsewhere"), Host(id = 5L, nickname = "work 2 ", hostname = "elsewhere"))
        assertEquals("work 3", HomeShortcuts.newHost(board("work"), feed, taken)!!.nickname)
        assertEquals("trimmed", HomeShortcuts.newHost(board(" trimmed ", session = "work"), feed, listOf(plain))!!.nickname)
    }

    @Test
    fun newHost_isNull_withNoPlace_noAccount_noNameOrABadSession() {
        assertNull("no place", HomeShortcuts.newHost(board("work", machine = null, session = null), feed, listOf(plain)))
        assertNull("no saved host and no link", HomeShortcuts.newHost(board("work", machine = "none"), feed, listOf(plain)))
        assertNull("a link that is not SSH", HomeShortcuts.newHost(board("work", machine = "bare"), feed, listOf(plain)))
        assertNull("a link with no account", HomeShortcuts.newHost(board("work", machine = "far"), HomeBoards(hosts = names, links = mapOf("far" to "ssh://far.example")), emptyList()))
        assertNull("not a session Herdr could have given", HomeShortcuts.newHost(board("work", session = "no way"), feed, listOf(plain)))
        assertNull("a board with no name", HomeShortcuts.newHost(board("  ", session = "work"), feed, listOf(plain)))
        assertNull("a telnet host on the machine is no account for SSH", HomeShortcuts.newHost(board("work"), feed, listOf(plain.copy(protocol = "telnet"))))
    }
}
