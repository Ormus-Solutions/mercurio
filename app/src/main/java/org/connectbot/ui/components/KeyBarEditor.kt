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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.connectbot.R
import org.connectbot.ui.theme.Ormus

internal const val KEY_BAR_SWITCH_TAG = "key_bar_switch"
internal const val KEY_BAR_EDIT_TAG = "key_bar_edit"

/**
 * The settings panel's rows for the key bar: the switch (off keeps the default bar) and the
 * editor, a full-screen dialog. Every edit is saved at once, so closing the editor loses nothing.
 */
@Composable
fun KeyBarSettingsSection(store: KeyBarStore, modifier: Modifier = Modifier) {
    var enabled by remember { mutableStateOf(store.enabled) }
    var layout by remember { mutableStateOf(store.layout) }
    var editing by remember { mutableStateOf(false) }

    Column(modifier) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(stringResource(R.string.key_bar_custom)) },
            supportingContent = {
                Text(stringResource(if (enabled) R.string.key_bar_custom_on else R.string.key_bar_custom_off))
            },
            trailingContent = { Switch(checked = enabled, onCheckedChange = null) },
            modifier = Modifier
                .testTag(KEY_BAR_SWITCH_TAG)
                .toggleable(value = enabled, role = Role.Switch) {
                    enabled = it
                    store.enabled = it
                },
        )
        HorizontalDivider(modifier = Modifier.padding(start = Ormus.spacing.lg), color = Ormus.extras.hairline)
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            headlineContent = { Text(stringResource(R.string.key_bar_edit)) },
            supportingContent = {
                Text(stringResource(R.string.key_bar_edit_summary, layout.pages.sumOf { it.size }, layout.pages.size))
            },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
            modifier = Modifier.testTag(KEY_BAR_EDIT_TAG).clickable { editing = true },
        )
    }
    if (editing) {
        KeyBarEditor(
            layout = layout,
            onChange = {
                layout = it
                store.layout = it
            },
            onReset = {
                layout = KeyBarLayout.DEFAULT
                store.layout = KeyBarLayout.DEFAULT
            },
            onClose = { editing = false },
        )
    }
}

/** Add, remove and order the keys of each page. Order is set with the up and down buttons. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun KeyBarEditor(
    layout: KeyBarLayout,
    onChange: (KeyBarLayout) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    var picked by remember { mutableIntStateOf(0) }
    var confirmReset by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    val page = picked.coerceIn(0, layout.pages.lastIndex)
    val keys = layout.pages[page]

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            text = { Text(stringResource(R.string.key_bar_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    picked = 0
                    onReset()
                }) { Text(stringResource(R.string.key_bar_reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.button_cancel)) } },
        )
    }
    if (customOpen) {
        CustomKeyDialog(
            onAdd = { id ->
                customOpen = false
                onChange(layout.add(page, id))
            },
            onDismiss = { customOpen = false },
        )
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.key_bar_edit)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.button_back))
                        }
                    },
                    actions = { TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.key_bar_reset)) } },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    layout.pages.forEachIndexed { i, _ ->
                        FilterChip(
                            selected = i == page,
                            onClick = { picked = i },
                            label = { Text(stringResource(R.string.key_bar_page, i + 1)) },
                        )
                    }
                    AssistChip(
                        onClick = {
                            onChange(layout.addPage())
                            picked = layout.pages.size.coerceAtMost(KeyBarLayout.MAX_PAGES - 1)
                        },
                        label = { Text(stringResource(R.string.key_bar_add_page)) },
                        enabled = layout.pages.size < KeyBarLayout.MAX_PAGES,
                    )
                }
                if (keys.isEmpty()) Text(stringResource(R.string.key_bar_empty_page), style = MaterialTheme.typography.bodyMedium)
                keys.forEachIndexed { index, id ->
                    val label = KeyBarCatalog.byId(id)?.label ?: id
                    Row(
                        modifier = Modifier.fillMaxWidth().testTag("key_bar_row_$index"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { onChange(layout.move(page, index, index - 1)) }, enabled = index > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.key_bar_move_up, label))
                        }
                        IconButton(onClick = { onChange(layout.move(page, index, index + 1)) }, enabled = index < keys.lastIndex) {
                            Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.key_bar_move_down, label))
                        }
                        IconButton(onClick = { onChange(layout.remove(page, index)) }) {
                            Icon(Icons.Filled.Close, stringResource(R.string.key_bar_remove, label))
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(stringResource(R.string.key_bar_add_key), style = MaterialTheme.typography.labelLarge)
                if (keys.size >= KeyBarLayout.MAX_KEYS_PER_PAGE) {
                    Text(stringResource(R.string.key_bar_page_full), style = MaterialTheme.typography.bodyMedium)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyBarCatalog.all.forEach { key ->
                        AssistChip(
                            onClick = { onChange(layout.add(page, key.id)) },
                            label = { Text(key.label) },
                            enabled = keys.size < KeyBarLayout.MAX_KEYS_PER_PAGE,
                            modifier = Modifier.testTag("key_bar_add_${key.id}"),
                        )
                    }
                }
                AssistChip(
                    onClick = { customOpen = true },
                    label = { Text(stringResource(R.string.key_bar_custom_key)) },
                    enabled = keys.size < KeyBarLayout.MAX_KEYS_PER_PAGE,
                    modifier = Modifier.testTag(KEY_BAR_CUSTOM_KEY_TAG),
                )
                if (layout.pages.size > 1) {
                    TextButton(onClick = {
                        onChange(layout.removePage(page))
                        picked = (page - 1).coerceAtLeast(0)
                    }) { Text(stringResource(R.string.key_bar_remove_page, page + 1)) }
                }
            }
        }
    }
}

internal const val KEY_BAR_CUSTOM_KEY_TAG = "key_bar_custom_key"
internal const val KEY_BAR_CUSTOM_TEXT_TAG = "key_bar_custom_text"
internal const val KEY_BAR_CUSTOM_ADD_TAG = "key_bar_custom_add"

/** A key that types the text he writes (like /clear), and presses Enter after it when he asks. */
@Composable
private fun CustomKeyDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var enter by remember { mutableStateOf(false) }
    val id = KeyBarCatalog.textKeyId(text, enter)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.key_bar_custom_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.key_bar_custom_key_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(KeyBarCatalog.MAX_TEXT_KEY) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(KEY_BAR_CUSTOM_TEXT_TAG),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(value = enter, role = Role.Checkbox, onValueChange = { enter = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = enter, onCheckedChange = null)
                    Text(stringResource(R.string.key_bar_custom_key_enter), modifier = Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { id?.let(onAdd) }, enabled = id != null, modifier = Modifier.testTag(KEY_BAR_CUSTOM_ADD_TAG)) {
                Text(stringResource(R.string.button_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.button_cancel)) } },
    )
}
