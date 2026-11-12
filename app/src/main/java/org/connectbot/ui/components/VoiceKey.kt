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

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import org.connectbot.R
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.ui.theme.Ormus
import solutions.ormus.logos.voice.AudioRecordTakeRecorder
import solutions.ormus.logos.voice.HttpVoiceTransport
import solutions.ormus.logos.voice.VoiceClient
import solutions.ormus.logos.voice.VoiceKeyController
import solutions.ormus.logos.voice.VoiceNote
import solutions.ormus.logos.voice.VoiceSettings
import solutions.ormus.logos.voice.VoiceState
import java.io.File

/**
 * The voice key's controller, tied to this composition. The microphone is asked for on the first
 * tap, never before. [io] is the dispatcher the voice server calls run on (the app's injected one). [sessionHost] names the server when the setting is blank; [onText] gets the
 * words (the compose bar puts them in its field). A take still being recorded when the bar leaves
 * the screen, or the app goes to the background, is dropped. [takeId] keeps one console's kept take apart from another's.
 */
@Composable
fun rememberVoiceKey(io: CoroutineDispatcher, settings: VoiceSettings, sessionHost: String?, takeId: Long, onText: (String) -> Unit): VoiceKeyController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host by rememberUpdatedState(sessionHost)
    val insert by rememberUpdatedState(onText)
    val holder = remember { arrayOfNulls<VoiceKeyController>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { holder[0]?.onMicResult(it) }
    val recorder = remember { AudioRecordTakeRecorder() }
    val controller = remember {
        VoiceKeyController(
            scope = scope,
            recorder = recorder,
            client = VoiceClient(HttpVoiceTransport(io)),
            takeFile = File(context.cacheDir, "voice-take-$takeId.wav"),
            micGranted = { ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
            askMic = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
            server = { settings.baseUrl(host) },
            style = { settings.style },
            insert = { insert(it) },
        ).also { holder[0] = it }
    }
    // Android silences a background microphone, so a take must not keep "listening" once the app is away.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.cancel() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller.cancel()
        }
    }
    return controller
}

/** Test tag on the mic key. */
internal const val VOICE_KEY_TEST_TAG = "composeVoiceKey"

/** Test tag on the line under the compose box that says what happened to a take. */
internal const val VOICE_NOTE_TEST_TAG = "composeVoiceNote"

/**
 * The mic key: a mic when idle; thin gold bars that follow the voice while listening; the same bars
 * sweeping dim while the server works. A tap toggles: start, then stop.
 */
@Composable
fun VoiceKeyButton(controller: VoiceKeyController, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsState()
    val levels by controller.levels.collectAsState()
    val gold = MaterialTheme.colorScheme.primary
    val listening = state == VoiceState.LISTENING
    val description = stringResource(
        when (state) {
            VoiceState.IDLE -> R.string.voice_key_idle
            VoiceState.LISTENING -> R.string.voice_key_listening
            VoiceState.THINKING -> R.string.voice_key_thinking
        },
    )
    Surface(
        modifier = modifier
            .width(VOICE_KEY_WIDTH)
            .height(KEY_HEIGHT)
            .testTag(VOICE_KEY_TEST_TAG)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, enabled = state != VoiceState.THINKING, onClick = controller::tap),
        shape = KEY_SHAPE,
        color = if (state == VoiceState.IDLE) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, if (listening) gold else Ormus.extras.hairline),
    ) {
        Box(contentAlignment = Alignment.Center) {
            when (state) {
                VoiceState.IDLE -> Icon(Icons.Filled.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))

                VoiceState.LISTENING -> VoiceBars(levels = levels, color = gold, sweep = null)

                VoiceState.THINKING -> {
                    val phase by rememberInfiniteTransition(label = "voice-think").animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(THINK_SWEEP_MS, easing = LinearEasing), RepeatMode.Restart),
                        label = "voice-think-phase",
                    )
                    VoiceBars(levels = THINKING_LEVELS, color = gold, sweep = phase)
                }
            }
        }
    }
}

private val VOICE_KEY_WIDTH = 48.dp
private const val THINK_SWEEP_MS = 1_100
private const val BAR_COUNT = 9
private val THINKING_LEVELS = List(BAR_COUNT) { 0.35f }

/**
 * [BAR_COUNT] thin bars, the newest level on the right. With [sweep] set, a bright patch travels
 * across dim bars instead of the levels moving them.
 */
@Composable
private fun VoiceBars(levels: List<Float>, color: androidx.compose.ui.graphics.Color, sweep: Float?) {
    Canvas(Modifier.size(width = 30.dp, height = 28.dp)) {
        val slot = size.width / BAR_COUNT
        val barWidth = slot * BAR_FRACTION
        val shown = List(BAR_COUNT) { i -> levels.getOrNull(levels.size - BAR_COUNT + i) ?: 0f }
        shown.forEachIndexed { i, level ->
            val h = maxOf(size.height * level, MIN_BAR_PX)
            val alpha = if (sweep == null) 1f else 0.3f + 0.7f * (1f - kotlin.math.abs(i / (BAR_COUNT - 1f) - sweep).coerceAtMost(0.4f) / 0.4f)
            drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(i * slot + (slot - barWidth) / 2, (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

private const val BAR_FRACTION = 0.42f
private const val MIN_BAR_PX = 3f

/** One line under the compose box about the last take; the failed ones offer a resend. Nothing when there is no note. */
@Composable
fun VoiceNoteLine(controller: VoiceKeyController, modifier: Modifier = Modifier) {
    val note by controller.note.collectAsState()
    val shown = note ?: return
    val failed = shown is VoiceNote.Failed || shown == VoiceNote.RecordFailed || shown == VoiceNote.MicDenied || shown == VoiceNote.NoServer
    Row(
        modifier = modifier.fillMaxWidth().testTag(VOICE_NOTE_TEST_TAG).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            noteText(shown),
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (shown is VoiceNote.Failed && shown.canRetry) {
            Text(
                stringResource(R.string.voice_retry),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(role = Role.Button, onClick = controller::retry).padding(vertical = 6.dp, horizontal = 4.dp),
            )
        }
        Text(
            stringResource(R.string.voice_note_dismiss),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(role = Role.Button, onClick = controller::clearNote).padding(vertical = 6.dp, horizontal = 4.dp),
        )
    }
}

@Composable
private fun noteText(note: VoiceNote): String = when (note) {
    VoiceNote.NoServer -> stringResource(R.string.voice_note_no_server)

    VoiceNote.MicDenied -> stringResource(R.string.voice_note_mic_denied)

    VoiceNote.RecordFailed -> stringResource(R.string.voice_note_record_failed)

    VoiceNote.Junk -> stringResource(R.string.voice_note_junk)

    VoiceNote.RewriteFailed -> stringResource(R.string.voice_note_rewrite_failed)

    is VoiceNote.Failed -> stringResource(
        R.string.voice_note_failed,
        stringResource(
            when (note.kind) {
                "unreachable" -> R.string.voice_why_unreachable
                "busy" -> R.string.voice_why_busy
                "no_engine" -> R.string.voice_why_no_engine
                "bad_audio" -> R.string.voice_why_bad_audio
                "too_large" -> R.string.voice_why_too_large
                "bad_request" -> R.string.voice_why_bad_request
                "bad_reply" -> R.string.voice_why_bad_reply
                else -> R.string.voice_why_server
            },
        ),
    )
}
