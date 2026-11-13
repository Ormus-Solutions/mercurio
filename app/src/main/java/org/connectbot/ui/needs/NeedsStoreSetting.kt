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

package org.connectbot.ui.needs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions

/** Test tag of the "Questions store" row. */
const val NEEDS_STORE_TAG = "needs_store"

/** Test tag of the address field in the "Questions store" editor. */
const val NEEDS_STORE_FIELD_TAG = "needs_store_url"

/**
 * Settings, under Home: the address of the questions store the Unblock view reads. Blank by
 * default: with none, the swipe on the home opens nothing and nothing is called.
 */
@Composable
fun NeedsStoreSetting(modifier: Modifier = Modifier, viewModel: NeedsViewModel = hiltViewModel()) {
    val address by viewModel.address.collectAsState()
    var editing by remember { mutableStateOf(false) }
    val usage = LocalUsageLog.current

    Column(modifier = modifier) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.pref_needs_store_title)) },
            supportingContent = { Text(address.ifBlank { stringResource(R.string.pref_needs_store_none) }) },
            modifier = Modifier
                .clickable { editing = true }
                .testTag(NEEDS_STORE_TAG),
        )
        HorizontalDivider()
    }

    if (editing) {
        var text by remember { mutableStateOf(address) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.pref_needs_store_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.pref_needs_store_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(NEEDS_STORE_FIELD_TAG),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    usage.log(UsageActions.NEEDS_STORE_SET)
                    viewModel.setAddress(text)
                }) { Text(stringResource(R.string.button_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.button_cancel)) }
            },
        )
    }
}
