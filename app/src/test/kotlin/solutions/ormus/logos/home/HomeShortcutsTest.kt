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
import solutions.ormus.logos.home.HomeShortcuts.Place

class HomeShortcutsTest {
    private val names = mapOf("box" to listOf("box", "box.example", "192.0.2.10"), "far" to listOf("far.example"), "bare" to emptyList())
    private val links = mapOf("far" to "ssh://ops@far.example:2222", "bare" to "https://bare.example/")
    private val plain = Host(id = 1L, nickname = "Box", protocol = "ssh", username = "me", hostname = "192.0.2.10", postLogin = "herdr\n", lastConnect = 30L, pubkeyId = 7L, useAuthAgent = "confirm", profileId = 3L, jumpHostId = 9L)
    private val copy = Host(id = 2L, nickname = "Box work", protocol = "ssh", username = "me", hostname = "box.example", postLogin = "herdr session attach work\n", lastConnect = 5L)
    private val shell = Host(id = 3L, nickname = "Box shell", protocol = "ssh", username = "other", hostname = "box", port = 2200, lastConnect = 20L)
    private val feed = HomeBoards(hosts = names, links = links)
    private val work = Place("box", "work")

    private fun board(name: String, vararg workspaces: HomeWorkspace, machine: String? = "box", session: String? = name) = HomeBoard(name = name, machine = machine, session = session, workspaces = workspaces.toList())

    @Test
    fun places_areTheBoards_elseOnePerMachineItsWorkspacesStandOn() {
        assertEquals(listOf(work), HomeShortcuts.places(board("work")))
        // A board the feed places nowhere lives where its placed workspaces stand; one with no place says nothing.
        val agree = board("work", HomeWorkspace("a", machine = "box", session = "work"), HomeWorkspace("b"), HomeWorkspace("c", machine = "box", session = "work"), machine = null, session = null)
        assertEquals(listOf(work), HomeShortcuts.places(agree))
        // A board's own machine with its workspaces' session is still one place.
        assertEquals(listOf(work), HomeShortcuts.places(board("work", HomeWorkspace("a", session = "work"), session = null)))
        // Spread over two machines, the board has a place on each, in the order its workspaces name them.
        val spread = board("work", HomeWorkspace("a", machine = "box", session = "work"), HomeWorkspace("b", machine = "far", session = "work"), HomeWorkspace("c", machine = "box", session = "work"), machine = null, session = null)
        assertEquals(listOf(work, Place("far", "work")), HomeShortcuts.places(spread))
        // On a machine whose workspaces use several sessions, the board's is the one named like it; with none such, that machine gives no place.
        val home = board("work", HomeWorkspace("a", machine = "box", session = "work"), HomeWorkspace("~", machine = "box", session = "default"), HomeWorkspace("b", machine = "far", session = "work"), machine = null, session = null)
        assertEquals(listOf(work, Place("far", "work")), HomeShortcuts.places(home))
        val vague = board("work", HomeWorkspace("a", machine = "box", session = "one"), HomeWorkspace("b", machine = "box", session = "two"), HomeWorkspace("c", machine = "far", session = "work"), machine = null, session = null)
        assertEquals(listOf(Place("far", "work")), HomeShortcuts.places(vague))
        // One machine with one session, named otherwise, is still the board's place there.
        assertEquals(listOf(Place("box", "other")), HomeShortcuts.places(board("work", HomeWorkspace("a", machine = "box", session = "other"), machine = null, session = null)))
        // Nothing placed gives none.
        assertEquals(emptyList<Place>(), HomeShortcuts.places(board("work", HomeWorkspace("a"), machine = null, session = null)))
        assertEquals(emptyList<Place>(), HomeShortcuts.places(board("work", session = null)))
        // The board's own place wins over a workspace that names another session: the Save key is about the board's session.
        assertEquals(listOf(work), HomeShortcuts.places(board("work", HomeWorkspace("a", session = "other"))))
        // A workspace with no name is no key, so it places nothing; a blank session or machine says nothing.
        assertEquals(listOf(work), HomeShortcuts.places(board("work", HomeWorkspace("a", machine = "box", session = "work"), HomeWorkspace(" ", machine = "box", session = "other"), machine = null, session = null)))
        assertEquals(listOf(work), HomeShortcuts.places(board("work", HomeWorkspace("a", session = "work"), session = " ")))
        assertEquals(emptyList<Place>(), HomeShortcuts.places(board("work", machine = " ")))
        // A board with a machine but no session of its own lives where its workspaces stand, even on another machine.
        assertEquals(listOf(Place("far", "work")), HomeShortcuts.places(board("work", HomeWorkspace("a", machine = "far", session = "work"), session = null)))
        // Spread, the board is its own session wherever that runs: a lone `~` in `default` on the other machine is no place of it.
        assertEquals(listOf(work), HomeShortcuts.places(board("work", HomeWorkspace("a", machine = "box", session = "work"), HomeWorkspace("~", machine = "far", session = "default"), machine = null, session = null)))
        // Spread with no session named like the board anywhere, each machine's lone session is its place.
        assertEquals(listOf(Place("box", "one"), Place("far", "two")), HomeShortcuts.places(board("work", HomeWorkspace("a", machine = "box", session = "one"), HomeWorkspace("b", machine = "far", session = "two"), machine = null, session = null)))
        // A blank workspace machine or session falls back to the board's; names are trimmed before they are matched.
        assertEquals(listOf(work), HomeShortcuts.places(board("work", HomeWorkspace("a", machine = " ", session = "work"), session = null)))
        assertEquals(listOf(work), HomeShortcuts.places(board("work ", HomeWorkspace("a", machine = "box", session = " work "), HomeWorkspace("b", machine = "box", session = "other"), machine = null, session = null)))
    }

    @Test
    fun shortcuts_areOnePerPlace_thatNoSavedHostAttachesYet() {
        val spread = board("work", HomeWorkspace("a", machine = "box", session = "work"), HomeWorkspace("b", machine = "far", session = "work"), machine = null, session = null)
        val made = HomeShortcuts.shortcuts(spread, feed, listOf(plain))

        // Box lends its saved host's account; Far has none, so its link's. Both attach the board's session.
        assertEquals(listOf(work, Place("far", "work")), made.map { it.place })
        assertEquals(listOf("192.0.2.10", "far.example"), made.map { it.host.hostname })
        assertEquals(listOf("herdr session attach 'work'\n"), made.map { it.host.postLogin }.distinct())
        // Saved on Box, the board keeps its key for Far only.
        assertEquals(listOf(Place("far", "work")), HomeShortcuts.shortcuts(spread, feed, listOf(plain, made[0].host)).map { it.place })
        // A place with no account known makes no key; a board placed nowhere has none.
        val unknown = board("work", HomeWorkspace("a", machine = "box", session = "work"), HomeWorkspace("b", machine = "none", session = "work"), machine = null, session = null)
        assertEquals(listOf(work), HomeShortcuts.shortcuts(unknown, feed, listOf(plain)).map { it.place })
        assertEquals(emptyList<HomeShortcuts.Shortcut>(), HomeShortcuts.shortcuts(board("work", machine = null, session = null), feed, listOf(plain)))
    }

    @Test
    fun saved_isTheHostOnTheMachineWhosePostLoginAttachesTheSession() {
        assertEquals(copy, HomeShortcuts.saved(work, feed, listOf(plain, copy, shell)))
        // A plain `herdr` attaches the default session and no other.
        assertEquals(plain, HomeShortcuts.saved(Place("box", "default"), feed, listOf(plain, copy)))
        assertNull(HomeShortcuts.saved(work, feed, listOf(plain, shell)))
        // Another machine's copy does not count.
        assertNull(HomeShortcuts.saved(Place("far", "work"), feed, listOf(copy)))
    }

    @Test
    fun newHost_copiesTheAccountAndKeysOfTheHostOnTheMachine_andAttachesTheSession() {
        val host = HomeShortcuts.newHost(board("work"), work, feed, listOf(shell, plain))!!

        // The host connected last on the machine lends its account, address, port and key settings; the session is the place's.
        assertEquals(
            Host(nickname = "work", protocol = "ssh", username = "me", hostname = "192.0.2.10", port = 22, postLogin = "herdr session attach 'work'\n", pubkeyId = 7L, useAuthAgent = "confirm", profileId = 3L, jumpHostId = 9L),
            host,
        )
        assertEquals("work", HerdrOpen.attached(host.postLogin))
        // Saved, it is what the board's keys and a launcher's tap open, and what a second Save finds.
        assertEquals(HerdrOpen.Target(host, null), HerdrOpen.resolve(listOf(plain, host), "work", HerdrOpen.names(names.getValue("box").joinToString(","), null)))
        assertEquals(host, HomeShortcuts.saved(work, feed, listOf(plain, host)))
        // The default session is a plain `herdr`; a host on another port keeps it.
        assertEquals("herdr\n", HomeShortcuts.newHost(board("sun", session = "default"), Place("box", "default"), feed, listOf(copy))!!.postLogin)
        assertEquals(2200, HomeShortcuts.newHost(board("work"), work, feed, listOf(shell))!!.port)
        // A host with no account, however recent, lends nothing: the next one does.
        assertEquals("me", HomeShortcuts.newHost(board("work"), work, feed, listOf(shell.copy(username = " ", lastConnect = 99L), plain))!!.username)
    }

    @Test
    fun newHost_takesTheFeedsLink_whenTheMachineHasNoSavedHost() {
        val far = Place("far", "edge")
        assertEquals(
            Host(nickname = "edge", protocol = "ssh", username = "ops", hostname = "far.example", port = 2222, postLogin = "herdr session attach 'edge'\n"),
            HomeShortcuts.newHost(board("edge", machine = "far"), far, feed, listOf(plain)),
        )
        // A link with no port is port 22; a password in a link is never part of the account.
        assertEquals(22, HomeShortcuts.newHost(board("edge", machine = "far"), far, HomeBoards(hosts = names, links = mapOf("far" to "ssh://ops@far.example")), emptyList())!!.port)
        assertEquals("ops", HomeShortcuts.newHost(board("edge", machine = "far"), far, HomeBoards(hosts = names, links = mapOf("far" to "ssh://ops:secret@far.example")), emptyList())!!.username)
        // A host with no account on the machine leaves the link its say.
        assertEquals("ops", HomeShortcuts.newHost(board("edge", machine = "far"), far, feed, listOf(Host(id = 6L, protocol = "ssh", username = "", hostname = "far.example", lastConnect = 9L)))!!.username)
        // Saved, the link's row is the one the board finds again.
        val saved = HomeShortcuts.newHost(board("edge", machine = "far"), far, feed, emptyList())!!
        assertEquals(saved, HomeShortcuts.saved(far, feed, listOf(saved)))
        // A link whose address is none of the feed's names for the machine makes no row: it would never be found again.
        assertNull(HomeShortcuts.newHost(board("edge", machine = "far"), far, HomeBoards(hosts = names, links = mapOf("far" to "ssh://ops@far-other.example")), emptyList()))
        assertNull(HomeShortcuts.newHost(board("edge", machine = "new"), Place("new", "edge"), HomeBoards(hosts = names, links = mapOf("new" to "ssh://ops@new.example")), emptyList()))
    }

    @Test
    fun newHost_isNamedAfterTheBoard_freeAmongTheSavedNames() {
        val taken = listOf(plain, Host(id = 4L, nickname = "work", hostname = "elsewhere"), Host(id = 5L, nickname = "work 2 ", hostname = "elsewhere"))
        assertEquals("work 3", HomeShortcuts.newHost(board("work"), work, feed, taken)!!.nickname)
        assertEquals("trimmed", HomeShortcuts.newHost(board(" trimmed ", session = "work"), work, feed, listOf(plain))!!.nickname)
    }

    @Test
    fun newHost_isNull_withNoAccount_noNameOrABadSession() {
        assertNull("no saved host and no link", HomeShortcuts.newHost(board("work", machine = "none"), Place("none", "work"), feed, listOf(plain)))
        assertNull("a link that is not SSH", HomeShortcuts.newHost(board("work", machine = "bare"), Place("bare", "work"), feed, listOf(plain)))
        assertNull("a link with no account", HomeShortcuts.newHost(board("work", machine = "far"), Place("far", "work"), HomeBoards(hosts = names, links = mapOf("far" to "ssh://far.example")), emptyList()))
        assertNull("not a session Herdr could have given", HomeShortcuts.newHost(board("work", session = "no way"), Place("box", "no way"), feed, listOf(plain)))
        assertNull("a board with no name", HomeShortcuts.newHost(board("  ", session = "work"), work, feed, listOf(plain)))
        assertNull("a telnet host on the machine is no account for SSH", HomeShortcuts.newHost(board("work"), work, feed, listOf(plain.copy(protocol = "telnet"))))
    }
}
