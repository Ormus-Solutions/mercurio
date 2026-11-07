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

package org.connectbot.ui.home

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import org.connectbot.R
import org.connectbot.ui.components.MonoLabel
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.home.HomeState

/** Test tag of the "Home feed" row. */
const val HOME_FEED_TAG = "home_feed"

/** Test tag of the URL field in the "Home feed" editor. */
const val HOME_FEED_FIELD_TAG = "home_feed_url"

/**
 * Settings section "Home": the URL of the sessions feed the home reads its workboards from,
 * with how the last read went under it. Empty by default: a phone with no feed reads nothing.
 */
@Composable
fun HomeFeedSetting(
    modifier: Modifier = Modifier,
    viewModel: HomeBoardsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    HomeFeedSettingContent(state = state, onSave = viewModel::setUrl, modifier = modifier)
}

@Composable
fun HomeFeedSettingContent(
    state: HomeState,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    val usage = LocalUsageLog.current

    Column(modifier = modifier) {
        // Same lab-log section label as the other Settings categories.
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        MonoLabel(
            text = stringResource(R.string.pref_home_category),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.pref_home_feed_title)) },
            supportingContent = { Text(homeFeedSummary(state)) },
            modifier = Modifier
                .clickable { editing = true }
                .testTag(HOME_FEED_TAG),
        )
        HorizontalDivider()
    }

    if (editing) {
        var text by remember { mutableStateOf(state.url) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.pref_home_feed_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.pref_home_feed_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(HOME_FEED_FIELD_TAG),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    usage.log(UsageActions.HOME_FEED_SET)
                    onSave(text)
                }) { Text(stringResource(R.string.button_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.button_cancel)) }
            },
        )
    }
}

/** The row's second line: no feed, or the URL and how its last read went. */
@Composable
internal fun homeFeedSummary(state: HomeState): String {
    if (state.url.isBlank()) return stringResource(R.string.pref_home_feed_none)
    val boards = state.boards
    val read = when {
        state.error != null -> stringResource(R.string.pref_home_feed_failed, state.error)

        boards != null -> {
            val ago = DateUtils.getRelativeTimeSpanString(state.fetchedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            stringResource(R.string.pref_home_feed_read, boards.boards.size, ago)
        }

        else -> stringResource(R.string.pref_home_feed_pending)
    }
    return state.url + "\n" + read
}
