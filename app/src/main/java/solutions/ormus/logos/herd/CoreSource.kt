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

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.connectbot.transport.CommandOutput

/**
 * The herdr-core terminals of one host, as [Agent]s. One instance per host: it remembers when each
 * terminal entered its state, because `hc ls` does not say.
 *
 * Both commands run through [HerdrApi.loginShell]: an ssh exec channel has no `~/.local/bin` on its
 * PATH, which is where `herdr-core` and `hc` live (measured on the spike).
 *
 * `hc ls` gives a terminal's `status` as a string (`"idle"`) or null; `hc get` gives it as an
 * object (`{"app":..,"state":..}`, and a `kind` for a terminal that needs the user). Both are read.
 * A shell terminal reports no status and is [AgentState.Unknown].
 */
class CoreSource {
    sealed interface Result {
        /** The setting is off: nothing was run. */
        data object Off : Result

        /** The host's terminals, possibly none. */
        data class Agents(val agents: List<Agent>) : Result

        /** No herdr-core here, or its core is down: [reason] is for the user, and there are no rows. */
        data class Unavailable(val reason: String) : Result
    }

    private data class Seen(val state: AgentState, val since: Long)

    private val seen = HashMap<String, Seen>()

    // The --version probe answered once; it is not asked again until a read fails, so a poll is one command.
    private var installed = false

    /**
     * Read the host's terminals.
     *
     * @param enabled the setting; off runs nothing
     * @param machine the host's name, put on every agent
     * @param now the clock, epoch milliseconds
     * @param run runs one command on the host's connection; null when it never ran
     */
    fun read(
        enabled: Boolean,
        machine: String?,
        now: Long,
        run: (String) -> CommandOutput?,
    ): Result {
        if (!enabled) return Result.Off

        if (!installed) {
            val version = run(HerdrApi.loginShell(VERSION_CMD))
                ?: return Result.Unavailable("could not reach the host")
            if (version.exitCode != 0 || version.stdout.isBlank()) {
                return Result.Unavailable(NOT_INSTALLED)
            }
            installed = true
        }

        val out = run(HerdrApi.loginShell(LIST_CMD))
            ?: return down("herdr-core did not answer")
        if (out.exitCode != 0) return down("herdr-core is not running (hc ls exit ${out.exitCode})")
        val records = parse(out.stdout)
            ?: return down("herdr-core answered something unreadable")

        val labels = readLabels(records, run)
        val agents = records.mapNotNull { toAgent(it, machine, now, labels) }
        seen.keys.retainAll(agents.map { it.address }.toSet())
        return Result.Agents(agents)
    }

    private fun down(reason: String): Result.Unavailable {
        installed = false
        return Result.Unavailable(reason)
    }

    /** What the labels command gave: the pinned terminals, and the `herdr.seen` of the ones asked. */
    private class Labels(val pinned: Set<String>, val seen: Map<String, Int>)

    /**
     * The shared labels of this host's terminals, in one command. Null when it could not be read:
     * the agents then keep the core's own status, nothing is pinned, and no row is unread.
     * Only an idle or done terminal past turn 0 can be unread, so only those are asked.
     */
    private fun readLabels(records: List<JsonObject>, run: (String) -> CommandOutput?): Labels? {
        val asked = records.mapNotNull { r ->
            val address = r.str("address")?.takeIf { TERMINAL.matches(it) } ?: return@mapNotNull null
            val turn = (r["turn"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            address.takeIf { turn > 0 && stateOf(r["status"]).let { it == AgentState.Idle || it == AgentState.Done } }
        }
        val out = try {
            run(HerdrApi.loginShell(labelsCmd(asked)))
        } catch (_: Exception) {
            null
        } ?: return null
        val pinned = HashSet<String>()
        val seen = HashMap<String, Int>()
        var any = false
        for (line in out.stdout.lineSequence()) {
            val obj = try {
                Json.parseToJsonElement(line.trim().ifEmpty { continue }) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: continue
            val list = obj["terminals"] as? JsonArray
            if (list != null) {
                any = true
                list.filterIsInstance<JsonObject>().mapNotNullTo(pinned) { it.str("address") }
                continue
            }
            val address = obj.str("address") ?: continue
            val value = (obj["labels"] as? JsonObject)?.str(SEEN)?.toIntOrNull() ?: continue
            seen[address] = value
        }
        return if (any) Labels(pinned, seen) else null
    }

    private fun toAgent(
        record: JsonObject,
        machine: String?,
        now: Long,
        labels: Labels?,
    ): Agent? {
        val address = record.str("address") ?: return null
        val turn = (record["turn"] as? JsonPrimitive)?.intOrNull
        var state = stateOf(record["status"])
        // The Herdr app's rule: idle or done is Done while the turn is past the seen one, else Idle.
        // A missing (or unreadable) label is not 0: the core's own status stands and unread is unknown.
        var unread: Boolean? = null
        val mark = labels?.seen?.get(address)
        if (mark != null && turn != null && (state == AgentState.Idle || state == AgentState.Done)) {
            unread = turn > mark
            state = if (unread) AgentState.Done else AgentState.Idle
        }
        val reported = (record["status_ms"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it > 0 }
        val since = reported ?: run {
            val prev = seen[address]
            if (prev != null && prev.state::class == state::class) prev.since else now
        }
        seen[address] = Seen(state, since)
        val where = record.str("where").orEmpty()
        val split = where.indexOf(WHERE_SEP)
        val space = if (split < 0) where else where.substring(0, split)
        val tab = if (split < 0) "" else where.substring(split + WHERE_SEP.length)
        return Agent(
            address = address,
            engine = AgentEngine.CORE,
            machine = machine,
            space = space,
            tab = tab,
            kind = record.str("agent") ?: (record["status"] as? JsonObject)?.str("app"),
            state = state,
            since = since,
            turn = turn,
            unread = unread,
            pinned = labels != null && address in labels.pinned,
            parent = record.str("parent"),
        )
    }

    /** The one terminal in an `hc get <t> --json` answer, or null when the text is not that. */
    fun parseOne(text: String, machine: String?, now: Long): Agent? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val root = try {
            Json.parseToJsonElement(text.substring(start))
        } catch (_: SerializationException) {
            return null
        } as? JsonObject ?: return null
        return toAgent(root, machine, now, null)
    }

    private fun stateOf(status: JsonElement?): AgentState {
        val (name, kind) = when (status) {
            is JsonPrimitive -> status.contentOrNull to null
            is JsonObject -> status.str("state") to status.str("kind")
            else -> null to null
        }
        return when (name) {
            "working" -> AgentState.Working

            "idle" -> AgentState.Idle

            "done" -> AgentState.Done

            "blocked" -> AgentState.NeedsYou(
                when (kind) {
                    "permission" -> NeedsYouKind.PERMISSION
                    "question" -> NeedsYouKind.QUESTION
                    "auth" -> NeedsYouKind.AUTH
                    else -> null
                },
            )

            else -> AgentState.Unknown
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** The terminal records of `hc ls --json`, or null when the text is not that. */
    private fun parse(text: String): List<JsonObject>? {
        // A login shell may print a banner before the JSON.
        val start = text.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return null
        val root = try {
            Json.parseToJsonElement(text.substring(start))
        } catch (_: SerializationException) {
            return null
        }
        val list = when (root) {
            is JsonArray -> root
            is JsonObject -> root["terminals"] as? JsonArray
            else -> null
        } ?: return null
        return list.filterIsInstance<JsonObject>()
    }

    companion object {
        const val VERSION_CMD = "herdr-core --version"

        /** The reason of [Result.Unavailable] for a host whose `--version` did not answer. */
        const val NOT_INSTALLED = "herdr-core is not installed on this host"
        const val LIST_CMD = "hc ls --json"

        /** The terminals carrying `herdr.pinned=1`; `hc ls` does not print labels, but it can filter on them. */
        const val PINNED_CMD = "hc ls --label herdr.pinned=1 --json"

        /** The label a client raises to the turn it has shown; see herdr-app docs/labels.md. */
        private const val SEEN = "herdr.seen"

        // A terminal (`t7`) or a remote one (`mac/t7`); it starts with a letter or digit so it can never read as an option.
        private val TERMINAL = Regex("[A-Za-z0-9][A-Za-z0-9_-]*(/[A-Za-z0-9][A-Za-z0-9_-]*)?")

        /**
         * One command that prints the pinned listing, then one `hc get` line per terminal in
         * [addresses] (their `labels` object carries `herdr.seen`). An address that is not a plain
         * terminal name is left out, so nothing else reaches the shell.
         */
        fun labelsCmd(addresses: List<String>): String = PINNED_CMD + addresses.filter { TERMINAL.matches(it) }.joinToString("") { "; hc get $it --json" }

        /**
         * Run [action] on terminal [address] of a host. [turn] is the terminal's current turn, which
         * mark read writes into `herdr.seen`. False when nothing ran (a bad address, no turn) or the
         * command failed.
         */
        fun write(action: RowAction, address: String, turn: Int?, run: (String) -> CommandOutput?): Boolean {
            if (!TERMINAL.matches(address)) return false
            val cmd = when (action) {
                RowAction.MARK_READ -> "hc label $address $SEEN=${turn ?: return false}"
                RowAction.PIN -> "hc label $address herdr.pinned=1"
                RowAction.UNPIN -> "hc label $address herdr.pinned="
                RowAction.INTERRUPT -> "hc keys $address esc"
            }
            val out = try {
                run(HerdrApi.loginShell(cmd))
            } catch (_: Exception) {
                null
            }
            return out != null && out.exitCode == 0
        }

        /** `hc ls` writes `where` as "<space> / <tab>". */
        private const val WHERE_SEP = " / "
    }
}
