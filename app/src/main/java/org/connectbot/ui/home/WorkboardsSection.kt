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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.connectbot.R
import org.connectbot.data.entity.Host
import org.connectbot.ui.components.MonoLabel
import org.connectbot.ui.components.OrmusKey
import org.connectbot.ui.components.WORD_KEY_PADDING
import org.connectbot.ui.theme.Ormus
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.herd.HerdrConsole
import solutions.ormus.logos.home.HomeShortcuts
import solutions.ormus.logos.home.WorkboardBlock
import solutions.ormus.logos.home.WorkboardKey
import solutions.ormus.logos.home.WorkboardOff

/** Tag of every workspace key, for tests. */
internal const val WORKBOARD_KEY_TAG = "workboard-key"

/** Tag of a board's Save key, for tests. */
internal const val WORKBOARD_SAVE_TAG = "workboard-save"

/** Before a workspace's name when its agents wait on the user. */
internal const val NEEDS_YOU_MARK = "• "

/** How often the "as of" time is redrawn. */
private const val AGE_TICK_MS = 30_000L

/**
 * The sessions feed's workboards at the top of the host list: one block per workboard, named
 * with the machine it lives on and how many of its agents wait on the user, and one key per
 * workspace, marked when its own agents wait. A tap opens that Herdr session on the saved host
 * the machine's names pick ([solutions.ormus.logos.home.HomeWorkboards]), landed on the
 * workspace. A key with no saved host on its machine, or no session known, is there and off:
 * its spoken name says what it lacks. A board no saved host attaches ends its line with a Save
 * key: a tap hands the host to save ([WorkboardBlock.shortcut]), so the board gets a row of its
 * own. The host list leaves the section out while the feed has no boards. No gold here: the
 * screen's one gold action stays its own.
 *
 * @param fetchedAt epoch millis of the read that gave the boards; 0 shows no time
 * @param error why the feed's last read failed, shown under the heading; null when it worked
 * @param onOpen receives the host and the console to open; callers pass it to the console route
 * @param onSave receives the host a board's Save key makes; callers save it
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkboardsSection(
    blocks: List<WorkboardBlock>,
    fetchedAt: Long,
    error: String?,
    onOpen: (Host, HerdrConsole) -> Unit,
    modifier: Modifier = Modifier,
    onSave: (Host) -> Unit = {},
) {
    val usage = LocalUsageLog.current
    // The time reads right while the home stays: a failed read leaves the boards as they were.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(AGE_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    Column(modifier = modifier.padding(horizontal = Ormus.spacing.xs)) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Ormus.spacing.xs),
        ) {
            MonoLabel(text = stringResource(R.string.workboards_title))
            if (fetchedAt > 0L) {
                // A read lands between two ticks: never "in 0 minutes".
                val ago = DateUtils.getRelativeTimeSpanString(fetchedAt, maxOf(now, fetchedAt), DateUtils.MINUTE_IN_MILLIS).toString()
                Text(
                    text = stringResource(R.string.workboards_as_of, ago),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (error != null) {
            Text(
                text = stringResource(R.string.workboards_not_read, error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        blocks.forEach { block ->
            val board = block.board
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Text(
                    text = board.machine?.let { stringResource(R.string.workboards_board, board.name, it) } ?: board.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    // A long name yields before the count and the Save key do.
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (board.agents.blocked > 0) {
                    Text(
                        text = stringResource(R.string.workboards_needs_you, board.agents.blocked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                block.shortcut?.let { host ->
                    OrmusKey(
                        label = stringResource(R.string.workboards_save),
                        onClick = {
                            usage.log(UsageActions.WORKBOARDS_SAVE_HOST)
                            onSave(host)
                        },
                        contentPadding = WORD_KEY_PADDING,
                        description = stringResource(R.string.workboards_save_host, board.name, HomeShortcuts.place(board)?.machine.orEmpty()),
                        modifier = Modifier.testTag(WORKBOARD_SAVE_TAG),
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
            ) {
                block.keys.forEach { key ->
                    val target = key.target
                    OrmusKey(
                        label = if (key.workspace.agents.blocked > 0) NEEDS_YOU_MARK + key.name else key.name,
                        onClick = {
                            if (target != null) {
                                usage.log(UsageActions.WORKBOARDS_OPEN)
                                onOpen(target.host, target.console)
                            }
                        },
                        enabled = target != null,
                        contentPadding = WORD_KEY_PADDING,
                        description = keyDescription(key),
                        modifier = Modifier.testTag(WORKBOARD_KEY_TAG),
                    )
                }
            }
        }
    }
}

/** The spoken name of a key: what it opens (and that it waits on the user), or why it cannot open. */
@Composable
internal fun keyDescription(key: WorkboardKey): String {
    val board = key.board.name
    if (key.target != null) {
        val opens = stringResource(R.string.workboards_open, key.name, board, key.machine.orEmpty())
        return if (key.workspace.agents.blocked > 0) stringResource(R.string.workboards_open_needs_you, opens) else opens
    }
    return when (key.off ?: WorkboardOff.NO_HOST) {
        WorkboardOff.NO_SESSION -> stringResource(R.string.workboards_no_session, key.name, board)
        WorkboardOff.BAD_SESSION -> stringResource(R.string.workboards_bad_session, key.name, board, key.session.orEmpty())
        WorkboardOff.NO_MACHINE -> stringResource(R.string.workboards_no_machine, key.name, board)
        WorkboardOff.UNNAMED_MACHINE -> stringResource(R.string.workboards_unnamed_machine, key.name, board, key.machine.orEmpty())
        WorkboardOff.NO_HOST -> stringResource(R.string.workboards_no_host, key.name, board, key.machine.orEmpty())
    }
}
