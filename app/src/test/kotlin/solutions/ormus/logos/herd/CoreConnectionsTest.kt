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

import kotlinx.coroutines.runBlocking
import org.connectbot.data.entity.Host
import org.connectbot.transport.CommandOutput
import org.connectbot.transport.HeadlessSession
import org.connectbot.transport.HeadlessSsh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreConnectionsTest {
    private class Fake : HeadlessSession {
        override fun login(keys: List<java.security.KeyPair>) = HeadlessSsh.Login.Ok

        override fun exec(command: String) = CommandOutput("", "", 0)

        override fun close() = Unit
    }

    // A pool whose lingers wait until the test runs them, and whose closes are counted.
    private class Rig(linger: Long = 60_000L) {
        var now = 0L
        val pending = mutableListOf<() -> Unit>()
        val closed = mutableListOf<HeadlessSession>()
        val pool = CoreConnections(linger, { now }, { _, run -> pending += run }, { closed += it })

        fun endLingers() {
            val all = pending.toList()
            pending.clear()
            all.forEach { it() }
        }
    }

    private val inbox = Any()
    private val console = Any()

    @Test fun `a link is shared by its holders and closes when the last one lets go and the linger ends`() {
        val rig = Rig()
        val s = Fake()
        assertSame(s, rig.pool.put("sg", inbox, s))
        assertSame(s, rig.pool.join("sg", console))
        rig.pool.release("sg", inbox)
        assertTrue(rig.pending.isEmpty())
        rig.pool.release("sg", console)
        assertTrue(rig.closed.isEmpty())
        rig.endLingers()
        assertEquals(listOf<HeadlessSession>(s), rig.closed)
        assertNull(rig.pool.join("sg", inbox))
    }

    @Test fun `a link held again during its linger stays open`() {
        val rig = Rig()
        val s = Fake()
        rig.pool.put("sg", inbox, s)
        rig.pool.release("sg", inbox)
        assertSame(s, rig.pool.join("sg", console))
        rig.endLingers()
        assertTrue(rig.closed.isEmpty())
        // Let go again: this linger is the latest, so it closes.
        rig.pool.release("sg", console)
        rig.endLingers()
        assertEquals(listOf<HeadlessSession>(s), rig.closed)
    }

    @Test fun `an older linger does not close a link let go again later`() {
        val rig = Rig()
        val s = Fake()
        rig.pool.put("sg", inbox, s)
        rig.pool.release("sg", inbox)
        val first = rig.pending.removeAt(0)
        rig.pool.join("sg", inbox)
        rig.pool.release("sg", inbox)
        first()
        assertTrue(rig.closed.isEmpty())
        rig.endLingers()
        assertEquals(listOf<HeadlessSession>(s), rig.closed)
    }

    @Test fun `with no linger the last let-go closes at once`() {
        val rig = Rig(linger = 0)
        val s = Fake()
        rig.pool.put("sg", inbox, s)
        rig.pool.release("sg", inbox)
        assertEquals(listOf<HeadlessSession>(s), rig.closed)
    }

    @Test fun `holding twice is holding once`() {
        val rig = Rig(linger = 0)
        val s = Fake()
        rig.pool.put("sg", inbox, s)
        rig.pool.join("sg", inbox)
        rig.pool.release("sg", inbox)
        assertEquals(listOf<HeadlessSession>(s), rig.closed)
    }

    @Test fun `a second login for a machine that has one is closed, and the first is kept`() {
        val rig = Rig()
        val first = Fake()
        val second = Fake()
        rig.pool.put("sg", inbox, first)
        assertSame(first, rig.pool.put("sg", console, second))
        assertEquals(listOf<HeadlessSession>(second), rig.closed)
    }

    @Test fun `a dead link is dropped for every holder, and only its own session can drop it`() {
        val rig = Rig()
        val s = Fake()
        rig.pool.put("sg", inbox, s)
        rig.pool.join("sg", console)
        assertFalse(rig.pool.drop("sg", Fake()))
        assertTrue(rig.pool.drop("sg", s))
        assertEquals(listOf<HeadlessSession>(s), rig.closed)
        assertNull(rig.pool.join("sg", console))
    }

    @Test fun `only a link that answered lately is given as fresh`() {
        val rig = Rig()
        val s = Fake()
        rig.pool.put("sg", inbox, s)
        rig.now = 20_000
        assertNull(rig.pool.joinFresh("sg", console, withinMs = 15_000))
        rig.pool.answered("sg", s)
        rig.now = 30_000
        assertSame(s, rig.pool.joinFresh("sg", console, withinMs = 15_000))
    }

    private fun host(id: Long) = Host(id = id, nickname = "sg", protocol = "ssh", username = "me", hostname = "sg.example", port = 22)

    @Test fun `two screens of one machine share one login, and the next screen finds it after the first left`() = runBlocking {
        val rig = Rig()
        var logins = 0
        val login: suspend (CoreMachines.Machine) -> CoreLinks.Opened = {
            logins++
            CoreLinks.Opened.Session(Fake())
        }
        val inboxLinks = CoreLinks({ emptyList() }, login, pool = rig.pool)
        val spacesLinks = CoreLinks({ emptyList() }, login, pool = rig.pool)
        val m = CoreMachines.group(listOf(host(1))).single()
        assertTrue(inboxLinks.link(m) is CoreLinks.Link.Up)
        assertTrue(spacesLinks.link(m) is CoreLinks.Link.Up)
        assertEquals(1, logins)
        inboxLinks.close()
        spacesLinks.close()
        // Shown again within the linger: no new login.
        inboxLinks.reopen()
        assertTrue(inboxLinks.link(m) is CoreLinks.Link.Up)
        assertEquals(1, logins)
        rig.endLingers()
        assertTrue(rig.closed.isEmpty())
    }

    @Test fun `one screen leaving keeps the link open for the other`() = runBlocking {
        val rig = Rig(linger = 0)
        val login: suspend (CoreMachines.Machine) -> CoreLinks.Opened = { CoreLinks.Opened.Session(Fake()) }
        val inboxLinks = CoreLinks({ emptyList() }, login, pool = rig.pool)
        val spacesLinks = CoreLinks({ emptyList() }, login, pool = rig.pool)
        val m = CoreMachines.group(listOf(host(1))).single()
        inboxLinks.link(m)
        spacesLinks.link(m)
        inboxLinks.close()
        assertTrue(rig.closed.isEmpty())
        spacesLinks.close()
        assertEquals(1, rig.closed.size)
    }

    @Test fun `a visit's let-go never touches the next visit's hold`() = runBlocking {
        val rig = Rig(linger = 0)
        val links = CoreLinks({ emptyList() }, { CoreLinks.Opened.Session(Fake()) }, pool = rig.pool)
        val m = CoreMachines.group(listOf(host(1))).single()
        val other = Any()
        links.link(m)
        // Someone else keeps it open across the visits, so the second visit joins the same login.
        rig.pool.join(CoreMachines.group(listOf(host(1))).single().key, other)
        links.close()
        links.reopen()
        links.link(m)
        rig.pool.release(m.key, other)
        assertTrue(rig.closed.isEmpty())
        links.close()
        assertEquals(1, rig.closed.size)
    }

    @Test fun `a link one screen finds dead is logged in again by the other`() = runBlocking {
        val rig = Rig(linger = 0)
        var logins = 0
        var dead = true
        val login: suspend (CoreMachines.Machine) -> CoreLinks.Opened = {
            logins++
            CoreLinks.Opened.Session(object : HeadlessSession by Fake() {
                override fun exec(command: String) = CommandOutput("", "", if (dead) null else 0)
            })
        }
        val inboxLinks = CoreLinks({ emptyList() }, login, pool = rig.pool)
        val spacesLinks = CoreLinks({ emptyList() }, login, pool = rig.pool)
        val m = CoreMachines.group(listOf(host(1))).single()
        (inboxLinks.link(m) as CoreLinks.Link.Up).run("hc ls")
        assertEquals(1, rig.closed.size)
        dead = false
        assertTrue(spacesLinks.link(m) is CoreLinks.Link.Up)
        assertEquals(2, logins)
    }
}
