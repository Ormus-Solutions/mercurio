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

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The 44-byte RIFF header of a PCM WAV file. */
object Wav {
    const val HEADER_SIZE: Int = 44
    const val SAMPLE_RATE: Int = 16_000

    fun header(pcmBytes: Int, sampleRate: Int = SAMPLE_RATE, channels: Int = 1, bits: Int = 16): ByteArray {
        val blockAlign = channels * bits / 8
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcmBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(sampleRate * blockAlign)
            putShort(blockAlign.toShort())
            putShort(bits.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmBytes)
        }.array()
    }
}

/** Writes PCM16 samples to [file] behind a header that is patched with the real sizes on close. */
class WavFileWriter(private val file: File, private val sampleRate: Int = Wav.SAMPLE_RATE) : Closeable {
    var pcmBytes: Int = 0
        private set

    private val out = RandomAccessFile(file, "rw").also {
        try {
            it.setLength(0)
            it.write(Wav.header(0, sampleRate))
        } catch (e: java.io.IOException) {
            it.close()
            throw e
        }
    }

    fun write(pcm: ByteArray, length: Int = pcm.size) {
        out.write(pcm, 0, length)
        pcmBytes += length
    }

    override fun close() {
        try {
            out.seek(0)
            out.write(Wav.header(pcmBytes, sampleRate))
        } finally {
            out.close()
        }
    }
}
