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
import kotlinx.serialization.json.intOrNull
import kotlin.math.floor

/** The core terminal a console watches: its address, and the space and tab it sits in. */
data class CoreWatchTarget(val address: String, val space: String, val tab: String) {
    /** The console's name in the drawer: `space · tab`, or the space alone when the tab has no name. */
    val label: String get() = if (space.isBlank()) {
        address
    } else if (tab.isBlank()) {
        space
    } else {
        "$space · $tab"
    }
}

/**
 * Watching a herdr-core terminal from a console. The console's shell types `hc attach --observe <t>`:
 * the core never changes size for it (measured on the spike). The first key typed detaches (ctrl+\)
 * and attaches again without `--observe`, so the terminal follows the phone from then on; the
 * desktop resizes at that point. `hc` itself prints `[<t> finished: ...]` when the terminal ends
 * and `[detached from <t>]` on a detach (measured), so the console shows an end without help.
 */
object CoreWatch {
    /** ctrl+\: how `hc attach` is left. */
    const val DETACH: Int = 0x1c

    /** How long the takeover waits after the detach before it types the second attach (inferred; the shell needs its prompt back). */
    const val DETACH_WAIT_MS = 500L

    /** The smallest font a watch shrinks to, sp: below it the text is not readable. */
    const val MIN_FONT_SP = 4f

    private const val FONT_STEP_SP = 0.5f

    // An address as `hc ls` gives it (`t7`, `box/t7`): nothing a shell would read as more than a word.
    private val SAFE = Regex("""[A-Za-z0-9_][A-Za-z0-9_./-]*""")

    /** Whether [address] is a plain word a shell reads as one argument. */
    fun isSafe(address: String): Boolean = SAFE.matches(address)

    /** The terminal [agent] sits in, or null when its address is not a plain word. */
    fun target(agent: Agent): CoreWatchTarget? = agent.address.takeIf { SAFE.matches(it) }
        ?.let { CoreWatchTarget(it, agent.space, agent.tab) }

    /** What the console's shell is given to watch [address]; null for an address that is not a plain word. */
    fun observeCommand(address: String): String? = address.takeIf { SAFE.matches(it) }?.let { "hc attach --observe '$it'\n" }

    /** What it is given once the user has taken the terminal over. */
    fun takeOverCommand(address: String): String? = address.takeIf { SAFE.matches(it) }?.let { "hc attach '$it'\n" }

    /** The exec command that reads the terminal's size (`cols`), through the login shell. */
    fun getCommand(address: String): String? = address.takeIf { SAFE.matches(it) }?.let { HerdrApi.loginShell("hc get '$it' --json") }

    /** The `cols` of `hc get --json` output, or null when it has none above 0. A login shell may print a banner first. */
    fun colsOf(output: String): Int? {
        val start = output.indexOf('{').takeIf { it >= 0 } ?: return null
        val root = try {
            Json.parseToJsonElement(output.substring(start)) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
        return ((root?.get("cols") as? JsonPrimitive)?.intOrNull)?.takeIf { it > 0 }
    }

    /**
     * The font, sp, at which a core terminal of [coreCols] columns fits a screen that holds [phoneCols]
     * columns at [normalSp]: cell width grows with the font, so the font scales by the ratio, rounded
     * down to a half step so the lines fit. Never above [normalSp] and never below [MIN_FONT_SP];
     * [normalSp] when either width is unknown.
     */
    fun fitFont(normalSp: Float, phoneCols: Int, coreCols: Int): Float {
        if (phoneCols <= 0 || coreCols <= 0 || phoneCols >= coreCols) return normalSp
        val fit = floor(normalSp * phoneCols / coreCols / FONT_STEP_SP) * FONT_STEP_SP
        return fit.coerceIn(minOf(MIN_FONT_SP, normalSp), normalSp)
    }

    /**
     * Whether [bytes], written by the terminal emulator to the host, are a key the user pressed. The
     * emulator also answers the program's queries (cursor position, device attributes, status, mode,
     * colours, focus): those are not keys and must not take the terminal over.
     */
    fun isTyped(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        if (bytes[0].toInt() != 0x1b || bytes.size < 2) return true
        return when (bytes[1].toInt().toChar()) {
            ']', 'P', '_', '^', 'X' -> false

            '[' -> {
                val final = bytes.drop(2).firstOrNull { it.toInt() in 0x40..0x7e }?.toInt()?.toChar()
                val bare = bytes.size == 3
                !(final in REPLY_FINALS || (bare && (final == 'I' || final == 'O')))
            }

            else -> true
        }
    }

    private val REPLY_FINALS = setOf('R', 'c', 'n', 't', 'y')
}

/** What to do with bytes the emulator wrote while a console watches. */
enum class WatchInput { PASS, TAKE_OVER, HOLD }

/**
 * Whether a watching console still only watches. The first key typed starts the takeover (once);
 * until [takeOverDone] every byte is held, so keys typed meanwhile do not land inside the attach line.
 */
class CoreWatchState {
    @Volatile
    var watching: Boolean = true
        private set

    @Volatile
    private var takingOver = false

    /** Whether a takeover is under way: the detach was typed and the attach line was not yet. */
    val isTakingOver: Boolean get() = takingOver

    /** Take [bytes] the emulator wrote: pass them on, hold them, or start the takeover with them. */
    @Synchronized
    fun onInput(bytes: ByteArray): WatchInput {
        if (takingOver) return WatchInput.HOLD
        if (!watching || !CoreWatch.isTyped(bytes)) return WatchInput.PASS
        watching = false
        takingOver = true
        return WatchInput.TAKE_OVER
    }

    /** The attach line was typed: bytes pass again. */
    @Synchronized
    fun takeOverDone() {
        takingOver = false
    }
}
