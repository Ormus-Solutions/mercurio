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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import solutions.ormus.logos.herd.HerdrSwipe

/**
 * What a two-finger swipe up or down does when its second finger lands late. The touch is judged once it has travelled 20 dp,
 * with the fingers down so far. A first finger that has moved 20 dp up or down before the second lands is a one-finger drag,
 * which is the terminal's scroll at any speed, and the second finger is never counted. Before G3 a fast one-finger flick was
 * a workspace swipe, and it rescued this case; the gate lane measured it (`~/work/.b2-tmp/gate-no-flick/REPORT.md`, M1) and
 * the orchestrator accepted the change: scroll always scrolls wins. This test pins the new behaviour. Robolectric, xhdpi: 1dp = 2px.
 */
@RunWith(AndroidJUnit4::class)
class HerdrLateSecondFingerTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val fired = mutableListOf<HerdrSwipe>()

    private fun setContent() {
        rule.setContent {
            Box(Modifier.size(400.dp).herdrSwipes(enabled = { true }) { swipe, _ -> fired += swipe }.testTag("terminal"))
        }
    }

    private fun twoFingersUp(secondLandsAfterFirstMovedPx: Float) {
        rule.onNodeWithTag("terminal").performTouchInput {
            down(0, Offset(300f, 600f))
            if (secondLandsAfterFirstMovedPx > 0f) {
                updatePointerTo(0, Offset(300f, 600f - secondLandsAfterFirstMovedPx))
                move(delayMillis = 8)
            }
            down(1, Offset(500f, 600f))
            val start0 = currentPosition(0)!!
            val start1 = currentPosition(1)!!
            for (step in 1..4) {
                updatePointerTo(0, start0 + Offset(0f, -75f * step))
                updatePointerTo(1, start1 + Offset(0f, -75f * step))
                move(delayMillis = 16)
            }
            up(0)
            up(1)
        }
        rule.waitForIdle()
    }

    @Test
    fun aFastTwoFingerSwipeUp_whoseSecondFingerLandsAfterTheFirstMoved25dp_isTheScroll_notAWorkspaceSwitch() {
        setContent()
        twoFingersUp(secondLandsAfterFirstMovedPx = 50f)
        assertEquals(emptyList<HerdrSwipe>(), fired)
    }

    @Test
    fun theSameSwipe_withBothFingersDownTogether_isAWorkspaceSwitch() {
        setContent()
        twoFingersUp(secondLandsAfterFirstMovedPx = 0f)
        assertEquals(listOf(HerdrSwipe.NEXT_WORKSPACE), fired)
    }
}
