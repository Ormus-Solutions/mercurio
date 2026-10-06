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

package org.connectbot.ui.screens.reader

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardDoubleArrowDown
import androidx.compose.material.icons.filled.KeyboardDoubleArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.launch
import org.connectbot.R
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.OrmusMono
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.util.TerminalText

private const val DEFAULT_TEXT_SP = 16f
private const val MIN_TEXT_SP = 10f
private const val MAX_TEXT_SP = 30f
private const val TEXT_STEP_SP = 2f

// Generous leading so dense agent output stays readable on a phone.
private const val LINE_HEIGHT_FACTOR = 1.45f

// A page keeps a little of the previous screen in view for context.
private const val PAGE_FRACTION = 0.85f

private val CONTROL_SIZE = 48.dp

/**
 * Full-screen, large-text reader for a session's recent output: the focused
 * Herdr pane's history in Herdr sessions, the rolling transcript elsewhere.
 * Opens scrolled to the bottom; back closes it.
 */
@Composable
fun OutputReaderDialog(
    bridge: TerminalBridge,
    onDismiss: () -> Unit,
    viewModel: OutputReaderViewModel = hiltViewModel(),
) {
    LaunchedEffect(bridge) { viewModel.load(bridge) }
    val state by viewModel.uiState.collectAsState()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        OutputReaderContent(
            state = state,
            fallbackTitle = bridge.host.nickname,
            onClose = onDismiss,
            onRefresh = { viewModel.load(bridge) },
        )
    }
}

@Composable
fun OutputReaderContent(
    state: OutputReaderUiState,
    fallbackTitle: String,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val usage = LocalUsageLog.current
    val listState = rememberLazyListState()
    var textSp by remember { mutableFloatStateOf(DEFAULT_TEXT_SP) }
    val lines = remember(state.text) {
        if (state.text.isEmpty()) emptyList() else state.text.split(TerminalText.LF)
    }

    // Land on the newest output whenever new text arrives.
    LaunchedEffect(lines) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    fun page(direction: Int) {
        scope.launch {
            val viewport = listState.layoutInfo.viewportSize.height
            listState.animateScrollBy(direction * viewport * PAGE_FRACTION)
        }
    }

    // The reading area is the brand's code panel, a step below the page ground.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    usage.log(UsageActions.READER_CLOSE)
                    onClose()
                }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.reader_close),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        state.title(fallbackTitle),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        state.herdr?.paneTitle ?: stringResource(R.string.reader_transcript_subtitle),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = OrmusMono),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = {
                    usage.log(UsageActions.READER_REFRESH)
                    onRefresh()
                }, enabled = !state.loading) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.reader_refresh))
                }
            }

            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            state.herdrError?.let { error ->
                Text(
                    stringResource(R.string.reader_herdr_failed, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = Ormus.extras.status.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (lines.isEmpty()) {
                    if (!state.loading) {
                        Text(
                            stringResource(R.string.reader_empty),
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    SelectionContainer {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxSize().testTag("reader_lines"),
                        ) {
                            items(lines.size) { index ->
                                Text(
                                    lines[index],
                                    fontFamily = OrmusMono,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = textSp.sp,
                                    lineHeight = (textSp * LINE_HEIGHT_FACTOR).sp,
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ReaderControl(Icons.Default.VerticalAlignTop, stringResource(R.string.reader_jump_top), onClick = {
                    usage.log(UsageActions.READER_TOP)
                    scope.launch { listState.scrollToItem(0) }
                })
                ReaderControl(Icons.Default.KeyboardDoubleArrowUp, stringResource(R.string.reader_page_up), onClick = {
                    usage.log(UsageActions.READER_PGUP)
                    page(-1)
                })
                ReaderControl(Icons.Default.KeyboardDoubleArrowDown, stringResource(R.string.reader_page_down), onClick = {
                    usage.log(UsageActions.READER_PGDN)
                    page(1)
                })
                ReaderControl(Icons.Default.VerticalAlignBottom, stringResource(R.string.reader_jump_bottom), onClick = {
                    usage.log(UsageActions.READER_BOTTOM)
                    scope.launch { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
                })
                ReaderControl(Icons.Default.TextDecrease, stringResource(R.string.reader_text_smaller), onClick = {
                    usage.log(UsageActions.READER_TEXT_SMALLER)
                    textSp = (textSp - TEXT_STEP_SP).coerceAtLeast(MIN_TEXT_SP)
                })
                ReaderControl(Icons.Default.TextIncrease, stringResource(R.string.reader_text_larger), onClick = {
                    usage.log(UsageActions.READER_TEXT_LARGER)
                    textSp = (textSp + TEXT_STEP_SP).coerceAtMost(MAX_TEXT_SP)
                })
            }
        }
    }
}

@Composable
private fun ReaderControl(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.size(CONTROL_SIZE),
        shape = OrmusCornerShape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(icon, contentDescription = description)
    }
}
