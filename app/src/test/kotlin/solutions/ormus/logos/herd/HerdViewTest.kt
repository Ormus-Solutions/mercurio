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
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.connectbot.transport.CommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The view a host URI intent asks for over the console (MainActivity.EXTRA_HERD_VIEW), and
 * how the Herdr side hands it to the console: once, and not before the first snapshot.
 */
class HerdViewTest {
    @Test
    fun parse_chatCarriesThePaneAndSession() {
        assertEquals(HerdView.Chat("work", "w1:p3"), HerdView.parse("chat", "w1:p3", "work"))
    }

    @Test
    fun parse_chatWithoutAPaneIsTheFocusedPane() {
        assertEquals(HerdView.Chat("default", null), HerdView.parse("chat", null, "default"))
        assertEquals(HerdView.Chat("default", null), HerdView.parse("chat", " ", "default"))
    }

    @Test
    fun parse_boardWithOrWithoutAPane() {
        assertEquals(HerdView.Board, HerdView.parse("board", null, "default"))
        assertEquals(HerdView.Board, HerdView.parse("board", "w1:p3", "work"))
    }

    @Test
    fun parse_missingOrUnknownKeepsTheTerminal() {
        listOf(null, "", "terminal", "Chat", "boards").forEach { value ->
            assertNull("$value", HerdView.parse(value, "w1:p3", "default"))
        }
    }

    // `herdr api snapshot` for the default session, one idle agent; anything else succeeds empty.
    private fun run(command: String): CommandOutput = if (command == HerdrSessions.snapshotCommand(HerdrCommands.DEFAULT_SESSION)) {
        CommandOutput("""{"result":{"snapshot":{"agents":[{"pane_id":"w1:p1","agent":"claude","agent_status":"idle"}]}}}""", "", 0)
    } else {
        CommandOutput("", "", 0)
    }

    private fun TestScope.herd(): HerdSession = HerdSession("host", ::run, sendKeys = {}, scope = backgroundScope, io = coroutineContext[CoroutineDispatcher]!!)

    @Test
    fun takeView_waitsForTheFirstSnapshot() = runTest {
        val herd = herd()
        herd.requestView(HerdView.Board)
        val taken = async { herd.takeView() }
        testScheduler.runCurrent()
        assertFalse("no snapshot yet: the request waits", taken.isCompleted)

        herd.refresh()
        assertEquals(HerdView.Board, taken.await())
    }

    @Test
    fun takeView_handsEachRequestOverOnce() = runTest {
        val herd = herd()
        herd.refresh()
        herd.requestView(HerdView.Chat("default", "w1:p1"))
        assertEquals(HerdView.Chat("default", "w1:p1"), herd.takeView())

        val next = async { herd.takeView() }
        testScheduler.runCurrent()
        assertFalse("the request was cleared when taken", next.isCompleted)
        herd.requestView(HerdView.Board)
        assertEquals(HerdView.Board, next.await())
    }

    @Test
    fun takeView_aNewerRequestReplacesOneNotTakenYet() = runTest {
        val herd = herd()
        herd.requestView(HerdView.Chat("default", "w1:p1"))
        herd.requestView(HerdView.Board)
        herd.refresh()
        assertEquals(HerdView.Board, herd.takeView())
    }
}
