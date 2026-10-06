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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.ui.components.MonoLabel
import org.connectbot.ui.screens.herd.herdrActionLabel
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.commands.PaletteAction
import solutions.ormus.logos.commands.PaletteEntry
import solutions.ormus.logos.commands.PaletteGroup
import solutions.ormus.logos.herd.HerdrAction

/** Test tag on the palette's search field. */
const val PALETTE_SEARCH_TAG = "palette_search"

/** Test tag on one result row; the entry key follows the prefix. */
const val PALETTE_ROW_TAG = "palette_row:"

/**
 * The command palette: one search field over every action in the console
 * ([context] says what exists right now), ranked recent and most used first.
 * Tapping a result runs it on [actions] and closes the palette; a Herdr rename
 * asks for the name and a close or stop asks once, inside the palette. Saved
 * hosts come from the view model, so [context] leaves them out.
 *
 * @param target the session on screen, for its agent's slash commands; null with none
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandPaletteSheet(
    context: PaletteContext,
    target: SlashTarget?,
    actions: PaletteTarget,
    onDismiss: () -> Unit,
    viewModel: CommandPaletteViewModel = hiltViewModel(),
) {
    val usage = LocalUsageLog.current
    LaunchedEffect(target) { viewModel.open(target) }
    val slash by viewModel.slash.collectAsState()
    val hosts by viewModel.hosts.collectAsState()
    // The field keeps its own text: fed back from the view model's flow, fast
    // typing or a dictation burst would land out of order.
    var query by remember { mutableStateOf("") }
    val results by viewModel.results.collectAsState()

    val resources = LocalResources.current
    val agentName = agentName(slash.agent)
    val herdrLabels = PALETTE_HERDR_ACTIONS.associateWith { herdrActionLabel(it) }
    val entries = remember(context, hosts, slash, agentName, herdrLabels) {
        buildPaletteEntries(resources, context.copy(hosts = hosts), agentName, slash.commands, herdrLabels)
    }
    LaunchedEffect(entries) { viewModel.setEntries(entries) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = OrmusCornerShape,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        CommandPaletteContent(
            query = query,
            results = results,
            onQueryChange = {
                query = it
                viewModel.setQuery(it)
            },
            onRun = { entry, text ->
                usage.log(UsageActions.PALETTE_RUN)
                viewModel.recordRun(entry)
                onDismiss()
                runPaletteAction(entry.action, actions, text)
            },
        )
    }
}

/** The palette's content, stateless for tests: the search field and the ranked results. */
@Composable
fun CommandPaletteContent(
    query: String,
    results: List<PaletteEntry>,
    onQueryChange: (String) -> Unit,
    onRun: (PaletteEntry, String?) -> Unit,
    modifier: Modifier = Modifier,
    focusSearch: Boolean = true,
) {
    // A result that asks for a name or a confirmation first.
    var pending by remember { mutableStateOf<PaletteEntry?>(null) }
    val focus = remember { FocusRequester() }
    // Focus the field so the keyboard (and its dictation key) comes straight up.
    LaunchedEffect(Unit) { if (focusSearch) focus.requestFocus() }

    fun tap(entry: PaletteEntry) {
        val herdr = (entry.action as? PaletteAction.Herdr)?.action
        if (herdr != null && (herdr.needsText || herdr.confirm)) pending = entry else onRun(entry, null)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            placeholder = { Text(stringResource(R.string.palette_search_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { results.firstOrNull()?.let(::tap) }),
            shape = OrmusCornerShape,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus)
                .testTag(PALETTE_SEARCH_TAG),
        )
        pending?.let { entry ->
            PendingStep(
                entry = entry,
                onRun = { text ->
                    pending = null
                    onRun(entry, text)
                },
                onCancel = { pending = null },
            )
        }
        if (results.isEmpty()) {
            Text(
                stringResource(R.string.palette_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(results, key = { it.key }) { entry -> ResultRow(entry) { tap(entry) } }
        }
    }
}

@Composable
private fun groupLabel(group: PaletteGroup): String = stringResource(
    when (group) {
        PaletteGroup.KEYS -> R.string.palette_group_keys
        PaletteGroup.AGENT -> R.string.palette_group_agent
        PaletteGroup.HERDR -> R.string.palette_group_herdr
        PaletteGroup.SESSIONS -> R.string.palette_group_sessions
        PaletteGroup.HOSTS -> R.string.palette_group_hosts
        PaletteGroup.MACHINES -> R.string.palette_group_machines
        PaletteGroup.SCREENS -> R.string.palette_group_screens
        PaletteGroup.SETTINGS -> R.string.palette_group_settings
    },
)

@Composable
private fun ResultRow(entry: PaletteEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .testTag(PALETTE_ROW_TAG + entry.key)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            entry.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        MonoLabel(groupLabel(entry.group))
    }
}

/** The second step of a Herdr rename (a name) or close/stop (a confirmation). */
@Composable
private fun PendingStep(entry: PaletteEntry, onRun: (String?) -> Unit, onCancel: () -> Unit) {
    val action = (entry.action as PaletteAction.Herdr).action
    Surface(
        color = if (action.confirm) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = OrmusCornerShape,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (action.needsText) {
                var name by remember(entry) { mutableStateOf("") }
                val focus = remember { FocusRequester() }
                LaunchedEffect(entry) { focus.requestFocus() }
                Text(entry.title, style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.herdr_name_label)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank()) onRun(name) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onRun(name) }, enabled = name.isNotBlank(), shape = OrmusCornerShape) {
                        Text(stringResource(R.string.herdr_name_run))
                    }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.herdr_cancel)) }
                }
            } else {
                Text(
                    stringResource(confirmQuestion(action)),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onRun(null) },
                        shape = OrmusCornerShape,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.herdr_confirm)) }
                    TextButton(onClick = onCancel) { Text(stringResource(R.string.herdr_cancel)) }
                }
            }
        }
    }
}

private fun confirmQuestion(action: HerdrAction): Int = when (action) {
    HerdrAction.ClosePane -> R.string.herdr_confirm_close_pane
    HerdrAction.CloseTab -> R.string.herdr_confirm_close_tab
    HerdrAction.CloseWorkspace -> R.string.herdr_confirm_close_workspace
    else -> R.string.herdr_confirm_stop_session
}
