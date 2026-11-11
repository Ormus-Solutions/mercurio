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

package org.connectbot.ui.screens.spaces

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import org.connectbot.R
import org.connectbot.ui.LocalTerminalManager
import org.connectbot.ui.components.OrmusGroup
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.OrmusTokens
import solutions.ormus.logos.herd.CoreSpaces
import solutions.ormus.logos.herd.SpaceColors

/** Test tag on every space's row. */
const val SPACE_ROW_TAG = "space_row"

/** Test tag on every tab's box. */
const val TAB_BOX_TAG = "tab_box"

/** A tab's box, in the Herdr app's proportion and big enough to tap. */
private val BOX_WIDTH = 56.dp
private val BOX_HEIGHT = 38.dp
private val TILE_SIZE = 34.dp

/** The identity accents a space group picks from, in the order [SpaceColors.accentIndex] counts. */
private val ACCENTS = listOf(
    OrmusTokens.Accents.Rose,
    OrmusTokens.Accents.Teal,
    OrmusTokens.Accents.Leaf,
    OrmusTokens.Accents.Ember,
    OrmusTokens.Accents.Sky,
    OrmusTokens.Accents.Iris,
)

/**
 * The Spaces list, read from the open connections while the herdr-core setting is on. A tap on a
 * tab calls [onOpenTab] with the machine and the core's id of the tab's first terminal; the route
 * decides how to open it.
 */
@Composable
fun SpacesScreen(
    onOpenTab: (machine: String, terminal: String) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpacesViewModel = hiltViewModel(),
) {
    val terminalManager = LocalTerminalManager.current
    LaunchedEffect(terminalManager) { terminalManager?.let { viewModel.setTerminalManager(it) } }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner, terminalManager) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }
    val sections by viewModel.sections.collectAsState()
    SpacesContent(sections = sections, onOpenTab = onOpenTab, onNavigateBack = onNavigateBack, modifier = modifier)
}

/** The Spaces list on [sections]: a group per machine, a row per space, a box per tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpacesContent(
    sections: List<SpacesSection>,
    onOpenTab: (machine: String, terminal: String) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateBack: (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.spaces_title)) },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.button_back))
                        }
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
            items(sections, key = { it.hostId }) { section -> MachineSpaces(section, onOpenTab) }
        }
    }
}

@Composable
private fun MachineSpaces(section: SpacesSection, onOpenTab: (String, String) -> Unit) {
    OrmusGroup(label = section.machine) {
        when (val result = section.result) {
            is CoreSpaces.Result.Spaces -> if (result.spaces.isEmpty()) {
                row { Note(stringResource(R.string.spaces_none)) }
            } else {
                result.spaces.forEach { space -> row(key = space.id) { SpaceRow(section.machine, space, onOpenTab) } }
            }

            is CoreSpaces.Result.Unavailable -> row { Note(result.reason) }

            CoreSpaces.Result.Off -> Unit
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(Ormus.spacing.lg),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpaceRow(machine: String, space: CoreSpaces.CoreSpace, onOpenTab: (String, String) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Ormus.spacing.md),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SPACE_ROW_TAG)
            .padding(horizontal = Ormus.spacing.lg, vertical = Ormus.spacing.md),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(TILE_SIZE)
                .clip(OrmusCornerShape)
                .background(ACCENTS[SpaceColors.accentIndex(SpaceColors.group(space.name))]),
        ) {
            Text(SpaceColors.letter(space.name), color = OrmusTokens.Deep, fontWeight = FontWeight.Bold)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Ormus.spacing.sm)) {
            Text(
                text = space.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Ormus.spacing.sm), verticalArrangement = Arrangement.spacedBy(Ormus.spacing.sm)) {
                space.tabs.forEach { tab -> TabBox(machine, space, tab, onOpenTab) }
            }
        }
    }
}

/**
 * One tab as a small outlined box split into its panes by their weights. Drawn from the layout
 * alone: no terminal text is read for it.
 */
@Composable
private fun TabBox(machine: String, space: CoreSpaces.CoreSpace, tab: CoreSpaces.CoreTab, onOpenTab: (String, String) -> Unit) {
    val first = CoreSpaces.firstTerminal(tab)
    val label = stringResource(R.string.spaces_open_tab, tab.name.ifBlank { tab.id }, space.name)
    val line = MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(4.dp)
    val rects = CoreSpaces.boxes(tab)
    Canvas(
        modifier = Modifier
            .size(BOX_WIDTH, BOX_HEIGHT)
            .testTag(TAB_BOX_TAG)
            .clip(shape)
            .then(if (first != null) Modifier.clickable(role = Role.Button) { onOpenTab(machine, first) } else Modifier)
            .semantics { contentDescription = label },
    ) {
        val inset = 1.dp.toPx()
        rects.forEach { r ->
            drawRoundRect(
                color = line,
                topLeft = Offset(r.x * size.width + inset, r.y * size.height + inset),
                size = Size((r.w * size.width - 2 * inset).coerceAtLeast(0f), (r.h * size.height - 2 * inset).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(3.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}
