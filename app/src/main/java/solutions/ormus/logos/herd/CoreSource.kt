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

        val version = run(HerdrApi.loginShell(VERSION_CMD))
            ?: return Result.Unavailable("could not reach the host")
        if (version.exitCode != 0 || version.stdout.isBlank()) {
            return Result.Unavailable("herdr-core is not installed on this host")
        }

        val out = run(HerdrApi.loginShell(LIST_CMD))
            ?: return Result.Unavailable("herdr-core did not answer")
        if (out.exitCode != 0) return Result.Unavailable("herdr-core is not running (hc ls exit ${out.exitCode})")
        val records = parse(out.stdout)
            ?: return Result.Unavailable("herdr-core answered something unreadable")

        val agents = records.mapNotNull { toAgent(it, machine, now) }
        seen.keys.retainAll(agents.map { it.address }.toSet())
        return Result.Agents(agents)
    }

    private fun toAgent(
        record: JsonObject,
        machine: String?,
        now: Long,
    ): Agent? {
        val address = record.str("address") ?: return null
        val state = stateOf(record["status"])
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
            kind = record.str("agent"),
            state = state,
            since = since,
            turn = (record["turn"] as? JsonPrimitive)?.intOrNull,
        )
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
        const val LIST_CMD = "hc ls --json"

        /** `hc ls` writes `where` as "<space> / <tab>". */
        private const val WHERE_SEP = " / "
    }
}
