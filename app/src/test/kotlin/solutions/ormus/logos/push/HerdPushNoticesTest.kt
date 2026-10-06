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
import kotlinx.coroutines.test.runTest
import org.connectbot.data.HostRepository
import org.connectbot.data.entity.Host
import org.connectbot.service.ConnectionNotifier
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.Shadows.shadowOf

/** A push becomes the monitor's own "Needs you", once per pane, for a saved host only. */
@RunWith(AndroidJUnit4::class)
class HerdPushNoticesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val sun = Host(id = 7, nickname = "Sun", hostname = "100.100.1.1", postLogin = "herdr")
    private val hosts = mock<HostRepository> { onBlocking { getSshHosts() } doReturn listOf(sun) }
    private val usage = mock<UsageTracker>()
    private val notices = HerdPushNotices(hosts, ConnectionNotifier(), usage)

    private fun alert(host: String = "Sun", pane: String = "w1:p3") = """{"v":1,"event":"pane.agent_status_changed","state":"blocked","host":"$host","session":"default",""" +
        """"pane_id":"$pane","agent":"claude","workspace_id":"w1","workspace":"mercurio","tab_id":"w1:t2","tab":"build","ts":1}"""

    @Test
    fun blocked_postsNeedsYouWithApproveAndDeny_onTheMonitorsTag() = runTest {
        assertTrue(notices.post(context, alert().toByteArray()))

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("claude needs you on Sun", posted.extras.getCharSequence("android.title").toString())
        assertEquals("mercurio / build", posted.extras.getCharSequence("android.text").toString())
        assertEquals(listOf("Approve", "Deny"), posted.actions.map { it.title.toString() })
        val approve = shadowOf(posted.actions[0].actionIntent).savedIntent
        assertEquals(7L, approve.getLongExtra(ConnectionNotifier.EXTRA_HERD_HOST, 0))
        assertEquals("w1:p3", approve.getStringExtra(ConnectionNotifier.EXTRA_HERD_PANE))
        assertEquals("herd:7:w1:p3", manager.activeNotifications.single().tag)
    }

    @Test
    fun aNoticeAlreadyShowingForThePane_isLeftAlone() = runTest {
        notices.post(context, alert().toByteArray())
        val first = manager.activeNotifications.single().postTime
        shadowOf(manager).allNotifications.single().extras.putString("marker", "kept")

        notices.post(context, alert().toByteArray())

        assertEquals(first, manager.activeNotifications.single().postTime)
        assertEquals("kept", shadowOf(manager).allNotifications.single().extras.getString("marker"))
    }

    @Test
    fun unknownHostOrBadMessage_postsNothing_andIsCounted() = runTest {
        assertFalse(notices.post(context, alert(host = "venus").toByteArray()))
        assertFalse(notices.post(context, "not json".toByteArray()))

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        verify(usage, org.mockito.kotlin.times(2)).log(UsageActions.PUSH_IGNORED)
        verify(usage, never()).log(UsageActions.PUSH_RECEIVED)
    }
}
