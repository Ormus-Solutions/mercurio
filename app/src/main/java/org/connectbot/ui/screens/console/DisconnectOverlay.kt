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

package org.connectbot.ui.screens.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.connectbot.R
import org.connectbot.ui.components.CopyDetailsButton
import org.connectbot.ui.components.ormusPanelTop
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.terminal
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions

/**
 * The bar shown at the bottom of the console when a session is disconnected:
 * "Connection Lost" with Copy details, Close and Reconnect. The actions flow
 * onto a second line on narrow screens.
 *
 * [wishListAction] is the slot for a "Send to wish list" button next to Copy
 * details; it can build the same text with [report].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisconnectOverlay(
    onClose: () -> Unit,
    onReconnect: () -> Unit,
    report: suspend () -> String,
    modifier: Modifier = Modifier,
    wishListAction: (@Composable FlowRowScope.() -> Unit)? = null,
) {
    val terminalColors = MaterialTheme.colorScheme.terminal
    val usage = LocalUsageLog.current
    // Opaque: the bar sits over the compose bar's key rows, which must neither show
    // through nor take taps meant for these buttons. A Surface also swallows touches.
    Surface(
        color = terminalColors.overlayBackground.copy(alpha = 1f),
        modifier = modifier
            .fillMaxWidth()
            .ormusPanelTop(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.alert_disconnect_msg),
                style = MaterialTheme.typography.headlineSmall,
                color = terminalColors.overlayText,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                CopyDetailsButton(
                    report = {
                        usage.log(UsageActions.OVERLAY_COPY_DETAILS)
                        report()
                    },
                    color = terminalColors.overlayText,
                )
                wishListAction?.invoke(this)
                TextButton(onClick = onClose, shape = OrmusCornerShape) {
                    Text(
                        stringResource(R.string.console_menu_close),
                        color = terminalColors.overlayText,
                    )
                }
                Button(onClick = onReconnect, shape = OrmusCornerShape) {
                    Text(stringResource(R.string.console_menu_reconnect))
                }
            }
        }
    }
}
