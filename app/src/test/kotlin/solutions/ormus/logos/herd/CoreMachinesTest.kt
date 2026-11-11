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
        val links = CoreLinks({ emptyList() }, {
            tries++
            error("boom")
        })
        val m = CoreMachines.group(listOf(host(1, "a"))).single()
        assertTrue(runCatching { links.link(m) }.isFailure)
        links.close()
        links.reopen()
        assertEquals("boom", (links.link(m) as CoreLinks.Link.Down).reason)
        assertEquals(1, tries)
    }
}
