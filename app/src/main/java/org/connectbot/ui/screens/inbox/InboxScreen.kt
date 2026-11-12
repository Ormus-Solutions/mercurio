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

package org.connectbot.ui.screens.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.connectbot.R
import org.connectbot.ui.LocalTerminalManager
import org.connectbot.ui.components.OrmusGroup
import org.connectbot.ui.components.OrmusKey
import org.connectbot.ui.components.WORD_KEY_PADDING
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.OrmusTokens
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.herd.Inbox
import solutions.ormus.logos.herd.InboxRow
import solutions.ormus.logos.herd.RowAction

/** Test tag of the search field. */
const val INBOX_SEARCH_TAG = "inbox_search"

/** Test tag of every agent row. */
const val INBOX_ROW_TAG = "inbox_row"

/** Test tag of a section's Show more / Show fewer line. */
const val INBOX_FOLD_TAG = "inbox_fold"

/** A row is about this tall, like the Herdr app's (measured 161 px at 2.625 px per dp). */
private val ROW_HEIGHT = 61.dp

/** The letter tile, like the Herdr app's (89 px). */
private val TILE_SIZE = 34.dp

/** How often the rows' "in this state for" times move on. */
private const val AGE_TICK_MS = 30_000L

/** The identity accents a space group picks from, in the order [Inbox.accentIndex] counts. */
private val ACCENTS = listOf(
    OrmusTokens.Accents.Rose,
    OrmusTokens.Accents.Teal,
    OrmusTokens.Accents.Leaf,
    OrmusTokens.Accents.Ember,
    OrmusTokens.Accents.Sky,
    OrmusTokens.Accents.Iris,
)

/**
 * The Inbox, read from the open connections. A tap on a row calls [onOpen] with it; the route
 * decides what to open.
 */
@Composable
fun InboxScreen(
    onOpen: (InboxRow) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: InboxViewModel = hiltViewModel(),
) {
    val terminalManager = LocalTerminalManager.current
    LaunchedEffect(terminalManager) { terminalManager?.let { viewModel.setTerminalManager(it) } }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner, terminalManager) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }
    val state by viewModel.state.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(AGE_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    InboxContent(rows = state.rows, notes = state.notes, loading = !state.loaded, now = now, onOpen = onOpen, onNavigateBack = onNavigateBack, onAction = viewModel::act, modifier = modifier)
}

/** The Inbox on [rows]: a search field, then one group per state, each folded after three rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxContent(
    rows: List<InboxRow>,
    notes: List<String>,
    now: Long,
    onOpen: (InboxRow) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAction: (InboxRow, RowAction) -> Unit = { _, _ -> },
    loading: Boolean = false,
) {
    val usage = LocalUsageLog.current
    // Each log is the id of the action alone; the row is passed on untouched.
    val openRow: (InboxRow) -> Unit = {
        usage.log(UsageActions.INBOX_OPEN)
        onOpen(it)
    }
    val actOnRow: (InboxRow, RowAction) -> Unit = { row, action ->
        usage.log(
            when (action) {
                RowAction.MARK_READ -> UsageActions.INBOX_MARK_READ
                RowAction.PIN -> UsageActions.INBOX_PIN
                RowAction.UNPIN -> UsageActions.INBOX_UNPIN
                RowAction.INTERRUPT -> UsageActions.INBOX_ESC
            },
        )
        onAction(row, action)
    }
    // The row a swipe left is asking about: Esc is sent only after the person confirms.
    var interrupting by remember { mutableStateOf<InboxRow?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf(emptyList<String>()) }
    // Rows whose helpers were flipped from their default: shut, or open while a helper needs the user.
    var flipped by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val sections = remember(rows, query, expanded) { Inbox.sections(rows, query, expanded.mapTo(HashSet()) { Inbox.Section.valueOf(it) }) }

    interrupting?.let { target ->
        AlertDialog(
            onDismissRequest = { interrupting = null },
            title = { Text(stringResource(R.string.inbox_esc_title, target.agent.space)) },
            text = { Text(stringResource(R.string.inbox_esc_body)) },
            confirmButton = {
                TextButton(onClick = {
                    interrupting = null
                    actOnRow(target, RowAction.INTERRUPT)
                }) { Text(stringResource(R.string.inbox_esc_send)) }
            },
            dismissButton = { TextButton(onClick = { interrupting = null }) { Text(stringResource(R.string.button_cancel)) } },
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.inbox_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.button_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(Ormus.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Ormus.spacing.lg),
        ) {
            item(key = "search") {
                SearchField(query = query, onChange = {
                    // One id when a search starts, not one per key.
                    if (query.isEmpty() && it.isNotEmpty()) usage.log(UsageActions.INBOX_SEARCH)
                    query = it
                })
            }
            // No rows: loading says so, a failure shows as its own line below, and only a read that
            // went through with no agents says there are none.
            val emptyText = when {
                rows.isNotEmpty() -> R.string.inbox_no_match
                loading && notes.isEmpty() -> R.string.inbox_loading
                notes.isNotEmpty() -> null
                else -> R.string.inbox_empty
            }
            if (sections.isEmpty() && emptyText != null) {
                item(key = "empty") {
                    Text(
                        text = stringResource(emptyText),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Ormus.spacing.xs),
                    )
                }
            }
            items(sections, key = { it.section.name }) { section ->
                val open = section.section.name in expanded
                OrmusGroup(label = stringResource(label(section.section))) {
                    section.shown.forEach { shown ->
                        val helpersOpen = shown.helpersNeedYou != (shown.key in flipped)
                        row {
                            key(shown.key) {
                                InboxRowItem(shown, now, openRow, actOnRow, helpersOpen = helpersOpen, onToggleHelpers = {
                                    usage.log(UsageActions.INBOX_HELPERS)
                                    flipped = if (shown.key in flipped) flipped - shown.key else flipped + shown.key
                                }) { interrupting = it }
                            }
                        }
                        if (helpersOpen) {
                            shown.helperRows.forEach { helper -> row { key(helper.key) { InboxRowItem(helper, now, openRow, actOnRow, indent = true) { interrupting = it } } } }
                        }
                    }
                    if (section.hidden > 0) {
                        row {
                            FoldLine(stringResource(R.string.inbox_show_more, section.hidden)) {
                                usage.log(UsageActions.INBOX_FOLD)
                                expanded = expanded + section.section.name
                            }
                        }
                    } else if (open && section.canFold) {
                        row {
                            FoldLine(stringResource(R.string.inbox_show_fewer)) {
                                usage.log(UsageActions.INBOX_FOLD)
                                expanded = expanded - section.section.name
                            }
                        }
                    }
                }
            }
            items(notes, key = { "note:$it" }) { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Ormus.spacing.xs),
                )
            }
        }
    }
}

private fun label(section: Inbox.Section): Int = when (section) {
    Inbox.Section.NEEDS_YOU -> R.string.inbox_section_needs_you
    Inbox.Section.DONE -> R.string.inbox_section_done
    Inbox.Section.WORKING -> R.string.inbox_section_working
    Inbox.Section.IDLE -> R.string.inbox_section_idle
    Inbox.Section.UNKNOWN -> R.string.inbox_section_unknown
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.inbox_search_hint)) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(OrmusTokens.RADIUS_PILL_DP.dp),
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(INBOX_SEARCH_TAG),
    )
}

/** Test tag of the dot on an unread row. */
const val INBOX_UNREAD_TAG = "inbox_unread"

/** Test tag of the pin on a pinned row. */
const val INBOX_PINNED_TAG = "inbox_pinned"

/** Test tag of the helper count on a row that has helpers. */
const val INBOX_HELPERS_TAG = "inbox_helpers"

/** Test tag of the dot on a helper count when a folded helper is unread. */
const val INBOX_HELPERS_UNREAD_TAG = "inbox_helpers_unread"

private val INDENT = 24.dp

private val DOT_SIZE = 8.dp

/**
 * One row. A herdr-core row swipes: right marks it read or toggles its pin ([Inbox.rightSwipe]),
 * left asks [onInterrupt] to confirm an Esc. The swipe always springs back; the row moves when the
 * next read says so.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxRowItem(
    row: InboxRow,
    now: Long,
    onOpen: (InboxRow) -> Unit,
    onAction: (InboxRow, RowAction) -> Unit,
    indent: Boolean = false,
    helpersOpen: Boolean = false,
    onToggleHelpers: () -> Unit = {},
    onInterrupt: (InboxRow) -> Unit,
) {
    val right = Inbox.rightSwipe(row.agent)
    val left = Inbox.canInterrupt(row.agent)
    if (right == null && !left) {
        InboxRowBody(row, now, onOpen, indent, onToggleHelpers = onToggleHelpers)
        return
    }
    val currentRow by rememberUpdatedState(row)
    val currentAction by rememberUpdatedState(onAction)
    val currentInterrupt by rememberUpdatedState(onInterrupt)
    val state = rememberSwipeToDismissBoxState()
    // The swipe is read once, when it lands; then the row springs back. (confirmValueChange is asked
    // many times during one drag, which wrote the same label many times.)
    LaunchedEffect(state.currentValue) {
        when (state.currentValue) {
            SwipeToDismissBoxValue.StartToEnd -> Inbox.rightSwipe(currentRow.agent)?.let { currentAction(currentRow, it) }
            SwipeToDismissBoxValue.EndToStart -> currentInterrupt(currentRow)
            SwipeToDismissBoxValue.Settled -> return@LaunchedEffect
        }
        state.reset()
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = right != null,
        enableDismissFromEndToStart = left,
        backgroundContent = {
            val toRight = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val toLeft = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            if (toRight || toLeft) {
                Box(
                    contentAlignment = if (toRight) Alignment.CenterStart else Alignment.CenterEnd,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = Ormus.spacing.lg),
                ) {
                    val label = when (if (toRight) right else RowAction.INTERRUPT) {
                        RowAction.MARK_READ -> R.string.inbox_swipe_read
                        RowAction.PIN -> R.string.inbox_swipe_pin
                        RowAction.UNPIN -> R.string.inbox_swipe_unpin
                        else -> R.string.inbox_swipe_esc
                    }
                    Text(stringResource(label), color = MaterialTheme.colorScheme.onSecondaryContainer, fontWeight = FontWeight.SemiBold)
                }
            }
        },
    ) {
        InboxRowBody(row, now, onOpen, indent, onToggleHelpers = onToggleHelpers, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer))
    }
}

@Composable
private fun InboxRowBody(row: InboxRow, now: Long, onOpen: (InboxRow) -> Unit, indent: Boolean, onToggleHelpers: () -> Unit, modifier: Modifier = Modifier) {
    val space = row.agent.space
    val helpers = if (row.helpers > 0) pluralStringResource(R.plurals.inbox_helpers, row.helpers, row.helpers) else null
    val helpersText = helpers?.let { if (row.helpersNeedYou) stringResource(R.string.inbox_helpers_need_you, it) else it }
    var description = stringResource(R.string.inbox_open, space, row.host)
    if (helpersText != null) description = stringResource(R.string.inbox_open_with, description, helpersText)
    if (row.helpersUnread) description = stringResource(R.string.inbox_open_with, description, stringResource(R.string.inbox_unread))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ormus.spacing.md),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ROW_HEIGHT)
            .testTag(INBOX_ROW_TAG)
            .clickable { onOpen(row) }
            .semantics {
                contentDescription = description
                // The count is a control of its own; a merged row hides it from a screen reader, so offer it as an action too.
                if (helpersText != null) {
                    customActions = listOf(
                        CustomAccessibilityAction(helpersText) {
                            onToggleHelpers()
                            true
                        },
                    )
                }
            }
            .padding(start = Ormus.spacing.lg + if (indent) INDENT else 0.dp, end = Ormus.spacing.lg, top = Ormus.spacing.sm, bottom = Ormus.spacing.sm),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(TILE_SIZE)
                .clip(OrmusCornerShape)
                .background(ACCENTS[Inbox.accentIndex(Inbox.group(space))]),
        ) {
            Text(Inbox.letter(space), color = OrmusTokens.Deep, fontWeight = FontWeight.Bold)
        }
        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Ormus.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            if (row.agent.unread == true) {
                val unread = stringResource(R.string.inbox_unread)
                Box(
                    modifier = Modifier
                        .size(DOT_SIZE)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .testTag(INBOX_UNREAD_TAG)
                        .semantics { contentDescription = unread },
                )
            }
            Text(
                text = space,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = row.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.agent.pinned) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = stringResource(R.string.inbox_pinned),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(16.dp)
                    .testTag(INBOX_PINNED_TAG),
            )
        }
        if (helpersText != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Ormus.spacing.xs),
                modifier = Modifier
                    .testTag(INBOX_HELPERS_TAG)
                    .clip(OrmusCornerShape)
                    .clickable(onClick = onToggleHelpers)
                    .padding(horizontal = Ormus.spacing.sm, vertical = Ormus.spacing.xs),
            ) {
                if (row.helpersUnread) {
                    val unread = stringResource(R.string.inbox_unread)
                    Box(
                        modifier = Modifier
                            .size(DOT_SIZE)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .testTag(INBOX_HELPERS_UNREAD_TAG)
                            .semantics { contentDescription = unread },
                    )
                }
                Text(
                    text = helpersText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.helpersNeedYou) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Inbox.age(row, now)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FoldLine(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(INBOX_FOLD_TAG)
            .clickable(onClick = onClick)
            .padding(horizontal = Ormus.spacing.lg, vertical = Ormus.spacing.md),
    )
}

/** Test tag of the home's Inbox key. */
const val INBOX_ENTRY_TAG = "inbox_entry"

/** The home's way into the Inbox: one key under the Inbox label. */
@Composable
fun InboxEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OrmusGroup(label = stringResource(R.string.inbox_title), modifier = modifier) {
        row {
            Row(modifier = Modifier.padding(Ormus.spacing.md)) {
                OrmusKey(
                    label = stringResource(R.string.inbox_title),
                    onClick = onClick,
                    contentPadding = WORD_KEY_PADDING,
                    modifier = Modifier.testTag(INBOX_ENTRY_TAG),
                )
            }
        }
    }
}
