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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRequestTest {
    @Test
    fun dictate_withNoStyle_hasNoStyleQuery() {
        val r = VoiceRequest.dictate("http://box.example:8097", VoiceStyle.SERVER)
        assertEquals("http://box.example:8097/v1/dictate", r.url)
        assertEquals("audio/wav", r.contentType)
    }

    @Test
    fun dictate_withAStyle_putsItInTheQuery() {
        assertEquals("http://box.example:8097/v1/dictate?style=prompt", VoiceRequest.dictate("http://box.example:8097", VoiceStyle.PROMPT).url)
        assertEquals("http://box.example:8097/v1/dictate?style=verbatim", VoiceRequest.dictate("http://box.example:8097", VoiceStyle.VERBATIM).url)
    }

    @Test
    fun dictate_carriesTheContentTypeOfTheTake() {
        assertEquals("audio/ogg", VoiceRequest.dictate("http://box.example:8097", VoiceStyle.SERVER, "audio/ogg").contentType)
    }

    @Test
    fun health_isAGetWithNoBodyType() {
        val r = VoiceRequest.health("http://box.example:8097")
        assertEquals("http://box.example:8097/v1/health", r.url)
        assertNull(r.contentType)
    }

    @Test
    fun styleFromKey_knowsTheFourAndFallsBackToTheServers() {
        assertEquals(VoiceStyle.PROMPT, VoiceStyle.fromKey("prompt"))
        assertEquals(VoiceStyle.FORMAL, VoiceStyle.fromKey("formal"))
        assertEquals(VoiceStyle.CASUAL, VoiceStyle.fromKey("casual"))
        assertEquals(VoiceStyle.VERBATIM, VoiceStyle.fromKey("verbatim"))
        assertEquals(VoiceStyle.SERVER, VoiceStyle.fromKey(null))
        assertEquals(VoiceStyle.SERVER, VoiceStyle.fromKey("shouting"))
    }

    @Test
    fun baseUrl_takesAHostAPortOrAUrl() {
        assertEquals("http://box.example:8097", VoiceServer.baseUrl("box.example", null))
        assertEquals("http://box.example:9000", VoiceServer.baseUrl("box.example:9000", null))
        assertEquals("http://box.example:8097", VoiceServer.baseUrl("http://box.example", null))
        assertEquals("https://box.example", VoiceServer.baseUrl("https://box.example/", null))
        assertEquals("http://box.example:8097", VoiceServer.baseUrl("  box.example  ", null))
    }

    @Test
    fun baseUrl_blankSettingUsesTheSessionHost_andNoneIsNull() {
        assertEquals("http://box.example:8097", VoiceServer.baseUrl("", "box.example"))
        assertEquals("http://box.example:8097", VoiceServer.baseUrl(null, "box.example"))
        assertEquals("http://other.example:8097", VoiceServer.baseUrl("other.example", "box.example"))
        assertNull(VoiceServer.baseUrl("", ""))
        assertNull(VoiceServer.baseUrl(null, null))
        assertNull(VoiceServer.baseUrl("two words", null))
    }

    @Test
    fun cleartext_isForTailnetLanAndLoopbackOnly() {
        assertTrue(VoiceServer.cleartextAllowed("100.64.0.1"))
        assertTrue(VoiceServer.cleartextAllowed("100.127.255.254"))
        assertFalse(VoiceServer.cleartextAllowed("100.128.0.1"))
        assertFalse(VoiceServer.cleartextAllowed("100.63.255.255"))
        assertTrue(VoiceServer.cleartextAllowed("127.0.0.1"))
        assertTrue(VoiceServer.cleartextAllowed("192.168.4.5"))
        assertTrue(VoiceServer.cleartextAllowed("10.1.2.3"))
        assertTrue(VoiceServer.cleartextAllowed("172.16.0.9"))
        assertFalse(VoiceServer.cleartextAllowed("172.32.0.9"))
        assertTrue(VoiceServer.cleartextAllowed("box"))
        assertTrue(VoiceServer.cleartextAllowed("box.tail1234.ts.net"))
        assertFalse(VoiceServer.cleartextAllowed("box.example"))
        assertFalse(VoiceServer.cleartextAllowed("8.8.8.8"))
    }

    @Test
    fun cleartext_refusesWhatOnlyLooksPrivate() {
        assertFalse(VoiceServer.cleartextAllowed("[2001:db8::1]"))
        assertFalse(VoiceServer.cleartextAllowed("2130706433"))
        assertFalse(VoiceServer.cleartextAllowed("010.0.0.1"))
        assertFalse(VoiceServer.cleartextAllowed("+10.0.0.1"))
        assertFalse(VoiceServer.cleartextAllowed("1.2.3"))
        assertFalse(VoiceServer.cleartextAllowed(""))
    }
}
