/*
 * Copied from Termish, https://github.com/farlume/termish (commit ee9e460),
 * composeApp/src/commonMain/kotlin/dev/termish/herdr/AgentStateMachine.kt.
 * Mercurio changes: Done event added; blocked to done/unknown now emits
 * Unblocked; comments translated.
 *
 * MIT License
 *
 * Copyright (c) 2026 Termish Project Authors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package solutions.ormus.logos.herd

/**
 * Agent state machine: snapshot diff -> state transition events.
 *
 * Purely functional, no IO and no UI: feed it each round's agents list and it
 * returns the transitions since the previous round. Entering and leaving
 * blocked is the source of notifications and UI badges.
 *
 * Baseline: the agents array (Herdr already did agent detection), tracked by pane_id.
 */
sealed interface HerdrAgentEvent {
    /** The agent entered blocked (waiting for the user to answer or approve). */
    data class Blocked(
        val paneId: String,
        val agent: String?,
        val title: String?,
        val cwd: String?,
    ) : HerdrAgentEvent

    /** The agent left blocked (the user replied, or the task resumed or ended). */
    data class Unblocked(
        val paneId: String,
        val agent: String?,
    ) : HerdrAgentEvent

    /** The agent started working. */
    data class Working(
        val paneId: String,
        val agent: String?,
    ) : HerdrAgentEvent

    /** The agent became idle. */
    data class Idle(
        val paneId: String,
        val agent: String?,
    ) : HerdrAgentEvent

    /** Mercurio: the agent finished its turn (Herdr's done state). */
    data class Done(
        val paneId: String,
        val agent: String?,
    ) : HerdrAgentEvent
}

class HerdrAgentStateMachine {
    /** pane_id -> state in the previous snapshot (tracked across rounds). */
    private val prev = HashMap<String, HerdrAgentStatus>()

    /** Current per-pane states (for UI reads and test assertions). */
    fun current(): Map<String, HerdrAgentStatus> = prev.toMap()

    /** Full reset (call after a monitor restart or reconnect so old state is not read as a transition). */
    fun reset() {
        prev.clear()
    }

    /**
     * Feed one round's snapshot and return the transitions since the previous round.
     * A repeated state produces no event (the natural basis for poll debouncing).
     */
    fun update(snapshot: HerdrSessionSnapshot): List<HerdrAgentEvent> {
        val events = ArrayList<HerdrAgentEvent>()
        val cur = snapshot.agents.associate { it.paneId to it.agentStatus }

        for (a in snapshot.agents) {
            val before = prev[a.paneId]
            if (before == a.agentStatus) continue
            when (a.agentStatus) {
                HerdrAgentStatus.BLOCKED ->
                    HerdrAgentEvent.Blocked(
                        a.paneId,
                        a.agent,
                        a.terminalTitleStripped ?: a.terminalTitle,
                        a.cwd,
                    )

                HerdrAgentStatus.WORKING -> HerdrAgentEvent.Working(a.paneId, a.agent)

                HerdrAgentStatus.IDLE -> HerdrAgentEvent.Idle(a.paneId, a.agent)

                HerdrAgentStatus.DONE -> HerdrAgentEvent.Done(a.paneId, a.agent)

                HerdrAgentStatus.UNKNOWN -> null
            }?.let { events += it }
            // blocked -> anything else leaves the waiting state (approved, timed out,
            // finished, or detection lost). Mercurio fix: Termish skipped this when
            // the new state had no event of its own (done / unknown), so the phone
            // kept thinking the agent was waiting on you.
            if (before == HerdrAgentStatus.BLOCKED) {
                events += HerdrAgentEvent.Unblocked(a.paneId, a.agent)
            }
        }

        // A pane gone from the snapshot (session or workspace closed): if it was
        // blocked, emit Unblocked too, or the notification would hang (the phone
        // would keep thinking the agent is waiting on you).
        prev.keys.filter { it !in cur }.forEach { gone ->
            if (prev[gone] == HerdrAgentStatus.BLOCKED) {
                events += HerdrAgentEvent.Unblocked(gone, null)
            }
            prev.remove(gone)
        }
        prev.putAll(cur)
        return events
    }
}
