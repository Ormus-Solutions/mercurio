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

package solutions.ormus.logos.push

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.Host
import org.connectbot.service.ConnectionNotifier
import org.connectbot.service.HerdAnswerRequest
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.Shadows.shadowOf
import solutions.ormus.logos.herd.CoreNotices
import solutions.ormus.logos.herd.HerdAttention

/** A push becomes the monitor's own "Needs you" for a saved host only, never showing an earlier prompt's question. */
@RunWith(AndroidJUnit4::class)
class HerdPushNoticesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val sun = Host(id = 7, nickname = "Sun", hostname = "100.100.1.1", postLogin = "herdr")
    private val hosts = mock<HostRepository> { onBlocking { getSshHosts() } doReturn listOf(sun) }
    private val usage = mock<UsageTracker>()
    private var paneEnd: String? = null
    private var reads = 0
    private val tail = mock<HerdPushTail> {
        onBlocking { read(any(), any(), any()) } doAnswer {
            reads++
            paneEnd?.let { HerdPushTail.Read.Text(it, CoreNotices.fingerprint(it)) } ?: HerdPushTail.Read.Missed
        }
    }
    private val scope = CoroutineScope(UnconfinedTestDispatcher())
    private var clock = 10_000L
    private fun notices(tail: HerdPushTail = this.tail) = HerdPushNotices(hosts, ConnectionNotifier(), usage, tail, scope) { clock }
    private val notices = notices()

    private fun alert(host: String = "Sun", pane: String = "w1:p3", ts: Long = 1) = """{"v":1,"event":"pane.agent_status_changed","state":"blocked","host":"$host","session":"default",""" +
        """"pane_id":"$pane","agent":"claude","workspace_id":"w1","workspace":"mercurio","tab_id":"w1:t2","tab":"build","ts":$ts}"""

    @Test
    fun blocked_postsNeedsYouOnTheMonitorsTag_withNoButtonsUntilItsPaneEndIsRead() = runTest {
        assertTrue(notices.post(context, alert().toByteArray()))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("claude needs you on Sun", posted.extras.getCharSequence("android.title").toString())
        assertEquals("mercurio / build", posted.extras.getCharSequence("android.text").toString())
        // The push has no state_change_seq and the read missed: nothing proves which prompt a tap would answer.
        assertTrue(posted.actions.isNullOrEmpty())
        // The monitor's tag: the machine (account, host, port), not the host row.
        assertEquals("herd:@100.100.1.1:22:w1:p3", manager.activeNotifications.single().tag)
    }

    @Test
    fun blocked_aPushRaisedNotice_answersOnlyThePromptItsPaneEndShowed() = runTest {
        paneEnd = "Run tool A?\n❯ 1. Yes\n2. No"
        assertTrue(notices.post(context, alert().toByteArray()))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(listOf("Approve", "Deny"), posted.actions.map { it.title.toString() })
        val approve = shadowOf(posted.actions[0].actionIntent).savedIntent
        assertEquals(7L, approve.getLongExtra(ConnectionNotifier.EXTRA_HERD_HOST, 0))
        assertEquals("w1:p3", approve.getStringExtra(ConnectionNotifier.EXTRA_HERD_PANE))
        // The push carries no seq, so the extra is absent (not 0): the guard must not hold the answer to a made-up one.
        assertFalse(approve.hasExtra(ConnectionNotifier.EXTRA_HERD_SEQ))
        val request = checkNotNull(HerdAnswerRequest.from(approve))
        assertNull(request.seq)
        assertEquals(CoreNotices.fingerprint(paneEnd!!), request.prompt)
        assertFalse(request.unchecked)
    }

    @Test
    fun aFinishedNoticeAlreadyShowingForThePane_isLeftAlone() = runTest {
        val done = alert().replace("\"blocked\"", "\"done\"").toByteArray()
        notices.post(context, done)
        val first = manager.activeNotifications.single().postTime
        shadowOf(manager).allNotifications.single().extras.putString("marker", "kept")

        notices.post(context, done)

        assertEquals(first, manager.activeNotifications.single().postTime)
        assertEquals("kept", shadowOf(manager).allNotifications.single().extras.getString("marker"))
    }

    @Test
    fun aPaneThatBlocksAgainWhileItsNoticeShows_losesTheEarlierQuestionAtOnce_quietly_thenGetsTheNewOne() = runTest {
        // Approve carries no prompt check, so the notice must never show one prompt while the pane asks another.
        val second = CompletableDeferred<HerdPushTail.Read>()
        var calls = 0
        val tail = mock<HerdPushTail> {
            onBlocking { read(any(), any(), any()) } doSuspendableAnswer {
                if (calls++ == 0) HerdPushTail.Read.Text("Edit build.gradle?") else second.await()
            }
        }
        val notices = notices(tail)
        notices.post(context, alert().toByteArray())
        assertEquals("mercurio / build\n\nEdit build.gradle?", bigText())

        assertTrue(notices.post(context, alert(ts = 60).toByteArray()))

        val shown = manager.activeNotifications.single()
        assertEquals("mercurio / build", bigText())
        assertTrue("the repost alerted again", shown.notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
        second.complete(HerdPushTail.Read.Text("Run rm -rf build?"))
        assertEquals("mercurio / build\n\nRun rm -rf build?", bigText())
    }

    @Test
    fun aPaneThatBlocksAgain_andWhoseReadMisses_keepsNoEarlierQuestion() = runTest {
        paneEnd = "Edit build.gradle?"
        notices.post(context, alert().toByteArray())
        paneEnd = null

        notices.post(context, alert(ts = 60).toByteArray())

        assertEquals("mercurio / build", bigText())
    }

    private fun bigText() = manager.activeNotifications.single().notification.extras.getCharSequence("android.bigText").toString()

    @Test
    fun unknownHostOrBadMessage_postsNothing_andIsCounted() = runTest {
        assertFalse(notices.post(context, alert(host = "venus").toByteArray()))
        assertFalse(notices.post(context, "not json".toByteArray()))

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        verify(usage, org.mockito.kotlin.times(2)).log(UsageActions.PUSH_IGNORED)
        verify(usage, never()).log(UsageActions.PUSH_RECEIVED)
    }

    @Test
    fun blocked_thePanesEnd_isPutInQuietly_onTheSameNotice() = runTest {
        paneEnd = "Edit app/build.gradle.kts?\n❯ 1. Yes\n2. No"

        assertTrue(notices.post(context, alert().toByteArray()))

        val shown = manager.activeNotifications.single()
        assertEquals("herd:@100.100.1.1:22:w1:p3", shown.tag)
        val big = shown.notification.extras.getCharSequence("android.bigText").toString()
        assertEquals("mercurio / build\n\nEdit app/build.gradle.kts?\n❯ 1. Yes\n2. No", big)
        // The update makes no second sound.
        assertTrue(shown.notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
        verify(usage).log(UsageActions.PUSH_TAIL_READ)
    }

    @Test
    fun blocked_aMissedRead_leavesTheNoticeAsPushed_andIsCounted() = runTest {
        paneEnd = null

        assertTrue(notices.post(context, alert().toByteArray()))

        val shown = manager.activeNotifications.single()
        assertEquals("mercurio / build", shown.notification.extras.getCharSequence("android.bigText").toString())
        assertEquals(0, shown.notification.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE)
        verify(usage).log(UsageActions.PUSH_TAIL_MISSED)
    }

    @Test
    fun blocked_aReadThatThrows_isAMiss_andTheNoticeStays() = runTest {
        val throwing = mock<HerdPushTail> { onBlocking { read(any(), any(), any()) } doAnswer { throw IllegalStateException("boom") } }

        assertTrue(notices(throwing).post(context, alert().toByteArray()))

        assertEquals(1, manager.activeNotifications.size)
        verify(usage).log(UsageActions.PUSH_TAIL_MISSED)
    }

    @Test
    fun blocked_aNoticeDismissedDuringTheRead_isNotBroughtBack() = runTest {
        val dismissing = mock<HerdPushTail> {
            onBlocking { read(any(), any(), any()) } doAnswer {
                manager.cancelAll()
                HerdPushTail.Read.Text("Allow?")
            }
        }

        assertTrue(notices(dismissing).post(context, alert().toByteArray()))

        assertTrue(manager.activeNotifications.isEmpty())
    }

    @Test
    fun finished_readsNothing_andEachBlockedPush_readsOnce() = runTest {
        notices.post(context, alert().replace("\"blocked\"", "\"done\"").toByteArray())
        notices.post(context, alert(pane = "w1:p4").toByteArray())
        notices.post(context, alert(pane = "w1:p4", ts = 60).toByteArray())

        assertEquals(2, reads)
    }

    @Test
    fun blocked_postReturnsBeforeTheReadEnds_andAWakeLockCoversTheRead() = runTest {
        // A cold push runs on the main thread, so post must not wait for the login.
        val answer = CompletableDeferred<HerdPushTail.Read>()
        val slow = mock<HerdPushTail> { onBlocking { read(any(), any(), any()) } doSuspendableAnswer { answer.await() } }

        val lock = HerdPushNotices.holdForRead(context)

        assertTrue(notices(slow).post(context, alert().toByteArray(), lock = lock))

        val shown = manager.activeNotifications.single()
        assertEquals("mercurio / build", shown.notification.extras.getCharSequence("android.bigText").toString())
        assertTrue("no wake lock held while the read runs", lock.isHeld)

        answer.complete(HerdPushTail.Read.Text("Allow?"))

        val updated = manager.activeNotifications.single().notification.extras.getCharSequence("android.bigText").toString()
        assertEquals("mercurio / build\n\nAllow?", updated)
        assertFalse("the wake lock outlived the read", lock.isHeld)
    }

    @Test
    fun blocked_aReadThatThrows_releasesTheWakeLock() = runTest {
        val throwing = mock<HerdPushTail> { onBlocking { read(any(), any(), any()) } doAnswer { throw IllegalStateException("boom") } }

        val lock = HerdPushNotices.holdForRead(context)

        notices(throwing).post(context, alert().toByteArray(), lock = lock)

        assertFalse(lock.isHeld)
    }

    @Test
    fun theReadLock_isNotCounted_andEndsByItselfAfterTheBudgetAndItsSlack() {
        val lock = HerdPushNotices.holdForRead(context)

        assertTrue(lock.isHeld)
        assertEquals("mercurio:push-tail", shadowOf(lock).tag)
        assertFalse(shadowOf(lock).isReferenceCounted)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(HerdPushTail.BUDGET_MS + 999L, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue("the lock ended before the budget and its slack", lock.isHeld)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(2L, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse("the lock outlived the budget and its slack", lock.isHeld)
    }

    @Test
    fun everyPushThatReadsNothing_releasesTheLockBeforePostReturns() = runTest {
        val pushes = listOf(
            "not json".toByteArray(),
            alert(host = "venus").toByteArray(),
            alert().replace("\"blocked\"", "\"done\"").toByteArray(),
            alert().replace("\"blocked\"", "\"done\"").toByteArray(),
        )
        for (push in pushes) {
            val lock = HerdPushNotices.holdForRead(context)

            notices.post(context, push, lock = lock)

            assertFalse(String(push), lock.isHeld)
        }
        assertEquals(0, reads)
    }

    @Test
    fun aRepostThatThrows_staysInsideTheScope_andReleasesTheLock() = runTest {
        var escaped: Throwable? = null
        val guarded = CoroutineScope(UnconfinedTestDispatcher() + CoroutineExceptionHandler { _, e -> escaped = e })
        val throwingUsage = mock<UsageTracker> { on { log(UsageActions.PUSH_TAIL_READ) } doAnswer { throw IllegalStateException("disk full") } }
        paneEnd = "Allow?"
        val lock = HerdPushNotices.holdForRead(context)

        assertTrue(HerdPushNotices(hosts, ConnectionNotifier(), throwingUsage, tail, guarded) { clock }.post(context, alert().toByteArray(), lock = lock))

        assertNull("the repost's throw left the scope", escaped)
        assertFalse(lock.isHeld)
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test
    fun blocked_theReadsBudget_runsFromThePushsArrival() = runTest {
        var budget = -1L
        val timed = mock<HerdPushTail> {
            onBlocking { read(any(), any(), any()) } doAnswer {
                budget = it.getArgument(2)
                HerdPushTail.Read.Missed
            }
        }
        clock = 10_000L

        notices(timed).post(context, alert().toByteArray(), arrived = 7_500L)

        assertEquals(HerdPushTail.BUDGET_MS - 2_500L, budget)
    }

    @Test
    fun blocked_aPushWhoseBudgetIsSpent_isStillPosted_startsNoRead_andIsAMiss() = runTest {
        var budget = -1L
        val timed = mock<HerdPushTail> {
            onBlocking { read(any(), any(), any()) } doAnswer {
                budget = it.getArgument(2)
                HerdPushTail.Read.Missed
            }
        }

        val lock = HerdPushNotices.holdForRead(context)

        assertTrue(notices(timed).post(context, alert().toByteArray(), arrived = clock - 60_000L, lock = lock))

        assertEquals(1, manager.activeNotifications.size)
        assertEquals("a spent budget still started a read", -1L, budget)
        assertFalse(lock.isHeld)
        verify(usage).log(UsageActions.PUSH_TAIL_MISSED)
    }

    @Test
    fun blocked_theBudget_isNeverMoreThanTheWholeBudget() = runTest {
        var budget = -1L
        val timed = mock<HerdPushTail> {
            onBlocking { read(any(), any(), any()) } doAnswer {
                budget = it.getArgument(2)
                HerdPushTail.Read.Missed
            }
        }

        notices(timed).post(context, alert().toByteArray(), arrived = clock + 5_000L)

        assertEquals(HerdPushTail.BUDGET_MS, budget)
    }

    @Test
    fun blocked_theQuietRepost_keepsTheNoticesChannel_actions_time_andTap() = runTest {
        val answer = CompletableDeferred<HerdPushTail.Read>()
        val slow = mock<HerdPushTail> { onBlocking { read(any(), any(), any()) } doSuspendableAnswer { answer.await() } }
        notices(slow).post(context, alert().toByteArray())
        val first = manager.activeNotifications.single().notification

        answer.complete(HerdPushTail.Read.Text("Allow?\n❯ 1. Yes\n  2. No", CoreNotices.fingerprint("Allow?\n❯ 1. Yes\n  2. No")))

        val second = manager.activeNotifications.single().notification
        assertEquals(first.channelId, second.channelId)
        // The buttons come with the read: the first post had no proof of its prompt, the repost has the pane's end.
        assertTrue(first.actions.isNullOrEmpty())
        assertEquals(listOf("Approve", "Deny"), second.actions.map { it.title.toString() })
        assertEquals(first.`when`, second.`when`)
        assertEquals(shadowOf(first.contentIntent).savedIntent.component, shadowOf(second.contentIntent).savedIntent.component)
        assertEquals(shadowOf(first.contentIntent).savedIntent.extras?.keySet(), shadowOf(second.contentIntent).savedIntent.extras?.keySet())
    }

    // The monitor's "Needs you" carries the question from a live read and its since is the phone's poll
    // time; the host's push for that same block carries the host's ts, in whole seconds.
    private fun showMonitorNotice(since: Long, question: String) {
        val notice = HerdPushAlert.parse(alert().toByteArray())!!.notice() as HerdAttention.Notice.NeedsYou
        ConnectionNotifier().showHerdNotification(context, sun, notice.copy(paneTail = question, since = since))
    }

    @Test
    fun aPushForTheBlockTheMonitorShows_keepsItsQuestion_andReadsNothing() = runTest {
        // The host saw the block at 1 s, the phone's poll at 1.5 s; the host's clock may also run up to 2 s ahead.
        for (ts in listOf(1L, 3L)) {
            manager.cancelAll()
            showMonitorNotice(since = 1_500L, question = "Edit build.gradle?")
            val lock = HerdPushNotices.holdForRead(context)

            assertTrue(notices.post(context, alert(ts = ts).toByteArray(), lock = lock))

            assertEquals("ts=$ts", "mercurio / build\n\nEdit build.gradle?", bigText())
            assertFalse("ts=$ts held the lock with no read", lock.isHeld)
        }
        assertEquals(0, reads)
    }

    @Test
    fun aPushMoreThanTwoSecondsAfterTheShownBlock_isANewBlock_andDropsTheQuestionAtOnce() = runTest {
        val answer = CompletableDeferred<HerdPushTail.Read>()
        val slow = mock<HerdPushTail> { onBlocking { read(any(), any(), any()) } doSuspendableAnswer { answer.await() } }
        showMonitorNotice(since = 1_500L, question = "Edit build.gradle?")

        assertTrue(notices(slow).post(context, alert(ts = 4).toByteArray()))

        assertEquals("mercurio / build", bigText())
        answer.complete(HerdPushTail.Read.Text("Run rm -rf build?"))
        assertEquals("mercurio / build\n\nRun rm -rf build?", bigText())
    }

    @Test
    fun aPushWithNoTime_cannotBeTheSameBlock_andDropsTheQuestion() = runTest {
        showMonitorNotice(since = 1_500L, question = "Edit build.gradle?")

        assertTrue(notices.post(context, alert(ts = 0).toByteArray()))

        assertEquals("mercurio / build", bigText())
        assertEquals(1, reads)
    }

    @Test
    fun anOlderReadThatEndsAfterTheNextBlocksPush_neverPutsItsQuestionBack() = runTest {
        // Read 1 ran while the pane asked A; the pane then asked B. A must not land beside Approve for B.
        val first = CompletableDeferred<HerdPushTail.Read>()
        val second = CompletableDeferred<HerdPushTail.Read>()
        var calls = 0
        val tail = mock<HerdPushTail> {
            onBlocking { read(any(), any(), any()) } doSuspendableAnswer { if (calls++ == 0) first.await() else second.await() }
        }
        val notices = notices(tail)
        notices.post(context, alert(ts = 1).toByteArray())
        notices.post(context, alert(ts = 60).toByteArray())

        first.complete(HerdPushTail.Read.Text("Edit build.gradle?"))
        assertEquals("mercurio / build", bigText())

        second.complete(HerdPushTail.Read.Text("Run rm -rf build?"))
        assertEquals("mercurio / build\n\nRun rm -rf build?", bigText())
    }

    @Test
    fun anOlderReadThatEndsAfterANewBlockWithASpentBudget_neverPutsItsQuestionBack() = runTest {
        val first = CompletableDeferred<HerdPushTail.Read>()
        val slow = mock<HerdPushTail> { onBlocking { read(any(), any(), any()) } doSuspendableAnswer { first.await() } }
        val notices = notices(slow)
        notices.post(context, alert(ts = 1).toByteArray())
        notices.post(context, alert(ts = 60).toByteArray(), arrived = clock - 60_000L)

        first.complete(HerdPushTail.Read.Text("Edit build.gradle?"))

        assertEquals("mercurio / build", bigText())
    }

    @Test
    fun anOlderReadThatEndsAfterTheNextBlocksPush_whenBothNoticesCarryOneTime_neverPutsItsQuestionBack() = runTest {
        // A push with no ts takes the phone's clock for its notice's time, so two blocks pushed within one
        // millisecond carry the same one. The notifier sets and reports one time for every notice it shows,
        // so only the block count can tell the older read it is late.
        val notifier = spy(ConnectionNotifier())
        doAnswer {
            it.callRealMethod()
            42L
        }.whenever(notifier).showHerdNotification(any(), any(), any(), any())
        doAnswer { (it.callRealMethod() as Long?)?.let { 42L } }.whenever(notifier).herdNoticeShownAt(any(), any(), any())
        val first = CompletableDeferred<HerdPushTail.Read>()
        var calls = 0
        val tail = mock<HerdPushTail> {
            onBlocking { read(any(), any(), any()) } doSuspendableAnswer { if (calls++ == 0) first.await() else HerdPushTail.Read.Missed }
        }
        val notices = HerdPushNotices(hosts, notifier, usage, tail, scope) { clock }
        notices.post(context, alert(ts = 0).toByteArray())
        notices.post(context, alert(ts = 0).toByteArray())
        assertEquals(2, calls)

        first.complete(HerdPushTail.Read.Text("Edit build.gradle?"))

        assertEquals("mercurio / build", bigText())
    }

    @Test
    fun onADeviceWhoseShownListLagsTheNotify_theQuestionStillGoesIn_onAFirstPost_andOnABlockAgain() = runTest {
        // A device's notification service adds a posted notice to its shown list on its own thread, so a read
        // of that list straight after notify() can still see the notice before (or none). Robolectric's list
        // is updated at once; this notifier gives the list as it was before the newest post until the pane
        // read runs (a login and a read over SSH, far longer than the service's lag).
        val real = ConnectionNotifier()
        val notifier = spy(ConnectionNotifier())
        var stale: Array<Long?>? = null
        doAnswer {
            stale = arrayOf(real.herdNoticeShownAt(context, it.getArgument(1), it.getArgument(2)))
            it.callRealMethod()
        }.whenever(notifier).showHerdNotification(any(), any(), any(), any())
        doAnswer {
            val lagged = stale
            stale = null
            if (lagged != null) lagged[0] else it.callRealMethod()
        }.whenever(notifier).herdNoticeShownAt(any(), any(), any())
        val tail = mock<HerdPushTail> {
            onBlocking { read(any(), any(), any()) } doAnswer {
                stale = null
                paneEnd?.let { HerdPushTail.Read.Text(it, CoreNotices.fingerprint(it)) } ?: HerdPushTail.Read.Missed
            }
        }
        val notices = HerdPushNotices(hosts, notifier, usage, tail, scope) { clock }

        paneEnd = "Edit build.gradle?"
        assertTrue(notices.post(context, alert(ts = 1).toByteArray()))
        assertEquals("mercurio / build\n\nEdit build.gradle?", bigText())

        paneEnd = "Run rm -rf build?"
        assertTrue(notices.post(context, alert(ts = 60).toByteArray()))
        assertEquals("mercurio / build\n\nRun rm -rf build?", bigText())
    }

    @Test
    fun aReadThatEndsAfterTheMonitorPostedANewBlock_leavesTheMonitorsQuestion() = runTest {
        val first = CompletableDeferred<HerdPushTail.Read>()
        val slow = mock<HerdPushTail> { onBlocking { read(any(), any(), any()) } doSuspendableAnswer { first.await() } }
        notices(slow).post(context, alert(ts = 1).toByteArray())
        showMonitorNotice(since = 60_500L, question = "Run rm -rf build?")

        first.complete(HerdPushTail.Read.Text("Edit build.gradle?"))

        assertEquals("mercurio / build\n\nRun rm -rf build?", bigText())
    }
}
