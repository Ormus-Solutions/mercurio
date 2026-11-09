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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which console posts a Herd notice when one machine has several host rows and consoles. */
class HerdNoticeRouteTest {
    private data class Console(val host: Host, val attached: String, val open: Boolean, val onScreen: Boolean = false)

    private val sun = Host(id = 7, nickname = "Sun", username = "ormus", hostname = "sun", port = 22)
    private val copy = sun.copy(id = 8, nickname = "Sun (copy)")
    private val sg = Host(id = 9, nickname = "sg", username = "ormus", hostname = "sg", port = 22)

    private fun poster(consoles: List<Console>, from: Console, session: String) = HerdNoticeRoute.poster(consoles, from, session, { it.host }, { it.attached }, { it.open }, { it.onScreen })

    @Test
    fun sameMachine_isTheUserHostAndPort_notTheRow() {
        assertTrue(HerdNoticeRoute.sameMachine(sun, copy))
        assertTrue(HerdNoticeRoute.sameMachine(sun, sun.copy(hostname = "SUN")))
        assertFalse(HerdNoticeRoute.sameMachine(sun, sg))
        assertFalse(HerdNoticeRoute.sameMachine(sun, sun.copy(port = 2222)))
        assertFalse(HerdNoticeRoute.sameMachine(sun, sun.copy(username = "root")))
    }

    @Test
    fun defaultSession_theFirstOpenConsoleOfTheMachinePosts() {
        val closed = Console(sun, "default", open = false)
        val first = Console(copy, "default", open = true)
        val second = Console(sun, "default", open = true)
        val elsewhere = Console(sg, "default", open = true)
        val all = listOf(closed, first, second, elsewhere)
        assertSame(first, poster(all, second, "default"))
        assertSame(first, poster(all, first, "default"))
        assertSame(elsewhere, poster(all, elsewhere, "default"))
    }

    @Test
    fun namedSession_theFirstOpenConsoleAttachedToItPosts() {
        val plain = Console(sun, "default", open = true)
        val board = Console(copy, "board", open = true)
        val boardToo = Console(sun, "board", open = true)
        val all = listOf(plain, board, boardToo)
        assertSame(board, poster(all, boardToo, "board"))
        assertSame(board, poster(all, board, "board"))
    }

    @Test
    fun theConsoleOnScreenPostsFirst_itsRulesKnowWhichPaneHeLooksAt() {
        val away = Console(sun, "default", open = true)
        val shown = Console(copy, "default", open = true, onScreen = true)
        val all = listOf(away, shown)
        assertSame(shown, poster(all, away, "default"))
        assertSame(shown, poster(all, shown, "default"))
    }

    @Test
    fun theRaiserPosts_whenNoOpenConsoleIsOnTheSession() {
        // It moved sessions while the notice was in flight, or its SSH session just closed: the notice still goes.
        val moved = Console(sun, "default", open = true)
        val closed = Console(copy, "ormus", open = false)
        assertSame(moved, poster(listOf(moved, closed), moved, "ormus"))
        assertSame(closed, poster(listOf(moved, closed), closed, "ormus"))
    }
}
