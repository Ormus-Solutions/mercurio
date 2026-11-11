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

package org.connectbot.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArrowsKeyUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val esc = "\u001b"
    private val sent = mutableListOf<String>()

    private fun showKey() {
        rule.setContent {
            ConnectBotTheme {
                ArrowsKey({ it }, { sent += it }, "Arrows", Modifier.width(200.dp).testTag("arrows"))
            }
        }
    }

    private fun stepPx(): Float = with(rule.density) { ARROW_STEP.toPx() }

    @Test
    fun aSwipeToTheRightPressesOneRightArrowPerStep_andNoTapArrow() {
        showKey()
        val step = stepPx()
        rule.onNodeWithTag("arrows").performTouchInput {
            down(Offset(10f, centerY))
            repeat(7) { moveBy(Offset(step / 2, 0f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(List(3) { esc + "[C" }, sent)
    }

    @Test
    fun aTapStillPressesTheArrowUnderTheFinger() {
        showKey()
        rule.onNodeWithTag("arrows").performTouchInput {
            down(Offset(centerX, 2f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(esc + "[A"), sent)
    }

    @Test
    fun aSwipeOnTheArrowsKeyDoesNotTurnTheBarsPage() {
        lateinit var pager: PagerState
        rule.setContent {
            ConnectBotTheme {
                pager = rememberPagerState { 2 }
                HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth()) { page ->
                    Box(Modifier.fillMaxWidth()) {
                        if (page == 0) ArrowsKey({ it }, { sent += it }, "Arrows", Modifier.fillMaxWidth().testTag("arrows"))
                    }
                }
            }
        }
        val step = stepPx()
        rule.onNodeWithTag("arrows").performTouchInput {
            down(Offset(right - 10f, centerY))
            repeat(12) { moveBy(Offset(-step / 2, 0f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(0, pager.currentPage)
        assertEquals(List(6) { esc + "[D" }, sent)
    }

    @Test
    fun aTapThatDriftsLessThanAStep_stillPressesItsArrow() {
        showKey()
        val step = stepPx()
        rule.onNodeWithTag("arrows").performTouchInput {
            down(Offset(centerX, 2f))
            moveBy(Offset(step * 0.6f, 0f))
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(esc + "[A"), sent)
    }

    @Test
    fun aSwipeThatTurnsBackPressesTheOppositeArrow() {
        showKey()
        val step = stepPx()
        rule.onNodeWithTag("arrows").performTouchInput {
            down(Offset(10f, centerY))
            repeat(5) { moveBy(Offset(step / 2, 0f)) }
            repeat(2) { moveBy(Offset(-step / 2, 0f)) }
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf(esc + "[C", esc + "[C", esc + "[D"), sent)
    }
}
