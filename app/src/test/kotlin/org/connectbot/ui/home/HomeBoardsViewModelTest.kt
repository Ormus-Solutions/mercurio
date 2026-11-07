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

package org.connectbot.ui.home

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.PreferenceConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.home.HomeBoards
import solutions.ormus.logos.home.HomeFeed

/** When the home reads its feed: at a start, on a new URL, and every minute while it shows. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class HomeBoardsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // The read runs on its own dispatcher (the same scheduler), as on the phone: a coroutine launched
    // while a read is away runs before the read comes back.
    private val ioDispatcher = StandardTestDispatcher(testDispatcher.scheduler)
    private val dispatchers = CoroutineDispatchers(testDispatcher, ioDispatcher, testDispatcher)
    private lateinit var prefs: SharedPreferences

    private val fixture = checkNotNull(javaClass.getResourceAsStream("/home/sessions.json")).readBytes().decodeToString()
    private val fetched = mutableListOf<String>()
    private var onFetch: () -> Unit = {}

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("home-boards-vm-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun feed() = HomeFeed(prefs, dispatchers, fetch = { url ->
        fetched += url
        onFetch()
        fixture
    }, clock = { 1L })

    private fun setUrl(url: String) {
        prefs.edit().putString(PreferenceConstants.HOME_FEED_URL, url).commit()
    }

    @Test
    fun aStart_loadsWhatWasKept_andNamesTheUrl() = runTest(testDispatcher) {
        setUrl(URL)
        feed().refresh()
        fetched.clear()

        val viewModel = HomeBoardsViewModel(feed())
        advanceUntilIdle()

        assertEquals(URL, viewModel.state.value.url)
        assertEquals(HomeBoards.parse(fixture), viewModel.state.value.boards)
        assertEquals(emptyList<String>(), fetched)
    }

    @Test
    fun setUrl_savesIt_andReadsAtOnce() = runTest(testDispatcher) {
        val viewModel = HomeBoardsViewModel(feed())
        advanceUntilIdle()
        assertEquals("", viewModel.state.value.url)

        viewModel.setUrl(" $URL ")
        advanceUntilIdle()

        assertEquals(URL, viewModel.state.value.url)
        assertEquals(URL, prefs.getString(PreferenceConstants.HOME_FEED_URL, null))
        assertEquals(listOf(URL), fetched)
        assertNotNull(viewModel.state.value.boards)

        viewModel.setUrl("")
        advanceUntilIdle()
        assertEquals("", viewModel.state.value.url)
        assertNull(viewModel.state.value.boards)
        assertEquals(listOf(URL), fetched)
    }

    @Test
    fun setUrl_whileAReadIsAway_readsTheNewUrl_once() = runTest(testDispatcher) {
        setUrl(URL)
        val viewModel = HomeBoardsViewModel(feed())
        advanceUntilIdle()
        onFetch = { if (fetched.size == 1) viewModel.setUrl(OTHER) }

        // runCurrent, not advanceUntilIdle: the watch never idles (it waits a minute and reads again).
        val watching = launch { viewModel.watch() }
        runCurrent()

        assertEquals(listOf(URL, OTHER), fetched)
        assertEquals(OTHER, viewModel.state.value.url)
        assertEquals(HomeBoards.parse(fixture), viewModel.state.value.boards)
        watching.cancel()
    }

    @Test
    fun watch_readsNow_andAgainEveryMinute_whileItRuns() = runTest(testDispatcher) {
        setUrl(URL)
        val viewModel = HomeBoardsViewModel(feed())
        advanceUntilIdle()

        val watching = launch { viewModel.watch() }
        runCurrent()
        assertEquals(1, fetched.size)

        advanceTimeBy(HomeBoardsViewModel.PERIOD_MS / 2)
        runCurrent()
        assertEquals(1, fetched.size)

        advanceTimeBy(HomeBoardsViewModel.PERIOD_MS)
        runCurrent()
        assertEquals(2, fetched.size)

        watching.cancel()
        advanceTimeBy(HomeBoardsViewModel.PERIOD_MS * 3)
        runCurrent()
        assertEquals(2, fetched.size)
    }

    @Test
    fun watch_withNoUrl_readsNothing() = runTest(testDispatcher) {
        val viewModel = HomeBoardsViewModel(feed())
        advanceUntilIdle()

        val watching = launch { viewModel.watch() }
        advanceTimeBy(HomeBoardsViewModel.PERIOD_MS * 3)
        runCurrent()
        watching.cancel()

        assertEquals(emptyList<String>(), fetched)
        assertNull(viewModel.state.value.boards)
    }

    private companion object {
        const val URL = "https://feed.example/feeds/sessions.json"
        const val OTHER = "https://other.example/feeds/sessions.json"
    }
}
