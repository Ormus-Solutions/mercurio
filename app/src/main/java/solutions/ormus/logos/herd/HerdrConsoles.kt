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
import org.connectbot.transport.CommandOutput

/**
 * One more console on a Herdr host: its own SSH session, beside the console the host
 * already has, whose terminal attaches Herdr [session] and lands on [workspaceId]
 * ([workspaceLabel] names it). With no id it lands on the workspace of that label, and
 * with neither wherever the session's focus is. Herdr 0.9.0
 * gives every client its own workspace and tab ("Multiple clients can now view different
 * workspaces and tabs independently", its changelog), so the consoles of one host can
 * show different workspaces, of one session or of several.
 */
data class HerdrConsole(
    val session: String,
    val workspaceId: String? = null,
    val workspaceLabel: String? = null,
) {
    init {
        require(HerdrSessions.isName(session)) { "Unexpected Herdr session name: $session" }
    }

    /**
     * Whether this console answers [request], a console asked for by session and label (a
     * Workspaces key, which never carries a workspace id): the same session, and the same
     * label when the request names one. A request with no label is content with any console
     * of its session, whatever workspace that one opened on.
     */
    fun shows(request: HerdrConsole): Boolean = session == request.session && (request.workspaceLabel == null || workspaceLabel == request.workspaceLabel)
}

/** How a new console reaches its workspace. */
sealed interface HerdrLanding {
    /** Attach as it is: no workspace asked, or the session's focus is already there. */
    data object AsIs : HerdrLanding

    /**
     * `workspace focus` before the attach, as the connect picker does. Herdr moves every
     * client of the session there (an API focus reaches them all; herdr 0.9.3,
     * `focus_all_shell_clients_on_default_target`), so this is the way only while no
     * other console of this phone shows the session.
     */
    data class Focus(val workspaceId: String) : HerdrLanding

    /**
     * Herdr's own switcher keys, typed in the new client once it is on screen. A client's
     * own navigation moves that client alone, so the other consoles of the session stay
     * where they are. A new client opens where the session's focus is (the snapshot's
     * focused workspace), so the steps count from there.
     */
    data class Keys(val strokes: List<HerdrStroke>) : HerdrLanding
}

/** Where and how extra consoles of a Herdr host attach. */
object HerdrConsoles {
    /** How long a console waits for Herdr's client on its terminal before it gives up its keys. */
    const val ON_SCREEN_WAIT_MS = 10_000L

    /**
     * How long the keys wait after Herdr's client went up, so its first frame and its
     * endpoint snapshot are in before the switcher opens.
     */
    const val SETTLE_MS = 600L

    /**
     * The host's console list, read when its first console connects: one console per line,
     * the Herdr session, then after a space the label of the workspace to land on; `#`
     * starts a comment. The operator keeps it on the host, so every phone opens the same.
     */
    const val LIST_PATH = "~/.config/mercurio/consoles"

    /**
     * Prints [LIST_PATH], or nothing and exits 0 when the host has none, so a missing file
     * reads as an empty list and only a read that fails reads as unknown ([read]).
     */
    const val LIST_COMMAND = "cat $LIST_PATH 2>/dev/null || true"

    /** At most this many consoles open on connect, the host's first one included. */
    const val MAX_ON_CONNECT = 8

    private val SPACE = Regex("\\s+")

    /**
     * The consoles [LIST_PATH] lists, in its order: lines whose session is not a name Herdr
     * gives are left out, then repeats, then every one past [MAX_ON_CONNECT].
     */
    fun parseList(raw: String): List<HerdrConsole> = raw.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val parts = line.split(SPACE, limit = 2)
            val label = parts.getOrNull(1)?.trim()?.ifEmpty { null }
            if (HerdrSessions.isName(parts[0])) HerdrConsole(parts[0], workspaceLabel = label) else null
        }
        .distinct()
        .take(MAX_ON_CONNECT)
        .toList()

    /**
     * The consoles to open as the host's first console connects: its list ([LIST_PATH]), kept
     * to the Herdr sessions that run now (`herdr session list`), in the list's order. Null when
     * the host has no list, none of its sessions runs, or a read does not answer within the
     * picker's wait; the connect picker then runs as before. [run] is as in [land].
     */
    suspend fun onConnect(
        scope: CoroutineScope,
        io: CoroutineDispatcher,
        run: (command: String, timeoutMs: Long) -> CommandOutput?,
    ): List<HerdrConsole>? = read(scope, io, run)?.let { running(it, scope, io, run) }?.ifEmpty { null }

    /**
     * What the host lists ([LIST_PATH]), in its order, stopped sessions included. Empty when it
     * lists nothing (no file, or no line with a name Herdr could give); null when the read did
     * not answer within the picker's wait, failed, or ended with no exit status (a dropped
     * channel), so a slow host says nothing about its list. [run] is as in [land].
     */
    suspend fun read(
        scope: CoroutineScope,
        io: CoroutineDispatcher,
        run: (command: String, timeoutMs: Long) -> CommandOutput?,
    ): List<HerdrConsole>? {
        val waitMs = HerdrWorkspacePicker.SNAPSHOT_WAIT_MS
        return HerdrWorkspacePicker.within(scope, io, waitMs) { run(LIST_COMMAND, waitMs) }.exited()?.let(::parseList)
    }

    /**
     * [listed] kept to the Herdr sessions that run now (`herdr session list`), in its order:
     * empty when none of them runs; null when the session list did not answer. [run] is as in [land].
     */
    suspend fun running(
        listed: List<HerdrConsole>,
        scope: CoroutineScope,
        io: CoroutineDispatcher,
        run: (command: String, timeoutMs: Long) -> CommandOutput?,
    ): List<HerdrConsole>? {
        if (listed.isEmpty()) return emptyList()
        val waitMs = HerdrWorkspacePicker.SNAPSHOT_WAIT_MS
        val running = HerdrWorkspacePicker.within(scope, io, waitMs) { run(HerdrSessions.listCommand, waitMs) }.exited()?.let(HerdrSessions::parse)
            ?: return null
        return listed.filter { it.session in running }
    }

    private fun CommandOutput?.ok(): String? = this?.takeIf { it.exitCode == null || it.exitCode == 0 }?.stdout

    // The output of a command that ran to its end and exited 0: a channel closed with no exit status says nothing.
    private fun CommandOutput?.exited(): String? = this?.takeIf { it.exitCode == 0 }?.stdout

    /**
     * How [console] lands: [snapshot] is its session's, read just before the attach; [keys]
     * and [crossesMachines] are the host's ([HerdSession.keys], [HerdSession.crossesMachines]);
     * [shared] says another console of this phone shows the session now. The keys only when
     * they can count the steps: both workspaces in the snapshot, no saved machine in the
     * switcher's walk, and the host binds the switcher keys. Otherwise the focus, which
     * lands right and moves the session's other clients too.
     */
    fun landing(
        console: HerdrConsole,
        snapshot: HerdrSessionSnapshot?,
        keys: HerdrKeys,
        crossesMachines: Boolean,
        shared: Boolean,
    ): HerdrLanding {
        val target = console.workspaceId
            ?: console.workspaceLabel?.let { label -> snapshot?.workspaces?.firstOrNull { it.label == label }?.workspaceId }
            ?: return HerdrLanding.AsIs
        val from = snapshot?.focusedWorkspaceId
        if (from == target) return HerdrLanding.AsIs
        if (!shared || snapshot == null || from == null || crossesMachines) return HerdrLanding.Focus(target)
        val order = snapshot.workspaces.sortedBy { it.number }.map { it.workspaceId }
        if (target !in order || from !in order) return HerdrLanding.Focus(target)
        val strokes = keys.switchWorkspaceBy(order.indexOf(target) - order.indexOf(from))
        return strokes?.let { HerdrLanding.Keys(it) } ?: HerdrLanding.Focus(target)
    }

    /**
     * Read [console]'s session snapshot, pick its [landing] and run the focus when that is
     * the way. The caller attaches next, and types [HerdrLanding.Keys] once Herdr's client
     * is on its terminal. [run] runs one command over an exec channel with a timeout and
     * blocks; it may throw. A slow host costs the picker's waits and no more: a snapshot
     * that does not come reads as none.
     */
    suspend fun land(
        console: HerdrConsole,
        scope: CoroutineScope,
        io: CoroutineDispatcher,
        run: (command: String, timeoutMs: Long) -> CommandOutput?,
        keys: HerdrKeys,
        crossesMachines: Boolean,
        shared: Boolean,
    ): HerdrLanding {
        if (console.workspaceId == null && console.workspaceLabel == null) return HerdrLanding.AsIs
        val waitMs = HerdrWorkspacePicker.SNAPSHOT_WAIT_MS
        val snapshot = HerdrWorkspacePicker.within(scope, io, waitMs) { run(HerdrSessions.snapshotCommand(console.session), waitMs) }
            .ok()
            ?.let(::parseHerdrSnapshot)
        val landing = landing(console, snapshot, keys, crossesMachines, shared)
        if (landing is HerdrLanding.Focus) {
            val focus = HerdrSessions.commands(console.session)
                .build(HerdrAction.FocusWorkspace(landing.workspaceId), snapshot) as? HerdrInvocation.Cli
            val focusMs = HerdrWorkspacePicker.FOCUS_WAIT_MS
            if (focus != null) HerdrWorkspacePicker.within(scope, io, focusMs) { run(focus.command, focusMs) }
        }
        return landing
    }

    /**
     * A console's name in the drawer and the title bar: the host, then the Herdr session its
     * terminal [attached] unless that is the default, then the workspace an extra [console]
     * opened on, while it still shows that console's session (a jump can attach another).
     */
    fun label(host: String, attached: String, console: HerdrConsole?): String = buildList {
        add(host)
        if (!HerdrSessions.isDefault(attached)) add(attached)
        console?.takeIf { it.session == attached }?.workspaceLabel?.takeIf { it.isNotBlank() }?.let(::add)
    }.joinToString(" · ")
}
