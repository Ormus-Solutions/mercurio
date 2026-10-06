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

package org.connectbot.usage

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.ui.wish.WishViewModel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WishViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val dispatchers = CoroutineDispatchers(dispatcher, dispatcher, dispatcher)
    private lateinit var tracker: UsageTracker
    private lateinit var dir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("wish-test", Context.MODE_PRIVATE)
            .apply { edit(commit = true) { clear() } }
        dir = File(temp.root, "usage")
        tracker = UsageTracker(UsageStore(dir), prefs, dispatchers) { 42L }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun wishSavesWithContextAndSurvivesRestart() {
        tracker.setScreen("console/{hostId}")
        tracker.setHerdr(true)
        var saved = false
        WishViewModel(InboxStore(dir), tracker, dispatchers) { 42L }
            .saveWish("I tend to use the up arrow to scroll") { saved = true }
        scheduler.advanceUntilIdle()
        assertThat(saved).isTrue()

        // A new store reads the same file, as after an app restart.
        val wish = InboxStore(dir).all().single()
        assertThat(wish.kind).isEqualTo(InboxKind.WISH)
        assertThat(wish.text).isEqualTo("I tend to use the up arrow to scroll")
        assertThat(wish.createdAt).isEqualTo(42L)
        assertThat(wish.screen).isEqualTo("console")
        assertThat(wish.herdr).isTrue()
        assertThat(wish.synced).isFalse()
        // Saving logs the action, never the wish text, in the usage store.
        assertThat(UsageStore(dir).snapshot().events.map { it.action }).containsExactly(UsageActions.WISH_SAVE)
        assertThat(File(dir, UsageStore.EVENTS_FILE).readText()).doesNotContain("arrow")
    }

    @Test
    fun errorReportSavesAsErrorEntry() {
        WishViewModel(InboxStore(dir), tracker, dispatchers) { 7L }
            .saveError({ "Mercurio connection report\nAUTH_FAIL" }, herdr = false)
        scheduler.advanceUntilIdle()
        val entry = InboxStore(dir).all().single()
        assertThat(entry.kind).isEqualTo(InboxKind.ERROR)
        assertThat(entry.text).contains("AUTH_FAIL")
        assertThat(entry.herdr).isFalse()
    }

    @Test
    fun blankWishIsIgnored() {
        WishViewModel(InboxStore(dir), tracker, dispatchers) { 1L }.saveWish("   ")
        scheduler.advanceUntilIdle()
        assertThat(InboxStore(dir).all()).isEmpty()
    }
}
