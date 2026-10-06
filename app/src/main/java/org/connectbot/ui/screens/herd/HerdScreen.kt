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

package org.connectbot.ui.screens.herd

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.connectbot.R
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.herd.Herd
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrAgentInfo
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import solutions.ormus.logos.herd.tabLabel
import solutions.ormus.logos.herd.title
import solutions.ormus.logos.herd.workspaceLabel
import solutions.ormus.logos.push.PushRegistration

/** Test tag on every agent card, in the order shown. */
const val HERD_CARD_TAG = "herd_card"

/** Test tag on the header line with the per-state counts. */
const val HERD_COUNTS_TAG = "herd_counts"

/** Test tag on the line that says push alerts need the ntfy app. */
const val HERD_PUSH_OFF_TAG = "herd_push_off"

/**
 * Full-screen Herd: every agent in the session's Herdr, ordered by who needs
 * you, live from the session's poller. Tapping a card hands the agent to
 * [onOpenAgent] (focus it and show the console).
 */
@Composable
fun HerdDialog(
    session: HerdSession?,
    onOpenAgent: (HerdrAgentInfo) -> Unit,
    onOpenCommands: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snapshot = session?.snapshot?.collectAsState()?.value
    val context = LocalContext.current
    val pushOff = remember { !PushRegistration.hasDistributor(context) }
    // Do not wait for the next poll: read the agents as the screen opens.
    LaunchedEffect(session) { session?.refresh() }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        HerdScreen(
            snapshot = snapshot,
            onOpenAgent = onOpenAgent,
            onRefresh = { scope.launch { session?.refresh() } },
            onOpenCommands = onOpenCommands,
            onClose = onDismiss,
            pushOff = pushOff,
        )
    }
}

@Composable
fun HerdScreen(
    snapshot: HerdrSessionSnapshot?,
    onOpenAgent: (HerdrAgentInfo) -> Unit,
    onRefresh: () -> Unit,
    onOpenCommands: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** No push distributor is installed, so alerts stop while Mercurio sleeps. */
    pushOff: Boolean = false,
) {
    val agents = Herd.order(snapshot?.agents.orEmpty())
    val usage = LocalUsageLog.current
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    usage.log(UsageActions.HERD_CLOSE)
                    onClose()
                }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.herd_close))
                }
                Text(
                    stringResource(R.string.herd_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    usage.log(UsageActions.HERD_REFRESH)
                    onRefresh()
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.herd_refresh))
                }
                IconButton(onClick = {
                    usage.log(UsageActions.HERD_COMMANDS)
                    onOpenCommands()
                }) {
                    Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.herd_commands))
                }
            }

            Text(
                text = when {
                    snapshot == null -> stringResource(R.string.herd_loading)

                    agents.isEmpty() -> stringResource(R.string.herd_empty)

                    else -> stringResource(
                        R.string.herd_counts,
                        agents.count { it.agentStatus == HerdrAgentStatus.BLOCKED },
                        agents.count { it.agentStatus == HerdrAgentStatus.DONE },
                        agents.count { it.agentStatus == HerdrAgentStatus.WORKING },
                        agents.count { it.agentStatus == HerdrAgentStatus.IDLE },
                    )
                },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag(HERD_COUNTS_TAG),
            )

            if (pushOff) {
                Text(
                    stringResource(R.string.herd_push_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .testTag(HERD_PUSH_OFF_TAG),
                )
            }

            // A plain scrolling column, not a lazy list: a herd is a handful of
            // agents, and every card stays in the tree for tests and TalkBack.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                agents.forEach { agent ->
                    HerdCard(
                        agent = agent,
                        place = snapshot?.let { placeOf(it, agent) },
                        onClick = {
                            usage.log(UsageActions.HERD_CARD)
                            onOpenAgent(agent)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun placeOf(snapshot: HerdrSessionSnapshot, agent: HerdrAgentInfo): String? {
    val workspace = snapshot.workspaceLabel(agent.workspaceId)
    val tab = snapshot.tabLabel(agent.tabId)
    return when {
        workspace != null && tab != null -> stringResource(R.string.herd_place, workspace, tab)
        else -> workspace ?: tab
    }
}

@Composable
private fun HerdCard(
    agent: HerdrAgentInfo,
    place: String?,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(HERD_CARD_TAG),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(agent.agentStatus)
                Text(
                    agent.agent ?: stringResource(R.string.herd_agent_unnamed),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            agent.title()?.let { title ->
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            place?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: HerdrAgentStatus) {
    val colors = MaterialTheme.colorScheme
    val (container: Color, content: Color, label: Int) = when (status) {
        HerdrAgentStatus.BLOCKED -> Triple(colors.errorContainer, colors.onErrorContainer, R.string.herd_status_blocked)
        HerdrAgentStatus.DONE -> Triple(colors.tertiaryContainer, colors.onTertiaryContainer, R.string.herd_status_done)
        HerdrAgentStatus.WORKING -> Triple(colors.primaryContainer, colors.onPrimaryContainer, R.string.herd_status_working)
        HerdrAgentStatus.IDLE -> Triple(colors.surfaceVariant, colors.onSurfaceVariant, R.string.herd_status_idle)
        HerdrAgentStatus.UNKNOWN -> Triple(colors.surfaceVariant, colors.onSurfaceVariant, R.string.herd_status_unknown)
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
