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

package solutions.ormus.logos.herd

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.connectbot.data.entity.Host
import org.connectbot.transport.CommandOutput
import org.connectbot.transport.HeadlessSession
import org.connectbot.transport.HeadlessSsh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreMachinesTest {
    private fun host(id: Long, name: String, hostname: String = "Box.example", port: Int = 22, user: String = "u", post: String? = null, protocol: String = "ssh") = Host(id = id, nickname = name, hostname = hostname, port = port, username = user, postLogin = post, protocol = protocol)

    @Test
    fun fiveRowsToOneHostname_areOneMachine_withThePlainHerdrRowFirst() {
        val machines = CoreMachines.group(
            listOf(host(1, "shell"), host(2, "x", post = "herdr session attach 'x'"), host(3, "plain", hostname = "box.example", post = "herdr "), host(4, "y", post = "herdr session attach 'y'"), host(5, "z")),
        )
        assertEquals(1, machines.size)
        assertEquals(3L, machines.single().primary.id)
        assertEquals("plain", machines.single().name)
        assertEquals(5, machines.single().hosts.size)
    }

    @Test
    fun anotherPortUserOrMachine_isAnotherMachine_andOnlySshCounts() {
        val machines = CoreMachines.group(listOf(host(1, "a"), host(2, "b", port = 2222), host(3, "c", user = "v"), host(4, "d", hostname = "other.example"), host(5, "t", protocol = "telnet"), host(6, "e", hostname = " ")))
        assertEquals(listOf(1L, 2L, 3L, 4L), machines.map { it.primary.id })
    }

    @Test
    fun aMachineIsMarkedHerdr_whenAHostOfItStartsHerdrAtLogin() {
        fun marked(post: String?) = CoreMachines.marked(CoreMachines.group(listOf(host(1, "h", post = post))).single())
        assertTrue(marked("herdr"))
        assertTrue(marked("exec herdr"))
        assertTrue(marked("herdr session attach 'work'"))
        assertTrue(marked("/usr/local/bin/herdr"))
        assertTrue(marked("exec ~/.local/bin/herdr session attach 'work'"))
        assertFalse(marked(null))
        assertFalse(marked(""))
        assertFalse(marked("bash"))
        // The word herdr somewhere in a command is not a start of Herdr.
        assertFalse(marked("cd ~/projects/herdr && claude"))
        assertFalse(marked("tail -f /var/log/herdr"))
        assertFalse(marked("ssh other herdr"))
        // It attaches another machine's Herdr: this host is only the way there.
        assertFalse(marked("herdr --remote other"))
    }

    @Test
    fun machines_leavesOutAHostThatIsNotHerdr_andKeepsAMarkedOne() = runBlocking {
        val links = CoreLinks({ listOf(host(1, "client", hostname = "client.example"), host(2, "lab", hostname = "lab.example", post = "herdr")) }, { error("no login") })
        assertEquals(listOf(2L), links.machines().map { it.primary.id })
    }

    @Test
    fun aMachineWithALiveHerdrConsole_isKept_andLearnedForWhenTheConsoleCloses() = runBlocking {
        val learned = HerdrHosts.inMemory()
        val plain = host(1, "plain", hostname = "plain.example")
        val links = CoreLinks({ listOf(plain) }, { error("no login") }, learned = learned)
        assertEquals(emptyList<Long>(), links.machines().map { it.primary.id })
        assertEquals(listOf(1L), links.machines(live = listOf(plain)).map { it.primary.id })
        assertEquals(listOf(1L), links.machines().map { it.primary.id })
        assertEquals(setOf("plain.example:22:u"), learned.learned())
    }

    @Test
    fun aLiveHerdrHostThatIsNotSaved_isRead_too() = runBlocking {
        val links = CoreLinks({ emptyList() }, { error("no login") })
        assertEquals(listOf(7L), links.machines(live = listOf(host(7, "temp", hostname = "temp.example"))).map { it.primary.id })
    }

    @Test
    fun forget_dropsALearnedMachine_butNeverAMarkedOne() = runBlocking {
        val marked = host(1, "lab", hostname = "lab.example", post = "herdr")
        val learnedOnly = host(2, "box", hostname = "box.example")
        val learned = HerdrHosts.inMemory(setOf("lab.example:22:u", "box.example:22:u"))
        val links = CoreLinks({ listOf(marked, learnedOnly) }, { error("no login") }, learned = learned)
        links.machines().forEach { links.forget(it) }
        assertEquals(listOf(1L), links.machines().map { it.primary.id })
        assertEquals(emptySet<String>(), learned.learned())
    }

    @Test
    fun aHostEditedToAPlainShell_forgetsTheMachine_evenWhenAConsoleLearnedItBefore() = runBlocking {
        val lab = host(1, "lab", hostname = "lab.example", post = "herdr")
        var saved = listOf(lab)
        val learned = HerdrHosts.inMemory()
        val links = CoreLinks({ saved }, { error("no login") }, learned = learned)
        // A console found Herdr there: the machine is marked and learned.
        assertEquals(listOf(1L), links.machines(live = listOf(lab)).map { it.primary.id })
        assertEquals(setOf("lab.example:22:u"), learned.learned())

        saved = listOf(lab.copy(postLogin = null))

        assertEquals("edited to a plain shell: no longer a Herdr machine", emptyList<Long>(), links.machines().map { it.primary.id })
        assertEquals(emptySet<String>(), learned.learned())
    }

    @Test
    fun aHostEditedToAPlainShell_whileItsConsoleIsOpen_isReadWhileOpen_andForgottenWhenItCloses() = runBlocking {
        val lab = host(1, "lab", hostname = "lab.example", post = "herdr")
        var saved = listOf(lab)
        val learned = HerdrHosts.inMemory()
        val links = CoreLinks({ saved }, { error("no login") }, learned = learned)
        links.machines(live = listOf(lab))

        saved = listOf(lab.copy(postLogin = null))
        val edited = saved.single()

        // The console is still open: the machine is read, as long as it stays open, and the edit is not learned over.
        repeat(3) { assertEquals(listOf(1L), links.machines(live = listOf(edited)).map { it.primary.id }) }
        assertEquals(emptySet<String>(), learned.learned())

        // The console closes: the edit wins.
        assertEquals(emptyList<Long>(), links.machines().map { it.primary.id })
        assertEquals(emptySet<String>(), learned.learned())
    }

    @Test
    fun aDeletedHost_dropsItsKeyFromTheList_andAKeyNoHostHasIsDropped() = runBlocking {
        val gone = host(1, "gone", hostname = "gone.example")
        val kept = host(2, "kept", hostname = "kept.example")
        var saved = listOf(gone, kept)
        val learned = HerdrHosts.inMemory(setOf("gone.example:22:u", "kept.example:22:u", "never-saved.example:22:u"))
        val links = CoreLinks({ saved }, { error("no login") }, learned = learned)

        assertEquals(setOf(1L, 2L), links.machines().map { it.primary.id }.toSet())
        assertEquals("a key no saved or live host has is dropped", setOf("gone.example:22:u", "kept.example:22:u"), learned.learned())

        saved = listOf(kept)

        assertEquals(listOf(2L), links.machines().map { it.primary.id })
        assertEquals(setOf("kept.example:22:u"), learned.learned())
    }

    @Test
    fun aMachineWithALiveConsole_writesTheListOnce_notOnEachCall() = runBlocking {
        var writes = 0
        var held = emptySet<String>()
        val learned = HerdrHosts({ held }, {
            held = it
            writes++
        })
        val live = host(1, "lab", hostname = "lab.example")
        val links = CoreLinks({ listOf(live) }, { error("no login") }, learned = learned)

        repeat(5) { links.machines(live = listOf(live)) }

        assertEquals(1, writes)
    }

    @Test
    fun theInjectConstructor_keepsTheSavedListItWasGiven() = runBlocking {
        val live = host(1, "lab", hostname = "lab.example")
        val hosts = org.mockito.Mockito.mock(org.connectbot.data.HostRepository::class.java)
        org.mockito.kotlin.whenever(hosts.getHosts()).thenReturn(listOf(live))
        val pubkeys = org.mockito.Mockito.mock(org.connectbot.data.PubkeyRepository::class.java)
        val learned = HerdrHosts.inMemory()

        val links = CoreLinks(hosts, pubkeys, CoreConnections(lingerMs = 0), learned)
        links.machines(live = listOf(live))

        assertEquals("the app's own links write to the list it was given", setOf("lab.example:22:u"), learned.learned())
    }

    private class Fake(val onExec: (String) -> CommandOutput) : HeadlessSession {
        var closed = 0
        override fun login(keys: List<java.security.KeyPair>) = HeadlessSsh.Login.Ok

        override fun exec(command: String) = onExec(command)

        override fun close() {
            closed++
        }
    }

    @Test
    fun aSkippedLogin_isNotRetriedBeforeTheDelay() = runBlocking {
        var now = 0L
        var tries = 0
        val links = CoreLinks({ emptyList() }, {
            tries++
            CoreLinks.Opened.Skipped("why")
        }, { now })
        val m = CoreMachines.group(listOf(host(1, "a"))).single()
        assertEquals("why", (links.link(m) as CoreLinks.Link.Down).reason)
        links.link(m)
        assertEquals(1, tries)
        now = CoreLinks.RETRY_MS
        links.link(m)
        assertEquals(2, tries)
    }

    // A login that cannot be made waits before the next try: a short first delay, then longer ones up to RETRY_MS. Measured on a phone
    // (herd-inbox-reach/MEASURE.md, H4): the network came back a moment after the first failure and the Inbox made no try for 30 s.
    private fun failingLinks(clock: () -> Long, tries: () -> Unit) = CoreLinks({ emptyList() }, {
        tries()
        CoreLinks.Opened.Skipped("could not log in: connect timed out", soon = true)
    }, clock)

    @Test
    fun aFailedLogin_isTriedAgainAfterAShortFirstDelay() = runBlocking {
        var now = 0L
        var tries = 0
        val links = failingLinks({ now }) { tries++ }
        val m = CoreMachines.group(listOf(host(1, "a"))).single()
        links.link(m)
        now = CoreLinks.RETRY_FIRST_MS - 1
        links.link(m)
        assertEquals(1, tries)
        now = CoreLinks.RETRY_FIRST_MS
        links.link(m)
        assertEquals(2, tries)
    }

    @Test
    fun theDelayAfterEachFailure_doubles_untilTheOldLimit() = runBlocking {
        var now = 0L
        var tries = 0
        val links = failingLinks({ now }) { tries++ }
        val m = CoreMachines.group(listOf(host(1, "a"))).single()
        links.link(m)
        val delays = mutableListOf<Long>()
        repeat(5) {
            val before = tries
            // The first moment at which a try is made, in steps of one second.
            val failedAt = now
            while (tries == before) {
                now += 1_000
                links.link(m)
            }
            delays += now - failedAt
        }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L), delays)
        assertEquals(CoreLinks.RETRY_MS, delays.last())
    }

    @Test
    fun aLoginThatWorks_startsTheDelaysAgain() = runBlocking {
        var now = 0L
        var tries = 0
        val results = ArrayDeque(listOf<CoreLinks.Opened>(CoreLinks.Opened.Skipped("x", soon = true), CoreLinks.Opened.Skipped("x", soon = true), CoreLinks.Opened.Session(Fake { CommandOutput("", "", null) })))
        val links = CoreLinks({ emptyList() }, {
            tries++
            results.removeFirstOrNull() ?: CoreLinks.Opened.Skipped("x", soon = true)
        }, { now })
        val m = CoreMachines.group(listOf(host(1, "a"))).single()
        links.link(m)
        now += CoreLinks.RETRY_FIRST_MS
        links.link(m)
        now += 2 * CoreLinks.RETRY_FIRST_MS
        // The third login works; its first command closes early, so the link is gone and the next read logs in again, and fails.
        (links.link(m) as CoreLinks.Link.Up).run("x")
        assertEquals(3, tries)
        links.link(m)
        assertEquals(4, tries)
        now += CoreLinks.RETRY_FIRST_MS - 1
        links.link(m)
        assertEquals("the delay is the first one again, not the third", 4, tries)
        now += 1
        links.link(m)
        assertEquals(5, tries)
    }

    @Test
    fun aConnectionThatClosedEarly_isLoggedInAgainOnTheNextRead() = runBlocking {
        var tries = 0
        val fakes = mutableListOf<Fake>()
        val links = CoreLinks({ emptyList() }, {
            tries++
            CoreLinks.Opened.Session(Fake { CommandOutput("", "", null) }.also { fakes += it })
        })
        val m = CoreMachines.group(listOf(host(1, "a"))).single()
        (links.link(m) as CoreLinks.Link.Up).run("x")
        links.link(m)
        assertEquals(2, tries)
        val end = System.nanoTime() + 2_000_000_000L
        while (fakes[0].closed < 1 && System.nanoTime() < end) Thread.sleep(5)
        assertTrue(fakes[0].closed == 1)
    }

    @Test
    fun twoReadsOfOneMachineAtOnce_shareOneLogin() = runBlocking {
        var tries = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val links = CoreLinks({ emptyList() }, {
            tries++
            gate.await()
            CoreLinks.Opened.Session(Fake { CommandOutput("", "", 0) })
        })
        val m = CoreMachines.group(listOf(host(1, "a"))).single()
        val a = async(kotlinx.coroutines.Dispatchers.Default) { links.link(m) }
        val b = async(kotlinx.coroutines.Dispatchers.Default) { links.link(m) }
        Thread.sleep(100)
        gate.complete(Unit)
        assertTrue(a.await() is CoreLinks.Link.Up && b.await() is CoreLinks.Link.Up)
        assertEquals(1, tries)
    }

    @Test
    fun aLoginThatEndsAfterTheScreenLeft_isClosed_andNothingIsHeldAfterClose() = runBlocking {
        val fakes = mutableListOf<Fake>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        var tries = 0
        val links = CoreLinks({ emptyList() }, {
            tries++
            gate.await()
            CoreLinks.Opened.Session(Fake { CommandOutput("", "", 0) }.also { fakes += it })
        })
        val m = CoreMachines.group(listOf(host(1, "a"))).single()
        val late = async(kotlinx.coroutines.Dispatchers.Default) { links.link(m) }
        Thread.sleep(100)
        links.close()
        gate.complete(Unit)
        assertEquals(CoreLinks.LEFT, (late.await() as CoreLinks.Link.Down).reason)
        val end = System.nanoTime() + 2_000_000_000L
        while (fakes[0].closed < 1 && System.nanoTime() < end) Thread.sleep(5)
        assertEquals(1, fakes[0].closed)
        // A read that starts after the close logs nothing in, until the screen is shown again.
        assertEquals(CoreLinks.LEFT, (links.link(m) as CoreLinks.Link.Down).reason)
        assertEquals(1, tries)
        links.reopen()
        assertTrue(links.link(m) is CoreLinks.Link.Up)
    }

    @Test
    fun aLoginThatThrows_isNotRetriedBeforeTheDelay_evenAcrossAClose() = runBlocking {
        var tries = 0
        val now = 0L
        val links = CoreLinks({ emptyList() }, {
            tries++
            error("boom")
        }, { now })
        val m = CoreMachines.group(listOf(host(1, "a"))).single()
        assertTrue(runCatching { links.link(m) }.isFailure)
        links.close()
        links.reopen()
        assertEquals("boom", (links.link(m) as CoreLinks.Link.Down).reason)
        assertEquals(1, tries)
    }

    // --- Round 1 of the gate lane's review of H4 (~/work/.b2-tmp/gate-reach-h4/REPORT.md) ---

    private val machine1 get() = CoreMachines.group(listOf(host(1, "a"))).single()

    /** The seconds, in steps of one second from the failure, at which [links] first makes a login again; null when none in [limitMs]. */
    private suspend fun firstRetryAt(links: CoreLinks, now: () -> Long, advance: (Long) -> Unit, tries: () -> Int, limitMs: Long = 40_000): Long? {
        val before = tries()
        val failedAt = now()
        while (tries() == before && now() - failedAt < limitMs) {
            advance(1_000)
            links.link(machine1)
        }
        return if (tries() == before) null else now() - failedAt
    }

    private fun retryAfter(opened: CoreLinks.Opened): Long? = runBlocking {
        var now = 0L
        var tries = 0
        val links = CoreLinks({ emptyList() }, {
            tries++
            opened
        }, { now })
        links.link(machine1)
        firstRetryAt(links, { now }, { now += it }, { tries })
    }

    @Test
    fun aLoginThatTookNoKey_waitsThirtySeconds_notFive() {
        val opened = CoreLinks.skippedAfter("it took none of the keys", listOf(HeadlessSsh.Login.KeysRefused))
        assertEquals(false, opened.soon)
        assertEquals(CoreLinks.RETRY_MS, retryAfter(opened))
    }

    @Test
    fun aChangedHostKey_waitsThirtySeconds_notFive() {
        val opened = CoreLinks.skippedAfter("its host key changed: open it in Mercurio", listOf(HeadlessSsh.Login.HostKeyRefused))
        assertEquals(false, opened.soon)
        assertEquals(CoreLinks.RETRY_MS, retryAfter(opened))
    }

    @Test
    fun aConnectFailure_waitsFiveSeconds() {
        val opened = CoreLinks.skippedAfter("could not log in: connect timed out", listOf(HeadlessSsh.Login.Failed("connect timed out")))
        assertEquals(true, opened.soon)
        assertEquals(CoreLinks.RETRY_FIRST_MS, retryAfter(opened))
    }

    @Test
    fun aMachineWhereOneSavedLoginWasRefused_waitsThirtySeconds_evenWhenAnotherOneFailedToConnect() {
        val opened = CoreLinks.skippedAfter("could not log in: connect timed out", listOf(HeadlessSsh.Login.KeysRefused, HeadlessSsh.Login.Failed("connect timed out")))
        assertEquals(false, opened.soon)
    }

    @Test
    fun aLoginThatSentNothing_noKey_noSavedHostKey_orAskingForSomething_waitsThirtySeconds() {
        val opened = CoreLinks.skippedAfter("no key that opens without asking", emptyList())
        assertEquals(false, opened.soon)
        assertEquals(CoreLinks.RETRY_MS, retryAfter(opened))
    }

    // M2 (a): a row of failures that ended with no good login (the user left while the host was down) still counted an hour later.
    @Test
    fun anOldRowOfFailures_doesNotMakeTheFirstFailureOfANewVisitWaitThirtySeconds() = runBlocking {
        var now = 0L
        var tries = 0
        val links = CoreLinks({ emptyList() }, {
            tries++
            CoreLinks.Opened.Skipped("could not log in: connect timed out", soon = true)
        }, { now })
        val m = machine1
        links.link(m)
        now += 5_000
        links.link(m)
        now += 10_000
        links.link(m)
        now += 20_000
        links.link(m)
        assertEquals(4, tries)
        links.close()
        now += 3_600_000
        links.reopen()
        // The first read of the new visit fails once, as the network changes: the short wait, not the fourth one.
        links.link(m)
        assertEquals(5, tries)
        now += CoreLinks.RETRY_FIRST_MS - 1
        links.link(m)
        assertEquals(5, tries)
        now += 1
        links.link(m)
        assertEquals("the first wait again, after an hour", 6, tries)
    }

    @Test
    fun aRowOfFailuresThatStillGoesOn_keepsItsCount_whenTheNextLoginComesAfterTheThirtySecondWait() = runBlocking {
        var now = 0L
        var tries = 0
        val links = CoreLinks({ emptyList() }, {
            tries++
            CoreLinks.Opened.Skipped("could not log in: connect timed out", soon = true)
        }, { now })
        val m = machine1
        links.link(m)
        now += 5_000
        links.link(m)
        now += 10_000
        links.link(m)
        now += 20_000
        links.link(m)
        assertEquals(4, tries)
        // The fifth login comes after the 30 s wait: the count goes on, and the wait stays 30 s.
        now += CoreLinks.RETRY_MS
        links.link(m)
        assertEquals(5, tries)
        now += CoreLinks.RETRY_MS - 1
        links.link(m)
        assertEquals(5, tries)
        now += 1
        links.link(m)
        assertEquals(6, tries)
    }

    // M2 (b): the Inbox and Spaces each have their own CoreLinks and share the pool; a good login of the other one ended the row.
    @Test
    fun aGoodLoginOfTheOtherScreen_endsTheRowOfFailures() = runBlocking {
        var now = 0L
        val pool = CoreConnections(lingerMs = 0)
        var dead = false
        var spacesTries = 0
        val spaces = CoreLinks({ emptyList() }, {
            spacesTries++
            CoreLinks.Opened.Skipped("could not log in: connect timed out", soon = true)
        }, { now }, pool)
        val inbox = CoreLinks({ emptyList() }, { CoreLinks.Opened.Session(Fake { if (dead) CommandOutput("", "", null) else CommandOutput("ok", "", 0) }) }, { now }, pool)
        val m = machine1
        spaces.link(m)
        now += 5_000
        spaces.link(m)
        now += 10_000
        spaces.link(m)
        now += 20_000
        spaces.link(m)
        assertEquals(4, spacesTries)
        spaces.close()
        // The network is back: the Inbox logs in. Spaces shows again and reads over that login, with no login of its own.
        now += 60_000
        assertEquals(0, (inbox.link(m) as CoreLinks.Link.Up).run("x")?.exitCode)
        spaces.reopen()
        assertEquals(0, (spaces.link(m) as CoreLinks.Link.Up).run("x")?.exitCode)
        assertEquals(4, spacesTries)
        // Within two minutes of the last failure (70 s, so the age of the old row does not decide): the link dies under Spaces, and its next login fails once.
        now += 10_000
        dead = true
        (spaces.link(m) as CoreLinks.Link.Up).run("x")
        spaces.link(m)
        assertEquals(5, spacesTries)
        now += CoreLinks.RETRY_FIRST_MS - 1
        spaces.link(m)
        assertEquals(5, spacesTries)
        now += 1
        spaces.link(m)
        assertEquals("the first wait again, not the fifth", 6, spacesTries)
        inbox.close()
    }

    // N2: two machines keep their own counts; a login that throws waits longer each time.
    @Test
    fun twoMachines_doNotShareTheCountOfFailures() = runBlocking {
        var now = 0L
        val tries = mutableMapOf<Long, Int>()
        val links = CoreLinks({ emptyList() }, { m ->
            tries.merge(m.primary.id, 1, Int::plus)
            CoreLinks.Opened.Skipped("could not log in: connect timed out", soon = true)
        }, { now })
        val a = CoreMachines.group(listOf(host(1, "a", hostname = "a.example"))).single()
        val b = CoreMachines.group(listOf(host(2, "b", hostname = "b.example"))).single()
        links.link(a)
        now += 5_000
        links.link(a)
        now += 10_000
        links.link(a)
        // a has failed three times; b fails for the first time now and has the first wait.
        links.link(b)
        now += CoreLinks.RETRY_FIRST_MS
        links.link(b)
        links.link(a)
        assertEquals(mapOf(1L to 3, 2L to 2), tries.toMap())
    }

    @Test
    fun aLoginThatThrows_waitsLongerEachTime() = runBlocking {
        var now = 0L
        var tries = 0
        val links = CoreLinks({ emptyList() }, {
            tries++
            error("boom")
        }, { now })
        val m = machine1
        val delays = mutableListOf<Long>()
        runCatching { links.link(m) }
        repeat(5) {
            val before = tries
            val failedAt = now
            while (tries == before) {
                now += 1_000
                runCatching { links.link(m) }
            }
            delays += now - failedAt
        }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L), delays)
    }

    // N5: a failure of another kind starts the count at one: a refusal (the flat 30 s) and then a connect failure waits the first short wait.
    @Test
    fun aConnectFailureAfterARefusal_startsTheShortWaitAtOne() = runBlocking {
        var now = 0L
        var tries = 0
        val results = ArrayDeque(listOf(CoreLinks.Opened.Skipped("it took none of the keys"), CoreLinks.Opened.Skipped("could not log in: connect timed out", soon = true)))
        val links = CoreLinks({ emptyList() }, {
            tries++
            results.removeFirstOrNull() ?: CoreLinks.Opened.Skipped("could not log in: connect timed out", soon = true)
        }, { now })
        val m = machine1
        links.link(m)
        now = CoreLinks.RETRY_MS
        links.link(m)
        assertEquals(2, tries)
        now += CoreLinks.RETRY_FIRST_MS - 1
        links.link(m)
        assertEquals(2, tries)
        now += 1
        links.link(m)
        assertEquals("the first short wait, not the second", 3, tries)
    }
}
