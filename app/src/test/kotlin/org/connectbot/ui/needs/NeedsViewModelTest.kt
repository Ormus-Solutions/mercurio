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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.util.PreferenceConstants
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.home.NeedsStore
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** The Unblock view against a store on 127.0.0.1 that answers with synthetic asks in the store's shapes. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NeedsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = CoroutineDispatchers(testDispatcher, testDispatcher, testDispatcher)
    private lateinit var prefs: SharedPreferences
    private lateinit var server: MockWebServer

    private val asks = checkNotNull(javaClass.getResourceAsStream("/needs/asks.json")).readBytes().decodeToString()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("needs-vm-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        server = MockWebServer()
        server.start(InetAddress.getByName("127.0.0.1"), 0)
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun viewModel(withAddress: Boolean = true): NeedsViewModel {
        if (withAddress) prefs.edit().putString(PreferenceConstants.NEEDS_STORE_URL, server.url("/").toString()).commit()
        return NeedsViewModel(NeedsStore(prefs, dispatchers))
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun nextRequest() = server.takeRequest(1, TimeUnit.SECONDS)

    // The view opened: the first read came back and the keys of its first question have settled.
    private fun TestScope.opened(vm: NeedsViewModel) {
        launch { vm.watch() }.also { runCurrent() }.cancel()
        advanceTimeBy(NeedsViewModel.SETTLE_MS)
        runCurrent()
    }

    @Test
    fun noAddress_readsNothing_andCallsNothing() = runTest(testDispatcher) {
        val vm = viewModel(withAddress = false)

        vm.watch()
        vm.choose("ask-one", "main")
        advanceUntilIdle()

        assertEquals(0, server.requestCount)
        assertFalse(vm.state.value.read)
    }

    @Test
    fun theViewShowsTheOldestAskFirst_kOfN() = runTest(testDispatcher) {
        server.enqueue(json(asks))
        val vm = viewModel()

        val watching = launch { vm.watch() }
        runCurrent()
        watching.cancel()

        val state = vm.state.value
        assertEquals("GET", nextRequest()!!.method)
        assertEquals("ask-one", state.pass.current?.id)
        assertEquals("Two branches carry the change.", state.pass.current?.context)
        assertEquals(listOf("main", "next"), state.pass.current?.options?.map { it.label })
        assertEquals(1 to 3, state.pass.position to state.pass.total)
        // ask-four takes several answers: it is not in the pass, it is counted for the page.
        assertEquals(1, state.pass.onPage)
    }

    @Test
    fun aTapOnAnOption_postsOneAnswer_andTheNextShows() = runTest(testDispatcher) {
        server.enqueue(json(asks))
        server.enqueue(json("""{"ok": true}"""))
        val vm = viewModel()
        opened(vm)
        nextRequest()

        vm.choose("ask-one", "main")
        // A second tap while the first is on its way sends nothing.
        vm.choose("ask-one", "main")
        advanceUntilIdle()

        val post = nextRequest()!!
        assertEquals("POST", post.method)
        assertEquals("/api/asks/ask-one/answer", post.path)
        assertEquals("""{"choice":["main"],"via":"mercurio"}""", post.body.readUtf8())
        assertNull(nextRequest())
        assertEquals("ask-two", vm.state.value.pass.current?.id)
        assertEquals(1, vm.state.value.pass.answered)
    }

    @Test
    fun aTapForAnotherAsk_orWithALabelTheAskLacks_isDropped_andSendsNothing() = runTest(testDispatcher) {
        server.enqueue(json(asks))
        val vm = viewModel()
        opened(vm)
        nextRequest()

        vm.choose("ask-two", "Yes")
        vm.choose("ask-one", "Yes")
        vm.choose("unknown", "main")
        advanceUntilIdle()

        assertEquals(1, server.requestCount)
        assertEquals("ask-one", vm.state.value.pass.current?.id)
        assertEquals(false, vm.state.value.sending)
    }

    @Test
    fun aSecondTapOfADoubleTap_neverAnswersTheNextAsk_evenWithALabelOfItsOwn() = runTest(testDispatcher) {
        server.enqueue(json(asks))
        server.enqueue(json("""{"ok": true}"""))
        server.enqueue(json("""{"ok": true}"""))
        val vm = viewModel()
        opened(vm)
        nextRequest()

        vm.choose("ask-one", "main")
        runCurrent()
        assertEquals("ask-two", vm.state.value.pass.current?.id)
        nextRequest()

        // The second tap of the double tap: made for ask-one, it lands on ask-two. And a tap on ask-two's own key too soon.
        vm.choose("ask-one", "main")
        vm.choose("ask-two", "Yes")
        runCurrent()
        assertNull("nothing was sent for the next ask", nextRequest())
        assertEquals("ask-two", vm.state.value.pass.current?.id)

        // Once the keys settle, a tap on ask-two's own key is sent.
        advanceTimeBy(NeedsViewModel.SETTLE_MS)
        runCurrent()
        vm.choose("ask-two", "Yes")
        advanceUntilIdle()
        val post = nextRequest()!!
        assertEquals("/api/asks/ask-two/answer", post.path)
        assertEquals("""{"choice":["Yes"],"via":"mercurio"}""", post.body.readUtf8())
    }

    @Test
    fun later_keepsTheKeysOfTheNextAskOffForAShortTime() = runTest(testDispatcher) {
        server.enqueue(json(asks))
        server.enqueue(json("""{"ok": true}"""))
        val vm = viewModel()
        opened(vm)
        nextRequest()

        vm.later()
        assertEquals(true, vm.state.value.settling)
        vm.choose("ask-two", "Yes")
        advanceUntilIdle()
        assertEquals(false, vm.state.value.settling)
        assertEquals("nothing sent while it settled", 1, server.requestCount)

        vm.choose("ask-two", "Yes")
        advanceUntilIdle()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun theFirstQuestionAfterTheViewOpens_hasItsKeysOffForTheSettleTime() = runTest(testDispatcher) {
        server.enqueue(json(asks))
        server.enqueue(json("""{"ok": true}"""))
        val vm = viewModel()

        launch { vm.watch() }.also { runCurrent() }.cancel()
        assertEquals("ask-one", vm.state.value.pass.current?.id)
        assertEquals(true, vm.state.value.settling)
        // The second tap of a double tap on the Unblock key lands on the first key of the first question.
        vm.choose("ask-one", "main")
        runCurrent()
        assertEquals("only the read was sent", 1, server.requestCount)

        advanceTimeBy(NeedsViewModel.SETTLE_MS)
        runCurrent()
        assertEquals(false, vm.state.value.settling)
        vm.choose("ask-one", "main")
        runCurrent()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun aNewQuestionAfterThePassEnded_hasItsKeysOffForTheSettleTime() = runTest(testDispatcher) {
        server.enqueue(json("""{"asks": []}"""))
        server.enqueue(json(asks))
        server.enqueue(json("""{"ok": true}"""))
        val vm = viewModel()

        val watching = launch { vm.watch() }
        runCurrent()
        assertNull(vm.state.value.pass.current)
        assertEquals(false, vm.state.value.settling)

        // He reaches for the Home key; the next read brings a question whose keys replace it.
        advanceTimeBy(NeedsViewModel.PERIOD_MS)
        runCurrent()
        assertEquals("ask-one", vm.state.value.pass.current?.id)
        assertEquals(true, vm.state.value.settling)
        vm.choose("ask-one", "main")
        runCurrent()
        assertEquals("nothing was sent for it", 2, server.requestCount)

        advanceTimeBy(NeedsViewModel.SETTLE_MS)
        runCurrent()
        vm.choose("ask-one", "main")
        runCurrent()
        watching.cancel()
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aReadThatLeavesTheQuestionOnScreen_doesNotStartTheSettleTimeAgain() = runTest(testDispatcher) {
        server.enqueue(json(asks))
        server.enqueue(json(asks))
        server.enqueue(json("""{"ok": true}"""))
        val vm = viewModel()
        val watching = launch { vm.watch() }
        runCurrent()
        advanceTimeBy(NeedsViewModel.SETTLE_MS)
        runCurrent()
        assertEquals(false, vm.state.value.settling)

        // To the second read and no farther: a settle time it started would still be running.
        advanceTimeBy(NeedsViewModel.PERIOD_MS - NeedsViewModel.SETTLE_MS)
        runCurrent()
        assertEquals(2, server.requestCount)
        assertEquals("the same question stays: its keys stay live", false, vm.state.value.settling)
        vm.choose("ask-one", "main")
        runCurrent()
        watching.cancel()
        assertEquals(3, server.requestCount)
    }

    @Test
    fun later_isDroppedWhileTheKeysSettle() = runTest(testDispatcher) {
        server.enqueue(json(asks))
        val vm = viewModel()
        opened(vm)

        vm.later()
        vm.later()

        assertEquals("ask-two", vm.state.value.pass.current?.id)
        assertEquals(listOf("ask-one"), vm.state.value.pass.later.map { it.id })
    }

    @Test
    fun later_isDroppedWhileASendRuns() = runTest(testDispatcher) {
        server.enqueue(json(asks))
        server.enqueue(json("""{"ok": true}"""))
        val vm = viewModel()
        opened(vm)

        vm.choose("ask-one", "main")
        assertEquals(true, vm.state.value.sending)
        vm.later()

        assertEquals("ask-one", vm.state.value.pass.current?.id)
        assertEquals(emptyList<String>(), vm.state.value.pass.later.map { it.id })
        advanceUntilIdle()
    }

    @Test
    fun aFailedAnswerAfterAClosedOne_showsTheFailure_notTheOldClosedLine() = runTest(testDispatcher) {
        server.enqueue(json(asks))
        server.enqueue(json("""{"error": "closed"}""", 409))
        server.enqueue(json("""{"error": "boom"}""", 500))
        val vm = viewModel()
        opened(vm)

        vm.choose("ask-one", "main")
        advanceUntilIdle()
        assertEquals(NeedsNotice.CLOSED, vm.state.value.notice)

        vm.choose("ask-two", "Yes")
        advanceUntilIdle()

        assertNull(vm.state.value.notice)
        assertEquals("HTTP 500", vm.state.value.error)
    }

    @Test
    fun a404OnAnAnswer_isAClosedAsk_andTheNextShows() = runTest(testDispatcher) {
        server.enqueue(json(asks))
        server.enqueue(json("""{"error": "no such ask"}""", 404))
        val vm = viewModel()
        opened(vm)

        vm.choose("ask-one", "main")
        advanceUntilIdle()

        assertEquals(NeedsNotice.CLOSED, vm.state.value.notice)
        assertEquals("ask-two", vm.state.value.pass.current?.id)
        assertEquals(0, vm.state.value.pass.answered)
    }

    @Test
    fun later_showsTheNext_andTellsTheStoreNothing() = runTest(testDispatcher) {
        server.enqueue(json(asks))
        val vm = viewModel()
        opened(vm)
        nextRequest()

        vm.later()
        advanceUntilIdle()

        assertEquals("ask-two", vm.state.value.pass.current?.id)
        assertEquals(listOf("ask-one"), vm.state.value.pass.later.map { it.id })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun a409_readsTheAskIsClosed_andTheNextShows() = runTest(testDispatcher) {
        server.enqueue(json(asks))
        server.enqueue(json("""{"error": "closed"}""", 409))
        val vm = viewModel()
        opened(vm)

        vm.choose("ask-one", "main")
        advanceUntilIdle()

        assertEquals(NeedsNotice.CLOSED, vm.state.value.notice)
        assertEquals("ask-two", vm.state.value.pass.current?.id)
        assertEquals(0, vm.state.value.pass.answered)
    }

    @Test
    fun aCallThatThrowsARuntimeException_isAFailure_andTheReadsGoOn() = runTest(testDispatcher) {
        prefs.edit().putString(PreferenceConstants.NEEDS_STORE_URL, "https://store.example/needs").commit()
        var calls = 0
        val vm = NeedsViewModel(
            NeedsStore(prefs, dispatchers) { _, _, _ ->
                calls++
                if (calls == 1) error("the HTTP stack broke") else 200 to asks
            },
        )

        val watching = launch { vm.watch() }
        runCurrent()
        assertEquals("the HTTP stack broke", vm.state.value.error)
        assertTrue(watching.isActive)

        advanceTimeBy(NeedsViewModel.PERIOD_MS + 1)
        runCurrent()
        watching.cancel()
        assertEquals(2, calls)
        assertNull(vm.state.value.error)
        assertEquals("ask-one", vm.state.value.pass.current?.id)
    }

    @Test
    fun a404OnTheRead_isAFailure_notAClosedAsk() = runTest(testDispatcher) {
        server.enqueue(json("""{"error": "no such route"}""", 404))
        val vm = viewModel()

        opened(vm)

        assertEquals("HTTP 404", vm.state.value.error)
        assertNull(vm.state.value.notice)
    }

    @Test
    fun a403_readsTheStoreRefusedThisPhone_andStartsNoRetry() = runTest(testDispatcher) {
        server.enqueue(json("""{"error": "forbidden"}""", 403))
        val vm = viewModel()

        val watching = launch { vm.watch() }
        runCurrent()
        advanceTimeBy(NeedsViewModel.PERIOD_MS * 3)
        runCurrent()

        assertTrue(watching.isCompleted)
        assertEquals(NeedsNotice.REFUSED, vm.state.value.notice)
        assertEquals(1, server.requestCount)
        vm.choose("ask-one", "main")
        advanceUntilIdle()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun a403OnAnAnswer_stopsTheReads() = runTest(testDispatcher) {
        server.enqueue(json(asks))
        server.enqueue(json("", 403))
        val vm = viewModel()
        val watching = launch { vm.watch() }
        runCurrent()
        advanceTimeBy(NeedsViewModel.SETTLE_MS)
        runCurrent()

        vm.choose("ask-one", "main")
        runCurrent()
        advanceTimeBy(NeedsViewModel.PERIOD_MS * 2)
        runCurrent()

        assertEquals(NeedsNotice.REFUSED, vm.state.value.notice)
        assertTrue(watching.isCompleted)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun theListIsReadEvery30s_andAnAskClosedElsewhereLeavesWithoutMovingTheOneOnScreen() = runTest(testDispatcher) {
        server.enqueue(json(asks))
        // The next read: the one on screen and the third closed elsewhere, a new one at the end.
        val later = """
            {"asks": [
              {"id": "ask-one", "question": "Which branch should the fix land on?", "options": [{"label": "main"}], "state": "answered"},
              {"id": "ask-two", "question": "Run the slow tests too?", "options": [{"label": "Yes"}, {"label": "No"}], "state": "open"},
              {"id": "ask-three", "question": "Keep the old flag?", "options": [{"label": "Keep"}], "state": "expired"},
              {"id": "ask-four", "question": "Rename the module?", "options": [{"label": "Yes"}], "state": "open"}
            ]}
        """.trimIndent()
        server.enqueue(json(later))
        val vm = viewModel()
        val watching = launch { vm.watch() }
        runCurrent()
        assertEquals(1, server.requestCount)

        advanceTimeBy(NeedsViewModel.PERIOD_MS - 1)
        runCurrent()
        assertEquals(1, server.requestCount)
        advanceTimeBy(1)
        runCurrent()
        watching.cancel()

        assertEquals(2, server.requestCount)
        val pass = vm.state.value.pass
        assertEquals("ask-one", pass.current?.id)
        assertEquals(listOf("ask-one", "ask-two", "ask-four"), pass.queue.map { it.id })
    }
}
