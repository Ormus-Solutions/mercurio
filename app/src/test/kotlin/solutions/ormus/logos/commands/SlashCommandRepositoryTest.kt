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

package solutions.ormus.logos.commands

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.transport.CommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SlashCommandRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(default = dispatcher, io = dispatcher, main = dispatcher)
    private lateinit var prefs: SharedPreferences

    private fun repository() = SlashCommandRepository(tmp.root, prefs, dispatchers) { 1_700_000_000_000L }

    private fun good() = CommandOutput(commandsFixture("commands/own-commands-sun.txt"), "", 0)

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("slash-repo-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @Test
    fun cancelledRefresh_letsTheNextRefreshRun() = runTest(dispatcher) {
        val repo = repository()
        repo.refresh(1, null) { good() }
        advanceUntilIdle()
        val lastGood = repo.state(1).value.commands
        assertTrue(lastGood.isNotEmpty())

        lateinit var job: kotlinx.coroutines.Job
        job = launch {
            repo.refresh(1, null) {
                job.cancel()
                good()
            }
        }
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertFalse(repo.state(1).value.refreshing)
        assertEquals(lastGood, repo.state(1).value.commands)

        var ran = false
        repo.refresh(1, null) {
            ran = true
            good()
        }
        assertTrue("the next refresh must run", ran)
    }

    @Test
    fun refreshThatThrowsUnexpectedly_returnsNormallyAndLetsTheNextRefreshRun() = runTest(dispatcher) {
        val repo = repository()
        repo.refresh(1, null) { good() }
        val lastGood = repo.state(1).value.commands

        repo.refresh(1, null) { throw IllegalArgumentException("boom") }

        assertFalse(repo.state(1).value.refreshing)
        assertTrue(repo.state(1).value.failed)
        assertEquals(lastGood, repo.state(1).value.commands)

        var ran = false
        repo.refresh(1, null) {
            ran = true
            good()
        }
        assertTrue("the next refresh must run", ran)
        assertFalse(repo.state(1).value.failed)
    }

    @Test
    fun cacheThatCannotBeWritten_stillPublishesTheFreshList() = runTest(dispatcher) {
        val blocker = tmp.newFile("not-a-directory")
        val repo = SlashCommandRepository(blocker, prefs, dispatchers) { 1_700_000_000_000L }

        repo.refresh(1, null) { good() }

        val state = repo.state(1).value
        assertTrue(state.commands.isNotEmpty())
        assertEquals(1_700_000_000_000L, state.fetchedAt)
        assertFalse(state.refreshing)
        assertFalse(state.failed)
    }
}
