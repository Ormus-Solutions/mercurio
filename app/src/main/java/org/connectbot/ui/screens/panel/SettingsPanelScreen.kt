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

package org.connectbot.ui.screens.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.connectbot.R
import org.connectbot.ui.components.KeyBarSettingsSection
import org.connectbot.ui.components.KeyBarStore
import org.connectbot.ui.components.OrmusGroup
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.Planet
import org.connectbot.ui.theme.rememberPlanet
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.util.PreferenceConstants
import solutions.ormus.logos.home.HomeStart

/**
 * The settings panel, opened from the gear on the console's top bar: the theme (one per
 * planet), the settings worth changing from inside a session, and a way on to all of them
 * ([onNavigateToAllSettings]).
 */
@Composable
fun SettingsPanelScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAllSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember(context) { PreferenceManager.getDefaultSharedPreferences(context) }
    val usage = LocalUsageLog.current
    var enterNewLine by remember { mutableStateOf(prefs.getBoolean(PreferenceConstants.ENTER_NEW_LINE, false)) }
    var inbox by remember { mutableStateOf(HomeStart.inboxOn(prefs)) }
    var core by remember { mutableStateOf(HomeStart.coreOn(prefs)) }
    val planet = rememberPlanet()

    SettingsPanelContent(
        planet = planet,
        onPlanetChange = { picked ->
            usage.log(UsageActions.PANEL_PLANET)
            prefs.edit { putString(PreferenceConstants.PLANET, picked.key) }
        },
        enterNewLine = enterNewLine,
        onEnterNewLineChange = { on ->
            usage.log(UsageActions.PANEL_ENTER_NEW_LINE)
            enterNewLine = on
            prefs.edit { putBoolean(PreferenceConstants.ENTER_NEW_LINE, on) }
        },
        inbox = inbox,
        onInboxChange = { on ->
            inbox = on
            prefs.edit { putBoolean(PreferenceConstants.INBOX, on) }
        },
        core = core,
        onCoreChange = { on ->
            core = on
            prefs.edit { putBoolean(PreferenceConstants.HERDR_CORE, on) }
        },
        onNavigateBack = onNavigateBack,
        onAllSettings = {
            usage.log(UsageActions.PANEL_ALL_SETTINGS)
            onNavigateToAllSettings()
        },
        keyBar = { KeyBarSettingsSection(remember(prefs) { KeyBarStore(prefs) }) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanelContent(
    planet: Planet,
    onPlanetChange: (Planet) -> Unit,
    enterNewLine: Boolean,
    onEnterNewLineChange: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    onAllSettings: () -> Unit,
    modifier: Modifier = Modifier,
    keyBar: @Composable () -> Unit = {},
    inbox: Boolean = false,
    onInboxChange: ((Boolean) -> Unit)? = null,
    core: Boolean = false,
    onCoreChange: ((Boolean) -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.button_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Ormus.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Ormus.spacing.lg),
        ) {
            // Muted section labels: the picked planet and the switch carry the accent.
            OrmusGroup(label = stringResource(R.string.settings_panel_theme), modifier = Modifier.testTag(THEME_GROUP_TAG)) {
                row { PlanetPicker(picked = planet, onPick = onPlanetChange, modifier = Modifier.padding(Ormus.spacing.md)) }
            }
            OrmusGroup(label = stringResource(R.string.settings_panel_compose_bar), modifier = Modifier.testTag(COMPOSE_GROUP_TAG)) {
                row {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.settings_panel_enter_new_line)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (enterNewLine) R.string.settings_panel_enter_new_line_on else R.string.settings_panel_enter_new_line_off,
                                ),
                            )
                        },
                        trailingContent = { Switch(checked = enterNewLine, onCheckedChange = null) },
                        modifier = Modifier
                            .testTag(ENTER_NEW_LINE_TAG)
                            .toggleable(value = enterNewLine, role = Role.Switch, onValueChange = onEnterNewLineChange),
                    )
                }
                row { keyBar() }
            }
            if (onInboxChange != null && onCoreChange != null) {
                OrmusGroup(label = stringResource(R.string.settings_panel_inbox), modifier = Modifier.testTag(INBOX_GROUP_TAG)) {
                    row { SwitchRow(R.string.settings_panel_inbox_switch, R.string.settings_panel_inbox_on, R.string.settings_panel_inbox_off, inbox, INBOX_SWITCH_TAG, onInboxChange) }
                    row { SwitchRow(R.string.settings_panel_core_switch, R.string.settings_panel_core_on, R.string.settings_panel_core_off, core, CORE_SWITCH_TAG, onCoreChange) }
                }
            }
            OrmusGroup(label = stringResource(R.string.settings_panel_more), modifier = Modifier.testTag(MORE_GROUP_TAG)) {
                row {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(R.string.settings_panel_all_settings)) },
                        supportingContent = { Text(stringResource(R.string.settings_panel_all_settings_summary)) },
                        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                        modifier = Modifier
                            .testTag(ALL_SETTINGS_TAG)
                            .clickable(onClick = onAllSettings),
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: Int, on: Int, off: Int, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(if (checked) on else off)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier
            .testTag(tag)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
    )
}

/**
 * One tile per planet, each drawn in its own palette so the pick is a preview: the sign in
 * the planet's accent on its ground, the name in its ink. The picked tile has an accent border.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanetPicker(picked: Planet, onPick: (Planet) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Planet.entries.forEach { planet ->
            val p = planet.palette
            val selected = planet == picked
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .size(PLANET_TILE)
                    .testTag(planetTag(planet))
                    .clip(OrmusCornerShape)
                    .background(p.deep)
                    .border(if (selected) 2.dp else 1.dp, if (selected) p.gold else p.hairline, OrmusCornerShape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onPick(planet) }),
            ) {
                Text(planet.sign, color = p.gold, fontSize = 24.sp)
                Text(stringResource(planet.label), color = p.ink, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
    }
}

private val PLANET_TILE = 76.dp

internal fun planetTag(planet: Planet) = "panel_planet_${planet.key}"

internal const val ENTER_NEW_LINE_TAG = "panel_enter_new_line"
internal const val INBOX_SWITCH_TAG = "panel_inbox_switch"
internal const val CORE_SWITCH_TAG = "panel_core_switch"
internal const val INBOX_GROUP_TAG = "panel_inbox_group"
internal const val ALL_SETTINGS_TAG = "panel_all_settings"

internal const val THEME_GROUP_TAG = "panel_theme_group"
internal const val COMPOSE_GROUP_TAG = "panel_compose_group"
internal const val MORE_GROUP_TAG = "panel_more_group"
