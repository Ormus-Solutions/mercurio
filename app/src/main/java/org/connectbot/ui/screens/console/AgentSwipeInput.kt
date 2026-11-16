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

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Velocity
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
