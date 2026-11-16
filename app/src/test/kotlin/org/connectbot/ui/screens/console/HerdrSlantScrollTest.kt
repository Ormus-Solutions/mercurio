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
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import solutions.ormus.logos.herd.HerdrSwipe
import kotlin.math.cos
import kotlin.math.sin

/**
 * A one-finger drag that leans away from the vertical, in a Herdr console whose program asked for the mouse. The console lane
 * measured on the emulator (`~/work/.b2-tmp/console-scroll/MEASURE-v1.27.87.md`): leaning 10 and 20 degrees sends its wheel
 * notches, leaning 30 and 45 sends none (the wheel path took only drags at least twice as far up or down as across, within
 * about 26.6 degrees; termlib sends no wheel report). A thumb often drags at that slant. The rule now: a one-finger drag that is
 * not a tab swipe (at least twice as far across as up or down) and is at least as far up or down as across (up to 45 degrees)
 * is the scroll. Between 45 degrees and the tab swipe's 63.4 degrees it is neither, as before. Robolectric, xhdpi (1 dp = 2 px):
 * the drag is 318 px (159 dp) long in 600 ms, over rows 24 px high.
 */
@RunWith(AndroidJUnit4::class)
class HerdrSlantScrollTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val bridge = mock(TerminalBridge::class.java)
    private val fired = mutableListOf<HerdrSwipe>()
    private var notchesUp = 0
    private var notchesDown = 0

    private fun setContent() {
        `when`(bridge.mouseReporting).thenReturn(true)
        `when`(bridge.mouseWheel(anyInt(), anyInt(), anyBoolean())).thenAnswer {
            if (it.getArgument<Boolean>(2)) notchesUp++ else notchesDown++
            true
        }
        val wheel = TerminalWheel(bridge = { bridge }, cell = { Size(20f, 24f) }, size = { TerminalDimensions(rows = 40, columns = 40) })
        rule.setContent {
            Box(Modifier.size(400.dp).herdrSwipes(enabled = { true }, wheel = wheel) { swipe, _ -> fired += swipe }.testTag("terminal"))
        }
    }

    private fun drag(from: Offset, by: Offset, durationMs: Int = 600, stepMs: Int = 16) {
        rule.onNodeWithTag("terminal").performTouchInput {
            down(from)
            val steps = durationMs / stepMs
            for (step in 1..steps) moveTo(from + by * (step / steps.toFloat()), delayMillis = stepMs.toLong())
            up()
        }
        rule.waitForIdle()
    }

    /** What one drag of [length] px leaning [degrees] from the vertical, going up or down, leaning right or left, did. */
    private class Result(val right: Int, val wrong: Int, val fired: List<HerdrSwipe>)

    private fun slanted(degrees: Double, goingUp: Boolean, leanRight: Boolean, length: Float = 318f): Result {
        notchesUp = 0
        notchesDown = 0
        fired.clear()
        val rad = Math.toRadians(degrees)
        val dy = (length * cos(rad)).toFloat() * (if (goingUp) -1f else 1f)
        val dx = (length * sin(rad)).toFloat() * (if (leanRight) 1f else -1f)
        drag(Offset(400f, if (goingUp) 700f else 200f), Offset(dx, dy))
        // A finger moving down scrolls up (wheel up); a finger moving up sends wheel down.
        return if (goingUp) Result(notchesDown, notchesUp, fired.toList()) else Result(notchesUp, notchesDown, fired.toList())
    }

    private fun rowsOf(degrees: Double, length: Float = 318f) = (length * cos(Math.toRadians(degrees)) / 24f).toInt()

    private fun cases(degrees: Double, check: (String, Result) -> Unit) {
        for (goingUp in listOf(true, false)) {
            for (leanRight in listOf(true, false)) {
                val label = "${degrees.toInt()} deg ${if (goingUp) "up" else "down"} ${if (leanRight) "right" else "left"}"
                val r = slanted(degrees, goingUp, leanRight)
                check(label, r)
            }
        }
    }

    @Test
    fun aDragUpToFortyFiveDegreesFromTheVertical_sendsItsWheelNotches_inTheRightDirection_andNoSwipe() {
        setContent()
        val bad = mutableListOf<String>()
        for (degrees in listOf(10.0, 20.0, 25.0, 30.0, 35.0, 40.0, 44.0)) {
            cases(degrees) { label, r ->
                // The notches are counted from the vertical travel; allow one row for where the judging distance falls.
                if (r.right < rowsOf(degrees) - 1 || r.right > rowsOf(degrees) + 1 || r.wrong != 0 || r.fired.isNotEmpty()) bad += "$label: right=${r.right} (rows ${rowsOf(degrees)}) wrong=${r.wrong} fired=${r.fired}"
            }
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun aDragAtExactly45Degrees_isTheScroll() {
        setContent()
        // 225 px across and 225 px up: as far up as across.
        notchesUp = 0
        notchesDown = 0
        drag(Offset(300f, 600f), Offset(225f, -225f))
        assertEquals("notches down (the finger went up): $notchesDown", 9, notchesDown)
        assertEquals(0, notchesUp)
        assertEquals(emptyList<HerdrSwipe>(), fired)
    }

    @Test
    fun aDragBetween45And63Degrees_isNeitherTheScrollNorATabSwipe() {
        setContent()
        val bad = mutableListOf<String>()
        for (degrees in listOf(46.0, 50.0, 55.0, 60.0)) {
            cases(degrees) { label, r -> if (r.right != 0 || r.wrong != 0 || r.fired.isNotEmpty()) bad += "$label: right=${r.right} wrong=${r.wrong} fired=${r.fired}" }
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun aDragLeaningTwiceAsFarAcrossAsUpOrDown_isStillATabSwipe_andSendsNoNotch() {
        setContent()
        val bad = mutableListOf<String>()
        for (degrees in listOf(64.0, 70.0, 80.0, 90.0)) {
            for (leanRight in listOf(true, false)) {
                for (goingUp in listOf(true, false)) {
                    val r = slanted(degrees, goingUp, leanRight)
                    val want = if (leanRight) HerdrSwipe.PREV_TAB else HerdrSwipe.NEXT_TAB
                    if (r.right != 0 || r.wrong != 0 || r.fired != listOf(want)) bad += "${degrees.toInt()} deg up=$goingUp right=$leanRight: right=${r.right} wrong=${r.wrong} fired=${r.fired}"
                }
            }
        }
        assertEquals(emptyList<String>(), bad)
    }

    @Test
    fun aVerticalDrag_stillSendsItsWheelNotches() {
        setContent()
        val up = slanted(0.0, goingUp = true, leanRight = true)
        val down = slanted(0.0, goingUp = false, leanRight = true)
        assertEquals(13, up.right)
        assertEquals(13, down.right)
        assertEquals(emptyList<HerdrSwipe>(), up.fired + down.fired)
    }
}
