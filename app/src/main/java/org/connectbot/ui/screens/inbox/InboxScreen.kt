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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.ripple
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.connectbot.R
import org.connectbot.ui.LocalTerminalManager
import org.connectbot.ui.components.KeyTone
import org.connectbot.ui.components.MercurioTopBar
import org.connectbot.ui.components.OrmusGroup
import org.connectbot.ui.components.OrmusKey
import org.connectbot.ui.components.WORD_KEY_PADDING
import org.connectbot.ui.needs.NeedsCountViewModel
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.OrmusTokens
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.herd.AgentEngine
import solutions.ormus.logos.herd.Inbox
import solutions.ormus.logos.herd.InboxRow
import solutions.ormus.logos.herd.RowAction

/** Test tag of the search field. */
const val INBOX_SEARCH_TAG = "inbox_search"

/** Test tag of the Unblock key on the Inbox. */
const val INBOX_UNBLOCK_TAG = "inbox_unblock"

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
    modifier: Modifier = Modifier,
    onNavigateToNeeds: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    viewModel: InboxViewModel = hiltViewModel(),
    needsViewModel: NeedsCountViewModel = hiltViewModel(),
) {
    val terminalManager = LocalTerminalManager.current
    LaunchedEffect(terminalManager) { terminalManager?.let { viewModel.setTerminalManager(it) } }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner, terminalManager) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }
    // The Unblock key counts the questions store's open asks while the Inbox shows.
    LaunchedEffect(needsViewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { needsViewModel.watch() }
    }
    val unblock by needsViewModel.count.collectAsState()
    val state by viewModel.state.collectAsState()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(AGE_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    InboxContent(rows = state.rows, notes = state.notes + listOfNotNull(stringResource(R.string.inbox_no_herdr_machine).takeIf { state.noHerdrMachine }), loading = !state.loaded, now = now, onOpen = onOpen, onAction = viewModel::act, unblock = unblock, onUnblock = onNavigateToNeeds, onSettings = onNavigateToSettings, modifier = modifier)
}

/** The Inbox on [rows]: a search field, then one group per state, each folded after three rows. */
@Composable
fun InboxContent(
    rows: List<InboxRow>,
    notes: List<String>,
    now: Long,
    onOpen: (InboxRow) -> Unit,
    modifier: Modifier = Modifier,
    onAction: (InboxRow, RowAction) -> Unit = { _, _ -> },
    loading: Boolean = false,
    /** The open questions of the questions store, or null with no store address: then there is no Unblock key. */
    unblock: Int? = null,
    onUnblock: () -> Unit = {},
    /** The gear on the shared top bar: open the app's Settings. */
    onSettings: () -> Unit = {},
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
    // The star pins or unpins with the same write as the swipe, and has its own ids: it is a control of its own.
    val starRow: (InboxRow, RowAction) -> Unit = { row, action ->
        usage.log(if (action == RowAction.UNPIN) UsageActions.INBOX_UNSTAR else UsageActions.INBOX_STAR)
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
            // The Inbox icon is selected here: it is the home, and a tap on it does nothing.
            MercurioTopBar(title = stringResource(R.string.inbox_title), homeSelected = true, onHome = {}, onSettings = onSettings)
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(Ormus.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Ormus.spacing.lg),
        ) {
            unblock?.let { count ->
                item(key = "unblock") {
                    OrmusKey(
                        label = stringResource(R.string.inbox_unblock, count),
                        onClick = {
                            usage.log(UsageActions.NEEDS_INBOX_KEY)
                            onUnblock()
                        },
                        tone = if (count > 0) KeyTone.ON else KeyTone.PLAIN,
                        contentPadding = WORD_KEY_PADDING,
                        modifier = Modifier.fillMaxWidth().testTag(INBOX_UNBLOCK_TAG),
                    )
                }
            }
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
                                InboxRowItem(shown, now, openRow, actOnRow, starRow, helpersOpen = helpersOpen, onToggleHelpers = {
                                    usage.log(UsageActions.INBOX_HELPERS)
                                    flipped = if (shown.key in flipped) flipped - shown.key else flipped + shown.key
                                }) { interrupting = it }
                            }
                        }
                        if (helpersOpen) {
                            shown.helperRows.forEach { helper -> row { key(helper.key) { InboxRowItem(helper, now, openRow, actOnRow, starRow, indent = true) { interrupting = it } } } }
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

/** Test tag of the filled star on a pinned row. */
const val INBOX_PINNED_TAG = "inbox_pinned"

/** Test tag of the Orchestrator mark on a row whose terminal other terminals name as their parent. */
const val INBOX_ORCHESTRATOR_TAG = "inbox_orchestrator"

/** Test tag of the star on a herdr-core row. */
const val INBOX_STAR_TAG = "inbox_star"

/** Test tag of the helper count on a row that has helpers. */
const val INBOX_HELPERS_TAG = "inbox_helpers"

/** Test tag of the dot on a helper count when a folded helper is unread. */
const val INBOX_HELPERS_UNREAD_TAG = "inbox_helpers_unread"

private val INDENT = 24.dp

private val DOT_SIZE = 8.dp

/** The touch target of the star: 48 dp square. */
private val STAR_TARGET = 48.dp

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
    onStar: (InboxRow, RowAction) -> Unit,
    indent: Boolean = false,
    helpersOpen: Boolean = false,
    onToggleHelpers: () -> Unit = {},
    onInterrupt: (InboxRow) -> Unit,
) {
    val right = Inbox.rightSwipe(row.agent)
    val left = Inbox.canInterrupt(row.agent)
    if (right == null && !left) {
        InboxRowBody(row, now, onOpen, indent, onToggleHelpers = onToggleHelpers, onStar = onStar)
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
        InboxRowBody(row, now, onOpen, indent, onToggleHelpers = onToggleHelpers, onStar = onStar, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer))
    }
}

@Composable
private fun InboxRowBody(row: InboxRow, now: Long, onOpen: (InboxRow) -> Unit, indent: Boolean, onToggleHelpers: () -> Unit, onStar: (InboxRow, RowAction) -> Unit, modifier: Modifier = Modifier) {
    val space = row.agent.space
    val helpers = if (row.helpers > 0) pluralStringResource(R.plurals.inbox_helpers, row.helpers, row.helpers) else null
    val helpersText = helpers?.let { if (row.helpersNeedYou) stringResource(R.string.inbox_helpers_need_you, it) else it }
    // The mark is words, and a name that already says Orchestrator is not repeated by it; the row's spoken name has either once.
    val mark = stringResource(R.string.inbox_orchestrator)
    val roleName = row.roleName
    val marked = row.orchestrator && roleName?.contains(mark, ignoreCase = true) != true
    var description = if (roleName != null) stringResource(R.string.inbox_open_named, space, roleName, row.host) else stringResource(R.string.inbox_open, space, row.host)
    if (marked) description = stringResource(R.string.inbox_open_with, description, mark)
    if (helpersText != null) description = stringResource(R.string.inbox_open_with, description, helpersText)
    if (row.helpersUnread) description = stringResource(R.string.inbox_open_with, description, stringResource(R.string.inbox_unread))
    // The row says it is pinned, as the 16 dp pin icon made it say before the star replaced the icon.
    if (row.agent.pinned) description = stringResource(R.string.inbox_open_with, description, stringResource(R.string.inbox_pinned))
    // A herdr-core row has a star: a tap pins it, or unpins it. A TUI row cannot be pinned and has none. Its label names the role
    // when the row has one, so two terminals of one space do not get the same words.
    val starAction = Inbox.pinAction(row.agent)
    val starLabel = starAction?.let { action ->
        val unpin = action == RowAction.UNPIN
        when {
            roleName == null -> stringResource(if (unpin) R.string.inbox_star_unpin else R.string.inbox_star_pin, space)
            else -> stringResource(if (unpin) R.string.inbox_star_unpin_named else R.string.inbox_star_pin_named, space, roleName)
        }
    }
    val starState = stringResource(if (row.agent.pinned) R.string.inbox_pinned else R.string.inbox_not_pinned)
    val age = Inbox.age(row, now)
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
                // The count is a control of its own that a merged row hides from a screen reader, so it is an action too. The star is a node of
                // its own; its action here is a second way to the same write, from the actions menu of the screen reader.
                val actions = buildList {
                    if (helpersText != null) {
                        add(
                            CustomAccessibilityAction(helpersText) {
                                onToggleHelpers()
                                true
                            },
                        )
                    }
                    if (starAction != null && starLabel != null) {
                        add(
                            CustomAccessibilityAction(starLabel) {
                                onStar(row, starAction)
                                true
                            },
                        )
                    }
                }
                if (actions.isNotEmpty()) customActions = actions
            }
            // The star's 48 dp target takes the row's end padding, so it costs the text 32 dp, not 60.
            .padding(start = Ormus.spacing.lg + if (indent) INDENT else 0.dp, end = if (starAction != null) 0.dp else Ormus.spacing.lg, top = Ormus.spacing.sm, bottom = Ormus.spacing.sm),
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
        // A core row stacks its texts, so each has the width of the column: the space name on the first line, whole; under it the
        // role, then the Orchestrator mark and the helper count (see RowLines). Side by side, a role of 25 characters (the median) took
        // the width first and left the name 60 of 142 dp at 360 dp, and a parent's count and mark took the rest.
        // A plain-herdr row keeps its tab beside the name and its count at the right, as before.
        val stacked = row.agent.engine == AgentEngine.CORE
        Column(modifier = Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Ormus.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
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
                if (!stacked) RowDetail(row.detail)
            }
            if (stacked) {
                RowLines {
                    if (row.detail.isNotBlank()) RowDetail(row.detail, Modifier.layoutId(RowLine.ROLE))
                    if (marked) {
                        Text(
                            text = mark,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            modifier = Modifier
                                .layoutId(RowLine.MARK)
                                .testTag(INBOX_ORCHESTRATOR_TAG),
                        )
                    }
                    if (helpersText != null) HelpersChip(row, helpersText, onToggleHelpers, Modifier.layoutId(RowLine.COUNT))
                }
            }
        }
        if (!stacked && helpersText != null) HelpersChip(row, helpersText, onToggleHelpers)
        if (starAction != null && starLabel != null) {
            RowStar(starLabel, starState, pinned = starAction == RowAction.UNPIN, age = age) { onStar(row, starAction) }
        } else {
            age?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/**
 * The star of a herdr-core row: filled when the row is pinned. [label] says what a tap does and [state] says whether the row is
 * pinned. The touch layer is a [STAR_TARGET] square at least (a tap in its corners pins too: an IconButton's area is a circle).
 * The row's [age] sits under the glyph, in the same cell, so the star costs the text column no more than the age did; at a
 * large font the cell grows with the age, and the age keeps 4 dp from the cell's sides, so it neither touches the glyph nor
 * reaches the row's edge.
 */
@Composable
private fun RowStar(label: String, state: String, pinned: Boolean, age: String?, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.defaultMinSize(minWidth = STAR_TARGET, minHeight = STAR_TARGET)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (pinned) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = null,
                tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (pinned) Modifier.testTag(INBOX_PINNED_TAG) else Modifier,
            )
            age?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
        // On top of the glyph and the age, so a tap anywhere in the cell pins.
        Box(
            modifier = Modifier
                .matchParentSize()
                .testTag(INBOX_STAR_TAG)
                .semantics {
                    contentDescription = label
                    stateDescription = state
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = false, radius = STAR_TARGET / 2),
                    role = Role.Button,
                    onClick = onClick,
                ),
        )
    }
}

/** The helper count of a parent row: a control of its own that folds or opens its helpers. */
@Composable
private fun HelpersChip(row: InboxRow, helpersText: String, onToggleHelpers: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Ormus.spacing.xs),
        modifier = modifier
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
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Which text of a core row [RowLines] places. */
private enum class RowLine { ROLE, MARK, COUNT }

/**
 * The lines under a core row's space name: the role, the Orchestrator mark and the helper count, each marked with its [RowLine].
 * The role has the first line, and the count its end when the role stays whole beside it. Else the count goes to the mark's
 * line, at its end, and to a line of its own when the mark and the count do not fit one line. A role longer than the column
 * takes the first line alone and is cut there. Beside the role, the count of the worst parent row (pin, unread dot, "7 helpers
 * · needs you") left the role 40.5 dp at 360 dp and 0 dp at font scale 1.3. The column must be bounded: the row gives it its weight.
 */
@Composable
private fun RowLines(content: @Composable () -> Unit) {
    val gapDp = Ormus.spacing.sm
    Layout(content = content) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = gapDp.roundToPx()
        fun of(line: RowLine) = measurables.firstOrNull { it.layoutId == line }
        val count = of(RowLine.COUNT)?.measure(Constraints(maxWidth = width))
        val mark = of(RowLine.MARK)?.measure(Constraints(maxWidth = width))
        val roleItem = of(RowLine.ROLE)
        val beside = count != null && (roleItem?.maxIntrinsicWidth(Constraints.Infinity) ?: 0) + gap + count.width <= width
        val role = roleItem?.measure(Constraints(maxWidth = if (beside && count != null) width - gap - count.width else width))
        val lines = mutableListOf<List<Placeable>>()
        listOfNotNull(role, count.takeIf { beside }).takeIf { it.isNotEmpty() }?.let { lines += it }
        val below = listOfNotNull(mark, count.takeIf { !beside })
        if (below.sumOf { it.width } + gap * (below.size - 1) <= width) {
            below.takeIf { it.isNotEmpty() }?.let { lines += it }
        } else {
            below.forEach { lines += listOf(it) }
        }
        val heights = lines.map { line -> line.maxOf { it.height } }
        layout(width, heights.sum()) {
            var y = 0
            lines.forEachIndexed { i, line ->
                line.forEach { it.placeRelative(if (it === count) width - it.width else 0, y + (heights[i] - it.height) / 2) }
                y += heights[i]
            }
        }
    }
}

/** The second text of a row: its role, else its title, name or tab. */
@Composable
private fun RowDetail(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) {
        Spacer(modifier)
        return
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
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
