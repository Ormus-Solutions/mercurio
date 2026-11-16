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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions

/** Test tags of the shared top bar and of its parts. */
const val TOP_BAR_TAG = "topbar"
const val TOP_BAR_HOME_TAG = "topbar_home"
const val TOP_BAR_TITLE_TAG = "topbar_title"
const val TOP_BAR_SUBTITLE_TAG = "topbar_subtitle"
const val TOP_BAR_SETTINGS_TAG = "topbar_settings"
const val TOP_BAR_MENU_TAG = "topbar_menu"

/** The one height of the bar, under the status bar, for every screen that uses it. */
val MercurioTopBarHeight = 64.dp

private val TARGET = 48.dp

/**
 * The top bar of Mercurio's screens, after the Herdr app's console bar: the Inbox icon at the left, a centred [title] with a
 * grey [subtitle] under it, an optional [screenSlot] (the console's pane icon), the gear, and an overflow menu only when the
 * screen has entries ([menu]). It is always shown and has one height. There is no back arrow: Back is the system's.
 *
 * The icon at the left is the way home: [onHome] goes to the Inbox while it is on (then [homeIsInbox] is true), else to the
 * hosts. On the Inbox itself the icon is [homeSelected]: it shows as the place you are in and does nothing. The gear calls
 * [onSettings]. Each logs its own usage id. The bar's own icons are 48 dp square targets; a [screenSlot] sizes its own targets (a
 * screen that puts an icon there gives it 48 dp). The title lines never run under an icon (the title takes the width between the
 * icons, whole or ellipsized). The bar pads for the status bar, the side bars and the display cutout as Material's `TopAppBar` does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MercurioTopBar(
    title: String,
    onHome: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    homeIsInbox: Boolean = true,
    homeSelected: Boolean = false,
    screenSlot: (@Composable RowScope.() -> Unit)? = null,
    menu: (@Composable ColumnScope.(dismiss: () -> Unit) -> Unit)? = null,
) {
    val usage = LocalUsageLog.current
    val homeLabel = stringResource(if (homeIsInbox) R.string.inbox_title else R.string.host_list_section)
    val settingsLabel = stringResource(R.string.title_settings)
    val moreLabel = stringResource(R.string.button_more_options)
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth().testTag(TOP_BAR_TAG)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .heightIn(min = MercurioTopBarHeight)
                .padding(horizontal = 4.dp),
        ) {
            IconButton(
                onClick = {
                    // On the Inbox the icon is the place you are in: nothing to do, nothing to log.
                    if (!homeSelected) {
                        usage.log(UsageActions.TOPBAR_HOME)
                        onHome()
                    }
                },
                modifier = Modifier
                    .size(TARGET)
                    .then(if (homeSelected) Modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape) else Modifier)
                    .testTag(TOP_BAR_HOME_TAG)
                    .semantics {
                        contentDescription = homeLabel
                        // Only the selected place says so: a home button on another screen is not "Not selected" to TalkBack.
                        if (homeSelected) selected = true
                    },
            ) {
                Icon(
                    if (homeIsInbox) Icons.Filled.Inbox else Icons.Filled.Dns,
                    contentDescription = null,
                    tint = if (homeSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag(TOP_BAR_TITLE_TAG),
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(TOP_BAR_SUBTITLE_TAG),
                    )
                }
            }
            screenSlot?.invoke(this)
            IconButton(
                onClick = {
                    usage.log(UsageActions.TOPBAR_SETTINGS)
                    onSettings()
                },
                modifier = Modifier.size(TARGET).testTag(TOP_BAR_SETTINGS_TAG).semantics { contentDescription = settingsLabel },
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (menu != null) {
                var open by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { open = true }, modifier = Modifier.size(TARGET).testTag(TOP_BAR_MENU_TAG).semantics { contentDescription = moreLabel }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
                }
            }
        }
    }
}
