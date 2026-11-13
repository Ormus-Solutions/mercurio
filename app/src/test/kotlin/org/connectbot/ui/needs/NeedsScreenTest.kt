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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicInteger

/** The Unblock view reads the store only while it shows: from STARTED to the time it stops. */
@RunWith(AndroidJUnit4::class)
class NeedsScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val started = AtomicInteger(0)
    private val cancelled = AtomicInteger(0)

    @Test
    fun theViewWatchesTheStore_onlyWhileItShows() {
        val vm = mock(NeedsViewModel::class.java)
        `when`(vm.state).thenReturn(MutableStateFlow(NeedsUi()))
        runBlockingStub(vm)
        composeTestRule.setContent { ConnectBotTheme { NeedsScreen(onNavigateBack = {}, viewModel = vm) } }
        composeTestRule.waitForIdle()
        assertEquals("watching while it shows", 1 to 0, started.get() to cancelled.get())

        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeTestRule.waitForIdle()
        assertEquals("the watch ended when the view stopped", 1 to 1, started.get() to cancelled.get())

        composeTestRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeTestRule.waitForIdle()
        assertEquals("a view that shows again watches again", 2 to 1, started.get() to cancelled.get())
    }

    private fun runBlockingStub(vm: NeedsViewModel) = kotlinx.coroutines.runBlocking {
        whenever(vm.watch()).doSuspendableAnswer {
            started.incrementAndGet()
            try {
                awaitCancellation()
            } finally {
                cancelled.incrementAndGet()
            }
        }
    }
}
