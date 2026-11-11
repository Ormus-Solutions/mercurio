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

package solutions.ormus.logos.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceSettingsTest {
    private fun settings(): VoiceSettings {
        val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("voice-test-${System.nanoTime()}", Context.MODE_PRIVATE)
        return VoiceSettings(prefs)
    }

    @Test
    fun shipsDark_noServer_theServersOwnStyle() {
        val s = settings()
        assertFalse(s.enabled)
        assertEquals("", s.server)
        assertEquals(VoiceStyle.SERVER, s.style)
    }

    @Test
    fun savesWhatItIsGiven() {
        val s = settings()
        s.enabled = true
        s.server = " box.example "
        s.style = VoiceStyle.CASUAL
        assertTrue(s.enabled)
        assertEquals("box.example", s.server)
        assertEquals(VoiceStyle.CASUAL, s.style)
    }

    @Test
    fun effectiveServer_isTheSettingElseTheSessionsHost() {
        val s = settings()
        assertEquals("http://session.example:8097", s.baseUrl("session.example"))
        s.server = "box.example"
        assertEquals("http://box.example:8097", s.baseUrl("session.example"))
        s.server = ""
        assertNull(s.baseUrl(null))
    }
}

class VoiceLevelTest {
    @Test
    fun silenceIsZero_fullScaleIsOne_andLouderIsHigher() {
        assertEquals(0f, VoiceLevel.of(ShortArray(100), 100), 0f)
        assertEquals(1f, VoiceLevel.of(ShortArray(100) { Short.MAX_VALUE }, 100), 0f)
        val quiet = VoiceLevel.of(ShortArray(100) { 300 }, 100)
        val loud = VoiceLevel.of(ShortArray(100) { 3000 }, 100)
        assertTrue(quiet > 0f && loud > quiet && loud < 1f)
    }

    @Test
    fun onlyTheSamplesReadCount() {
        assertEquals(0f, VoiceLevel.of(ShortArray(100) { Short.MAX_VALUE }, 0), 0f)
    }
}
