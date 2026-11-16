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

package org.connectbot.ui.screens.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import solutions.ormus.logos.terminal.AgentSwipe.Hint

/** The hint of a swipe that opens another console is handed to that console, once, and only while it is fresh. */
class AgentHintHandoffTest {
    @Before
    fun clear() {
        AgentHintHandoff.take("t2", Long.MAX_VALUE)
    }

    @Test
    fun whatIsPut_isTakenOnce_byTheConsoleItIsFor() {
        AgentHintHandoff.put(Hint.NEXT, "t2", now = 0)

        assertEquals(Hint.NEXT, AgentHintHandoff.take("t2", now = 1_000))
        assertNull("one shot", AgentHintHandoff.take("t2", now = 1_001))
    }

    @Test
    fun aConsoleForAnotherTerminal_takesNothing_andTheHintWaitsForTheRightOne() {
        AgentHintHandoff.put(Hint.NEXT, "t2", now = 0)

        assertNull("a console opened by a tap", AgentHintHandoff.take("t3", now = 500))
        assertEquals("still there for t2", Hint.NEXT, AgentHintHandoff.take("t2", now = 600))
    }

    @Test
    fun aConsoleWithNoWatch_takesNothing() {
        AgentHintHandoff.put(Hint.NEXT, "t2", now = 0)

        assertNull(AgentHintHandoff.take(null, now = 500))
    }

    @Test
    fun aHintNobodyTook_isGoneAfterTwoSeconds_soALaterConsoleDoesNotShowIt() {
        AgentHintHandoff.put(Hint.PREVIOUS, "t2", now = 0)

        assertNull(AgentHintHandoff.take("t2", now = 2_001))
        assertNull("and it was dropped, not kept", AgentHintHandoff.take("t2", now = 2_002))
    }

    @Test
    fun theLastPutWins() {
        AgentHintHandoff.put(Hint.NEXT, "t2", now = 0)
        AgentHintHandoff.put(Hint.PREVIOUS, "t3", now = 10)

        assertNull(AgentHintHandoff.take("t2", now = 20))
        assertEquals(Hint.PREVIOUS, AgentHintHandoff.take("t3", now = 21))
    }

    @Test
    fun nothingPut_nothingTaken() {
        assertNull(AgentHintHandoff.take("t2", now = 0))
    }
}
