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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.ui.components.MonoLabel

/**
 * Settings section: "Machine usernames", one `name=user` line per tailnet machine,
 * so a first tap on that machine in the Machines list connects without asking.
 */
@Composable
fun MachineUsernamesSetting(
    modifier: Modifier = Modifier,
    viewModel: MachinesViewModel = hiltViewModel(),
) {
    var saved by remember { mutableStateOf(viewModel.machineUsernames) }
    var editing by remember { mutableStateOf(false) }
    val lines = saved.lines().map { it.trim() }.filter { it.isNotEmpty() }

    Column(modifier = modifier) {
        // Same lab-log section label as the other Settings categories.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MonoLabel(
            text = stringResource(R.string.pref_machines_category),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.pref_machine_users_title)) },
            supportingContent = {
                Text(if (lines.isEmpty()) stringResource(R.string.pref_machine_users_none) else lines.joinToString(", "))
            },
            modifier = Modifier
                .clickable { editing = true }
                .testTag("machine_usernames"),
        )
        HorizontalDivider()
    }

    if (editing) {
        var text by remember { mutableStateOf(saved) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.pref_machine_users_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 4,
                    placeholder = { Text(stringResource(R.string.pref_machine_users_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    viewModel.machineUsernames = text
                    saved = viewModel.machineUsernames
                }) { Text(stringResource(R.string.button_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.button_cancel)) }
            },
        )
    }
}
