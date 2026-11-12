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

package org.connectbot.transport

import com.trilead.ssh2.Connection
import com.trilead.ssh2.Session
import org.connectbot.data.entity.Host
import org.connectbot.service.DisconnectReason
import org.connectbot.service.TerminalBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import solutions.ormus.logos.herd.CoreConnections
import solutions.ormus.logos.herd.CoreMachines
import java.io.IOException

/** A console that shows a herdr-core terminal opens its session on the link the app already signed in. */
class SSHSharedLinkTest {
    private val attach = "bash -lc 'exec hc attach '\\''t7'\\'''"

    // Nothing listens on port 1 of the loopback: a console that logs in on its own is refused at once.
    private val host = Host(id = 1, nickname = "sg", protocol = "ssh", username = "me", hostname = "127.0.0.1", port = 1)
    private val key = CoreMachines.keyOf(host)

    private class Shared(override val lent: Connection?) : HeadlessSession {
        var lost: ((Throwable) -> Unit)? = null
        var handles = 0
        var execs = 0
        var onExec: () -> CommandOutput = { CommandOutput("", "", 0) }

        override fun login(keys: List<java.security.KeyPair>) = HeadlessSsh.Login.Ok

        override fun exec(command: String): CommandOutput {
            execs++
            return onExec()
        }

        override fun onLost(lost: (Throwable) -> Unit): AutoCloseable {
            this.lost = lost
            handles++
            return AutoCloseable {
                this.lost = null
                handles--
            }
        }

        override fun close() = Unit
    }

    private class Rig {
        var now = 0L
        val closed = mutableListOf<HeadlessSession>()
        val pool = CoreConnections(0, { now }, { _, run -> run() }, { closed += it })
    }

    private val inbox = Any()

    private fun console(rig: Rig, command: String? = attach): Pair<SSH, TerminalBridge> {
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.sessionCommand).thenReturn(command)
        val ssh = SSH().apply {
            setBridge(bridge)
            this.host = this@SSHSharedLinkTest.host
            sharedLinks = rig.pool
        }
        return ssh to bridge
    }

    private fun lent(session: Session = mock(Session::class.java)): Connection = mock(Connection::class.java).also { `when`(it.openSession()).thenReturn(session) }

    @Test
    fun aCoreConsoleOpensItsTerminalOnAFreshSharedLink_withNoLoginOfItsOwn() {
        val rig = Rig()
        val session = mock(Session::class.java)
        val connection = lent(session)
        rig.pool.put(key, inbox, Shared(connection))
        val (ssh, bridge) = console(rig)

        ssh.connect()

        verify(connection).openSession()
        verify(session).requestPTY(any(), anyInt(), anyInt(), anyInt(), anyInt(), any())
        verify(session).execCommand(attach)
        verify(bridge).onConnected()
        verify(bridge, never()).dispatchDisconnect(DisconnectReason.IO_ERROR)
        assertTrue(ssh.isSessionOpen())
    }

    @Test
    fun closingTheConsoleClosesItsSessionOnly_andTheLinkClosesWhenTheLastHolderLetsGo() {
        val rig = Rig()
        val session = mock(Session::class.java)
        val connection = lent(session)
        val shared = Shared(connection)
        rig.pool.put(key, inbox, shared)
        val (ssh, _) = console(rig)
        ssh.connect()

        // The Inbox leaves while the console shows: the console still holds the link.
        rig.pool.release(key, inbox)
        assertTrue(rig.closed.isEmpty())

        ssh.close()

        verify(session).close()
        verify(connection, never()).close()
        assertEquals(0, shared.handles)
        assertEquals(listOf<HeadlessSession>(shared), rig.closed)
    }

    @Test
    fun aStaleLinkThatAnswersTheProbe_isBorrowed_withNoSecondLogin() {
        val rig = Rig()
        val session = mock(Session::class.java)
        val connection = lent(session)
        val shared = Shared(connection)
        rig.pool.put(key, inbox, shared)
        rig.now = SSH.SHARED_FRESH_MS + 1
        val (ssh, bridge) = console(rig)

        ssh.connect()

        assertEquals(1, shared.execs)
        verify(connection).openSession()
        verify(session).execCommand(attach)
        verify(bridge, never()).dispatchDisconnect(DisconnectReason.IO_ERROR)
        assertTrue(ssh.isSessionOpen())
        // The answer made it fresh: the next console borrows with no probe.
        val (next, _) = console(rig)
        next.connect()
        assertEquals(1, shared.execs)
        assertTrue(next.isSessionOpen())
    }

    @Test
    fun aStaleLinkThatFailsTheProbe_isLetGo_andTheConsoleLogsInAsBefore() {
        val rig = Rig()
        val connection = lent()
        val shared = Shared(connection)
        shared.onExec = { throw IOException("connection reset") }
        rig.pool.put(key, inbox, shared)
        rig.now = SSH.SHARED_FRESH_MS + 1
        val (ssh, bridge) = console(rig)

        ssh.connect()

        assertEquals(1, shared.execs)
        verify(connection, never()).openSession()
        assertEquals(0, shared.handles)
        // It logged in on its own instead: refused here, so the console ends with the reason (the catch
        // and the connection's monitor both report it; the bridge takes the first).
        verify(bridge, atLeastOnce()).dispatchDisconnect(DisconnectReason.IO_ERROR)
        // Nobody is lent the failed link again.
        assertEquals(listOf<HeadlessSession>(shared), rig.closed)
    }

    @Test
    fun aStaleLinkThatDoesNotAnswerTheProbeInTime_isNotWaitedFor_andStaysForTheOthers() {
        val rig = Rig()
        val connection = lent()
        val shared = Shared(connection)
        val stuck = java.util.concurrent.CountDownLatch(1)
        shared.onExec = {
            stuck.await()
            CommandOutput("", "", 0)
        }
        rig.pool.put(key, inbox, shared)
        rig.now = SSH.SHARED_FRESH_MS + 1
        val (ssh, bridge) = console(rig)
        ssh.sharedProbeMs = 50

        try {
            ssh.connect()
        } finally {
            stuck.countDown()
        }

        verify(connection, never()).openSession()
        verify(bridge, atLeastOnce()).dispatchDisconnect(DisconnectReason.IO_ERROR)
        // A slow answer is weak evidence: the link stays for the Inbox, and the next open probes again.
        assertEquals(0, shared.handles)
        assertTrue(rig.closed.isEmpty())
        rig.pool.release(key, inbox)
        assertEquals(listOf<HeadlessSession>(shared), rig.closed)
    }

    @Test
    fun aProbeThatExitsNonZero_stillCountsAsAnAnswer() {
        // A Windows host or a refused exec channel answers `true` with an error status: the link is alive.
        val rig = Rig()
        val connection = lent()
        val shared = Shared(connection)
        shared.onExec = { CommandOutput("", "not recognized", 9009) }
        rig.pool.put(key, inbox, shared)
        rig.now = SSH.SHARED_FRESH_MS + 1
        val (ssh, _) = console(rig)

        ssh.connect()

        verify(connection).openSession()
        assertTrue(ssh.isSessionOpen())
        assertTrue(rig.closed.isEmpty())
    }

    @Test
    fun aConsoleClosedDuringTheProbe_leavesNoHolder_andOpensNoSession() {
        val rig = Rig()
        val connection = lent()
        val shared = Shared(connection)
        val stuck = java.util.concurrent.CountDownLatch(1)
        shared.onExec = {
            stuck.await()
            CommandOutput("", "", 0)
        }
        rig.pool.put(key, inbox, shared)
        rig.now = SSH.SHARED_FRESH_MS + 1
        val (ssh, _) = console(rig)
        ssh.sharedProbeMs = 5_000
        val connecting = Thread { ssh.connect() }.apply { start() }
        while (shared.execs == 0) Thread.sleep(5)

        ssh.close()
        stuck.countDown()
        connecting.join()

        verify(connection, never()).openSession()
        assertEquals(0, shared.handles)
        // No leaked hold: the Inbox letting go is the last, and the link closes.
        rig.pool.release(key, inbox)
        assertEquals(listOf<HeadlessSession>(shared), rig.closed)
    }

    @Test
    fun aProbeInterrupted_releasesTheLink_withoutRetiringIt() {
        val rig = Rig()
        val connection = lent()
        val shared = Shared(connection)
        val stuck = java.util.concurrent.CountDownLatch(1)
        shared.onExec = {
            stuck.await()
            CommandOutput("", "", 0)
        }
        rig.pool.put(key, inbox, shared)
        rig.now = SSH.SHARED_FRESH_MS + 1
        val (ssh, _) = console(rig)
        ssh.sharedProbeMs = 5_000
        val connecting = Thread { ssh.connect() }.apply { start() }
        while (shared.execs == 0) Thread.sleep(5)

        connecting.interrupt()
        connecting.join()
        stuck.countDown()

        verify(connection, never()).openSession()
        assertTrue(rig.closed.isEmpty())
        rig.pool.release(key, inbox)
        assertEquals(listOf<HeadlessSession>(shared), rig.closed)
    }

    @Test
    fun consolesOnOneStaleLink_shareOneProbe() {
        val rig = Rig()
        val shared = Shared(lent())
        val stuck = java.util.concurrent.CountDownLatch(1)
        shared.onExec = {
            stuck.await()
            CommandOutput("", "", 0)
        }
        rig.pool.put(key, inbox, shared)
        rig.now = SSH.SHARED_FRESH_MS + 1
        val consoles = List(3) { console(rig).first.also { it.sharedProbeMs = 5_000 } }
        val threads = consoles.map { c -> Thread { c.connect() }.apply { start() } }
        while (shared.execs == 0) Thread.sleep(5)
        Thread.sleep(200)

        stuck.countDown()
        threads.forEach { it.join() }

        assertEquals(1, shared.execs)
        consoles.forEach { assertTrue(it.isSessionOpen()) }
    }

    @Test
    fun aLinkRetiredByAnotherReaderDuringTheProbe_stillServesTheConsole_andClosesWhenItLetsGo() {
        val rig = Rig()
        val shared = Shared(lent())
        val stuck = java.util.concurrent.CountDownLatch(1)
        shared.onExec = {
            stuck.await()
            CommandOutput("", "", 0)
        }
        rig.pool.put(key, inbox, shared)
        rig.now = SSH.SHARED_FRESH_MS + 1
        val (ssh, _) = console(rig)
        ssh.sharedProbeMs = 5_000
        val connecting = Thread { ssh.connect() }.apply { start() }
        while (shared.execs == 0) Thread.sleep(5)

        // The Inbox's read failed meanwhile and retired the link; the console is a borrower, so it stays open.
        rig.pool.retire(key, shared)
        stuck.countDown()
        connecting.join()

        assertTrue(ssh.isSessionOpen())
        assertTrue(rig.closed.isEmpty())
        ssh.close()
        assertEquals(listOf<HeadlessSession>(shared), rig.closed)
    }

    @Test
    fun aFreshLink_isBorrowedWithNoProbe() {
        val rig = Rig()
        val shared = Shared(lent())
        rig.pool.put(key, inbox, shared)
        rig.now = SSH.SHARED_FRESH_MS
        val (ssh, _) = console(rig)

        ssh.connect()

        assertEquals(0, shared.execs)
        assertTrue(ssh.isSessionOpen())
    }

    @Test
    fun aConsoleThatRunsNoCommandOfItsOwn_neverBorrows() {
        val rig = Rig()
        val connection = lent()
        rig.pool.put(key, inbox, Shared(connection))
        val (ssh, _) = console(rig, command = null)

        ssh.connect()

        verify(connection, never()).openSession()
    }

    @Test
    fun aSharedLinkWhoseChannelWillNotOpen_isLetGo_andTheConsoleLogsInAsBefore() {
        val rig = Rig()
        val connection = mock(Connection::class.java)
        `when`(connection.openSession()).thenThrow(IOException("channel refused"))
        val shared = Shared(connection)
        rig.pool.put(key, inbox, shared)
        val (ssh, bridge) = console(rig)

        ssh.connect()

        assertFalse(ssh.isSessionOpen())
        assertEquals(0, shared.handles)
        verify(bridge, atLeastOnce()).dispatchDisconnect(DisconnectReason.IO_ERROR)
        // Only the Inbox holds it now.
        rig.pool.release(key, inbox)
        assertEquals(listOf<HeadlessSession>(shared), rig.closed)
    }

    @Test
    fun theLossOfTheSharedLink_endsTheConsole() {
        val rig = Rig()
        val shared = Shared(lent())
        rig.pool.put(key, inbox, shared)
        val (ssh, bridge) = console(rig)
        ssh.connect()

        shared.lost!!.invoke(IOException("link down"))

        verify(bridge, atLeastOnce()).dispatchDisconnect(DisconnectReason.IO_ERROR)
    }

    @Test
    fun aLoginWithNothingToLend_isNotBorrowed() {
        val rig = Rig()
        val shared = Shared(lent = null)
        rig.pool.put(key, inbox, shared)
        val (ssh, bridge) = console(rig)

        ssh.connect()

        verify(bridge, atLeastOnce()).dispatchDisconnect(DisconnectReason.IO_ERROR)
        rig.pool.release(key, inbox)
        assertEquals(listOf<HeadlessSession>(shared), rig.closed)
    }

    @Test
    fun twoClosesAtOnce_neverCloseTheSharedLink() {
        // The end of an attach closes twice: read() after REMOTE_EOF, and dispatchDisconnect on the IO dispatcher.
        repeat(50) {
            val rig = Rig()
            val connection = lent()
            rig.pool.put(key, inbox, Shared(connection))
            val (ssh, _) = console(rig)
            ssh.connect()
            val start = java.util.concurrent.CountDownLatch(1)
            val closers = List(2) {
                Thread {
                    start.await()
                    ssh.close()
                }.apply { start() }
            }
            start.countDown()
            closers.forEach { it.join() }
            verify(connection, never()).close()
        }
    }

    @Test
    fun aHostThatForwardsTheAuthAgent_neverBorrows() {
        val rig = Rig()
        val connection = lent()
        rig.pool.put(key, inbox, Shared(connection))
        val (ssh, _) = console(rig)
        ssh.setUseAuthAgent("yes")

        ssh.connect()

        verify(connection, never()).openSession()
    }

    @Test
    fun aSharedLinkClosedMeanwhile_isLetGo_andTheConsoleLogsInAsBefore() {
        val rig = Rig()
        val connection = mock(Connection::class.java)
        `when`(connection.openSession()).thenThrow(IllegalStateException("You need to establish a connection first."))
        val shared = Shared(connection)
        rig.pool.put(key, inbox, shared)
        val (ssh, bridge) = console(rig)

        ssh.connect()

        assertFalse(ssh.isSessionOpen())
        verify(bridge, atLeastOnce()).dispatchDisconnect(DisconnectReason.IO_ERROR)
        assertEquals(0, shared.handles)
    }

    @Test
    fun aLossOfTheLinkAfterTheConsoleLetGo_isNotTheConsoles() {
        val rig = Rig()
        val shared = Shared(lent())
        rig.pool.put(key, inbox, shared)
        val (ssh, bridge) = console(rig)
        ssh.connect()
        val lost = shared.lost!!

        ssh.close()
        lost.invoke(IOException("link down"))

        verify(bridge, never()).dispatchDisconnect(DisconnectReason.IO_ERROR)
    }

    @Test
    fun aFailureOfTheBridgeAfterTheTerminalOpened_isNotTakenForAFailureOfTheLink() {
        val rig = Rig()
        val connection = lent()
        rig.pool.put(key, inbox, Shared(connection))
        val (ssh, bridge) = console(rig)
        `when`(bridge.onConnected()).thenThrow(IllegalStateException("bridge broke"))

        val thrown = runCatching { ssh.connect() }.exceptionOrNull()

        assertEquals("bridge broke", thrown?.message)
        verify(bridge, never()).dispatchDisconnect(DisconnectReason.IO_ERROR)
    }

    @Test
    fun aClosedConsole_runsNothingMoreOnTheSharedLink() {
        val rig = Rig()
        rig.pool.put(key, inbox, Shared(lent()))
        val (ssh, _) = console(rig)
        ssh.connect()

        ssh.close()

        assertTrue(runCatching { ssh.execDetailed("hc ls") }.exceptionOrNull() is IllegalStateException)
    }
}
