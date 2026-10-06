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
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.service.DisconnectReason
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UsageTrackerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val dispatchers = CoroutineDispatchers(dispatcher, dispatcher, dispatcher)
    private var clock = 1_791_115_200_000L
    private lateinit var prefs: SharedPreferences
    private lateinit var store: UsageStore
    private lateinit var tracker: UsageTracker

    @Before
    fun setUp() {
        prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("usage-test", Context.MODE_PRIVATE)
        prefs.edit(commit = true) { clear() }
        store = UsageStore(File(temp.root, "usage"))
        tracker = UsageTracker(store, prefs, dispatchers) { clock }
    }

    private fun events(): List<UsageEvent> {
        scheduler.advanceUntilIdle()
        return store.snapshot().events
    }

    @Test
    fun logsActionWithScreenAndHostType() {
        tracker.setScreen("console/{hostId}")
        tracker.setHerdr(true)
        tracker.log(UsageActions.KEY_UP)
        tracker.setScreen("settings")
        tracker.log(UsageActions.SETTINGS_USAGE_SUMMARY)

        val (up, summary) = events()
        assertThat(up.action).isEqualTo(UsageActions.KEY_UP)
        assertThat(up.screen).isEqualTo("console")
        assertThat(up.herdr).isTrue()
        assertThat(up.ts).isEqualTo(clock)
        // Leaving the console clears the host type.
        assertThat(summary.screen).isEqualTo("settings")
        assertThat(summary.herdr).isNull()
    }

    @Test
    fun toggleOff_recordsNothing() {
        prefs.edit(commit = true) { putBoolean(UsageSettings.KEY_RECORD, false) }
        tracker.log(UsageActions.KEY_UP)
        tracker.sessionConnecting(this, true)
        tracker.sessionOpened(this, true)
        tracker.sessionClosed(this, true, DisconnectReason.REMOTE_EOF.name)

        assertThat(events()).isEmpty()
        assertThat(File(temp.root, "usage/${UsageStore.EVENTS_FILE}").exists()).isFalse()
    }

    @Test
    fun recordsByDefault() {
        tracker.log(UsageActions.KEY_ESC)
        assertThat(events().map { it.action }).containsExactly(UsageActions.KEY_ESC)
    }

    @Test
    fun dropsIdsThatAreNotListed() {
        tracker.log("my password is hunter2")
        tracker.log("ls -la /home/alice")
        tracker.log("key:up hunter2")
        assertThat(events()).isEmpty()
    }

    @Test
    fun sessionLifecycle_startEndDurationReconnectAndFailure() {
        val session = Any()
        tracker.sessionConnecting(session, true)
        tracker.sessionOpened(session, true)
        clock += 90_000
        tracker.sessionClosed(session, true, DisconnectReason.NETWORK_LOST.name)
        // A second close (Close after the drop) records nothing more.
        tracker.sessionClosed(session, true, DisconnectReason.USER_REQUESTED.name)
        tracker.sessionConnecting(session, true)
        tracker.sessionClosed(session, true, DisconnectReason.AUTH_FAIL.name)

        val recorded = events()
        assertThat(recorded.map { it.action }).containsExactly(
            UsageActions.SESSION_START,
            UsageActions.SESSION_END,
            UsageActions.SESSION_RECONNECT,
            UsageActions.SESSION_FAIL,
        )
        assertThat(recorded[1].durationMs).isEqualTo(90_000)
        assertThat(recorded[1].reason).isEqualTo("NETWORK_LOST")
        assertThat(recorded[3].reason).isEqualTo("AUTH_FAIL")
    }

    @Test
    fun reasonMustBeAnIdentifier() {
        val session = Any()
        tracker.sessionConnecting(session, false)
        tracker.sessionClosed(session, false, "Auth failed for alice@100.100.1.1 with password hunter2")
        val fail = events().single()
        assertThat(fail.action).isEqualTo(UsageActions.SESSION_FAIL)
        assertThat(fail.reason).isNull()
    }

    @Test
    fun screenTokenKeepsOnlyTheRouteName() {
        assertThat(UsageActions.screenToken("console/{hostId}")).isEqualTo("console")
        assertThat(UsageActions.screenToken("settings?highlight=conn_persist")).isEqualTo("settings")
        assertThat(UsageActions.screenToken("Host List With Spaces")).isNull()
        assertThat(UsageActions.screenToken(null)).isNull()
    }
}
