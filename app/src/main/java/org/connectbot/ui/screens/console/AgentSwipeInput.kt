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

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.connectbot.R
import org.connectbot.ui.components.MonoLabel
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import solutions.ormus.logos.terminal.AgentSwipe

// The fewest samples Compose's velocity tracker reads a velocity from.
private const val MIN_TRACKED_SAMPLES = 3

/**
 * The agent swipe on the terminal of a watched herdr-core console ([AgentSwipe]): one finger across opens the next
 * or the previous agent. The touch is watched on the Initial pass, ahead of termlib, as [herdrSwipes] does, and is
 * taken only once [AgentSwipe.claim] says swipe: from the next event on it is consumed, so termlib's tap and long
 * press never fire. A touch up, down or diagonal is let go at the first judgement and nothing of it is consumed, so
 * the terminal's own scroll is untouched. A second finger (a pinch, the font zoom) lets the touch go, or cancels a
 * swipe already taken; so does a finger held still past the long-press timeout (selection, drag arrows). [enabled] is
 * read as each touch starts and while it waits: false while a selection is up. This and [herdrSwipes] are never both
 * on: the Herdr swipes are off in a watched console.
 */
internal fun Modifier.agentSwipes(enabled: () -> Boolean, onSwipe: (AgentSwipe.Direction) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled()) return@awaitEachGesture
        val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var samples = 1
        var lastAt = down.uptimeMillis
        var travel = Offset.Zero
        var claimed = false
        var cancelled = false
        var slopped = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val finger = event.changes.firstOrNull { it.id == down.id }
            finger?.let {
                velocity.addPointerInputChange(it)
                if (it.pressed) samples++
                lastAt = it.uptimeMillis
                travel = it.position - down.position
            }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            if (pressed.size > 1) {
                if (!claimed) return@awaitEachGesture
                cancelled = true
            }
            if (claimed) {
                event.changes.forEach { it.consume() }
                continue
            }
            if (!slopped && lastAt - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis) return@awaitEachGesture
            if (travel.getDistance() > viewConfiguration.touchSlop) slopped = true
            // The claiming event itself goes on to termlib; the ones after it do not.
            when (AgentSwipe.claim(travel.x.toDp().value, travel.y.toDp().value)) {
                AgentSwipe.Claim.CLAIM -> claimed = true
                AgentSwipe.Claim.RELEASE -> return@awaitEachGesture
                AgentSwipe.Claim.WAIT -> if (!enabled()) return@awaitEachGesture
            }
        }
        if (!claimed || cancelled) return@awaitEachGesture
        // A flick of a move or two leaves the tracker without the samples it needs: judge it by its average. With them the tracker
        // alone says how fast the finger left, as the Herdr swipe does: a quick drag that rests before the lift is not a fling.
        val v = if (samples < MIN_TRACKED_SAMPLES) claimVelocity(Velocity.Zero, travel, lastAt - down.uptimeMillis) else velocity.calculateVelocity()
        AgentSwipe.classify(travel.x.toDp().value, travel.y.toDp().value, v.x.toDp().value)?.let(onSwipe)
    }
}

/** Test tag on the hint over the terminal after an agent swipe. */
const val AGENT_HINT_TAG = "console_agent_hint"

// How long the hint stays after a swipe fires, as the Herdr swipes' hint does.
private const val HINT_MS = 900L

/** What a console shows of its last agent swipe: the [hint] and how many swipes [fired] (a repeat shows it again). */
@Stable
internal class AgentHintState {
    var hint by mutableStateOf<AgentSwipe.Hint?>(null)
        private set
    var fired by mutableIntStateOf(0)
        private set

    fun show(hint: AgentSwipe.Hint) {
        this.hint = hint
        fired++
    }
}

/** The hint state of this console, which watches [address]: it starts with the hint of the swipe that opened this terminal ([AgentHintHandoff]), if one did. */
@Composable
internal fun rememberAgentHintState(address: String?): AgentHintState = remember { AgentHintState().also { state -> AgentHintHandoff.take(address)?.let(state::show) } }

/**
 * A swipe that opens another agent leaves this console: the new console takes its place, so a hint set here would show on the
 * console that fades out. The hint of such a swipe is handed to the console of the terminal it opens ([AgentHintHandoff.put]), once,
 * and only while it is fresh: a console opened for another terminal (a tap) takes nothing. One that opens nothing (an end, or no place yet) says "No more agents" here, on the console that stays.
 */
internal object AgentHintHandoff {
    private const val FRESH_MS = 2_000L
    private var pending: AgentSwipe.Hint? = null
    private var forAddress: String? = null
    private var at = 0L

    fun put(hint: AgentSwipe.Hint, address: String, now: Long = SystemClock.uptimeMillis()) {
        pending = hint
        forAddress = address
        at = now
    }

    /**
     * The hint put for the terminal [address], once; null when none, when it is for another terminal (it waits, for that one), or when
     * it is older than two seconds (it is dropped).
     */
    fun take(address: String?, now: Long = SystemClock.uptimeMillis()): AgentSwipe.Hint? {
        val hint = pending ?: return null
        if (now - at > FRESH_MS) {
            pending = null
            return null
        }
        if (address == null || address != forAddress) return null
        pending = null
        return hint
    }
}

/**
 * What an agent swipe does once it fired, apart from the tick: opens the console [AgentSwipeSource.open] names and logs its usage
 * id, handing it the hint; or, with nothing to open, shows the hint on this console ([hint]).
 */
internal fun handleAgentSwipe(place: AgentSwipe.Place?, direction: AgentSwipe.Direction, hint: AgentHintState, log: (String) -> Unit, open: (String) -> Unit) {
    val target = place?.toward(direction)
    if (target == null) {
        hint.show(AgentSwipe.hint(place, direction))
        return
    }
    AgentHintHandoff.put(AgentSwipe.hint(place, direction), target.agent.address)
    log(AgentSwipe.usageId(direction))
    open(AgentSwipeSource.route(target))
}

/**
 * A brief "/ NEXT AGENT" over the terminal after an agent swipe fires, as [HerdrSwipeHint] does for the Herdr swipes.
 * [hint] is the last swipe's; [fired] counts them, so a repeat shows it again.
 */
@Composable
internal fun AgentSwipeHint(hint: AgentSwipe.Hint?, fired: Int, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(fired) {
        if (fired == 0) return@LaunchedEffect
        visible = true
        delay(HINT_MS)
        visible = false
    }
    val label = when (hint ?: return) {
        AgentSwipe.Hint.NEXT -> R.string.swipe_hint_next_agent
        AgentSwipe.Hint.PREVIOUS -> R.string.swipe_hint_prev_agent
        AgentSwipe.Hint.NONE_LEFT -> R.string.swipe_hint_no_more_agents
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier.testTag(AGENT_HINT_TAG)) {
        MonoLabel(
            text = stringResource(label),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .background(Ormus.extras.panelOverLive, OrmusCornerShape)
                .border(1.dp, Ormus.extras.hairline, OrmusCornerShape)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** Test tag on the place in a watched console's title. */
const val AGENT_PLACE_TAG = "console_agent_place"

/**
 * "3 of 9": where this terminal is among the agents a swipe moves between, at the start of the title's second line. A screen reader
 * gets one node, "Agent 3 of 9": the text under it is drawn, not read again.
 */
@Composable
internal fun AgentPlaceChip(place: AgentSwipe.Place, modifier: Modifier = Modifier) {
    val text = stringResource(R.string.console_agent_place, place.number, place.total)
    val description = stringResource(R.string.console_agent_place_description, place.number, place.total)
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        modifier = modifier.testTag(AGENT_PLACE_TAG).clearAndSetSemantics { contentDescription = description },
    )
}

/**
 * The title of a watched console: the tab on the first line, whole; on the second, the place ("3 of 9", when there are two or more
 * agents), then `space · host`. The place is on the second line so that the tab, which changes with every swipe, keeps its width.
 */
@Composable
internal fun WatchedTitle(title: String, subtitle: String, place: AgentSwipe.Place?, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            place?.takeIf { it.showsChip }?.let {
                AgentPlaceChip(it)
                Text(" \u00b7 ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clearAndSetSemantics {})
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}
