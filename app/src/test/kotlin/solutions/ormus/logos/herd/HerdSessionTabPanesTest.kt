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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.connectbot.transport.CommandOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The tab layout a console reads for a watched herdr-core terminal: [HerdSession.tabPanes], polled only while it is collected,
 * with the same read the Spaces list makes (one `doc.get` through the login shell), on a fake host and a virtual clock.
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlin.ExperimentalStdlibApi::class)
class HerdSessionTabPanesTest {
    private fun block(terminal: String) = """{"id":"p$terminal","type":"block","content":{"terminal":"$terminal"}}"""

    private fun docOf(tabs: List<Pair<String, Array<String>>>) = """{"id":1,"jsonrpc":"2.0","result":{"version":4,"value":{"spaces":[{"id":"s1","name":"alpha · ONE","folder":"/w","tabs":[${tabs.joinToString(",") { (name, terminals) -> """{"id":"tab-$name","name":"$name","root":{"id":"r","type":"row","children":[${terminals.joinToString(",") { block(it) }}]}}""" }}]}]}}}"""

    private fun doc(vararg terminals: String) = """{"id":1,"jsonrpc":"2.0","result":{"version":4,"value":{"spaces":[{"id":"s1","name":"alpha · ONE","folder":"/w","tabs":[{"id":"a","name":"work","root":{"id":"r","type":"row","children":[${terminals.joinToString(",") { block(it) }}]}}]}]}}}"""

    /** A host that answers the spaces read from [answer], and logs every command it was given. */
    private class Host(var answer: () -> CommandOutput?) {
        val ran = mutableListOf<String>()

        fun run(command: String): CommandOutput? {
            ran += command
            return if (CoreSpaces.COMMAND == command) answer() else CommandOutput("", "", 0)
        }

        fun spacesReads() = ran.count { it == CoreSpaces.COMMAND }
    }

    private fun out(text: String) = CommandOutput(text, "", 0)

    /** The watch a console has for [address]: the space and tab of the fixture's one tab. */
    private fun watch(address: String) = CoreWatchTarget(address, "alpha · ONE", "work")

    private fun TestScope.session(host: Host, enabled: () -> Boolean = { true }) = HerdSession(
        hostName = "box",
        runner = host::run,
        sendKeys = {},
        scope = backgroundScope,
        io = coroutineContext[CoroutineDispatcher]!!,
        pollIntervalMs = 2_500,
        coreEnabled = enabled,
    )

    @Test
    fun `nothing is read until someone collects, and nothing after they stop`() = runTest {
        val host = Host { out(doc("t7", "t8")) }
        val s = session(host)
        val flow = s.tabPanes(watch("t7"), periodMs = 10_000)
        advanceTimeBy(60_000)
        assertEquals(0, host.spacesReads())
        val job = flow.onEach { }.launchIn(this)
        advanceTimeBy(100)
        assertEquals(1, host.spacesReads())
        job.cancel()
        val after = host.spacesReads()
        advanceTimeBy(60_000)
        assertEquals(after, host.spacesReads())
    }

    @Test
    fun `the first answer comes at once, with the panes of the tab and the watched one`() = runTest {
        val host = Host { out(doc("t7", "t8")) }
        val seen = mutableListOf<CoreTabPanes.TabPanes?>()
        val job = session(host).tabPanes(watch("t8"), periodMs = 10_000).onEach { seen += it }.launchIn(this)
        advanceTimeBy(100)
        val tabPanes = assertNotNull(seen.single())
        assertEquals(listOf("t7", "t8"), tabPanes.panes.map { it.terminal })
        assertEquals("t8", tabPanes.watched?.terminal)
        job.cancel()
    }

    @Test
    fun `it reads again each period, and a changed layout is a new answer`() = runTest {
        var terminals = arrayOf("t7", "t8")
        val host = Host { out(doc(*terminals)) }
        val seen = mutableListOf<CoreTabPanes.TabPanes?>()
        val job = session(host).tabPanes(watch("t7"), periodMs = 10_000).onEach { seen += it }.launchIn(this)
        advanceTimeBy(100)
        terminals = arrayOf("t7", "t8", "t9")
        advanceTimeBy(10_000)
        assertEquals(2, host.spacesReads())
        assertEquals(listOf(2, 3), seen.map { it?.panes?.size })
        job.cancel()
    }

    @Test
    fun `an unchanged layout is not announced again`() = runTest {
        val host = Host { out(doc("t7", "t8")) }
        val seen = mutableListOf<CoreTabPanes.TabPanes?>()
        val job = session(host).tabPanes(watch("t7"), periodMs = 10_000).onEach { seen += it }.launchIn(this)
        advanceTimeBy(35_000)
        assertEquals(4, host.spacesReads())
        assertEquals(1, seen.size)
        job.cancel()
    }

    @Test
    fun `it asks the host the way the Spaces list does`() = runTest {
        val host = Host { out(doc("t7")) }
        val job = session(host).tabPanes(watch("t7"), periodMs = 10_000).onEach { }.launchIn(this)
        advanceTimeBy(100)
        assertEquals(listOf(CoreSpaces.COMMAND), host.ran)
        job.cancel()
    }

    @Test
    fun `the setting off runs no command and says there are no panes`() = runTest {
        val host = Host { out(doc("t7", "t8")) }
        var on = true
        val seen = mutableListOf<CoreTabPanes.TabPanes?>()
        val job = session(host, enabled = { on }).tabPanes(watch("t7"), periodMs = 10_000).onEach { seen += it }.launchIn(this)
        advanceTimeBy(100)
        assertNotNull(seen.last())
        on = false
        val reads = host.spacesReads()
        advanceTimeBy(10_000)
        assertEquals(reads, host.spacesReads())
        assertNull(seen.last())
        job.cancel()
    }

    @Test
    fun `one failed read keeps the last panes, a second one drops them, and a good one brings them back`() = runTest {
        var answer: CommandOutput? = out(doc("t7", "t8"))
        val host = Host { answer }
        val seen = mutableListOf<CoreTabPanes.TabPanes?>()
        val job = session(host).tabPanes(watch("t7"), periodMs = 10_000).onEach { seen += it }.launchIn(this)
        advanceTimeBy(100)
        answer = null
        advanceTimeBy(10_000)
        assertEquals(1, seen.size, "one miss: the last panes stay (nothing new is announced)")
        advanceTimeBy(10_000)
        assertNull(seen.last(), "two misses: no panes")
        assertEquals(2, seen.size)
        answer = out(doc("t7", "t8"))
        advanceTimeBy(10_000)
        assertNotNull(seen.last())
        assertEquals(3, seen.size)
        job.cancel()
    }

    @Test
    fun `only misses in a row count - a good read between two failed ones keeps the panes`() = runTest {
        var answer: CommandOutput? = out(doc("t7", "t8"))
        val host = Host { answer }
        val seen = mutableListOf<CoreTabPanes.TabPanes?>()
        val job = session(host).tabPanes(watch("t7"), periodMs = 10_000).onEach { seen += it }.launchIn(this)
        advanceTimeBy(100)
        answer = null
        advanceTimeBy(10_000)
        answer = out(doc("t7", "t8"))
        advanceTimeBy(10_000)
        answer = null
        advanceTimeBy(10_000)
        assertNotNull(seen.last(), "the second miss is not a second one in a row")
        assertEquals(1, seen.size)
        job.cancel()
    }

    @Test
    fun `a host with no herdr-core gives no panes, and a terminal its tab does not draw gets the tab, with none watched`() = runTest {
        val seen = mutableListOf<CoreTabPanes.TabPanes?>()
        val gone = Host { CommandOutput("", "command not found", 127) }
        val job = session(gone).tabPanes(watch("t7"), periodMs = 10_000).onEach { seen += it }.launchIn(this)
        advanceTimeBy(100)
        assertNull(seen.single())
        job.cancel()
        val other = Host { out(doc("t1", "t2")) }
        val seen2 = mutableListOf<CoreTabPanes.TabPanes?>()
        val job2 = session(other).tabPanes(watch("t7"), periodMs = 10_000).onEach { seen2 += it }.launchIn(this)
        advanceTimeBy(100)
        val named = assertNotNull(seen2.single())
        assertEquals(listOf("t1", "t2"), named.panes.map { it.terminal })
        assertNull(named.watched)
        job2.cancel()
    }

    @Test
    fun `a host without herdr-core is no Herdr fault`() = runTest {
        val host = Host { null }
        val s = session(host)
        val job = s.tabPanes(watch("t7"), periodMs = 10_000).onEach { }.launchIn(this)
        advanceTimeBy(100)
        assertNull(s.failure.value, "a host without herdr-core is no Herdr fault")
        assertTrue(host.spacesReads() >= 1)
        job.cancel()
    }

    // Gate lane L1: the read is a blocking exec (up to 15 s). A console collects on the main thread, so it must run on the session's io
    // dispatcher, not on the collector's. A real executor, so the thread of the read can be told from the collector's.
    @Test
    fun `the read runs on the session's io dispatcher, not on the collector's thread`() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "tab-panes-io") }
        try {
            val readOn = mutableListOf<String>()
            val host = Host { out(doc("t7", "t8")) }
            val s = HerdSession(
                hostName = "box",
                runner = { command ->
                    if (command == CoreSpaces.COMMAND) readOn += Thread.currentThread().name
                    host.run(command)
                },
                sendKeys = {},
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
                io = executor.asCoroutineDispatcher(),
                coreEnabled = { true },
            )
            val first = kotlinx.coroutines.runBlocking { s.tabPanes(watch("t7"), periodMs = 10_000).first() }
            assertNotNull(first)
            // The thread's name may carry a coroutine suffix when debugging is on.
            assertEquals(1, readOn.size)
            assertTrue(readOn.single().startsWith("tab-panes-io"), "the read ran on ${readOn.single()}")
            assertTrue(!Thread.currentThread().name.startsWith("tab-panes-io"))
        } finally {
            executor.shutdownNow()
        }
    }

    // Gate lane L2: a tab that closes while the flow is collected, and a terminal that then shows up in another tab.
    @Test
    fun `a tab that closes gives no panes at once, and a terminal drawn in another tab gives that tab`() = runTest {
        var tabs = listOf("work" to arrayOf("t7", "t8"))
        val host = Host { out(docOf(tabs)) }
        val seen = mutableListOf<CoreTabPanes.TabPanes?>()
        val job = session(host).tabPanes(watch("t7"), periodMs = 10_000).onEach { seen += it }.launchIn(this)
        advanceTimeBy(100)
        tabs = listOf("other" to arrayOf("t1"))
        advanceTimeBy(10_000)
        tabs = listOf("other" to arrayOf("t1", "t7"))
        advanceTimeBy(10_000)
        assertEquals(listOf("work", null, "other"), seen.map { it?.tab })
        assertEquals("t7", seen.last()?.watched?.terminal)
        job.cancel()
    }

    // Gate lane M1 on a flow: a helper its tab does not draw keeps the tab's panes, and when the desktop draws it the watched pane appears.
    @Test
    fun `a helper that is swapped into a pane later gets its watched pane then`() = runTest {
        var tabs = listOf("work" to arrayOf("t8", "t9"))
        val host = Host { out(docOf(tabs)) }
        val seen = mutableListOf<CoreTabPanes.TabPanes?>()
        val job = session(host).tabPanes(watch("t50"), periodMs = 10_000).onEach { seen += it }.launchIn(this)
        advanceTimeBy(100)
        assertNull(seen.single()?.watched)
        tabs = listOf("work" to arrayOf("t8", "t50"))
        advanceTimeBy(10_000)
        assertEquals("t50", seen.last()?.watched?.terminal)
        assertEquals(2, seen.size)
        job.cancel()
    }
}
