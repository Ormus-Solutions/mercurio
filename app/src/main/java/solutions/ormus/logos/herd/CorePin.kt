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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.connectbot.transport.CommandOutput

/**
 * Pin or unpin the herdr-core terminal a console watches. The pin is the `herdr.pinned` label the Inbox
 * reads (`hc ls --label herdr.pinned=1`), so a pin made here is the Inbox's pin, and Herdr's on the same host.
 * The state is read from `hc get` ([look]); the write is the Inbox swipe's own, [CoreSource.write] ([set]):
 * no second command.
 */
object CorePin {
    private const val LABEL = "herdr.pinned"

    /** The label value that pins; the Inbox lists exactly this. */
    private const val PINNED = "1"

    /**
     * Whether an `hc get --json` answer says the terminal is pinned. A terminal with no labels has no `labels`
     * key (measured), so it is not pinned. Null when the text is not a terminal's answer.
     */
    fun pinnedOf(getOutput: String): Boolean? {
        val start = getOutput.indexOf('{')
        if (start < 0) return null
        val root = try {
            Json.parseToJsonElement(getOutput.substring(start))
        } catch (_: SerializationException) {
            return null
        } as? JsonObject ?: return null
        // `address` is in every terminal's answer; an object without it is not one.
        if (root["address"] == null) return null
        return ((root["labels"] as? JsonObject)?.get(LABEL) as? JsonPrimitive)?.contentOrNull == PINNED
    }

    /**
     * Whether terminal [address] is pinned, read with `hc get` through [run] (one command on the host's
     * connection, null when it never ran). Null when it could not be read: the caller never guesses.
     */
    fun look(address: String, run: (String) -> CommandOutput?): Boolean? {
        val get = CoreSource.getCommand(address) ?: return null
        val out = try {
            run(get)
        } catch (_: Exception) {
            null
        }?.takeIf { it.exitCode == 0 } ?: return null
        return pinnedOf(out.stdout)
    }

    /** Write the pin of [address]: on for [pin], off otherwise. False when the command failed or gave no answer: it may have run all the same (a timeout after the host took it). */
    fun set(address: String, pin: Boolean, run: (String) -> CommandOutput?): Boolean = CoreSource.write(if (pin) RowAction.PIN else RowAction.UNPIN, address, null, run)
}
