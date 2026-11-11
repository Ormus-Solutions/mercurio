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

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Records 16 kHz mono PCM16 with [AudioRecord] straight into a WAV file. WAV is the server's first
 * format and needs no encoder and no new dependency; a take costs 32 KB a second, a minute of speech
 * about 1.9 MB over the tailnet. The caller holds the RECORD_AUDIO permission before [start].
 */
class AudioRecordTakeRecorder(private val maxSeconds: Int = MAX_SECONDS) : TakeRecorder {
    @Volatile private var running = false

    /** True once the pump has closed the file without a read or write error. */
    @Volatile private var finished = false
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    override fun start(file: File, onLevel: (Float) -> Unit, onLimit: () -> Unit, onFail: () -> Unit): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val bufferBytes = maxOf(minBuffer, Wav.SAMPLE_RATE / 5 * 2)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                Wav.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: SecurityException) {
            Timber.w(e, "voice key: microphone refused")
            return false
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "voice key: microphone not usable")
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        val writer = try {
            WavFileWriter(file)
        } catch (e: java.io.IOException) {
            record.release()
            Timber.w(e, "voice key: take file not writable")
            return false
        }
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            record.release()
            writer.close()
            return false
        }
        // Another app can hold the microphone: startRecording does not throw then, it just never records.
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            writer.close()
            return false
        }
        finished = false
        running = true
        worker = Thread({ pump(record, writer, bufferBytes / 2, onLevel, onLimit, onFail) }, "voice-key-record").also { it.start() }
        return true
    }

    private fun pump(record: AudioRecord, writer: WavFileWriter, frameSamples: Int, onLevel: (Float) -> Unit, onLimit: () -> Unit, onFail: () -> Unit) {
        val samples = ShortArray(frameSamples)
        val bytes = ByteBuffer.allocate(frameSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        val maxBytes = maxSeconds * Wav.SAMPLE_RATE * 2
        var hitLimit = false
        var failed = false
        try {
            while (running) {
                val n = record.read(samples, 0, samples.size)
                if (n <= 0) {
                    failed = true
                    break
                }
                bytes.clear()
                for (i in 0 until n) bytes.putShort(samples[i])
                writer.write(bytes.array(), n * 2)
                onLevel(VoiceLevel.of(samples, n))
                if (writer.pcmBytes >= maxBytes) {
                    hitLimit = true
                    break
                }
            }
        } catch (e: java.io.IOException) {
            Timber.w(e, "voice key: take file not writable")
            failed = true
        } finally {
            try {
                record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
            try {
                writer.close()
            } catch (e: java.io.IOException) {
                failed = true
            }
        }
        finished = !failed
        if (failed && running) {
            onFail()
        } else if (hitLimit && running) {
            onLimit()
        }
    }

    override suspend fun stop(): Boolean {
        running = false
        val thread = worker ?: return false
        worker = null
        withContext(Dispatchers.IO) { thread.join(JOIN_MS) }
        // A pump still alive after the wait has not patched the header: that take must not be sent.
        return !thread.isAlive && finished
    }

    override fun abort() {
        running = false
        worker = null
    }

    private companion object {
        // Five minutes: about 9.6 MB of WAV.
        const val MAX_SECONDS = 300
        const val JOIN_MS = 3_000L
    }
}
