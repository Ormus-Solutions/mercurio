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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.terminal.AgentSwipe.Direction

/**
 * The agent swipe over a stand-in for termlib's terminal: a child that, like termlib, reads the first finger's
 * movement on the Main pass (consumed movement reads as none) and counts pinches. Robolectric, xhdpi: 1dp = 2px.
 */
@RunWith(AndroidJUnit4::class)
class AgentSwipeInputTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val fired = mutableListOf<Direction>()
    private var enabled = true

    /** What the stand-in terminal saw: the first finger's unconsumed travel, and the touches it saw end. */
    private var termTravel = Offset.Zero
    private var termTouches = 0
    private var termMaxPointers = 0

    private fun setContent() {
        rule.setContent {
            Box(
                Modifier
                    .size(400.dp)
                    .agentSwipes(enabled = { enabled }) { fired += it }
                    .testTag("terminal"),
            ) {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                termMaxPointers = maxOf(termMaxPointers, event.changes.count { it.pressed })
                                event.changes.firstOrNull { it.id == down.id }?.let { termTravel += it.positionChange() }
                                if (event.changes.none { it.pressed }) break
                            }
                            termTouches++
                        }
                    },
                )
            }
        }
    }

    /** Move every listed finger by [by] (px) in [steps] frames of 16 ms. */
    private fun TouchInjectionScope.drag(fingers: List<Int>, by: Offset, steps: Int = 10) {
        val start = fingers.associateWith { currentPosition(it)!! }
        for (step in 1..steps) {
            fingers.forEach { updatePointerTo(it, start.getValue(it) + by * (step / steps.toFloat())) }
            move()
        }
    }

    private fun swipe(by: Offset, steps: Int = 10, from: Offset = Offset(400f, 400f), fingers: Int = 1) {
        rule.onNodeWithTag("terminal").performTouchInput {
            down(0, from)
            if (fingers == 2) down(1, from + Offset(200f, 0f))
            drag((0 until fingers).toList(), by, steps)
            (0 until fingers).forEach { up(it) }
        }
        rule.waitForIdle()
    }

    @Test
    fun oneFingerLeft_isNext_right_isPrevious_andTheTerminalStopsMovingOnceClaimed() {
        setContent()
        swipe(Offset(-300f, 0f))
        assertEquals(listOf(Direction.NEXT), fired)
        // 20 dp = 40 px claims it; after that the terminal sees no movement.
        assertTrue("terminal saw ${termTravel.x}px", termTravel.x > -100f && termTravel.x <= -40f)

        swipe(Offset(300f, 0f))
        assertEquals(listOf(Direction.NEXT, Direction.PREVIOUS), fired)
    }

    @Test
    fun aClaimedTouch_neverEndsAsTheTerminalsTap() {
        setContent()
        swipe(Offset(-300f, 0f))
        // The terminal saw the touch end (it is not cancelled), with its movement past touch slop already seen: a drag, not a tap.
        assertEquals(1, termTouches)
        assertTrue(termTravel.getDistance() > 40f)
    }

    @Test
    fun aSwipeFromTheRightSide_isTheSameSwipe() {
        setContent()
        swipe(Offset(-300f, 0f), from = Offset(780f, 400f))
        swipe(Offset(300f, 0f), from = Offset(20f, 400f))
        assertEquals(listOf(Direction.NEXT, Direction.PREVIOUS), fired)
    }

    @Test
    fun oneFingerUpOrDown_isTheTerminalsScroll_slowOrFast_onTheLeftOrTheRight() {
        setContent()
        swipe(Offset(0f, -300f), steps = 30, from = Offset(700f, 600f)) // slow, on the right side
        swipe(Offset(0f, 300f), steps = 30, from = Offset(100f, 400f)) // slow, on the left
        swipe(Offset(0f, -300f), steps = 5, from = Offset(700f, 600f)) // a fast flick
        swipe(Offset(0f, 300f), steps = 5, from = Offset(400f, 300f))
        assertEquals(emptyList<Direction>(), fired)
        assertEquals("the terminal saw the whole of each drag", 0f, termTravel.y, 0.5f)
        assertEquals(4, termTouches)
    }

    @Test
    fun aDiagonalDrag_isTheTerminalsScroll_neverASwipe() {
        setContent()
        swipe(Offset(-200f, -240f), steps = 4)
        swipe(Offset(-300f, 200f), steps = 30)
        assertEquals(emptyList<Direction>(), fired)
        assertEquals(-500f, termTravel.x, 0.5f)
        assertEquals(-40f, termTravel.y, 0.5f)
    }

    @Test
    fun aDragThatStartsAcrossAndEndsDown_doesNotFire() {
        setContent()
        rule.onNodeWithTag("terminal").performTouchInput {
            down(0, Offset(400f, 300f))
            drag(listOf(0), Offset(-60f, 0f), steps = 3) // across, past 20 dp: taken
            drag(listOf(0), Offset(0f, 300f), steps = 5) // then down
            up(0)
        }
        rule.waitForIdle()
        assertEquals(emptyList<Direction>(), fired)
    }

    @Test
    fun aShortFastSwipe_fires_aShortSlowOne_doesNot() {
        setContent()
        swipe(Offset(-80f, 0f), steps = 3) // 40 dp in 48 ms
        assertEquals(listOf(Direction.NEXT), fired)

        swipe(Offset(80f, 0f), steps = 30) // 40 dp in 480 ms
        assertEquals(listOf(Direction.NEXT), fired)
    }

    /** Drag [dxPx] left in one step at +2 ms, then rest for [rests] events [restMs] apart, then lift. */
    private fun dragThenRest(dxPx: Float, rests: Int, restMs: Long) {
        rule.onNodeWithTag("terminal").performTouchInput {
            down(0, Offset(400f, 400f))
            updatePointerTo(0, Offset(400f - dxPx, 400f))
            move(delayMillis = 2)
            // A rest still moves a pixel, so that every rest is an event the tracker gets (a move with no change sends none).
            repeat(rests) { i ->
                updatePointerTo(0, Offset(400f - dxPx + (i % 2), 400f))
                move(delayMillis = restMs)
            }
            up(0)
        }
        rule.waitForIdle()
    }

    @Test
    fun aShortQuickDrag_thatRestsBeforeTheLift_doesNotFire_asTheHerdrSwipeDoesNot() {
        setContent()
        // 63 dp in 2 ms, then 120 ms at rest: its average since landing is 516 dp/s (a fling), but at the lift the finger is
        // still. The Herdr swipe reads the tracker alone here (it has 6 samples), so does this one.
        dragThenRest(dxPx = 126f, rests = 4, restMs = 30)
        assertEquals(emptyList<Direction>(), fired)
    }

    @Test
    fun aShortQuickDrag_liftedAtOnce_fires_theTrackerHasTooFewSamplesAndTheAverageDecides() {
        setContent()
        dragThenRest(dxPx = 126f, rests = 0, restMs = 0)
        assertEquals(listOf(Direction.NEXT), fired)
    }

    @Test
    fun tapsAndShortMoves_neverFire() {
        setContent()
        rule.onNodeWithTag("terminal").performTouchInput { click(Offset(300f, 400f)) }
        swipe(Offset(-30f, 0f), steps = 5) // 15 dp: under the judging distance, even at speed
        assertEquals(emptyList<Direction>(), fired)
        assertEquals(2, termTouches)
    }

    @Test
    fun twoFingers_areAPinchOrATwoFingerDrag_neverTheAgentSwipe() {
        setContent()
        swipe(Offset(-300f, 0f), fingers = 2)
        rule.onNodeWithTag("terminal").performTouchInput {
            down(0, Offset(300f, 400f))
            down(1, Offset(500f, 400f))
            for (step in 1..10) {
                updatePointerTo(0, Offset(300f - 15f * step, 400f))
                updatePointerTo(1, Offset(500f + 15f * step, 400f))
                move()
            }
            up(0)
            up(1)
        }
        rule.waitForIdle()
        assertEquals(emptyList<Direction>(), fired)
        assertEquals(2, termMaxPointers)
    }

    @Test
    fun aSecondFingerAfterTheClaim_cancelsTheSwipe() {
        setContent()
        rule.onNodeWithTag("terminal").performTouchInput {
            down(0, Offset(600f, 400f))
            drag(listOf(0), Offset(-100f, 0f), steps = 5) // taken
            down(1, Offset(200f, 600f))
            drag(listOf(0), Offset(-200f, 0f), steps = 5)
            up(1)
            up(0)
        }
        rule.waitForIdle()
        assertEquals(emptyList<Direction>(), fired)
    }

    @Test
    fun aFingerHeldStill_thenDragged_isALongPressDrag_notASwipe() {
        setContent()
        rule.onNodeWithTag("terminal").performTouchInput {
            down(0, Offset(600f, 400f))
            advanceEventTime(900) // past the long-press timeout: the terminal's selection, or drag arrows
            drag(listOf(0), Offset(-300f, 0f), steps = 5)
            up(0)
        }
        rule.waitForIdle()
        assertEquals(emptyList<Direction>(), fired)
        assertEquals("the terminal kept the whole drag", -300f, termTravel.x, 0.5f)
    }

    @Test
    fun disabled_leavesEveryTouchToTheTerminal() {
        enabled = false
        setContent()
        swipe(Offset(-300f, 0f))
        swipe(Offset(300f, 0f), steps = 3)
        assertEquals(emptyList<Direction>(), fired)
        assertEquals(0f, termTravel.x, 0.5f)
        assertEquals(2, termTouches)
    }

    @Test
    fun enabledIsReadWhenATouchStarts_soASelectionThatCameUpLeavesItToTheTerminal() {
        setContent()
        swipe(Offset(-300f, 0f))
        enabled = false
        swipe(Offset(-300f, 0f))
        enabled = true
        swipe(Offset(-300f, 0f))
        assertEquals(listOf(Direction.NEXT, Direction.NEXT), fired)
    }
}
