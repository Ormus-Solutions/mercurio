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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Herdr client's exit, as captured on Sun (herdr 0.9.0), and look-alikes that must not count. */
class HerdrDropTest {

    private val esc = 27.toChar().toString()

    // The tail a herdr client wrote when it quit, with the drop Ormus hit as the reason.
    private val exitModes = "$esc[<1u$esc[?7h$esc[?1004l$esc[?2004l$esc[?1000l$esc[?1049l$esc[?2031l$esc[?25h$esc[0 q"

    @Test
    fun aLostConnectionAfterTheClientLeavesItsScreen_isADrop() {
        assertTrue(HerdrDrop.dropped(exitModes + "herdr: lost connection to server: endpoint output queue is full\r\n"))
    }

    @Test
    fun theSameWordsPrintedByAnAgentInAPane_areNotADrop() {
        assertFalse(HerdrDrop.dropped("$esc[12;3Hherdr: lost connection to server: endpoint output queue is full"))
        assertFalse(HerdrDrop.dropped("herdr: lost connection to server"))
    }

    @Test
    fun aServerStoppedOnPurpose_isNotADrop() {
        assertFalse(HerdrDrop.dropped(exitModes + "herdr: server shut down: server is shutting down\r\n"))
    }

    @Test
    fun aDropSplitAcrossTwoReads_isSeenWithTheCarry() {
        val whole = exitModes + "herdr: lost connection to server: endpoint output queue is full\r\n"
        // Cut inside the message: the second read alone lacks the screen exit.
        val cut = whole.indexOf("lost")
        val first = whole.substring(0, cut)
        val second = whole.substring(cut)
        assertFalse(HerdrDrop.dropped(second))
        assertTrue(HerdrDrop.dropped(first.takeLast(HerdrDrop.CARRY_CHARS) + second))
    }
}
