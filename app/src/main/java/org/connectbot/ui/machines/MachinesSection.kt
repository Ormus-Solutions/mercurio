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

package org.connectbot.ui.machines

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import org.connectbot.R
import org.connectbot.data.TailnetMachines
import org.connectbot.data.TailscaleDevice
import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.LocalTerminalManager
import org.connectbot.ui.components.CopyPromptButton
import org.connectbot.ui.components.DotStyle
import org.connectbot.ui.components.MonoLabel
import org.connectbot.ui.components.StatusDot
import org.connectbot.ui.components.ormusDrawerItemColors
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions

/**
 * The tailnet's machines with one-tap connect. Tapping a machine opens the
 * saved host that already points at it, or asks for a username once, saves a
 * new host and opens that. [onConnect] receives the host to open; callers pass
 * it to their existing console navigation.
 *
 * @param preferredBridge the session on screen, asked first on refresh
 * @param hideWhenEmpty render nothing until there is at least one machine
 */
@Composable
fun MachinesSection(
    onConnect: (Host) -> Unit,
    modifier: Modifier = Modifier,
    preferredBridge: TerminalBridge? = null,
    hideWhenEmpty: Boolean = false,
    viewModel: MachinesViewModel = hiltViewModel(),
) {
    val terminalManager = LocalTerminalManager.current
    LaunchedEffect(terminalManager) {
        terminalManager?.let { viewModel.setTerminalManager(it) }
    }
    val machines by viewModel.machines.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val refreshFailed by viewModel.refreshFailed.collectAsState()
    val scope = rememberCoroutineScope()
    val usage = LocalUsageLog.current
    var pendingDevice by remember { mutableStateOf<TailscaleDevice?>(null) }

    if (hideWhenEmpty && machines.devices.isEmpty()) return

    MachinesList(
        machines = machines,
        refreshing = refreshing,
        refreshFailed = refreshFailed,
        fixPrompt = {
            usage.log(UsageActions.MACHINES_FIX_PROMPT)
            viewModel.fixPrompt().orEmpty()
        },
        onRefresh = {
            usage.log(UsageActions.MACHINES_REFRESH)
            viewModel.refresh(preferredBridge)
        },
        onDeviceClick = { device ->
            usage.log(UsageActions.MACHINES_CONNECT)
            scope.launch {
                val saved = viewModel.findSavedHost(device)
                val known = viewModel.knownUsername(device)
                when {
                    saved != null -> onConnect(saved)
                    known != null -> viewModel.saveHostFor(device, known)?.let(onConnect)
                    else -> pendingDevice = device
                }
            }
        },
        modifier = modifier,
    )

    pendingDevice?.let { device ->
        NewMachineHostDialog(
            device = device,
            defaultUsername = machines.sourceUsername,
            onDismiss = { pendingDevice = null },
            onConfirm = { username ->
                pendingDevice = null
                scope.launch {
                    viewModel.saveHostFor(device, username)?.let(onConnect)
                }
            },
        )
    }
}

@Composable
private fun MachinesList(
    machines: TailnetMachines,
    refreshing: Boolean,
    refreshFailed: Boolean,
    onRefresh: () -> Unit,
    onDeviceClick: (TailscaleDevice) -> Unit,
    modifier: Modifier = Modifier,
    fixPrompt: (suspend () -> String)? = null,
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                MonoLabel(
                    text = stringResource(R.string.machines_title),
                    style = MaterialTheme.typography.labelMedium,
                )
                if (machines.updatedAt > 0L) {
                    val ago = DateUtils.getRelativeTimeSpanString(
                        machines.updatedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS,
                    ).toString()
                    Text(
                        text = stringResource(R.string.machines_last_updated, ago),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (refreshing) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.machines_refresh))
                }
            }
        }

        val hint = when {
            refreshFailed -> stringResource(R.string.machines_refresh_failed)
            machines.devices.isEmpty() && machines.updatedAt == 0L -> stringResource(R.string.machines_empty_hint)
            machines.devices.isEmpty() -> stringResource(R.string.machines_none_found)
            else -> null
        }
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp),
            )
        }
        // A failed refresh: a prompt to paste into an agent, with the error and the host it ran on.
        if (refreshFailed && fixPrompt != null) {
            CopyPromptButton(prompt = fixPrompt, modifier = Modifier.padding(horizontal = 16.dp))
        }

        machines.devices.forEach { device ->
            MachineRow(device = device, onClick = { onDeviceClick(device) })
        }
    }
}

@Composable
private fun MachineRow(
    device: TailscaleDevice,
    onClick: () -> Unit,
) {
    val status = stringResource(if (device.online) R.string.machines_status_online else R.string.machines_status_offline)
    NavigationDrawerItem(
        selected = false,
        onClick = onClick,
        icon = {
            // Same marks as the session drawer: solid ink online, hollow dim ring offline.
            StatusDot(
                color = if (device.online) Ormus.extras.status.done else Ormus.extras.status.idle,
                style = if (device.online) DotStyle.FILLED else DotStyle.HOLLOW,
                modifier = Modifier.semantics { contentDescription = status },
            )
        },
        label = {
            Column {
                Text(
                    text = device.shortName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (device.osLabel.isNotBlank()) {
                    Text(
                        text = device.osLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        shape = OrmusCornerShape,
        colors = ormusDrawerItemColors(),
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

/** Asks which user to log in as before saving a host for a new machine. */
@Composable
private fun NewMachineHostDialog(
    device: TailscaleDevice,
    defaultUsername: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var username by remember { mutableStateOf(defaultUsername) }
    val canConfirm = username.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.machines_connect_title, device.shortName)) },
        text = {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                singleLine = true,
                label = { Text(stringResource(R.string.hostpref_username_title)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (canConfirm) onConfirm(username) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(username) }, enabled = canConfirm) {
                Text(stringResource(R.string.machines_connect_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_cancel))
            }
        },
    )
}
