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

package org.connectbot.ui.needs

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The view swipe: right to left, left to right, and the left-edge zone left alone. */
@RunWith(AndroidJUnit4::class)
class ViewSwipeTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var lefts = 0
    private var rights = 0

    private fun show() {
        composeTestRule.setContent {
            Box(Modifier.fillMaxSize().testTag("area").viewSwipe(onLeft = { lefts++ }, onRight = { rights++ }))
        }
    }

    @Test
    fun rightToLeft_andLeftToRight_eachChangeTheView() {
        show()
        val area = composeTestRule.onNodeWithTag("area")

        area.performTouchInput { swipeLeft() }
        area.performTouchInput { swipeRight(startX = width * 0.3f, endX = right) }

        assertEquals(1 to 1, lefts to rights)
    }

    @Test
    fun aSwipeFromTheLeftEdgeZone_isLeftToTheDrawer() {
        show()

        composeTestRule.onNodeWithTag("area").performTouchInput {
            swipe(start = Offset(VIEW_SWIPE_EDGE.toPx() / 2, centerY), end = Offset(right, centerY))
        }

        assertEquals(0 to 0, lefts to rights)
    }

    @Test
    fun aVerticalDrag_changesNothing() {
        show()

        composeTestRule.onNodeWithTag("area").performTouchInput { swipeUp() }

        assertEquals(0 to 0, lefts to rights)
    }

    @Test
    fun aSwipeShorterThan72dp_changesNothing_andOneLongerDoes() {
        show()
        val area = composeTestRule.onNodeWithTag("area")

        area.performTouchInput { swipe(start = Offset(centerX, centerY), end = Offset(centerX - 60.dp.toPx(), centerY)) }
        assertEquals(0 to 0, lefts to rights)

        area.performTouchInput { swipe(start = Offset(centerX, centerY), end = Offset(centerX - 90.dp.toPx(), centerY)) }
        assertEquals(1 to 0, lefts to rights)
    }

    @Test
    fun aDragThatIsCancelledAfter72dp_changesNothing() {
        show()

        composeTestRule.onNodeWithTag("area").performTouchInput {
            down(Offset(centerX, centerY))
            moveTo(Offset(centerX - 100.dp.toPx(), centerY))
            cancel()
        }

        assertEquals(0 to 0, lefts to rights)
    }

    @Test
    fun aSteepDiagonalDrag_changesNothing_andAMostlyHorizontalOneDoes() {
        show()
        val area = composeTestRule.onNodeWithTag("area")

        // 100 dp across and 160 dp down: a scroll that slipped sideways, not a swipe.
        area.performTouchInput { swipe(start = Offset(centerX, centerY - 80.dp.toPx()), end = Offset(centerX - 100.dp.toPx(), centerY + 80.dp.toPx())) }
        assertEquals(0 to 0, lefts to rights)

        // 120 dp across and 30 dp down is a swipe.
        area.performTouchInput { swipe(start = Offset(centerX, centerY - 15.dp.toPx()), end = Offset(centerX - 120.dp.toPx(), centerY + 15.dp.toPx())) }
        assertEquals(1 to 0, lefts to rights)
    }
}
