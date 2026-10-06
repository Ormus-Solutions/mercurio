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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.connectbot.service.TerminalBridge
import org.connectbot.transport.CommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrPrefix
import solutions.ormus.logos.herd.HerdrSwipe

/**
 * The swipe layer over a stand-in for termlib's terminal: a child that, like termlib, reads
 * the first finger's movement on the Main pass (consumed movement reads as none) and counts
 * pinches. Real multi-pointer touches go through Compose's input pipeline (Robolectric,
 * xhdpi: 1dp = 2px).
 */
@RunWith(AndroidJUnit4::class)
class HerdrSwipeInputTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val fired = mutableListOf<HerdrSwipe>()
    private var enabled = true

    /** What the stand-in terminal saw: the first finger's unconsumed travel, and touches it ended. */
    private var termTravel = Offset.Zero
    private var termTouches = 0
    private var termMaxPointers = 0

    private fun setContent() {
        rule.setContent {
            Box(
                Modifier
                    .size(400.dp)
                    .herdrSwipes(enabled = { enabled }) { fired += it }
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

    /** Move every listed finger by [by] (px) in [steps] frames of 16ms. */
    private fun TouchInjectionScope.drag(fingers: List<Int>, by: Offset, steps: Int = 10) {
        val start = fingers.associateWith { currentPosition(it)!! }
        for (step in 1..steps) {
            fingers.forEach { updatePointerTo(it, start.getValue(it) + by * (step / steps.toFloat())) }
            move()
        }
    }

    private fun swipe(fingers: Int, by: Offset, steps: Int = 10) {
        rule.onNodeWithTag("terminal").performTouchInput {
            down(0, Offset(300f, 400f))
            if (fingers == 2) down(1, Offset(500f, 400f))
            drag((0 until fingers).toList(), by, steps)
            (0 until fingers).forEach { up(it) }
        }
        rule.waitForIdle()
    }

    @Test
    fun oneFingerAcross_switchesTab_andTheTerminalStopsMovingOnceClaimed() {
        setContent()
        swipe(1, Offset(-300f, 0f))
        assertEquals(listOf(HerdrSwipe.NEXT_TAB), fired)
        // 20dp = 40px claims it; after that the terminal sees no movement.
        assertTrue("terminal saw ${termTravel.x}px", termTravel.x > -100f && termTravel.x <= -40f)

        swipe(1, Offset(300f, 0f))
        assertEquals(listOf(HerdrSwipe.NEXT_TAB, HerdrSwipe.PREV_TAB), fired)
    }

    @Test
    fun oneFingerUpOrDown_isTheTerminalsScroll() {
        setContent()
        swipe(1, Offset(0f, -300f))
        assertEquals(emptyList<HerdrSwipe>(), fired)
        assertEquals(-300f, termTravel.y, 0.5f)
        assertEquals(1, termTouches)
    }

    @Test
    fun twoFingers_movePaneFocusAcrossAndWorkspacesUpOrDown() {
        setContent()
        swipe(2, Offset(-300f, 0f))
        swipe(2, Offset(300f, 0f))
        swipe(2, Offset(0f, -300f))
        swipe(2, Offset(0f, 300f))
        assertEquals(
            listOf(HerdrSwipe.PANE_RIGHT, HerdrSwipe.PANE_LEFT, HerdrSwipe.NEXT_WORKSPACE, HerdrSwipe.PREV_WORKSPACE),
            fired,
        )
    }

    @Test
    fun pinch_staysTheTerminalsZoom() {
        setContent()
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
        assertEquals(emptyList<HerdrSwipe>(), fired)
        assertEquals(2, termMaxPointers)
        assertEquals("the terminal saw the whole pinch", -150f, termTravel.x, 0.5f)
    }

    @Test
    fun tapsAndSlowShortMoves_neverFire() {
        setContent()
        rule.onNodeWithTag("terminal").performTouchInput { click(Offset(300f, 400f)) }
        swipe(1, Offset(-60f, 0f), steps = 30) // 30dp over 480ms: short and slow
        assertEquals(emptyList<HerdrSwipe>(), fired)
        assertEquals(2, termTouches)
    }

    @Test
    fun disabled_offHerdrOrWithASelectionUp_leavesEveryTouchToTheTerminal() {
        enabled = false
        setContent()
        swipe(1, Offset(-300f, 0f))
        swipe(2, Offset(0f, -300f))
        assertEquals(emptyList<HerdrSwipe>(), fired)
        assertEquals(-300f, termTravel.x, 0.5f)
    }

    @Test
    fun tabSwipes_sendTheHostsBindings_orSayWhyNot() = runBlocking {
        val sent = mutableListOf<String>()
        val config = "[keys]\nprevious_tab = \"prefix+shift+p\"\nnext_tab = \"alt+right\"\n"
        val herd = HerdSession(
            hostName = "stock",
            runner = { command -> if (command == HerdrPrefix.readCommand) CommandOutput(config, "", 0) else null },
            sendKeys = { sent += it },
            scope = CoroutineScope(Dispatchers.Unconfined),
            io = Dispatchers.Unconfined,
        )
        herd.readKeys()
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.herd).thenReturn(herd)

        assertNull(runHerdrSwipe(bridge, HerdrSwipe.PREV_TAB))
        // alt+right is an arrow chord Mercurio cannot send: nothing goes out, and the hint says why.
        val why = runHerdrSwipe(bridge, HerdrSwipe.NEXT_TAB)
        assertTrue(why.toString(), why!!.contains("next_tab to \"alt+right\""))
        assertEquals(listOf(CTRL_B + "P"), sent)
    }

    @Test
    fun tabSwipes_withoutAHerdrSide_sendHerdrsDefaults() {
        val bridge = mock(TerminalBridge::class.java)

        assertNull(runHerdrSwipe(bridge, HerdrSwipe.NEXT_TAB))
        assertNull(runHerdrSwipe(bridge, HerdrSwipe.PREV_TAB))

        verify(bridge).injectString(CTRL_B + "n")
        verify(bridge).injectString(CTRL_B + "p")
    }

    private companion object {
        val CTRL_B = 2.toChar().toString()
    }
}
