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

package org.connectbot.ui.commands

import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.ui.components.MonoLabel
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.OrmusMono
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.commands.AgentKind
import solutions.ormus.logos.commands.SlashCommand
import solutions.ormus.logos.commands.SlashSource

/** Test tag on the slash menu's search field. */
const val SLASH_SEARCH_TAG = "slash_search"

/** Test tag on one command row; the command's text ("/compact") follows the prefix. */
const val SLASH_ROW_TAG = "slash_row:"

/**
 * Bottom sheet with the slash commands of the agent running in [target]'s
 * session: the agent's built-ins and Ormus's own commands and skills.
 *
 * A tap on a command that takes nothing sends "/name" and Enter. A command
 * that takes more (an argument hint, or any of his own) goes to the dictation
 * field as "/name " through [onInsert], to be finished by voice and sent. A long
 * press always goes to the field. Either way the sheet closes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlashMenuSheet(
    target: SlashTarget,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: SlashMenuViewModel = hiltViewModel(),
) {
    val usage = LocalUsageLog.current
    val state by viewModel.uiState.collectAsState()
    // The field keeps its own text: fed back from the view model's async state,
    // fast typing or a dictation burst would land out of order.
    var query by remember { mutableStateOf("") }
    LaunchedEffect(target) { viewModel.open(target) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = OrmusCornerShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        SlashMenuContent(
            state = state,
            query = query,
            onQueryChange = {
                query = it
                viewModel.setQuery(it)
            },
            onPickAgent = { agent ->
                usage.log(UsageActions.SLASH_AGENT)
                viewModel.pickAgent(agent)
            },
            onRefresh = {
                usage.log(UsageActions.SLASH_REFRESH)
                viewModel.refresh()
            },
            onRun = { command, insert ->
                // The usage log records send or insert, never which command.
                val sent = viewModel.run(command, onInsert, insert)
                usage.log(if (sent) UsageActions.SLASH_SEND else UsageActions.SLASH_INSERT)
                onDismiss()
            },
        )
    }
}

/** The sheet's content, stateless for previews and tests. */
@Composable
fun SlashMenuContent(
    state: SlashMenuUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onPickAgent: (AgentKind) -> Unit,
    onRefresh: () -> Unit,
    onRun: (SlashCommand, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.slash_menu_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            if (state.refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = onRefresh, enabled = !state.refreshing) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.slash_refresh))
            }
        }
        AgentPicker(agent = state.agent, detected = state.detected, onPick = onPickAgent)
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.slash_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = OrmusCornerShape,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SLASH_SEARCH_TAG),
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (state.builtIns.isEmpty() && state.own.isEmpty() && state.query.isNotBlank()) {
                item { Note(stringResource(R.string.slash_no_match)) }
            }
            if (state.builtIns.isNotEmpty()) {
                item { SectionLabel(stringResource(R.string.slash_group_builtin)) }
                items(state.builtIns, key = { "b" + it.name }) { CommandRow(it, onRun) }
            }
            item { SectionLabel(stringResource(R.string.slash_group_own)) }
            item { OwnStatus(state) }
            items(state.own, key = { "o" + it.source + it.name }) { CommandRow(it, onRun) }
        }
    }
}

@Composable
private fun AgentPicker(agent: AgentKind, detected: Boolean, onPick: (AgentKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AgentKind.entries.forEach { kind ->
            FilterChip(
                selected = kind == agent,
                onClick = { onPick(kind) },
                label = { Text(agentName(kind)) },
                shape = OrmusCornerShape,
            )
        }
        if (detected) {
            Text(
                stringResource(R.string.slash_detected),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The agent's product name. */
@Composable
fun agentName(agent: AgentKind): String = stringResource(
    when (agent) {
        AgentKind.CLAUDE -> R.string.agent_claude
        AgentKind.CODEX -> R.string.agent_codex
        AgentKind.GROK -> R.string.agent_grok
    },
)

@Composable
private fun SectionLabel(text: String) {
    MonoLabel(text, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun OwnStatus(state: SlashMenuUiState) {
    val text = when {
        state.refreshing -> stringResource(R.string.slash_own_reading)

        state.failed -> stringResource(R.string.slash_own_failed)

        state.fetchedAt == 0L -> stringResource(R.string.slash_own_never)

        state.own.isEmpty() && state.query.isBlank() -> stringResource(R.string.slash_own_empty)

        else -> stringResource(
            R.string.slash_own_read_at,
            DateUtils.getRelativeTimeSpanString(state.fetchedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
        )
    }
    Note(text)
}

@Composable
private fun sourceTag(source: SlashSource): String? = when (source) {
    SlashSource.BUILT_IN -> null
    SlashSource.COMMAND -> stringResource(R.string.slash_source_command)
    SlashSource.SKILL -> stringResource(R.string.slash_source_skill)
    SlashSource.PROJECT -> stringResource(R.string.slash_source_project)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CommandRow(command: SlashCommand, onRun: (SlashCommand, Boolean) -> Unit) {
    val sends = command.sendsAtOnce
    val mark = stringResource(if (sends) R.string.slash_runs_now else R.string.slash_to_field)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .combinedClickable(
                onClick = { onRun(command, false) },
                onLongClick = { onRun(command, true) },
            )
            .testTag(SLASH_ROW_TAG + command.text)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    command.text,
                    fontFamily = OrmusMono,
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = if (command.isOwn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                command.argumentHint?.let { hint ->
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                sourceTag(command.source)?.let { tag ->
                    Text(tag.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (command.description.isNotBlank()) {
                Text(
                    command.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            if (sends) Icons.AutoMirrored.Filled.KeyboardReturn else Icons.Default.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(18.dp)
                .semantics { contentDescription = mark },
        )
    }
}
