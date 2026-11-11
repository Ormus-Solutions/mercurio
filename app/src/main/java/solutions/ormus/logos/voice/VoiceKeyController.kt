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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class VoiceState { IDLE, LISTENING, THINKING }

/** A short line the key shows after a take, or when it could not start. */
sealed interface VoiceNote {
    data object NoServer : VoiceNote

    data object MicDenied : VoiceNote

    data object RecordFailed : VoiceNote

    data object Junk : VoiceNote

    /** The text went in as the raw words: the server's clean-up failed. */
    data object RewriteFailed : VoiceNote

    data class Failed(val kind: String, val message: String, val canRetry: Boolean) : VoiceNote
}

/** Records one take to a file. [start] returns false when the microphone cannot be opened. */
interface TakeRecorder {
    fun start(file: File, onLevel: (Float) -> Unit, onLimit: () -> Unit, onFail: () -> Unit): Boolean

    /** Ends the take and finishes the file; false when the file could not be finished and must not be sent. */
    suspend fun stop(): Boolean

    /** Ends the take at once, without waiting; the file is closed in the background. */
    fun abort()
}

/**
 * The voice key: tap to record, tap to stop, the server turns the take into text, and [insert]
 * puts it in the compose field. Nothing here sends to the terminal.
 */
class VoiceKeyController(
    private val scope: CoroutineScope,
    private val recorder: TakeRecorder,
    private val client: VoiceClient,
    private val takeFile: File,
    private val micGranted: () -> Boolean,
    private val askMic: () -> Unit,
    private val server: () -> String?,
    private val style: () -> VoiceStyle,
    private val insert: (String) -> Unit,
) {
    private val _state = MutableStateFlow(VoiceState.IDLE)
    private val _note = MutableStateFlow<VoiceNote?>(null)
    private val _levels = MutableStateFlow<List<Float>>(emptyList())

    /** What the key shows: idle, listening (the levels move) or thinking (the server works). */
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** The last thing worth telling him; cleared by the next tap. */
    val note: StateFlow<VoiceNote?> = _note.asStateFlow()

    /** The last few input levels, 0 to 1, oldest first, for the bars. */
    val levels: StateFlow<List<Float>> = _levels.asStateFlow()

    private var awaitingMic = false
    private var takeBase: String? = null

    fun tap() {
        when (_state.value) {
            VoiceState.IDLE -> begin()
            VoiceState.LISTENING -> finish()
            VoiceState.THINKING -> Unit
        }
    }

    /** The answer to [askMic]. */
    fun onMicResult(granted: Boolean) {
        if (!awaitingMic) return
        awaitingMic = false
        if (granted) startRecording() else _note.value = VoiceNote.MicDenied
    }

    /** Send the kept take again, after a failure that can be retried. */
    fun retry() {
        val failed = _note.value as? VoiceNote.Failed ?: return
        val base = takeBase
        if (_state.value != VoiceState.IDLE || !failed.canRetry || base == null || !takeFile.exists()) return
        _note.value = null
        _state.value = VoiceState.THINKING
        scope.launch { send(base) }
    }

    /** The bar is leaving the screen: a take being recorded is dropped. */
    fun cancel() {
        if (_state.value != VoiceState.LISTENING) return
        recorder.abort()
        _levels.value = emptyList()
        takeFile.delete()
        _state.value = VoiceState.IDLE
    }

    fun clearNote() {
        _note.value = null
    }

    private fun begin() {
        _note.value = null
        val base = server()
        if (base == null) {
            _note.value = VoiceNote.NoServer
            return
        }
        takeBase = base
        if (!micGranted()) {
            awaitingMic = true
            askMic()
            return
        }
        startRecording()
    }

    private fun startRecording() {
        // The kept take of a failed send is replaced only now, by the next one.
        takeFile.delete()
        _levels.value = emptyList()
        val opened = recorder.start(
            takeFile,
            onLevel = { level -> _levels.update { (it + level.coerceIn(0f, 1f)).takeLast(MAX_LEVELS) } },
            onLimit = { scope.launch { if (_state.value == VoiceState.LISTENING) finish() } },
            onFail = { scope.launch { if (_state.value == VoiceState.LISTENING) failTake() } },
        )
        if (opened) _state.value = VoiceState.LISTENING else _note.value = VoiceNote.RecordFailed
    }

    private fun finish() {
        val base = takeBase ?: return
        _state.value = VoiceState.THINKING
        scope.launch {
            val finished = recorder.stop()
            _levels.value = emptyList()
            if (finished) send(base) else failTake()
        }
    }

    /** The microphone or the disk gave out: nothing is sent, and the half take goes. */
    private fun failTake() {
        recorder.abort()
        _levels.value = emptyList()
        takeFile.delete()
        _note.value = VoiceNote.RecordFailed
        _state.value = VoiceState.IDLE
    }

    private suspend fun send(base: String) {
        when (val result = client.dictate(base, style(), takeFile)) {
            is Dictation.Text -> {
                insert(result.text)
                takeFile.delete()
                _note.value = if (result.rewriteFailed) VoiceNote.RewriteFailed else null
            }

            Dictation.Junk -> {
                takeFile.delete()
                _note.value = VoiceNote.Junk
            }

            // The take stays in the cache for a retry; the next take replaces it.
            is Dictation.Failed -> _note.value = VoiceNote.Failed(result.kind, result.message, result.retryable)
        }
        _state.value = VoiceState.IDLE
    }

    private companion object {
        const val MAX_LEVELS = 28
    }
}
