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

import android.content.ClipData
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.connectbot.R
import org.connectbot.service.FixPrompt
import org.connectbot.ui.theme.OrmusCornerShape

/** Test tag of every "Copy details" button. */
const val COPY_DETAILS_TAG = "copy_details"

private const val CLIP_LABEL = "Mercurio fix prompt"
private const val COPIED_MS = 2000L

/**
 * Put a connection report on the clipboard as a fix prompt: the report wrapped in what an
 * agent needs to act on it ([FixPrompt.connection]), ready to paste.
 */
suspend fun Clipboard.copyDiagnostics(report: String) {
    copyFixPrompt(FixPrompt.connection(report))
}

/** Put [text] (a URL, a reply) on this phone's clipboard as plain text. */
suspend fun Clipboard.copyText(text: String) {
    setClipEntry(ClipData.newPlainText("Mercurio", text).toClipEntry())
}

/** Put a ready-made fix prompt on the clipboard as plain text. */
suspend fun Clipboard.copyFixPrompt(prompt: String) {
    setClipEntry(ClipData.newPlainText(CLIP_LABEL, prompt).toClipEntry())
}

/**
 * "Copy fix prompt": builds the report with [report] (which does its own work off
 * the main thread), copies it, and reads "Copied" for a moment as confirmation.
 */
@Composable
fun CopyDetailsButton(
    report: suspend () -> String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val currentReport by rememberUpdatedState(report)
    CopyPromptButton(prompt = { FixPrompt.connection(currentReport()) }, modifier = modifier, color = color)
}

/** "Copy fix prompt" for a prompt [prompt] builds; reads "Copied" for a moment after. */
@Composable
fun CopyPromptButton(
    prompt: suspend () -> String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val currentPrompt by rememberUpdatedState(prompt)
    var copied by remember { mutableStateOf(false) }

    TextButton(
        onClick = {
            scope.launch {
                clipboard.copyFixPrompt(currentPrompt())
                copied = true
                delay(COPIED_MS)
                copied = false
            }
        },
        modifier = modifier.testTag(COPY_DETAILS_TAG),
        shape = OrmusCornerShape,
    ) {
        Text(
            text = stringResource(if (copied) R.string.copy_details_copied else R.string.copy_details),
            color = color,
        )
    }
}
