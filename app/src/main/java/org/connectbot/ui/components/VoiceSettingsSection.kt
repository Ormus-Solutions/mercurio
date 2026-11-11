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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.connectbot.R
import solutions.ormus.logos.voice.VoiceStyle

internal const val VOICE_SWITCH_TAG = "panel_voice_switch"
internal const val VOICE_SERVER_TAG = "panel_voice_server"
internal const val VOICE_STYLE_TAG = "panel_voice_style"
internal const val VOICE_SERVER_FIELD_TAG = "panel_voice_server_field"

/** What the settings panel's voice key card shows and changes; the panel saves through [VoiceSettings][solutions.ormus.logos.voice.VoiceSettings]. */
class VoiceRows(
    val enabled: Boolean,
    val server: String,
    val style: VoiceStyle,
    val onEnabled: (Boolean) -> Unit,
    val onServer: (String) -> Unit,
    val onStyle: (VoiceStyle) -> Unit,
)

/** Edit the voice server: host, host:port or an https URL; empty uses the session's host. */
@Composable
fun VoiceServerDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_settings_server)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                supportingText = { Text(stringResource(R.string.voice_settings_server_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag(VOICE_SERVER_FIELD_TAG),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Pick the rewrite style the server is asked for. */
@Composable
fun VoiceStyleDialog(current: VoiceStyle, onPick: (VoiceStyle) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_settings_style)) },
        text = {
            Column(Modifier.selectableGroup()) {
                VoiceStyle.entries.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = option == current, role = Role.RadioButton, onClick = { onPick(option) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == current, onClick = null)
                        Text(stringResource(voiceStyleLabel(option)), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

internal fun voiceStyleLabel(style: VoiceStyle): Int = when (style) {
    VoiceStyle.SERVER -> R.string.voice_style_server
    VoiceStyle.PROMPT -> R.string.voice_style_prompt
    VoiceStyle.FORMAL -> R.string.voice_style_formal
    VoiceStyle.CASUAL -> R.string.voice_style_casual
    VoiceStyle.VERBATIM -> R.string.voice_style_verbatim
}
