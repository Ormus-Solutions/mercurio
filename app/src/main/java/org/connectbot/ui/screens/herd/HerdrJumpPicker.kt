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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.connectbot.R
import org.connectbot.ui.components.DotStyle
import org.connectbot.ui.components.OrmusGroup
import org.connectbot.ui.components.StatusChip
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.OrmusMono
import solutions.ormus.logos.herd.AgentState
import solutions.ormus.logos.herd.CoreSource
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrCommands
import solutions.ormus.logos.herd.HerdrJump
import solutions.ormus.logos.herd.Inbox
import solutions.ormus.logos.herd.InboxRow
import solutions.ormus.logos.herd.JumpRecents
import solutions.ormus.logos.herd.JumpSheet
import java.util.concurrent.ConcurrentHashMap

/** Test tag on the Jump to search field. */
const val HERDR_JUMP_SEARCH_TAG = "herdr_jump_search"

// One CoreSource per host for the life of the process: it remembers when each terminal entered
// its state, so the sheet ranks core rows as the Inbox does, and skips the version probe after the first.
private val coreSources = ConcurrentHashMap<Long, CoreSource>()

/** Test tag on the Jump to list. */
const val HERDR_JUMP_LIST_TAG = "herdr_jump_list"

/**
 * Jump to as a bottom sheet: a search field on top that takes the keyboard as it opens; empty,
 * a Recent group (the panes he jumped to last) then all the rows; typed into, a Matches group.
 * Opening it lists the sessions and fetches each one's snapshot again, and, when [core] is on,
 * reads the host's herdr-core terminals once. A tap hands the row to [onPick] and keeps it in
 * [recents].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HerdrJumpPicker(
    session: HerdSession,
    host: String,
    hostId: Long,
    core: Boolean,
    recents: JumpRecents,
    onPick: (JumpSheet.Item) -> Unit,
    onDismiss: () -> Unit,
) {
    val sessions by session.sessions.collectAsState()
    val primary by session.snapshot.collectAsState()
    val others by session.others.collectAsState()
    var coreRows by remember(session) { mutableStateOf<List<InboxRow>>(emptyList()) }
    LaunchedEffect(session) { session.refreshAll() }
    LaunchedEffect(session, core) {
        if (!core) return@LaunchedEffect
        val read = withContext(Dispatchers.IO) { coreSources.getOrPut(hostId) { CoreSource() }.read(true, host, System.currentTimeMillis(), session::exec) }
        coreRows = Inbox.fromCore(hostId, host, read).rows
    }
    val snapshots = buildMap {
        primary?.let { put(HerdrCommands.DEFAULT_SESSION, it) }
        putAll(others.filterKeys { it in sessions })
    }
    val items = remember(snapshots, host, coreRows) { JumpSheet.items(HerdrJump.targets(snapshots), host, coreRows) }
    // Read once as the sheet opens: the order he left it in does not shift under his thumb.
    val recent = remember { recents.load() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = OrmusCornerShape,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        HerdrJumpContent(
            items = items,
            recent = recent,
            onPick = { item ->
                recents.record(item.key)
                onPick(item)
            },
        )
    }
}

@Composable
fun HerdrJumpContent(items: List<JumpSheet.Item>, recent: List<String>, onPick: (JumpSheet.Item) -> Unit, modifier: Modifier = Modifier) {
    var query by rememberSaveable { mutableStateOf("") }
    val sections = remember(items, recent, query) { JumpSheet.sections(items, recent, query) }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { searchFocus.requestFocus() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = Ormus.spacing.lg, vertical = Ormus.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Ormus.spacing.md),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.herdr_jump_search), maxLines = 1) },
            singleLine = true,
            shape = OrmusCornerShape,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = OrmusMono),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(searchFocus)
                .testTag(HERDR_JUMP_SEARCH_TAG),
        )
        if (sections.isEmpty()) {
            Text(stringResource(R.string.herdr_jump_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .testTag(HERDR_JUMP_LIST_TAG),
            verticalArrangement = Arrangement.spacedBy(Ormus.spacing.lg),
        ) {
            sections.forEach { section ->
                val label = when (section.group) {
                    JumpSheet.Group.RECENT -> R.string.herdr_jump_recent
                    JumpSheet.Group.ALL -> R.string.herdr_jump_all
                    JumpSheet.Group.MATCHES -> R.string.herdr_jump_matches
                }
                OrmusGroup(label = stringResource(label)) {
                    section.items.forEach { item -> row(item.key) { JumpRow(item) { onPick(item) } } }
                }
            }
        }
    }
}

/** One pane: its space, then `tab · host`, then its title when the tab's name lacks it; its agent's kind and state on the right. */
@Composable
private fun JumpRow(item: JumpSheet.Item, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ormus.spacing.md),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Ormus.spacing.lg, vertical = Ormus.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.space, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                item.second,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.third?.let { title ->
                Text(
                    title,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = OrmusMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AgentChip(item.agent.kind, item.agent.state)
    }
}

/** The agent and its state as the Herd's chip; nothing for a pane without either. */
@Composable
private fun AgentChip(agent: String?, state: AgentState) {
    val status = Ormus.extras.status
    val (word, color, style) = when (state) {
        is AgentState.NeedsYou -> Triple(stringResource(R.string.herd_status_blocked), status.blocked, DotStyle.FILLED)
        AgentState.Working -> Triple(stringResource(R.string.herd_status_working), status.working, DotStyle.RING)
        AgentState.Done -> Triple(stringResource(R.string.herd_status_done), status.done, DotStyle.FILLED)
        AgentState.Idle -> Triple(stringResource(R.string.herd_status_idle), status.idle, DotStyle.HOLLOW)
        AgentState.Unknown -> if (agent == null) return else Triple(null, status.idle, DotStyle.HOLLOW)
    }
    StatusChip(listOfNotNull(agent, word).joinToString(" · "), color, style = style)
}
