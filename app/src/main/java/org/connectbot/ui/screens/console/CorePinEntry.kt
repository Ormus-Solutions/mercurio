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

package org.connectbot.ui.screens.console

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.connectbot.R
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions

/** The tag of the Pin entry of the console menu. */
const val CONSOLE_PIN_TAG = "console_pin"

/**
 * The Pin entry of the menu of a console that watches a herdr-core terminal. Its label says what a tap
 * does: "Pin to Inbox", or "Unpin" when [read] says the terminal is pinned. [read] runs each time the menu
 * opens (the entry is composed while the menu shows); until it answers, or when it gives null, the entry is
 * off, and says why when it could not read. A tap closes the menu, then [write] puts the pin on or takes it
 * off with the Inbox's own write, in [scope] (the menu's composition ends with the tap, the write must not),
 * and [onNote] says how it went: a write that failed says so; it may still have run on the host (a timeout after the host took the command), and the next time the menu opens it shows the state the host has.
 */
@Composable
fun CorePinEntry(
    read: suspend () -> Boolean?,
    write: suspend (pin: Boolean) -> Boolean,
    scope: CoroutineScope,
    onClose: () -> Unit,
    onNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val usage = LocalUsageLog.current
    var reading by remember { mutableStateOf(true) }
    var pinned by remember { mutableStateOf<Boolean?>(null) }
    val readNow by rememberUpdatedState(read)
    LaunchedEffect(Unit) {
        pinned = readNow()
        reading = false
    }
    val pinDone = stringResource(R.string.console_pin_done)
    val unpinDone = stringResource(R.string.console_unpin_done)
    val pinFailed = stringResource(R.string.console_pin_failed)
    val unpinFailed = stringResource(R.string.console_unpin_failed)
    val isPinned = pinned == true
    DropdownMenuItem(
        text = {
            Column {
                Text(stringResource(if (isPinned) R.string.console_menu_unpin else R.string.console_menu_pin))
                if (!reading && pinned == null) {
                    Text(
                        stringResource(R.string.console_pin_unknown),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        onClick = {
            val pin = !isPinned
            usage.log(if (pin) UsageActions.MENU_PIN else UsageActions.MENU_UNPIN)
            onClose()
            scope.launch {
                onNote(
                    when {
                        write(pin) -> if (pin) pinDone else unpinDone
                        pin -> pinFailed
                        else -> unpinFailed
                    },
                )
            }
        },
        leadingIcon = {
            Icon(if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, contentDescription = null)
        },
        enabled = pinned != null,
        modifier = modifier.testTag(CONSOLE_PIN_TAG),
    )
}
