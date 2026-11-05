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

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.connectbot.di.CoroutineDispatchers
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.CoroutineContext

/** What the phone keeps of each Herdr host's console list, across launches. */
@RunWith(AndroidJUnit4::class)
class HerdrConsoleCacheTest {
    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(testDispatcher, testDispatcher, testDispatcher)
    private lateinit var prefs: SharedPreferences

    private val work = HerdrConsole("work", workspaceLabel = "main")
    private val review = HerdrConsole("review")

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("herdr-consoles-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun cache() = HerdrConsoleCache(prefs, dispatchers)

    @Test
    fun remember_keepsAHostsListInItsOrder_andAConnectReplacesIt() = runTest(testDispatcher) {
        val cache = cache()
        cache.remember(7L, listOf(work, review))
        assertEquals(mapOf(7L to listOf(work, review)), cache.consoles.value)

        cache.remember(7L, listOf(review))
        assertEquals(mapOf(7L to listOf(review)), cache.consoles.value)
    }

    @Test
    fun remember_anEmptyList_forgetsTheHost_theOthersStay() = runTest(testDispatcher) {
        val cache = cache()
        cache.remember(7L, listOf(work))
        cache.remember(8L, listOf(review))

        cache.remember(7L, emptyList())
        assertEquals(mapOf(8L to listOf(review)), cache.consoles.value)

        cache.forget(8L)
        assertEquals(emptyMap<Long, List<HerdrConsole>>(), cache.consoles.value)
    }

    @Test
    fun load_readsWhatAnEarlierLaunchKept_labelsIncluded() = runTest(testDispatcher) {
        cache().remember(7L, listOf(work, review))

        val relaunched = cache()
        assertEquals(emptyMap<Long, List<HerdrConsole>>(), relaunched.consoles.value)
        relaunched.load()
        assertEquals(mapOf(7L to listOf(work, review)), relaunched.consoles.value)
    }

    @Test
    fun remember_beforeLoad_keepsWhatTheLastLaunchHeldForOtherHosts() = runTest(testDispatcher) {
        cache().remember(7L, listOf(work))

        val relaunched = cache()
        relaunched.remember(8L, listOf(review))
        assertEquals(mapOf(7L to listOf(work), 8L to listOf(review)), relaunched.consoles.value)
    }

    @Test
    fun aLoadCutShort_isReadAgain_soNothingKeptIsLost() = runTest(testDispatcher) {
        cache().remember(7L, listOf(work))

        // The preferences are read on the io dispatcher. Hold it, so the load's read stays queued while the
        // console whose scope asked for it goes away; then let the io side run and see the cancellation.
        val io = HeldDispatcher()
        val relaunched = HerdrConsoleCache(prefs, CoroutineDispatchers(testDispatcher, io, testDispatcher))
        io.hold = true
        val cut = launch { relaunched.load() }
        runCurrent()
        cut.cancel()
        io.release()
        advanceUntilIdle()
        assertEquals(true, cut.isCancelled)

        relaunched.remember(8L, listOf(review))
        assertEquals(mapOf(7L to listOf(work), 8L to listOf(review)), relaunched.consoles.value)
        val again = cache()
        again.load()
        assertEquals(mapOf(7L to listOf(work), 8L to listOf(review)), again.consoles.value)
    }

    /** Runs each block where it is dispatched, unless [hold]: then the blocks wait for [release]. */
    private class HeldDispatcher : CoroutineDispatcher() {
        var hold = false
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (hold) queued += block else block.run()
        }

        fun release() {
            hold = false
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    @Test
    fun load_preferencesThatDoNotParse_readAsNothing() = runTest(testDispatcher) {
        prefs.edit().putString(HerdrConsoleCache.PREF_KEY, "{not json").commit()
        val cache = cache()
        cache.load()
        assertEquals(emptyMap<Long, List<HerdrConsole>>(), cache.consoles.value)

        // A name Herdr would not give, written by hand: the whole value is dropped, never run.
        prefs.edit().putString(HerdrConsoleCache.PREF_KEY, """[{"host":7,"consoles":[{"session":"bad;name"}]}]""").commit()
        val again = cache()
        again.load()
        assertEquals(emptyMap<Long, List<HerdrConsole>>(), again.consoles.value)
    }
}
