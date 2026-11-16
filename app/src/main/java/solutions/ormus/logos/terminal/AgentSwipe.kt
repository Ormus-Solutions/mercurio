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

package solutions.ormus.logos.terminal

import org.connectbot.usage.UsageActions
import solutions.ormus.logos.herd.AgentEngine
import solutions.ormus.logos.herd.CoreWatch
import solutions.ormus.logos.herd.HerdrSwipes
import solutions.ormus.logos.herd.Inbox
import solutions.ormus.logos.herd.InboxRow
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

/**
 * The agent swipe of a watched herdr-core console: one finger across the terminal opens the next
 * (finger moves left, as a page turns, like [HerdrSwipes]) or the previous agent in the Inbox order.
 * Up, down and diagonal touches stay the terminal's scroll. Distances are in dp, speeds in dp per
 * second, y grows downward; the thresholds are the Herdr swipe's, so the two feel alike.
 */
object AgentSwipe {
    enum class Direction { NEXT, PREVIOUS }

    /** Whether to follow a touch as a swipe (and take it from the terminal), let it go, or wait. */
    enum class Claim { WAIT, CLAIM, RELEASE }

    /**
     * Where a terminal stands among the rows a swipe moves between: [index] of [total] (from 0), and the rows
     * either side of it. The first has no [previous] and the last no [next]: a swipe stops there, it does not wrap.
     */
    data class Place(val index: Int, val total: Int, val previous: InboxRow?, val next: InboxRow?) {
        /** The place's number, counted from 1. */
        val number: Int get() = index + 1

        /** The row a swipe in [direction] opens; null at the end. */
        fun toward(direction: Direction): InboxRow? = if (direction == Direction.NEXT) next else previous
    }

    /** The usage-log id of a swipe that opened an agent. */
    fun usageId(direction: Direction): String = if (direction == Direction.NEXT) UsageActions.SWIPE_NEXT_AGENT else UsageActions.SWIPE_PREV_AGENT

    /** Judge a touch in progress, by its travel ([dx], [dy]) so far. One finger only: the caller lets a second go. */
    fun claim(dx: Float, dy: Float): Claim {
        if (hypot(dx, dy) < HerdrSwipes.JUDGE_DP) return Claim.WAIT
        return if (HerdrSwipes.horizontal(dx, dy) == true) Claim.CLAIM else Claim.RELEASE
    }

    /**
     * The swipe a claimed touch makes once it lifts, from its travel and the horizontal speed [vx]; null when it falls
     * short (under [HerdrSwipes.FIRE_DP], unless it leaves at [HerdrSwipes.FLING_DP_PER_S] or more the way it went),
     * or ends on another axis.
     */
    fun classify(dx: Float, dy: Float, vx: Float): Direction? {
        if (HerdrSwipes.horizontal(dx, dy) != true) return null
        if (abs(dx) < HerdrSwipes.JUDGE_DP) return null
        if (abs(dx) < HerdrSwipes.FIRE_DP && vx * sign(dx) < HerdrSwipes.FLING_DP_PER_S) return null
        return if (dx < 0) Direction.NEXT else Direction.PREVIOUS
    }

    /**
     * The rows a swipe moves between, in the order the Inbox shows them: its sections (Needs you, Done, Working, Idle,
     * Unknown), each ranked with the pinned first, helpers folded under their parent. A section the Inbox folds to its
     * first rows is taken whole: the fold is the Inbox screen's own state. Only herdr-core terminals are in the run: a
     * Herdr session's agent opens a Herdr console, where the one-finger swipe is the tab swipe and does not come back, so
     * a swipe never lands there. A core terminal that cannot be watched (its address is not a plain word) is left out, as
     * the Jump sheet leaves it out.
     */
    fun order(rows: List<InboxRow>): List<InboxRow> = Inbox.sections(rows, "", emptySet())
        .flatMap { it.rows }
        .filter { it.agent.engine == AgentEngine.CORE && CoreWatch.target(it.agent) != null }

    /** The place of the row [isCurrent] picks in [order]; a helper folded under a row reads that row's place. Null when no row is it. */
    fun place(order: List<InboxRow>, isCurrent: (InboxRow) -> Boolean): Place? {
        val at = order.indexOfFirst { row -> isCurrent(row) || row.helperRows.any(isCurrent) }
        if (at < 0) return null
        return Place(at, order.size, order.getOrNull(at - 1), order.getOrNull(at + 1))
    }
}
