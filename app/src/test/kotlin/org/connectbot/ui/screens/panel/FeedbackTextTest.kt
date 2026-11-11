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

package org.connectbot.ui.screens.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The text the Send feedback row prefills: versions only, never logs or host names. */
class FeedbackTextTest {

    @Test
    fun body_namesTheAppVersion_andTheCoreVersionWhenAHostAnswered() {
        val body = FeedbackText.body(appVersion = "1.4.2", coreVersion = "0.1.20")
        assertTrue(body.contains("1.4.2"))
        assertTrue(body.contains("herdr-core 0.1.20"))
    }

    @Test
    fun body_leavesTheCoreLineOut_whenNoHostAnswered() {
        val body = FeedbackText.body(appVersion = "1.4.2", coreVersion = null)
        assertTrue(body.contains("1.4.2"))
        assertFalse(body.contains("herdr-core"))
    }

    @Test
    fun coreVersionOf_keepsTheVersionToken_andNothingElse() {
        assertEquals("0.1.20", FeedbackText.coreVersionOf("herdr-core 0.1.20\n"))
        assertEquals("0.1.20-rc1", FeedbackText.coreVersionOf("herdr-core v0.1.20-rc1 on box.example.net\n"))
    }

    @Test
    fun coreVersionOf_isNullWhenTheAnswerHasNoVersion() {
        assertNull(FeedbackText.coreVersionOf(""))
        assertNull(FeedbackText.coreVersionOf("command not found: box.local"))
    }

    @Test
    fun coreVersionOf_ignoresALoginBannerWithAnAddress() {
        assertNull(FeedbackText.coreVersionOf("Last login: from 192.0.2.7"))
        assertNull(FeedbackText.coreVersionOf("box-10.0.0.5: welcome"))
    }

    @Test
    fun coreVersionOf_dropsATrailingDot() {
        assertEquals("0.1.20", FeedbackText.coreVersionOf("herdr-core 0.1.20."))
    }
}
