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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import org.connectbot.R
import org.connectbot.ui.components.KeyTone
import org.connectbot.ui.components.MonoLabel
import org.connectbot.ui.components.OrmusKey
import org.connectbot.ui.components.WORD_KEY_PADDING
import org.connectbot.ui.theme.Ormus
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions

/** Test tags of the Unblock view. */
object NeedsTags {
    const val VIEW = "needs_view"
    const val LATER = "needs_later"
    const val HOME = "needs_home"

    fun option(index: Int): String = "needs_option_$index"
}

/**
 * The Unblock view: the questions store's open asks, one at a time. Read when it shows and every
 * 30 s while it stays visible, never in the background. Back, or a swipe left to right, is home.
 */
@Composable
fun NeedsScreen(onNavigateBack: () -> Unit, viewModel: NeedsViewModel = hiltViewModel()) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }
    val state by viewModel.state.collectAsState()
    NeedsContent(state = state, onChoose = viewModel::choose, onLater = viewModel::later, onHome = onNavigateBack)
}

@Composable
fun NeedsContent(
    state: NeedsUi,
    onChoose: (askId: String, label: String) -> Unit,
    onLater: () -> Unit,
    onHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val usage = LocalUsageLog.current
    val home by rememberUpdatedState(onHome)
    val swipeHome = remember(usage) {
        {
            usage.log(UsageActions.NEEDS_HOME)
            home()
        }
    }
    Scaffold(modifier = modifier.testTag(NeedsTags.VIEW).viewSwipe(onRight = swipeHome)) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Ormus.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Ormus.spacing.md),
        ) {
            val ask = state.pass.current
            val line = when {
                state.notice == NeedsNotice.REFUSED -> stringResource(R.string.needs_refused)
                state.notice == NeedsNotice.CLOSED -> stringResource(R.string.needs_closed)
                state.error != null -> stringResource(R.string.needs_failed, state.error)
                else -> null
            }
            if (line != null) Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                state.notice == NeedsNotice.REFUSED -> HomeKey(onHome)

                ask != null -> {
                    MonoLabel(text = stringResource(R.string.needs_position, state.pass.position, state.pass.total))
                    Text(ask.question, style = MaterialTheme.typography.headlineSmall)
                    ask.context?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (ask.options.isEmpty()) {
                        // No key to tap: only the page can answer it.
                        Text(stringResource(R.string.needs_no_options), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ask.options.forEachIndexed { index, option ->
                        // Gold is the one filled key: the store's first option, its default.
                        OrmusKey(
                            label = option.label,
                            onClick = {
                                usage.log(UsageActions.NEEDS_ANSWER)
                                onChoose(ask.id, option.label)
                            },
                            tone = if (index == 0) KeyTone.ON else KeyTone.PLAIN,
                            enabled = !state.sending && !state.settling,
                            contentPadding = WORD_KEY_PADDING,
                            modifier = Modifier.fillMaxWidth().testTag(NeedsTags.option(index)),
                        )
                        option.detail?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    OrmusKey(
                        label = stringResource(R.string.needs_later),
                        onClick = {
                            usage.log(UsageActions.NEEDS_LATER)
                            onLater()
                        },
                        enabled = !state.sending && !state.settling,
                        contentPadding = WORD_KEY_PADDING,
                        modifier = Modifier.fillMaxWidth().testTag(NeedsTags.LATER),
                    )
                }

                !state.read -> MonoLabel(text = stringResource(R.string.needs_reading))

                else -> {
                    Text(stringResource(R.string.needs_none), style = MaterialTheme.typography.headlineSmall)
                    HomeKey(onHome)
                }
            }
            // Asks that take several answers are not in the pass: one line sends the reader to the page.
            if (state.pass.onPage > 0 && state.notice != NeedsNotice.REFUSED) {
                Text(stringResource(R.string.needs_on_page), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HomeKey(onHome: () -> Unit) {
    val usage = LocalUsageLog.current
    OrmusKey(
        label = stringResource(R.string.needs_home),
        onClick = {
            usage.log(UsageActions.NEEDS_HOME)
            onHome()
        },
        tone = KeyTone.ON,
        contentPadding = WORD_KEY_PADDING,
        modifier = Modifier.fillMaxWidth().testTag(NeedsTags.HOME),
    )
}
