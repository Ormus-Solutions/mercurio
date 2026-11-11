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

package org.connectbot.ui.workspaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.ui.components.OrmusGroup
import org.connectbot.ui.components.OrmusKey
import org.connectbot.ui.components.WORD_KEY_PADDING
import org.connectbot.ui.theme.Ormus
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.herd.HerdrConsole
import solutions.ormus.logos.herd.HerdrWorkspace

/** Tag of every workboard key, for tests. */
internal const val WORKSPACE_KEY_TAG = "workspace-key"

/** Tag of the Workspaces group, for tests. */
internal const val WORKSPACES_GROUP_TAG = "workspaces-group"

/**
 * The workboards of the saved Herdr hosts, one key each, at the top of the host list: a tap
 * opens that console of its host, and connects the host when nothing of it is open. The keys
 * are what each host put in its console list, kept on the phone after a connect
 * ([solutions.ormus.logos.herd.HerdrConsoleCache]), so they are there before any connection.
 * The host list takes [workspaces] from [WorkspacesViewModel] and leaves the section out
 * while there are none, so a phone without them keeps its layout.
 *
 * @param onOpen receives the host and the console to open; callers pass it to the console route
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspacesSection(
    workspaces: List<HerdrWorkspace>,
    onOpen: (Host, HerdrConsole) -> Unit,
    modifier: Modifier = Modifier,
) {
    val usage = LocalUsageLog.current
    val byHost = workspaces.groupBy { it.host.id }
    OrmusGroup(
        label = stringResource(R.string.workspaces_title),
        modifier = modifier.testTag(WORKSPACES_GROUP_TAG),
    ) {
        // One row per host; its name only when more than one host has workboards.
        byHost.values.forEach { rows ->
            row {
                Column(modifier = Modifier.padding(Ormus.spacing.md)) {
                    if (byHost.size > 1) {
                        Text(
                            text = rows.first().host.nickname,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        rows.forEach { ws ->
                            OrmusKey(
                                label = ws.name,
                                onClick = {
                                    usage.log(UsageActions.WORKSPACES_OPEN)
                                    onOpen(ws.host, ws.console)
                                },
                                contentPadding = WORD_KEY_PADDING,
                                description = stringResource(R.string.workspaces_open, ws.name, ws.host.nickname),
                                modifier = Modifier.testTag(WORKSPACE_KEY_TAG),
                            )
                        }
                    }
                }
            }
        }
    }
}
