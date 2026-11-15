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

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.service.TerminalBridge
import org.connectbot.terminal.TerminalDimensions
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import solutions.ormus.logos.herd.HerdrSwipe

/**
 * The scroll bug of the board: in a Herdr console a one-finger drag up, faster than about 200 dp/s, sent no wheel notch
 * and no scroll, at any x; a drag down scrolled. Measured on the emulator by the console lane
 * (`~/work/.b2-tmp/console-scroll/MEASURE.md`, table C): a 600 ms drag of 417 px at 420 dpi (159 dp, 265 dp/s) on a
 * Herdr-flagged host whose program asked for the mouse must send 13 wheel notches, up or down. Robolectric, xhdpi
 * (1 dp = 2 px): the drag is 318 px (159 dp) in 600 ms, and the rows are 24 px high so that it crosses 13 of them.
 */
@RunWith(AndroidJUnit4::class)
class HerdrBoardScrollTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val bridge = mock(TerminalBridge::class.java)
    private val fired = mutableListOf<HerdrSwipe>()

    private fun setContent() {
        `when`(bridge.mouseReporting).thenReturn(true)
        `when`(bridge.mouseWheel(anyInt(), anyInt(), anyBoolean())).thenReturn(true)
        val wheel = TerminalWheel(
            bridge = { bridge },
            cell = { Size(20f, 24f) },
            size = { TerminalDimensions(rows = 40, columns = 40) },
        )
        rule.setContent {
            Box(Modifier.size(400.dp).herdrSwipes(enabled = { true }, wheel = wheel) { swipe, _ -> fired += swipe }.testTag("terminal"))
        }
    }

    /**
     * One finger from [from] to [to] in [durationMs], every [stepMs]. With a [burstPx] its first move comes 2 ms after the
     * down and is already that far along the way (the samples of a real swipe come bunched at its start, which the console
     * lane could not read on the emulator); the rest of the drag is even, so the whole drag still takes [durationMs].
     */
    private fun drag(from: Offset, to: Offset, durationMs: Int, burstPx: Float = 0f, stepMs: Int = 16) {
        rule.onNodeWithTag("terminal").performTouchInput {
            down(from)
            val way = to - from
            val unit = way / way.getDistance()
            var at = 0
            if (burstPx > 0f) {
                moveTo(from + unit * burstPx, delayMillis = 2)
                at = 2
            }
            val steps = (durationMs - at) / stepMs
            for (step in 1..steps) {
                moveTo(from + unit * burstPx + (to - from - unit * burstPx) * (step / steps.toFloat()), delayMillis = stepMs.toLong())
            }
            up()
        }
        rule.waitForIdle()
    }

    private val down = Offset(400f, 200f) to Offset(400f, 518f)
    private val up = Offset(400f, 700f) to Offset(400f, 382f)

    // The repro of the board's bug. The finger reaches the judging distance (20 dp) in the first move, so the average since
    // the landing is huge and main took the drag for a flick: consumed, then dropped at the lift (classify wants 1000 dp/s
    // again). Cause read in the code (`HerdrSwipes.claim`), not instrumented on the phone. The emulator showed it for the
    // drag up only; this profile is the same in both directions, so both fail on main.
    @Test
    fun aDragUpOf265DpPerSecond_whoseFirstMoveIsFar_sendsThirteenWheelNotches() {
        setContent()
        drag(up.first, up.second, durationMs = 592, burstPx = 48f)
        verify(bridge, times(13)).mouseWheel(anyInt(), anyInt(), eq(false))
        assertEquals(emptyList<HerdrSwipe>(), fired)
    }

    @Test
    fun aDragDownOf265DpPerSecond_whoseFirstMoveIsFar_sendsThirteenWheelNotches() {
        setContent()
        drag(down.first, down.second, durationMs = 592, burstPx = 48f)
        verify(bridge, times(13)).mouseWheel(anyInt(), anyInt(), eq(true))
        assertEquals(emptyList<HerdrSwipe>(), fired)
    }

    // Controls: an even drag scrolled on main too.
    @Test
    fun anEvenDragUpOf265DpPerSecond_sendsThirteenWheelNotches() {
        setContent()
        drag(up.first, up.second, durationMs = 592)
        verify(bridge, times(13)).mouseWheel(anyInt(), anyInt(), eq(false))
        assertEquals(emptyList<HerdrSwipe>(), fired)
    }

    @Test
    fun anEvenDragDownOf265DpPerSecond_sendsThirteenWheelNotches() {
        setContent()
        drag(down.first, down.second, durationMs = 592)
        verify(bridge, times(13)).mouseWheel(anyInt(), anyInt(), eq(true))
        assertEquals(emptyList<HerdrSwipe>(), fired)
    }
}
