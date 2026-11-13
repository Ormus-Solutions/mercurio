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

package org.connectbot.ui.needs

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.PreferenceConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.home.NeedsStore

/** The count behind the Inbox's Unblock key: the open questions of the store, nothing with no address. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NeedsCountViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(testDispatcher, testDispatcher, testDispatcher)
    private lateinit var prefs: SharedPreferences
    private val asks = checkNotNull(javaClass.getResourceAsStream("/needs/asks.json")).readBytes().decodeToString()

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("needs-count-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun viewModel(address: Boolean = true, call: (String, String, String?) -> Pair<Int, String>): NeedsCountViewModel {
        if (address) prefs.edit().putString(PreferenceConstants.NEEDS_STORE_URL, "https://store.example/needs").commit()
        return NeedsCountViewModel(NeedsStore(prefs, dispatchers, call))
    }

    @Test
    fun noAddress_hidesTheKey_andCallsNothing() = runTest(testDispatcher) {
        var calls = 0
        val vm = viewModel(address = false) { _, _, _ -> 200 to asks.also { calls++ } }

        launch { vm.watch() }.also { runCurrent() }.cancel()

        assertNull(vm.count.value)
        assertEquals(0, calls)
    }

    @Test
    fun theCount_isTheOpenQuestions_andIsReadAgainAfterThePeriod() = runTest(testDispatcher) {
        var body = asks
        val vm = viewModel { _, _, _ -> 200 to body }

        val watching = launch { vm.watch() }
        runCurrent()
        assertEquals("the fixture has four open asks, one of them multi: the key counts the three the view walks", 3, vm.count.value)

        body = """{"asks": [{"id": "only-one", "question": "q", "options": [{"label": "a"}], "state": "open"}]}"""
        advanceTimeBy(NeedsCountViewModel.PERIOD_MS + 1)
        runCurrent()
        watching.cancel()
        assertEquals(1, vm.count.value)
    }

    @Test
    fun whenOnlyAMultiAskIsOpen_theKeyCountsZero_becauseTheViewCannotAnswerIt() = runTest(testDispatcher) {
        val onlyMulti = """{"asks": [{"id": "many", "question": "q", "options": [{"label": "a"}, {"label": "b"}], "multi": true, "state": "open"}]}"""
        val vm = viewModel { _, _, _ -> 200 to onlyMulti }

        launch { vm.watch() }.also { runCurrent() }.cancel()

        assertEquals(0, vm.count.value)
    }

    @Test
    fun aFailedRead_keepsTheLastCount_andARefusalHidesTheKeyAndStops() = runTest(testDispatcher) {
        var step = 0
        val vm = viewModel { _, _, _ ->
            when (step++) {
                0 -> 200 to asks
                1 -> 500 to ""
                else -> 403 to ""
            }
        }

        val watching = launch { vm.watch() }
        runCurrent()
        advanceTimeBy(NeedsCountViewModel.PERIOD_MS + 1)
        runCurrent()
        assertEquals("a failed read keeps the last count", 3, vm.count.value)

        advanceTimeBy(NeedsCountViewModel.PERIOD_MS + 1)
        runCurrent()
        assertNull("refused: no key", vm.count.value)
        assertTrue("refused: no more reads", watching.isCompleted)
    }
}
