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
import org.junit.Assert.assertEquals
import org.junit.Test

/** The workboard rows the host list offers, from the saved hosts and what was kept for each. */
class HerdrWorkspacesTest {
    private val box = Host(id = 1L, nickname = "box", postLogin = "herdr\n")
    private val named = Host(id = 2L, nickname = "named", postLogin = "herdr session attach work\n")
    private val plain = Host(id = 3L, nickname = "plain")
    private val work = HerdrConsole("work", workspaceLabel = "main")
    private val review = HerdrConsole("review")

    @Test
    fun rows_oneRowPerKeptConsole_inTheHostsOrderThenTheLists() {
        val kept = mapOf(1L to listOf(review, work), 9L to listOf(work))
        val other = box.copy(id = 4L, nickname = "other")
        assertEquals(
            listOf(HerdrWorkspace(other, work), HerdrWorkspace(box, review), HerdrWorkspace(box, work)),
            HerdrWorkspaces.rows(listOf(other, box), kept + (4L to listOf(work))),
        )
        // A list kept for a host that is no longer saved (9) shows nothing.
        assertEquals(emptyList<HerdrWorkspace>(), HerdrWorkspaces.rows(emptyList(), kept))
        assertEquals(emptyList<HerdrWorkspace>(), HerdrWorkspaces.rows(listOf(box), emptyMap()))
    }

    @Test
    fun rows_onlyForHostsWhosePostLoginAttachesTheDefaultSession() {
        // A post-login that names a session cannot be retargeted; a host without Herdr has no workboards.
        val kept = mapOf(1L to listOf(work), 2L to listOf(work), 3L to listOf(work))
        assertEquals(listOf(HerdrWorkspace(box, work)), HerdrWorkspaces.rows(listOf(plain, box, named), kept))
    }

    @Test
    fun name_theWorkspaceTheLineLandsOn_elseTheSession() {
        assertEquals("main", HerdrWorkspace(box, work).name)
        assertEquals("review", HerdrWorkspace(box, review).name)
        assertEquals("review", HerdrWorkspace(box, HerdrConsole("review", workspaceLabel = " ")).name)
    }
}
