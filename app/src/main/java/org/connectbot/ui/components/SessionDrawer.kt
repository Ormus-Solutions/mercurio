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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.OrmusMono
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions

/**
 * Left-edge drawer for switching between, disconnecting, and starting terminal
 * sessions. Lists every open bridge across all hosts (not just the host the
 * console was launched for), so it doubles as a session hub. [onOpenHerd], when
 * a session runs Herdr, adds a Herd item that lists its agents by who needs you.
 */
@Composable
fun SessionDrawer(
    bridges: List<TerminalBridge>,
    activeBridge: TerminalBridge?,
    onSelect: (TerminalBridge) -> Unit,
    onDisconnect: (TerminalBridge) -> Unit,
    onQuickConnect: (String) -> Unit,
    onOpenHostList: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenHerd: (() -> Unit)? = null,
    machines: @Composable ColumnScope.() -> Unit = {},
) {
    val usage = LocalUsageLog.current
    ModalDrawerSheet(modifier = modifier) {
        MonoLabel(
            text = stringResource(R.string.drawer_sessions_title),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 8.dp),
        )

        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            items(bridges, key = { it.host.id }) { bridge ->
                val host = bridge.host
                val label = host.nickname.ifBlank { host.hostname }
                val endpoint = buildString {
                    if (host.username.isNotBlank()) append("${host.username}@")
                    append(host.hostname)
                    if (host.port != 22) append(":${host.port}")
                }
                // Live hint of what's happening in the session (e.g. what the
                // agent is doing); falls back to the endpoint when quiet.
                val activity by bridge.activityPreview.collectAsState()
                val subtitle = activity.ifBlank { endpoint }
                NavigationDrawerItem(
                    selected = bridge === activeBridge,
                    onClick = {
                        usage.log(UsageActions.DRAWER_SWITCH)
                        onSelect(bridge)
                    },
                    icon = { StatusDot(bridge) },
                    label = {
                        Column {
                            Text(
                                text = label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            // Session output reads in the terminal's mono face.
                            Text(
                                text = subtitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = OrmusMono),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    badge = {
                        IconButton(onClick = {
                            usage.log(UsageActions.DRAWER_DISCONNECT)
                            onDisconnect(bridge)
                        }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(
                                    R.string.drawer_session_disconnect,
                                    label,
                                ),
                            )
                        }
                    },
                    shape = OrmusCornerShape,
                    colors = ormusDrawerItemColors(),
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                )
            }
        }

        if (onOpenHerd != null) {
            NavigationDrawerItem(
                selected = false,
                onClick = {
                    usage.log(UsageActions.HERD_OPEN)
                    onOpenHerd()
                },
                icon = { Icon(Icons.Default.Groups, contentDescription = null) },
                label = { Text(stringResource(R.string.drawer_herd)) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Tailnet machines (one-tap connect), above quick connect.
        machines()

        // Quick connect: type a saved host name (e.g. "sun") or user@host[:port]
        // and connect without leaving the terminal.
        var quickConnect by remember { mutableStateOf("") }
        fun submit() {
            if (quickConnect.isNotBlank()) {
                usage.log(UsageActions.DRAWER_QUICK_CONNECT)
                onQuickConnect(quickConnect)
                quickConnect = ""
            }
        }
        OutlinedTextField(
            value = quickConnect,
            onValueChange = { quickConnect = it },
            singleLine = true,
            label = { Text(stringResource(R.string.drawer_quick_connect_label)) },
            placeholder = { Text(stringResource(R.string.drawer_quick_connect_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
            trailingIcon = {
                IconButton(onClick = { submit() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.drawer_quick_connect_label),
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )

        NavigationDrawerItem(
            selected = false,
            onClick = {
                usage.log(UsageActions.DRAWER_HOST_LIST)
                onOpenHostList()
            },
            icon = { Icon(Icons.Default.Dns, contentDescription = null) },
            label = { Text(stringResource(R.string.drawer_host_list)) },
            shape = OrmusCornerShape,
            colors = ormusDrawerItemColors(),
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
        )
    }
}

/**
 * Drawer rows in brand colors: the active session sits on a gold-tinted container,
 * the rest on the sheet. Shared with the Machines rows.
 */
@Composable
fun ormusDrawerItemColors() = NavigationDrawerItemDefaults.colors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedContainerColor = Color.Transparent,
    selectedTextColor = MaterialTheme.colorScheme.onSurface,
    unselectedTextColor = MaterialTheme.colorScheme.onSurface,
    selectedIconColor = MaterialTheme.colorScheme.primary,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * Live connection state, by intensity and shape: gold bright while connecting, a
 * solid ink dot when the session is open, a hollow dim ring once it is closed.
 */
@Composable
private fun StatusDot(bridge: TerminalBridge) {
    val status = Ormus.extras.status
    when {
        bridge.isConnecting -> StatusDot(color = status.connecting)
        bridge.isSessionOpen && !bridge.isDisconnected -> StatusDot(color = status.done)
        else -> StatusDot(color = status.idle, style = DotStyle.HOLLOW)
    }
}
