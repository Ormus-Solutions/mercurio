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

package org.connectbot.ui.needs

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** The left-edge zone, as wide as the console's: a swipe that starts there is the drawer's or the system's, never a view change. */
internal val VIEW_SWIPE_EDGE = 24.dp

/** How far a finger travels across before the swipe changes the view. */
internal val VIEW_SWIPE_DISTANCE = 72.dp

/** How much farther across than up or down a swipe goes: a steep diagonal drag over an area that does not scroll is not a swipe. */
internal const val VIEW_SWIPE_DOMINANCE = 2f

/**
 * A horizontal swipe across this element changes the view: [onLeft] for right to left,
 * [onRight] for left to right; a null side does nothing. A vertical drag stays the list's, a drag
 * not at least twice as far across as it is up or down is not a swipe, and a swipe that starts in
 * the left-edge zone is left alone. Pass remembered callbacks.
 */
fun Modifier.viewSwipe(onLeft: (() -> Unit)? = null, onRight: (() -> Unit)? = null): Modifier = // New callbacks restart the detector: callers remember theirs, so a recomposition mid-swipe does not cut it.
    pointerInput(onLeft, onRight) { detectViewSwipe({ onLeft?.invoke() }, { onRight?.invoke() }) }

private suspend fun PointerInputScope.detectViewSwipe(onLeft: () -> Unit, onRight: () -> Unit) {
    val edge = VIEW_SWIPE_EDGE.toPx()
    val distance = VIEW_SWIPE_DISTANCE.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.position.x < edge) return@awaitEachGesture
        var last = down.position.x
        var lastY = down.position.y
        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
            last = change.position.x
            lastY = change.position.y
            change.consume()
        } ?: return@awaitEachGesture
        // A drag that was cancelled (the system took the gesture) is no swipe, however far it went.
        val finished = horizontalDrag(drag.id) { change ->
            last = change.position.x
            lastY = change.position.y
            change.consume()
        }
        if (!finished) return@awaitEachGesture
        val moved = last - down.position.x
        if (abs(moved) < VIEW_SWIPE_DOMINANCE * abs(lastY - down.position.y)) return@awaitEachGesture
        when {
            moved <= -distance -> onLeft()
            moved >= distance -> onRight()
        }
    }
}
