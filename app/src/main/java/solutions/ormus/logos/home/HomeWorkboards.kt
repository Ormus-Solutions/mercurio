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

package solutions.ormus.logos.home

import org.connectbot.data.entity.Host
import solutions.ormus.logos.herd.HerdrConsole
import solutions.ormus.logos.herd.HerdrOpen
import solutions.ormus.logos.herd.HerdrSessions
import solutions.ormus.logos.herd.HerdrWorkspace

/** Where a workspace key opens: [console] (the session landed on the workspace) on [host]. */
data class WorkboardTarget(val host: Host, val console: HerdrConsole)

/** Why a workspace key cannot open; the home says it in words. */
enum class WorkboardOff {
    /** The feed names no session for the workspace or its board. */
    NO_SESSION,

    /** The session the feed names is not a name Herdr gives. */
    BAD_SESSION,

    /** The feed places the workspace on no machine. */
    NO_MACHINE,

    /** The feed lists no names for the machine, so no saved host can be matched. */
    UNNAMED_MACHINE,

    /** No saved SSH host is on the machine, or none runs Herdr there. */
    NO_HOST,
}

/**
 * One key of the home: [workspace] of [board], and where a tap opens it. [target] is set when
 * a saved SSH host is on the workspace's machine (as the feed names it) and the session is a
 * name Herdr could have given; else [off] says why, and the key is drawn and off.
 */
data class WorkboardKey(
    val board: HomeBoard,
    val workspace: HomeWorkspace,
    val target: WorkboardTarget? = null,
    val off: WorkboardOff? = null,
) {
    /** The workspace's name as drawn and as the landing looks for it: trimmed. */
    val name: String = workspace.name.trim()

    /** The feed's alias of the machine the workspace lives on: its own, else the board's. */
    val machine: String? = workspace.machine ?: board.machine

    /** The Herdr session the workspace is in: its own, else the board's. */
    val session: String? = workspace.session ?: board.session

    val opens: Boolean
        get() = target != null
}

/**
 * One block of the home: a workboard and its keys, in the feed's order. [shortcut] is the host a
 * Save on the board makes ([HomeShortcuts.newHost]) while no saved host attaches the board's
 * session on its machine; null once one does, or when none can be made.
 */
data class WorkboardBlock(val board: HomeBoard, val keys: List<WorkboardKey>, val shortcut: Host? = null)

/** The workboards the home draws from the feed, joined with the saved hosts. Pure. */
object HomeWorkboards {
    /**
     * Every board of [boards] in the feed's order, each with one key per workspace that has a
     * name. A key opens on the saved host [HerdrOpen.resolve] picks among [hosts] for the
     * workspace's session and the feed's names of its machine ([HomeBoards.hosts]): the copy
     * whose post-login attaches the session, else a plain `herdr` host, a [connected] one
     * first. Nothing opens for a board with no machine or session, or one whose machine the
     * feed does not name. A board no saved host attaches carries the host its Save key makes.
     */
    fun blocks(boards: HomeBoards?, hosts: List<Host>, connected: Set<Long> = emptySet()): List<WorkboardBlock> {
        val feed = boards ?: return emptyList()
        return feed.boards.map { board ->
            WorkboardBlock(
                board,
                board.workspaces.filter { it.name.isNotBlank() }.map { key(board, it, feed.hosts, hosts, connected) },
                shortcut = if (HomeShortcuts.saved(board, feed, hosts) == null) HomeShortcuts.newHost(board, feed, hosts) else null,
            )
        }
    }

    /**
     * The kept Workspaces rows no opening key of [blocks] already stands for. A row is covered
     * when a key opens the same host and session and the row's workspace is one the keys open,
     * or the row names no workspace (the session as it is, which the keys open too). The home
     * shows the rest beside the blocks, so a kept key never goes missing.
     */
    fun uncovered(workspaces: List<HerdrWorkspace>, blocks: List<WorkboardBlock>): List<HerdrWorkspace> {
        val covered = blocks.flatMap { it.keys }.mapNotNull { it.target }
            .groupBy({ it.host.id to it.console.session }, { it.console.workspaceLabel })
        return workspaces.filter { row ->
            val labels = covered[row.host.id to row.console.session] ?: return@filter true
            val label = row.console.workspaceLabel
            label != null && label !in labels
        }
    }

    private fun key(board: HomeBoard, workspace: HomeWorkspace, names: Map<String, List<String>>, hosts: List<Host>, connected: Set<Long>): WorkboardKey {
        val key = WorkboardKey(board, workspace)
        val session = key.session ?: return key.copy(off = WorkboardOff.NO_SESSION)
        if (!HerdrSessions.isName(session)) return key.copy(off = WorkboardOff.BAD_SESSION)
        val machine = key.machine ?: return key.copy(off = WorkboardOff.NO_MACHINE)
        val known = HerdrOpen.names(names[machine]?.joinToString(","), null)
        if (known.isEmpty()) return key.copy(off = WorkboardOff.UNNAMED_MACHINE)
        val target = HerdrOpen.resolve(hosts, session, known, connected = connected) ?: return key.copy(off = WorkboardOff.NO_HOST)
        return key.copy(target = WorkboardTarget(target.host, HerdrConsole(session, workspaceLabel = key.name)))
    }
}
