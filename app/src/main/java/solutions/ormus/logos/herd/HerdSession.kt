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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.service.FixPrompt
import org.connectbot.transport.CommandOutput
import timber.log.Timber

/** How the last Herdr action went, for the one result line in the command sheet. */
sealed interface HerdResult {
    val action: HerdrAction

    data class Running(override val action: HerdrAction) : HerdResult

    /** It worked; [output] is what it printed when the sheet should show it (explain). */
    data class Succeeded(override val action: HerdrAction, val output: String? = null) : HerdResult

    /** Nothing to act on yet (no focused pane, no blocked agent, a blank name). */
    data class Unavailable(override val action: HerdrAction) : HerdResult

    /** It failed; [message] is Herdr's error, one short line. */
    data class Failed(override val action: HerdrAction, val message: String) : HerdResult
}

/**
 * A Herdr command that failed: what ran, its exit code (null when it threw or never ran),
 * and its output. [sessionUp] is false when no SSH session was there to run it.
 */
data class HerdrFailure(
    val command: String,
    val exitCode: Int?,
    val stderr: String,
    val stdout: String,
    val sessionUp: Boolean,
)

// Esc leaves Herdr's prefix mode and is swallowed there.
private val ESC: String = 27.toChar().toString()

/**
 * The live Herdr side of one SSH session whose host runs Herdr: polls the
 * snapshot with [HerdrMonitor] while the session is up, and runs the command
 * sheet's actions against the focused ids in the latest snapshot.
 *
 * [runner] runs one command over an exec channel beside the PTY and blocks
 * (SSH.execDetailed); null means it never ran. [sendKeys] writes client
 * keystrokes to the PTY. Blocking work runs on [io].
 */
class HerdSession(
    private val hostName: String,
    private val runner: (String) -> CommandOutput?,
    private val sendKeys: (String) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val commands: HerdrCommands = HerdrCommands(),
    pollIntervalMs: Long = POLL_INTERVAL_MS,
    /** The time for the attention rules (a fake clock in tests). */
    clock: () -> Long = System::currentTimeMillis,
    /** Whether this session's console is on screen now (the user is looking at its focused pane). */
    private val isOnScreen: () -> Boolean = { false },
    /** Notifications the attention rules want posted. */
    private val onNotices: (List<HerdAttention.Notice>) -> Unit = {},
) {
    private val attention = HerdAttention(clock)

    private val _snapshot = MutableStateFlow<HerdrSessionSnapshot?>(null)

    /** The latest snapshot, null until the first poll lands. */
    val snapshot: StateFlow<HerdrSessionSnapshot?> = _snapshot.asStateFlow()

    private val _failure = MutableStateFlow<HerdrFailure?>(null)

    /** The last Herdr command that failed on this host, null once one succeeds. */
    val failure: StateFlow<HerdrFailure?> = _failure.asStateFlow()

    private val _result = MutableStateFlow<HerdResult?>(null)

    /** The last action's outcome, null before any. */
    val result: StateFlow<HerdResult?> = _result.asStateFlow()

    private val _keys = MutableStateFlow(HerdrKeys.DEFAULT)

    /** Herdr's prefix and client keys on this host, read from its config when the session starts. */
    val keys: StateFlow<HerdrKeys> = _keys.asStateFlow()

    private val monitor = HerdrMonitor(
        hostName = hostName,
        runCommand = { command -> stdoutOrNull(command) },
        scope = CoroutineScope(scope.coroutineContext + io),
        pollIntervalMs = pollIntervalMs,
        onSnapshot = { snapshot ->
            _snapshot.value = snapshot
            val notices = attention.onSnapshot(snapshot, isOnScreen())
            if (notices.isNotEmpty()) onNotices(notices.map(::withPaneTail))
        },
    )

    // A "Needs you" carries the end of its pane, so the notification shows what Approve
    // answers. Runs on the poller's io thread; the text is never logged.
    private fun withPaneTail(notice: HerdAttention.Notice): HerdAttention.Notice {
        if (notice !is HerdAttention.Notice.NeedsYou) return notice
        val read = stdoutOrNull(Herd.readCommand(notice.agent.paneId, HerdApproval.READ_LINES))
        return notice.copy(paneTail = read?.let { HerdApproval.tail(it) })
    }

    /** Start polling (again); a restart forgets the old state so it is not read as a transition. */
    fun start() {
        attention.reset()
        monitor.stop()
        monitor.start()
        scope.launch { readKeys() }
    }

    fun stop() {
        monitor.stop()
    }

    /** Fetch a snapshot now, outside the poll rhythm (after an action, or on opening a screen). */
    suspend fun refresh() {
        val raw = withContext(io) { stdoutOrNull(Herd.snapshotCommand) } ?: return
        parseHerdrSnapshot(raw)?.let { _snapshot.value = it }
    }

    /**
     * Read Herdr's keys from the host's config. Off the failure line on purpose: a
     * config that cannot be read is the prefix's [HerdrPrefix.problem], not a Herdr failure.
     */
    suspend fun readKeys() {
        val out = withContext(io) {
            try {
                runner(HerdrPrefix.readCommand)
            } catch (e: Exception) {
                Timber.w(e, "Reading the Herdr keys failed on %s", hostName)
                null
            }
        }
        _keys.value = HerdrKeys.fromRead(out)
    }

    // Whether Herdr's prefix ([keys]) went ahead of the next keystroke: the Herdr key
    // sends it as the panel opens. Herdr stays in prefix mode until a key arrives
    // (measured on Sun, herdr 0.9.0), so it is either used by the next Herdr keystroke
    // or cancelled with Esc, which Herdr swallows.
    private var prefixArmed = false

    /** Send Herdr's prefix now, so the next Herdr keystroke needs only its key. */
    fun armPrefix() {
        if (prefixArmed) return
        prefixArmed = true
        sendKeys(_keys.value.prefix.bytes)
    }

    /** Leave prefix mode without acting: Esc, which Herdr swallows. Nothing when not armed. */
    fun cancelPrefix() {
        if (!prefixArmed) return
        prefixArmed = false
        sendKeys(ESC)
    }

    /**
     * What to write for [sequence] given the armed prefix: a Herdr keystroke (prefix, then
     * key) sends just its key; anything else cancels prefix mode first.
     */
    fun keystroke(sequence: String): String {
        if (!prefixArmed) return sequence
        val prefix = _keys.value.prefix.bytes
        if (sequence.startsWith(prefix)) {
            prefixArmed = false
            return sequence.removePrefix(prefix)
        }
        cancelPrefix()
        return sequence
    }

    /** Clear the result line (the sheet closed). */
    fun clearResult() {
        _result.value = null
    }

    /**
     * Run [action] (with [text] for a rename). Keystrokes go to the PTY at once;
     * commands run off the main thread, and success refreshes the snapshot.
     */
    fun perform(action: HerdrAction, text: String? = null) {
        when (val invocation = commands.build(action, _snapshot.value, text, _keys.value)) {
            null -> _result.value = HerdResult.Unavailable(action)

            is HerdrInvocation.Keys -> {
                sendKeys(keystroke(invocation.sequence))
                _result.value = HerdResult.Succeeded(action)
            }

            is HerdrInvocation.Cli -> {
                // A command runs beside the PTY; a waiting prefix would eat the next keystroke.
                cancelPrefix()
                _result.value = HerdResult.Running(action)
                scope.launch {
                    val out = withContext(io) { run(invocation.command) }
                    val error = HerdrApi.parseCommandError(out)
                    _result.value = if (error != null) {
                        HerdResult.Failed(action, oneLine(error.message.ifBlank { error.code }))
                    } else {
                        HerdResult.Succeeded(action, out?.stdout?.trim()?.takeIf { action == HerdrAction.ExplainDetection })
                    }
                    if (error == null) refresh()
                }
            }
        }
    }

    /**
     * Answer the blocked agent in [paneId] from its notification: read a fresh snapshot
     * and send [answer]'s key only if that pane is still blocked on an answerable agent.
     */
    suspend fun answer(paneId: String, answer: HerdAnswer): HerdAnswerOutcome = withContext(io) {
        val snapshot = stdoutOrNull(Herd.snapshotCommand)?.let { parseHerdrSnapshot(it) }
            ?: return@withContext HerdAnswerOutcome.Failed(FixPrompt.herdrReason(_failure.value))
        _snapshot.value = snapshot
        if (!HerdApproval.stillBlocked(snapshot, paneId)) return@withContext HerdAnswerOutcome.NotBlocked
        val error = HerdrApi.parseCommandError(run(HerdApproval.sendKeysCommand(paneId, answer)))
        if (error != null) return@withContext HerdAnswerOutcome.Failed(oneLine(error.message.ifBlank { error.code }))
        attention.answered(paneId)
        HerdAnswerOutcome.Sent
    }

    private fun stdoutOrNull(command: String): String? = run(command)?.takeIf { it.exitCode == null || it.exitCode == 0 }?.stdout

    private fun run(command: String): CommandOutput? {
        val out = try {
            runner(command)
        } catch (e: Exception) {
            // Timeouts, a dropped connection: report it as "never ran", not a crash.
            Timber.w(e, "Herdr command failed on %s", hostName)
            _failure.value = HerdrFailure(command, exitCode = null, stderr = e.toString(), stdout = "", sessionUp = true)
            return null
        }
        _failure.value = when {
            out == null -> HerdrFailure(command, exitCode = null, stderr = "", stdout = "", sessionUp = false)
            out.exitCode != null && out.exitCode != 0 -> HerdrFailure(command, out.exitCode, out.stderr, out.stdout, sessionUp = true)
            else -> null
        }
        return out
    }

    private fun oneLine(message: String): String = message.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(MAX_ERROR_CHARS) ?: message

    companion object {
        const val POLL_INTERVAL_MS = 4_000L
        private const val MAX_ERROR_CHARS = 160
    }
}
