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

import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/** A tap on a core terminal reuses a console that is up, never one whose attach has ended. */
class CoreWatchReuseTest {
    private fun bridge(hostId: Long, address: String?, disconnected: Boolean = false): TerminalBridge {
        val b = mock<TerminalBridge>()
        whenever(b.host).thenReturn(Host(id = hostId, nickname = "box"))
        whenever(b.watch).thenReturn(address?.let { CoreWatchTarget(it, "alpha · ONE", "build") })
        whenever(b.isDisconnected).thenReturn(disconnected)
        return b
    }

    @Test
    fun aConsoleThatIsUpIsReused() {
        val up = bridge(1L, "box/t1")
        val list = listOf(bridge(1L, "box/t2"), bridge(2L, "box/t1"), up)
        assertSame(up, CoreWatchReuse.live(list, 1L, "box/t1"))
        assertEquals(emptyList<TerminalBridge>(), CoreWatchReuse.dead(list, 1L, "box/t1"))
    }

    @Test
    fun aConsoleWhoseAttachEndedIsNotReusedAndIsToBeClosed() {
        val ended = bridge(1L, "box/t1", disconnected = true)
        val list = listOf(bridge(1L, null), ended)
        assertNull(CoreWatchReuse.live(list, 1L, "box/t1"))
        assertEquals(listOf(ended), CoreWatchReuse.dead(list, 1L, "box/t1"))
    }

    @Test
    fun anEndedConsoleDoesNotHideALiveOne() {
        val ended = bridge(1L, "box/t1", disconnected = true)
        val up = bridge(1L, "box/t1")
        assertSame(up, CoreWatchReuse.live(listOf(ended, up), 1L, "box/t1"))
    }
}
