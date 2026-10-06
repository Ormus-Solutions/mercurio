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

package solutions.ormus.logos.herd

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/** What a swipe on the terminal of a Herdr session does. */
enum class HerdrSwipe { PREV_TAB, NEXT_TAB, PANE_LEFT, PANE_RIGHT, PREV_WORKSPACE, NEXT_WORKSPACE }

/**
 * Reads swipes on the terminal of a Herdr session, the way Moshi does: one finger across
 * moves between tabs, two fingers across move pane focus, two fingers up or down move
 * between workspaces. Left and up go to the next one, the way a page turns. One finger up
 * or down stays the terminal's scroll and a pinch stays its zoom, so neither is a swipe.
 *
 * Distances are in dp, velocities in dp per second; y grows downward.
 */
object HerdrSwipes {
    /** Travel before a touch is judged. Under it, the touch is still a tap, a long press or anything. */
    const val JUDGE_DP = 20f

    /** Travel that fires on its own. */
    const val FIRE_DP = 64f

    /** A shorter swipe (from [JUDGE_DP]) fires when it leaves at least this fast. */
    const val FLING_DP_PER_S = 500f

    // The swipe's axis must outweigh the other this many times (within about 27 degrees).
    private const val DOMINANCE = 2f

    /** Whether to follow a touch as a swipe (and take it from the terminal), let it go, or wait. */
    enum class Claim { WAIT, CLAIM, RELEASE }

    /**
     * Judge a touch in progress: [pointers] fingers down so far, travel ([dx], [dy]) of
     * their average, and how far the gap between two fingers grew or shrank ([spanChange]).
     */
    fun claim(pointers: Int, dx: Float, dy: Float, spanChange: Float = 0f): Claim {
        if (pointers !in 1..2 || abs(spanChange) >= JUDGE_DP) return Claim.RELEASE // three fingers, or a pinch
        if (hypot(dx, dy) < JUDGE_DP) return Claim.WAIT
        val horizontal = horizontal(dx, dy) ?: return Claim.RELEASE // diagonal
        return if (pointers == 1 && !horizontal) Claim.RELEASE else Claim.CLAIM
    }

    /** The swipe a claimed touch makes once it lifts, from its travel and velocity; null when it falls short. */
    fun classify(pointers: Int, dx: Float, dy: Float, vx: Float, vy: Float): HerdrSwipe? {
        if (pointers !in 1..2) return null
        val horizontal = horizontal(dx, dy) ?: return null
        if (pointers == 1 && !horizontal) return null
        val travel = if (horizontal) dx else dy
        val speed = (if (horizontal) vx else vy) * sign(travel)
        if (abs(travel) < FIRE_DP && (abs(travel) < JUDGE_DP || speed < FLING_DP_PER_S)) return null
        val back = travel > 0 // right or down
        return when {
            pointers == 1 -> if (back) HerdrSwipe.PREV_TAB else HerdrSwipe.NEXT_TAB
            horizontal -> if (back) HerdrSwipe.PANE_LEFT else HerdrSwipe.PANE_RIGHT
            else -> if (back) HerdrSwipe.PREV_WORKSPACE else HerdrSwipe.NEXT_WORKSPACE
        }
    }

    /**
     * The Herdr action for [swipe] against [snapshot]. Tabs are the host's previous_tab and
     * next_tab keys (the compose bar's ← and →); panes and workspaces are CLI focus commands. Herdr has no
     * next or previous workspace command, so that one picks the neighbor in the snapshot's
     * order by number, wrapping; null with no other workspace to go to.
     */
    fun action(swipe: HerdrSwipe, snapshot: HerdrSessionSnapshot?): HerdrAction? = when (swipe) {
        HerdrSwipe.PREV_TAB -> HerdrAction.BoundKey(HerdrKey.PREVIOUS_TAB)
        HerdrSwipe.NEXT_TAB -> HerdrAction.BoundKey(HerdrKey.NEXT_TAB)
        HerdrSwipe.PANE_LEFT -> HerdrAction.FocusPane(HerdrDirection.LEFT)
        HerdrSwipe.PANE_RIGHT -> HerdrAction.FocusPane(HerdrDirection.RIGHT)
        HerdrSwipe.PREV_WORKSPACE -> neighborWorkspace(snapshot, -1)?.let { HerdrAction.FocusWorkspace(it) }
        HerdrSwipe.NEXT_WORKSPACE -> neighborWorkspace(snapshot, 1)?.let { HerdrAction.FocusWorkspace(it) }
    }

    private fun neighborWorkspace(snapshot: HerdrSessionSnapshot?, step: Int): String? {
        val ids = snapshot?.workspaces.orEmpty().sortedBy { it.number }.map { it.workspaceId }
        val at = ids.indexOf(snapshot?.focusedWorkspaceId)
        if (at < 0 || ids.size < 2) return null
        return ids[(at + step).mod(ids.size)]
    }

    // True across, false up or down, null when neither axis clearly leads.
    private fun horizontal(dx: Float, dy: Float): Boolean? = when {
        abs(dx) >= DOMINANCE * abs(dy) -> true
        abs(dy) >= DOMINANCE * abs(dx) -> false
        else -> null
    }
}
