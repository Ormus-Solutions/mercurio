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

package org.connectbot.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.data.entity.Host
import org.connectbot.ui.MainActivity
import org.connectbot.usage.UsageActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import solutions.ormus.logos.herd.CoreFacts
import solutions.ormus.logos.herd.CoreNotices
import solutions.ormus.logos.herd.HerdAnswer
import solutions.ormus.logos.herd.HerdAnswerOutcome
import solutions.ormus.logos.herd.HerdAttention.Notice
import solutions.ormus.logos.herd.HerdrAgentInfo
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.NeedsYouKind

/** The Herdr "Needs you" and "Finished" notifications and their channels. */
@RunWith(AndroidJUnit4::class)
class ConnectionNotifierHerdTest {
    class HostService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }

    private val service = Robolectric.buildService(HostService::class.java).create().get()
    private val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val host = Host(id = 7, nickname = "Sun", hostname = "100.100.1.1", postLogin = "herdr")
    private val agent = HerdrAgentInfo(
        paneId = "w1:p1",
        agent = "claude",
        agentStatus = HerdrAgentStatus.BLOCKED,
        terminalTitleStripped = "Docs site build",
    )

    @Test
    fun needsYou_onTheHighPriorityAgentChannel_keyedToThePane() {
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("logos_agent_channel", posted.channelId)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("logos_agent_channel").importance)
        val extras = posted.extras
        assertEquals("claude needs you on Sun", extras.getCharSequence("android.title").toString())
        assertEquals("~Demo / 1 · Docs site build", extras.getCharSequence("android.text").toString())

        val tap = shadowOf(posted.contentIntent).savedIntent
        assertEquals("w1:p1", tap.getStringExtra(MainActivity.EXTRA_HERD_PANE))
        assertEquals(UsageActions.NOTIFY_NEEDS_YOU_OPEN, tap.getStringExtra(MainActivity.EXTRA_HERD_NOTICE))
        assertEquals(host.getUri(), tap.data)
    }

    @Test
    fun finished_onItsOwnDefaultChannel_andClearsNeedsYou() {
        val notifier = ConnectionNotifier()
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))
        notifier.showHerdNotification(service, host, Notice.Finished(agent.copy(agentStatus = HerdrAgentStatus.DONE), "~Demo", "1"))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("logos_agent_finished_channel", posted.channelId)
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            manager.getNotificationChannel("logos_agent_finished_channel").importance,
        )
        assertEquals("claude finished on Sun", posted.extras.getCharSequence("android.title").toString())
        assertEquals(
            UsageActions.NOTIFY_FINISHED_OPEN,
            shadowOf(posted.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_HERD_NOTICE),
        )
    }

    @Test
    fun differentPanes_keepSeparateNotifications() {
        val notifier = ConnectionNotifier()
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent.copy(paneId = "w1:p2"), "~Demo", "2"))

        assertTrue(shadowOf(manager).allNotifications.size == 2)
    }

    @Test
    fun samePaneInAnotherSession_keepsItsOwnNotification_andCarriesItsSession() {
        val notifier = ConnectionNotifier()
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "ops", "1", session = "work", seq = 1, prompt = 5))

        val posted = shadowOf(manager).allNotifications
        assertEquals(2, posted.size)
        val work = posted.single { it.extras.getCharSequence("android.text").toString().startsWith("ops") }
        assertEquals("work", shadowOf(work.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_HERD_SESSION))
        assertEquals("work", shadowOf(work.actions[0].actionIntent).savedIntent.getStringExtra(ConnectionNotifier.EXTRA_HERD_SESSION))
        assertTrue(notifier.herdNoticeShownAt(service, host, Notice.NeedsYou(agent, null, null, session = "work")) != null)

        // Answering the work session's pane clears its notice only.
        notifier.showHerdAnswer(service, host, "work", "w1:p1", "claude", HerdAnswerOutcome.Sent)
        assertEquals("~Demo / 1 · Docs site build", shadowOf(manager).allNotifications.single().extras.getCharSequence("android.text").toString())
    }

    @Test
    fun needsYou_provenAgent_approveAndDenyAskForTheUnlock_andShowThePane() {
        val tail = "Do you want to proceed?\n\u276F 1. Yes\n  2. No"
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1", paneTail = tail, prompt = CoreNotices.fingerprint(tail)))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("~Demo / 1 · Docs site build\n\n$tail", posted.extras.getCharSequence("android.bigText").toString())
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals(listOf("Approve", "Deny"), posted.actions.map { it.title.toString() })
        posted.actions.forEach { assertTrue("${it.title} asks for the unlock", it.isAuthenticationRequired) }

        val approve = shadowOf(posted.actions[0].actionIntent).savedIntent
        assertEquals(TerminalManager::class.java.name, approve.component?.className)
        assertEquals(ConnectionNotifier.ACTION_HERD_ANSWER, approve.action)
        assertEquals(7L, approve.getLongExtra(ConnectionNotifier.EXTRA_HERD_HOST, 0))
        assertEquals("w1:p1", approve.getStringExtra(ConnectionNotifier.EXTRA_HERD_PANE))
        assertEquals(HerdAnswer.APPROVE.name, approve.getStringExtra(ConnectionNotifier.EXTRA_HERD_ANSWER))
        assertEquals(HerdAnswer.DENY.name, shadowOf(posted.actions[1].actionIntent).savedIntent.getStringExtra(ConnectionNotifier.EXTRA_HERD_ANSWER))
    }

    @Test
    fun coreNeedsYou_permission_approveCarriesTheTurn_andTheTapCarriesNoHerdPane() {
        val core = HerdrAgentInfo(
            paneId = "t1",
            agent = "claude-code",
            agentStatus = HerdrAgentStatus.BLOCKED,
            core = CoreFacts(NeedsYouKind.PERMISSION, turn = 4),
        )
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(core, "alpha · ONE", "main", paneTail = "Run it?\n❯ 1. Yes\n2. No", prompt = CoreNotices.fingerprint("Run it?\n❯ 1. Yes\n2. No"), session = CoreNotices.SESSION))

        val posted = shadowOf(manager).allNotifications.single()
        // The core session is no workboard name: the title never says "@core".
        assertEquals("claude-code needs you on Sun", posted.extras.getCharSequence("android.title").toString())
        assertEquals(listOf("Approve", "Deny"), posted.actions.map { it.title.toString() })
        val approve = shadowOf(posted.actions[0].actionIntent).savedIntent
        assertEquals(CoreNotices.SESSION, approve.getStringExtra(ConnectionNotifier.EXTRA_HERD_SESSION))
        assertEquals("t1", approve.getStringExtra(ConnectionNotifier.EXTRA_HERD_PANE))
        assertEquals(4, approve.getIntExtra(ConnectionNotifier.EXTRA_HERD_TURN, -1))
        assertEquals(CoreNotices.fingerprint("Run it?\n❯ 1. Yes\n2. No"), approve.getIntExtra(ConnectionNotifier.EXTRA_HERD_PROMPT, 0))
        val tap = shadowOf(posted.contentIntent).savedIntent
        assertFalse(tap.hasExtra(MainActivity.EXTRA_HERD_PANE))
        assertFalse(tap.hasExtra(MainActivity.EXTRA_HERD_SESSION))
        assertEquals(host.getUri(), tap.data)
    }

    @Test
    fun coreNeedsYou_tapCarriesTheTerminalAddress_toOpenItsWatchView() {
        val core = HerdrAgentInfo(paneId = "box/t7", agent = "claude-code", agentStatus = HerdrAgentStatus.BLOCKED, core = CoreFacts(NeedsYouKind.QUESTION, turn = 2))
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(core, "alpha", "ONE", session = CoreNotices.SESSION))

        val tap = shadowOf(shadowOf(manager).allNotifications.single().contentIntent).savedIntent
        assertEquals("box/t7", tap.getStringExtra(MainActivity.EXTRA_CORE_ADDRESS))
        assertEquals("alpha", tap.getStringExtra(MainActivity.EXTRA_CORE_SPACE))
        assertEquals("ONE", tap.getStringExtra(MainActivity.EXTRA_CORE_TAB))
        assertEquals(host.id, tap.getLongExtra(MainActivity.EXTRA_CORE_HOST, -1L))
        assertEquals(host.getUri(), tap.data)
        // Not a Herdr pane: the TUI focus extras stay off.
        assertFalse(tap.hasExtra(MainActivity.EXTRA_HERD_PANE))
    }

    @Test
    fun twoAgentsOnOneHost_haveTapIntentsThatAreNotFilterEqual() {
        // A PendingIntent is found by its filterEquals intent and request code, never by extras: two agents
        // whose request codes collide must still be two intents, or one notice would open the other's agent.
        val notifier = ConnectionNotifier()
        fun core(address: String) = HerdrAgentInfo(paneId = address, agent = "claude-code", agentStatus = HerdrAgentStatus.BLOCKED, core = CoreFacts(NeedsYouKind.QUESTION, turn = 1))
        notifier.showHerdNotification(service, host, Notice.NeedsYou(core("t1"), "alpha", "ONE", session = CoreNotices.SESSION))
        notifier.showHerdNotification(service, host, Notice.NeedsYou(core("t2"), "alpha", "ONE", session = CoreNotices.SESSION))

        val taps = shadowOf(manager).allNotifications.map { shadowOf(it.contentIntent).savedIntent }
        assertEquals(2, taps.size)
        assertFalse(taps[0].filterEquals(taps[1]))
    }

    @Test
    fun coreAnswerOutcome_tapKeepsTheHostConsole() {
        ConnectionNotifier().showHerdAnswer(service, host, CoreNotices.SESSION, "t1", "claude-code", HerdAnswerOutcome.NotBlocked)

        val tap = shadowOf(shadowOf(manager).allNotifications.single().contentIntent).savedIntent
        assertFalse(tap.hasExtra(MainActivity.EXTRA_CORE_ADDRESS))
        assertEquals(host.getUri(), tap.data)
    }

    @Test
    fun tuiNeedsYou_tapCarriesNoCoreAddress() {
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(agent, "w", "t"))

        val tap = shadowOf(shadowOf(manager).allNotifications.single().contentIntent).savedIntent
        assertFalse(tap.hasExtra(MainActivity.EXTRA_CORE_ADDRESS))
    }

    @Test
    fun coreNeedsYou_permission_withNoPromptToFingerprint_hasNoActions_butStillShowsAndOpens() {
        val core = HerdrAgentInfo(paneId = "t1", agent = "claude-code", agentStatus = HerdrAgentStatus.BLOCKED, core = CoreFacts(NeedsYouKind.PERMISSION, turn = 4))
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(core, "alpha · ONE", "main", paneTail = null, session = CoreNotices.SESSION))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("claude-code needs you on Sun", posted.extras.getCharSequence("android.title").toString())
        assertTrue(posted.actions.isNullOrEmpty())
        assertEquals(host.getUri(), shadowOf(posted.contentIntent).savedIntent.data)
    }

    @Test
    fun tuiNeedsYou_approveAndDeny_carryTheSeqTheNoticeWasRaisedAt_andTheAnswerReadsItBack() {
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1", seq = 7, prompt = 5))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(2, posted.actions.size)
        for (action in posted.actions) {
            val intent = shadowOf(action.actionIntent).savedIntent
            assertEquals(7L, intent.getLongExtra(ConnectionNotifier.EXTRA_HERD_SEQ, -1))
            assertEquals(7L, HerdAnswerRequest.from(intent)?.seq)
        }
    }

    @Test
    fun tuiNeedsYou_withNeitherASeqNorAPromptToHash_hasNoActions_butStillShows() {
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("claude needs you on Sun", posted.extras.getCharSequence("android.title").toString())
        assertTrue(posted.actions.isNullOrEmpty())
    }

    @Test
    fun tuiNeedsYou_withASeqButNoRecognisedYesNoPrompt_hasNoActions_butStillShows() {
        // A plan pick or a question: Enter could land on an option nobody offered.
        val plan = "Ready to code?\n❯ 1. Yes, clear context\n  2. Yes, and manually approve\n  3. Type here to tell Claude what to change"
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1", paneTail = plan, prompt = CoreNotices.fingerprint(plan), seq = 7))

        val posted = shadowOf(manager).allNotifications.single()
        assertTrue(posted.actions.isNullOrEmpty())
        assertEquals("~Demo / 1 · Docs site build\n\n$plan", posted.extras.getCharSequence("android.bigText").toString())
    }

    @Test
    fun tuiNeedsYou_aPromptHashFromTheFullRead_isCarried_notOneRehashedFromTheTail() {
        // The notice's tail is cut; the hash was taken from the whole read and rides on the notice.
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1", paneTail = "wrapped end of options", prompt = 1234, seq = 7))

        val intent = shadowOf(shadowOf(manager).allNotifications.single().actions[0].actionIntent).savedIntent
        assertEquals(1234, HerdAnswerRequest.from(intent)?.prompt)
    }

    @Test
    fun tuiNeedsYou_withNoSeqButItsPaneEnd_carriesTheHashOfIt_andNoSeq() {
        val tail = "Run it?\n❯ 1. Yes\n2. No"
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1", paneTail = tail, prompt = CoreNotices.fingerprint(tail)))

        val intent = shadowOf(shadowOf(manager).allNotifications.single().actions[0].actionIntent).savedIntent
        assertFalse(intent.hasExtra(ConnectionNotifier.EXTRA_HERD_SEQ))
        assertEquals(CoreNotices.fingerprint(tail), HerdAnswerRequest.from(intent)?.prompt)
    }

    @Test
    fun aSeqOfZero_isARealSeq_andIsCarried() {
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1", seq = 0, prompt = 5))

        val intent = shadowOf(shadowOf(manager).allNotifications.single().actions[0].actionIntent).savedIntent
        assertEquals(0L, HerdAnswerRequest.from(intent)?.seq)
    }

    @Test
    fun anAnswerFromANoticeBeforeTheSeqExisted_hasNoSeq() {
        val intent = Intent(service, TerminalManager::class.java).apply {
            action = ConnectionNotifier.ACTION_HERD_ANSWER
            putExtra(ConnectionNotifier.EXTRA_HERD_HOST, 7L)
            putExtra(ConnectionNotifier.EXTRA_HERD_PANE, "w1:p1")
            putExtra(ConnectionNotifier.EXTRA_HERD_ANSWER, HerdAnswer.DENY.name)
        }
        val request = HerdAnswerRequest.from(intent)!!
        assertEquals(null, request.seq)
        assertEquals(null, request.prompt)
        assertTrue("nothing to hold the answer to", request.unchecked)
        assertEquals("w1:p1", request.paneId)
        assertEquals(HerdAnswer.DENY, request.answer)
    }

    @Test
    fun answerForAHostThatIsGone_replacesTheNeedsYouWithWhy_withNoOpenAction() {
        val notifier = ConnectionNotifier()
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1", seq = 3, prompt = 5))
        val approve = shadowOf(shadowOf(manager).allNotifications.single().actions[0].actionIntent).savedIntent
        val request = HerdAnswerRequest.from(approve)!!

        notifier.showHerdHostGone(service, request)

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("Could not answer claude on Sun", posted.extras.getCharSequence("android.title").toString())
        assertEquals("That host is no longer saved.", posted.extras.getCharSequence("android.text").toString())
        assertTrue(posted.actions.isNullOrEmpty())
    }

    @Test
    fun answerForAGoneHost_fromANoticeWithNoTag_stillReplacesTheNeedsYou() {
        val notifier = ConnectionNotifier()
        // A notice posted by the build before the tag was carried, on the tag that build used (the host row's id).
        val old = androidx.core.app.NotificationCompat.Builder(service, "logos_agent_channel").setContentTitle("old").build()
        manager.notify(notifier.legacyTag(host, "default", "w1:p1"), ConnectionNotifier.HERD_NEEDS_YOU_NOTIFICATION, old)
        val intent = Intent(service, TerminalManager::class.java).apply {
            action = ConnectionNotifier.ACTION_HERD_ANSWER
            putExtra(ConnectionNotifier.EXTRA_HERD_HOST, 7L)
            putExtra(ConnectionNotifier.EXTRA_HERD_PANE, "w1:p1")
            putExtra(ConnectionNotifier.EXTRA_HERD_ANSWER, HerdAnswer.APPROVE.name)
            putExtra(ConnectionNotifier.EXTRA_HERD_AGENT, "claude")
        }

        notifier.showHerdHostGone(service, HerdAnswerRequest.from(intent)!!)

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("That host is no longer saved.", posted.extras.getCharSequence("android.text").toString())
    }

    @Test
    fun coreNeedsYou_question_hasNoActions() {
        val core = HerdrAgentInfo(paneId = "t1", agent = "claude-code", agentStatus = HerdrAgentStatus.BLOCKED, core = CoreFacts(NeedsYouKind.QUESTION, turn = 4))
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(core, "alpha · ONE", "main", session = CoreNotices.SESSION))

        assertTrue(shadowOf(manager).allNotifications.single().actions.isNullOrEmpty())
    }

    @Test
    fun needsYou_unprovenAgent_hasNoActions() {
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(agent.copy(agent = "grok"), "~Demo", "1"))

        assertTrue(shadowOf(manager).allNotifications.single().actions.isNullOrEmpty())
    }

    @Test
    fun answerSent_clearsTheNeedsYou() {
        val notifier = ConnectionNotifier()
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))
        notifier.showHerdAnswer(service, host, "default", "w1:p1", "claude", HerdAnswerOutcome.Sent)

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun answerNotSent_replacesTheNeedsYouWithWhy_withoutActions() {
        val notifier = ConnectionNotifier()
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))
        notifier.showHerdAnswer(service, host, "default", "w1:p1", "claude", HerdAnswerOutcome.NotBlocked)

        val notBlocked = shadowOf(manager).allNotifications.single()
        assertEquals("claude on Sun is no longer waiting", notBlocked.extras.getCharSequence("android.title").toString())
        assertEquals("Nothing was sent.", notBlocked.extras.getCharSequence("android.text").toString())
        assertTrue(notBlocked.actions.isNullOrEmpty())

        notifier.showHerdAnswer(service, host, "default", "w1:p1", "claude", HerdAnswerOutcome.Failed("pane w1:p1 not found"))
        val failed = shadowOf(manager).allNotifications.single()
        assertEquals("Could not answer claude on Sun", failed.extras.getCharSequence("android.title").toString())
        assertEquals("pane w1:p1 not found", failed.extras.getCharSequence("android.text").toString())
        assertFalse(failed.actions?.isNotEmpty() == true)
        assertEquals("w1:p1", shadowOf(failed.contentIntent).savedIntent.getStringExtra(MainActivity.EXTRA_HERD_PANE))
    }

    @Test
    fun anAnswerThatCouldNotBeProven_saysToOpenTheApp_notThatTheAgentStoppedWaiting() {
        val notifier = ConnectionNotifier()
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1", seq = 1, prompt = 5))
        notifier.showHerdAnswer(service, host, "default", "w1:p1", "claude", HerdAnswerOutcome.Unproven)

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("Could not answer claude on Sun", posted.extras.getCharSequence("android.title").toString())
        assertEquals("Open Mercurio to answer.", posted.extras.getCharSequence("android.text").toString())
    }

    @Test
    fun needsYou_inAnotherSession_namesItInTheTitle_andShowsWhenTheBlockBegan() {
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1", since = 1_700_000_000_000, session = "board"))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("claude needs you on Sun · board", posted.extras.getCharSequence("android.title").toString())
        assertEquals("~Demo / 1 · Docs site build", posted.extras.getCharSequence("android.text").toString())
        assertEquals(1_700_000_000_000, posted.`when`)
        assertTrue(posted.extras.getBoolean("android.showWhen"))
    }

    @Test
    fun hostRowsOfOneMachine_shareOneNotification() {
        val copy = host.copy(id = 8, nickname = "Sun (copy)")
        val notifier = ConnectionNotifier()
        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))
        notifier.showHerdNotification(service, copy, Notice.NeedsYou(agent, "~Demo", "1"))

        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertTrue(notifier.herdNoticeShownAt(service, copy, Notice.NeedsYou(agent, null, null)) != null)
    }

    @Test
    fun finished_inAnotherSession_namesItInTheTitle() {
        ConnectionNotifier().showHerdNotification(service, host, Notice.Finished(agent.copy(agentStatus = HerdrAgentStatus.DONE), "~Demo", "1", session = "board"))
        assertEquals("claude finished on Sun · board", shadowOf(manager).allNotifications.single().extras.getCharSequence("android.title").toString())
    }

    @Test
    fun needsYou_timeIsNeverAheadOfThePhone() {
        // A push carries the host's clock; a host ahead of the phone must not put the notice in the future.
        val before = System.currentTimeMillis()
        ConnectionNotifier().showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1", since = before + 3_600_000))
        val posted = shadowOf(manager).allNotifications.single()
        assertTrue(posted.`when` >= before && posted.`when` <= System.currentTimeMillis())
    }

    @Test
    fun aNoticeFromTheBuildBefore_keyedOnTheHostRow_isReplaced() {
        val notifier = ConnectionNotifier()
        val old = androidx.core.app.NotificationCompat.Builder(service, "logos_agent_channel").setContentTitle("old").build()
        manager.notify(notifier.legacyTag(host, "default", agent.paneId), ConnectionNotifier.HERD_NEEDS_YOU_NOTIFICATION, old)
        manager.notify(notifier.legacyTag(host, "board", agent.paneId), ConnectionNotifier.HERD_FINISHED_NOTIFICATION, old)
        assertEquals(2, shadowOf(manager).allNotifications.size)

        notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))
        notifier.showHerdNotification(service, host, Notice.Finished(agent, "~Demo", "1", session = "board"))
        val titles = shadowOf(manager).allNotifications.map { it.extras.getCharSequence("android.title").toString() }.sorted()
        assertEquals(listOf("claude finished on Sun · board", "claude needs you on Sun"), titles)
    }

    @Test
    fun herdNoticeShownAt_isTheShowingNoticesTime_ofItsKindOnly_andNullWhenNoneShows() {
        val notifier = ConnectionNotifier()
        val needsYou = Notice.NeedsYou(agent, "~Demo", "1", since = 1_500L)
        assertEquals(null, notifier.herdNoticeShownAt(service, host, needsYou))

        notifier.showHerdNotification(service, host, needsYou)

        assertEquals(1_500L, notifier.herdNoticeShownAt(service, host, Notice.NeedsYou(agent, null, null)))
        assertEquals(null, notifier.herdNoticeShownAt(service, host, Notice.Finished(agent, null, null)))
        assertEquals(null, notifier.herdNoticeShownAt(service, host, Notice.NeedsYou(agent, null, null, session = "work")))
        manager.cancelAll()
        assertEquals(null, notifier.herdNoticeShownAt(service, host, needsYou))
    }

    @Test
    fun showHerdNotification_returnsTheTimeOnTheNotice_onEveryPath() {
        // A push keeps the returned time to know its own notice later: it must be the posted `when` exactly.
        val notifier = ConnectionNotifier()
        fun postedWhen() = shadowOf(manager).allNotifications.single().`when`
        val now = System.currentTimeMillis()

        val noBlockTime = notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1"))
        assertEquals(postedWhen(), noBlockTime)

        val past = notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1", since = 1_500L))
        assertEquals(1_500L, past)
        assertEquals(postedWhen(), past)

        val future = notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1", since = now + 3_600_000))
        assertEquals(postedWhen(), future)
        assertTrue(future <= System.currentTimeMillis())

        val quietRepost = notifier.showHerdNotification(service, host, Notice.NeedsYou(agent, "~Demo", "1", since = 2_500L, paneTail = "Allow?\n❯ 1. Yes\n  2. No", prompt = CoreNotices.fingerprint("Allow?\n❯ 1. Yes\n  2. No")), quiet = true)
        assertEquals(2_500L, quietRepost)
        assertEquals(postedWhen(), quietRepost)

        manager.cancelAll()
        val finished = notifier.showHerdNotification(service, host, Notice.Finished(agent.copy(agentStatus = HerdrAgentStatus.DONE), "~Demo", "1"))
        assertEquals(postedWhen(), finished)
    }
}
