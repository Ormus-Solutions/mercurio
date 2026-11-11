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
import org.connectbot.transport.CommandOutput
import solutions.ormus.logos.terminal.BracketedPaste
import java.io.IOException

/** What a compose Send to a core terminal came to. */
sealed interface SendResult {
    /** `hc send` pasted the text and pressed Enter. */
    data object Sent : SendResult

    /** `hc send` refused because the program is blocked on a dialog: nothing was typed. */
    data class Refused(val reason: String) : SendResult

    /** Nothing was typed for another reason (no such terminal, no `hc`, no answer). */
    data class Failed(val reason: String) : SendResult
}

/**
 * Compose and screenshots for a herdr-core terminal, over `hc` through the login shell. `hc send`
 * pastes the text, presses Enter, waits for a person's unfinished draft (up to 60 s) and refuses a
 * blocked program unless forced; Mercurio never forces, so a blocked agent is not answered by
 * mistake. `hc put` copies a file beside the terminal on its machine and prints where it landed.
 * `hc` is run on the machine the console is connected to, so the file for `hc put` is first put
 * there over SFTP, as the screenshot key already does.
 */
object CoreSend {
    /** The most `hc put` takes. */
    const val MAX_PUT_BYTES = 8L * 1024 * 1024

    /** How long one `hc send` may take: it waits up to 60 s for a draft. */
    const val SEND_TIMEOUT_MS = 75_000L

    /** How long `hc put` may take. */
    const val PUT_TIMEOUT_MS = 20_000L

    /** How long a paste waits after the attach line was typed, for `hc attach` to be reading (inferred). */
    const val ATTACH_WAIT_MS = 700L

    /** One typed key as the watch state sees it: how a compose action takes a watch over. */
    val TYPED_KEY: ByteArray = byteArrayOf(0x0d)

    /** The exec command that pastes [text] into [address] and presses Enter; null for nothing to send or an address that is no plain word. */
    fun sendCommand(address: String, text: String): String? {
        if (!CoreWatch.isSafe(address)) return null
        val clean = text.replace("\u0000", "")
        if (clean.isEmpty()) return null
        // `--` ends the options, so a text that starts with a dash is still the text.
        return HerdrApi.loginShell("hc send --json -- ${HerdrApi.shQuote(address)} ${HerdrApi.shQuote(clean)}")
    }

    /** The exec command that sends the file at [remoteFile] (on the connected machine) beside [address]. */
    fun putCommand(address: String, remoteFile: String): String? {
        if (!CoreWatch.isSafe(address) || remoteFile.isEmpty()) return null
        return HerdrApi.loginShell("hc put --json ${HerdrApi.shQuote(address)} ${HerdrApi.shQuote(remoteFile)}")
    }

    /** Whether a file of [bytes] is within what `hc put` takes. */
    fun fitsPut(bytes: Long): Boolean = bytes <= MAX_PUT_BYTES

    /** What [output] of `hc send` means. A null output or exit code is no answer. */
    fun resultOf(output: CommandOutput?): SendResult {
        val code = output?.exitCode ?: return SendResult.Failed("hc send did not answer; look in the terminal before sending again")
        if (code == 0) return SendResult.Sent
        val error = errorOf(output.stdout) ?: errorOf(output.stderr)
        if (error != null) {
            // The refusal of a blocked program is kind `refused` with "<t> is blocked (...)" (inferred from hc's strings).
            val refused = error.kind == "refused" || error.text.contains(" is blocked")
            return if (refused) SendResult.Refused(error.text) else SendResult.Failed(error.text)
        }
        return SendResult.Failed(output.stderr.trim().ifEmpty { "hc send failed (exit $code)" })
    }

    /** The path `hc put` printed, or null when it failed or printed none. A login shell may print a banner first. */
    fun landedPath(output: CommandOutput?): String? {
        if (output == null || output.exitCode != 0) return null
        val json = objectIn(output.stdout)
        val path = (json?.get("path") as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (!path.isNullOrEmpty()) return path
        return output.stdout.lines().map { it.trim() }.lastOrNull { it.startsWith("/") }
    }

    private class HcError(val kind: String, val text: String)

    // The kind, and `message. hint`, of an `{"error":{...}}` document in [text], or null when it holds none.
    private fun errorOf(text: String): HcError? {
        val error = objectIn(text)?.get("error") as? JsonObject ?: return null
        val message = (error["message"] as? JsonPrimitive)?.content.orEmpty()
        val hint = (error["hint"] as? JsonPrimitive)?.content.orEmpty()
        if (message.isEmpty()) return null
        return HcError((error["kind"] as? JsonPrimitive)?.content.orEmpty(), if (hint.isEmpty()) message else "$message. $hint")
    }

    private fun objectIn(text: String): JsonObject? {
        val start = text.indexOf('{').takeIf { it >= 0 } ?: return null
        return try {
            Json.parseToJsonElement(text.substring(start).trim()) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
    }
}

/**
 * The compose bar's actions on a watching console. Every one takes the watch over first, as a typed
 * key does ([CoreWatchState]); [takeOver] starts that (detach, then the plain attach).
 *
 * [exec] runs one command over an exec channel of the console's host; [write] types into the
 * console's shell; [settle] waits until the attach line has been typed and `hc attach` is reading, and returns at once when it is.
 */
class CoreCompose(
    private val state: CoreWatchState,
    private val takeOver: () -> Unit,
    private val exec: suspend (String) -> CommandOutput?,
    private val settle: suspend () -> Unit,
    private val write: (String) -> Unit,
) {
    private suspend fun takeOverFirst() {
        if (state.onInput(CoreSend.TYPED_KEY) == WatchInput.TAKE_OVER) takeOver()
        // Under way or just done: wait until `hc attach` is reading ([settle] returns at once when it is).
        settle()
    }

    /** Take the watch over, then `hc send` [text] to [address]: one paste, then Enter. */
    suspend fun send(address: String, text: String): SendResult {
        val command = CoreSend.sendCommand(address, text) ?: return SendResult.Failed("nothing to send to this terminal")
        takeOverFirst()
        val output = try {
            exec(command)
        } catch (_: IOException) {
            null
        }
        return CoreSend.resultOf(output)
    }

    /** Take the watch over, then type [text] into the terminal with no Enter (a paste, or a long press on Send). */
    suspend fun type(text: String, bracketed: Boolean) {
        takeOverFirst()
        write(BracketedPaste.wrap(text, bracketed))
    }
}
