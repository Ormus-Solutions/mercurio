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

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun le(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun header_isTheCanonical44BytesForMono16kPcm16() {
        val h = Wav.header(pcmBytes = 32_000)
        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 32_000, le(h).getInt(4))
        assertEquals("WAVEfmt ", String(h, 8, 8, Charsets.US_ASCII))
        assertEquals(16, le(h).getInt(16))
        assertEquals(1, le(h).getShort(20).toInt()) // PCM
        assertEquals(1, le(h).getShort(22).toInt()) // mono
        assertEquals(16_000, le(h).getInt(24))
        assertEquals(32_000, le(h).getInt(28)) // byte rate
        assertEquals(2, le(h).getShort(32).toInt()) // block align
        assertEquals(16, le(h).getShort(34).toInt())
        assertEquals("data", String(h, 36, 4, Charsets.US_ASCII))
        assertEquals(32_000, le(h).getInt(40))
    }

    @Test
    fun writer_patchesTheSizesOnClose_andKeepsTheSamplesAfterTheHeader() {
        val file = tmp.newFile("take.wav")
        val pcm = ByteArray(1_000) { (it % 251).toByte() }
        WavFileWriter(file).use {
            it.write(pcm, 600)
            it.write(pcm.copyOfRange(600, 1_000))
            assertEquals(1_000, it.pcmBytes)
        }
        val bytes = file.readBytes()
        assertEquals(44 + 1_000, bytes.size)
        assertEquals(36 + 1_000, le(bytes).getInt(4))
        assertEquals(1_000, le(bytes).getInt(40))
        assertArrayEquals(pcm, bytes.copyOfRange(44, bytes.size))
    }

    @Test
    fun writer_withNoSamples_isAHeaderOfZeroLength() {
        val file = tmp.newFile("empty.wav")
        WavFileWriter(file).close()
        val bytes = file.readBytes()
        assertEquals(44, bytes.size)
        assertEquals(0, le(bytes).getInt(40))
    }
}
