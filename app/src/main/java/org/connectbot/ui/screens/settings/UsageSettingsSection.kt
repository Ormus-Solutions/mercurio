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

package org.connectbot.ui.screens.settings

import android.content.ClipData
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import org.connectbot.R
import org.connectbot.ui.components.MonoLabel
import org.connectbot.usage.InboxEntry
import org.connectbot.usage.InboxKind
import org.connectbot.usage.UploadResult

/**
 * Settings section: "Record my usage", the usage summary, clearing it, the upload
 * to the usage host (none until the user sets one) and its target, and the wish list.
 */
@Composable
fun UsageSettingsSection(
    modifier: Modifier = Modifier,
    viewModel: UsageSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val wishes by viewModel.wishes.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var editHosts by remember { mutableStateOf(false) }
    var editKey by remember { mutableStateOf(false) }
    var showWishes by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // Same lab-log section label as the other Settings categories.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MonoLabel(
            text = stringResource(R.string.pref_usage_category),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        )
        UsageItem(
            title = stringResource(R.string.pref_usage_record_title),
            summary = stringResource(R.string.pref_usage_record_summary),
            onClick = { viewModel.setRecord(!state.record) },
            trailing = { Switch(checked = state.record, onCheckedChange = viewModel::setRecord) },
        )
        UsageItem(
            title = stringResource(R.string.pref_usage_summary_title),
            summary = stringResource(R.string.pref_usage_summary_summary),
            onClick = viewModel::showSummary,
            modifier = Modifier.testTag("usage_summary"),
        )
        UsageItem(
            title = stringResource(R.string.pref_wishes_title),
            summary = stringResource(R.string.pref_wishes_summary, wishes.count { !it.done }, state.pendingInbox),
            onClick = {
                viewModel.openWishes()
                showWishes = true
            },
        )
        UsageItem(
            title = stringResource(R.string.pref_usage_upload_title),
            summary = uploadSummary(state),
            onClick = { if (!state.uploading) viewModel.uploadNow() },
        )
        UsageItem(
            title = stringResource(R.string.pref_usage_sun_hosts_title),
            summary = state.sunHosts.ifEmpty { stringResource(R.string.pref_usage_sun_hosts_none) },
            onClick = { editHosts = true },
        )
        UsageItem(
            title = stringResource(R.string.pref_usage_sun_key_title),
            summary = state.sunHostKey.ifEmpty { stringResource(R.string.pref_usage_sun_key_none) },
            onClick = { editKey = true },
        )
        UsageItem(
            title = stringResource(R.string.pref_usage_clear_title),
            summary = stringResource(R.string.pref_usage_clear_summary),
            onClick = { confirmClear = true },
        )
    }

    state.summary?.let { summary -> UsageSummaryDialog(summary, onDismiss = viewModel::dismissSummary) }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.pref_usage_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearUsage()
                }) { Text(stringResource(R.string.delete_pos)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.button_cancel)) }
            },
        )
    }

    if (editHosts) {
        UsageTextDialog(
            title = stringResource(R.string.pref_usage_sun_hosts_title),
            initial = state.sunHosts,
            onDismiss = { editHosts = false },
            onConfirm = {
                editHosts = false
                viewModel.setSunHosts(it)
            },
        )
    }

    if (editKey) {
        UsageTextDialog(
            title = stringResource(R.string.pref_usage_sun_key_title),
            initial = state.sunHostKey,
            onDismiss = { editKey = false },
            onConfirm = {
                editKey = false
                viewModel.setSunHostKey(it)
            },
        )
    }

    if (showWishes) {
        WishListDialog(
            wishes = wishes,
            onDismiss = { showWishes = false },
            onEdit = viewModel::editWish,
            onDone = viewModel::setWishDone,
            onDelete = viewModel::deleteWish,
        )
    }
}

@Composable
private fun uploadSummary(state: UsageSettingsUiState): String {
    val last = if (state.lastUploadAt == 0L) {
        stringResource(R.string.pref_usage_upload_never)
    } else {
        DateUtils.getRelativeTimeSpanString(state.lastUploadAt).toString()
    }
    val status = stringResource(R.string.pref_usage_upload_summary, last, state.pendingInbox)
    val result = when (val r = state.lastResult) {
        null -> null
        UploadResult.NoSun -> stringResource(R.string.pref_usage_upload_no_sun)
        UploadResult.HostKeyRefused -> stringResource(R.string.pref_usage_upload_refused)
        is UploadResult.Failed -> stringResource(R.string.pref_usage_upload_failed, r.reason)
        is UploadResult.Uploaded -> stringResource(R.string.pref_usage_upload_done, r.events + r.dailies, r.inbox)
    }
    return if (state.uploading) stringResource(R.string.pref_usage_uploading) else listOfNotNull(result, status).joinToString("\n")
}

@Composable
private fun UsageItem(
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier = modifier) {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(summary) },
            trailingContent = trailing,
            modifier = Modifier.clickable(onClick = onClick),
        )
        HorizontalDivider()
    }
}

@Composable
private fun UsageSummaryDialog(summary: String, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.usage_summary_title)) },
        text = {
            SelectionContainer {
                Text(
                    summary,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .testTag("usage_summary_text"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch { clipboard.setClipEntry(ClipData.newPlainText("usage", summary).toClipEntry()) }
                onDismiss()
            }) { Text(stringResource(R.string.usage_summary_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_close)) }
        },
    )
}

@Composable
private fun UsageTextDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(stringResource(R.string.button_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) }
        },
    )
}

/** Read, edit, close and delete wishes and error reports. */
@Composable
private fun WishListDialog(
    wishes: List<InboxEntry>,
    onDismiss: () -> Unit,
    onEdit: (String, String) -> Unit,
    onDone: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<InboxEntry?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.button_close))
                    }
                    Text(stringResource(R.string.pref_wishes_title), style = MaterialTheme.typography.titleLarge)
                }
                if (wishes.isEmpty()) {
                    Text(
                        stringResource(R.string.wishes_empty),
                        modifier = Modifier.padding(24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.testTag("wish_list")) {
                    items(wishes, key = { it.id }) { entry ->
                        WishRow(
                            entry = entry,
                            onEdit = { editing = entry },
                            onDone = { onDone(entry.id, !entry.done) },
                            onDelete = { onDelete(entry.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
    editing?.let { entry ->
        UsageTextDialog(
            title = stringResource(R.string.wishes_edit),
            initial = entry.text,
            onDismiss = { editing = null },
            onConfirm = {
                editing = null
                onEdit(entry.id, it)
            },
        )
    }
}

@Composable
private fun WishRow(
    entry: InboxEntry,
    onEdit: () -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit,
) {
    val kind = stringResource(if (entry.kind == InboxKind.ERROR) R.string.wishes_kind_error else R.string.wishes_kind_wish)
    val date = DateUtils.getRelativeTimeSpanString(entry.createdAt).toString()
    val flags = listOfNotNull(
        stringResource(R.string.wishes_done).takeIf { entry.done },
        stringResource(R.string.wishes_waiting).takeIf { !entry.synced },
    )
    ListItem(
        overlineContent = { Text((listOf(kind, date) + flags).joinToString(" · ")) },
        headlineContent = {
            Text(
                entry.text,
                maxLines = if (entry.kind == InboxKind.ERROR) 4 else 8,
                fontFamily = if (entry.kind == InboxKind.ERROR) FontFamily.Monospace else null,
                color = if (entry.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = entry.note?.let { note -> { Text(note) } },
        trailingContent = {
            Row {
                IconButton(onClick = onDone) {
                    if (entry.done) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.wishes_reopen))
                    } else {
                        Icon(Icons.Default.Check, contentDescription = stringResource(R.string.wishes_mark_done))
                    }
                }
                if (entry.kind == InboxKind.WISH) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.wishes_edit))
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.wishes_delete))
                }
            }
        },
    )
}
