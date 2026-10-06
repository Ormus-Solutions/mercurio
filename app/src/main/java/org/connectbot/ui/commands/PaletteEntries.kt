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

package org.connectbot.ui.commands

import android.content.res.Resources
import org.connectbot.R
import org.connectbot.data.TailscaleDevice
import org.connectbot.data.entity.Host
import org.connectbot.ui.navigation.NavDestinations
import org.connectbot.ui.screens.herd.herdrSheetUsageId
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.commands.PaletteAction
import solutions.ormus.logos.commands.PaletteEntry
import solutions.ormus.logos.commands.PaletteGroup
import solutions.ormus.logos.commands.PaletteScreen
import solutions.ormus.logos.commands.SlashCommand
import solutions.ormus.logos.commands.SlashSource
import solutions.ormus.logos.commands.TerminalKeys
import solutions.ormus.logos.herd.HerdrAction
import solutions.ormus.logos.herd.HerdrDirection
import solutions.ormus.logos.herd.HerdrKey
import solutions.ormus.logos.herd.HerdrKeys
import solutions.ormus.logos.herd.HerdrSessionSnapshot

/** An open session the palette can switch to. */
data class PaletteSession(val hostId: Long, val nickname: String)

/**
 * What the console knows when the palette opens: which session is on screen,
 * whether it runs Herdr, the other sessions, saved hosts and tailnet machines.
 */
data class PaletteContext(
    val hasSession: Boolean = false,
    /** The session's host lands in Herdr (the compose bar shows the Herdr row). */
    val runsHerdr: Boolean = false,
    /** The session's Herdr side is up, so sheet actions can run. */
    val herdrReady: Boolean = false,
    val snapshot: HerdrSessionSnapshot? = null,
    /** The session's Herdr keys, read from its host's config. */
    val herdrKeys: HerdrKeys = HerdrKeys.DEFAULT,
    val disconnected: Boolean = false,
    val canForwardPorts: Boolean = false,
    val sessions: List<PaletteSession> = emptyList(),
    val hosts: List<Host> = emptyList(),
    val machines: List<TailscaleDevice> = emptyList(),
)

/** The Herdr sheet actions the palette offers, besides tab and workspace jumps. */
val PALETTE_HERDR_ACTIONS: List<HerdrAction> = buildList {
    add(HerdrAction.FocusNextBlocked)
    HerdrAction.START_KINDS.forEach { add(HerdrAction.StartAgent(it)) }
    add(HerdrAction.ExplainDetection)
    add(HerdrAction.SplitRight)
    add(HerdrAction.SplitDown)
    add(HerdrAction.ZoomPane)
    HerdrDirection.entries.forEach { add(HerdrAction.FocusPane(it)) }
    HerdrDirection.entries.forEach { add(HerdrAction.SwapPane(it)) }
    add(HerdrAction.RenamePane)
    add(HerdrAction.ClosePane)
    add(HerdrAction.NewTab)
    add(HerdrAction.RenameTab)
    add(HerdrAction.CloseTab)
    add(HerdrAction.NewWorkspace)
    add(HerdrAction.RenameWorkspace)
    add(HerdrAction.CloseWorkspace)
    add(HerdrAction.COPY_MODE)
    add(HerdrAction.GOTO)
    add(HerdrAction.OPEN_NOTIFICATION)
    add(HerdrAction.DETACH)
    add(HerdrAction.RELOAD_CONFIG)
    add(HerdrAction.HELP)
    add(HerdrAction.TOGGLE_SIDEBAR)
    add(HerdrAction.NAVIGATE_WORKSPACES)
    add(HerdrAction.StopSession)
}

/**
 * Every palette entry for [context], grouped in palette order: compose bar
 * keys, the agent's slash commands, Herdr actions, open sessions, saved hosts,
 * machines, console tools, then app screens and settings.
 *
 * @param agentName the agent the slash commands belong to
 * @param herdrLabels each of [PALETTE_HERDR_ACTIONS]'s button label in the Herdr sheet
 */
fun buildPaletteEntries(
    res: Resources,
    context: PaletteContext,
    agentName: String,
    slash: List<SlashCommand>,
    herdrLabels: Map<HerdrAction, String>,
): List<PaletteEntry> = buildList {
    fun s(id: Int): String = res.getString(id)

    // Compose bar keys.
    if (context.hasSession) {
        val keySubtitle = s(R.string.palette_subtitle_key)
        fun key(id: String, title: Int, sequence: String, usageId: String, keywords: String = "") = add(
            PaletteEntry("key:$id", s(title), PaletteGroup.KEYS, PaletteAction.Key(sequence), keySubtitle, usageId, keywords),
        )
        key("up", R.string.palette_key_up, TerminalKeys.UP, UsageActions.KEY_UP, "arrow")
        key("down", R.string.palette_key_down, TerminalKeys.DOWN, UsageActions.KEY_DOWN, "arrow")
        key("left", R.string.palette_key_left, TerminalKeys.LEFT, UsageActions.KEY_LEFT, "arrow")
        key("right", R.string.palette_key_right, TerminalKeys.RIGHT, UsageActions.KEY_RIGHT, "arrow")
        key("enter", R.string.palette_key_enter, TerminalKeys.ENTER, UsageActions.KEY_ENTER, "return confirm")
        key("esc", R.string.palette_key_escape, TerminalKeys.ESCAPE, UsageActions.KEY_ESC, "esc cancel")
        key("mode", R.string.palette_key_mode, TerminalKeys.MODE, UsageActions.KEY_MODE, "plan auto-accept")
        key("stop", R.string.palette_key_stop, TerminalKeys.STOP, UsageActions.KEY_STOP, "interrupt cancel")
        key("tab", R.string.palette_key_tab, TerminalKeys.TAB, UsageActions.KEY_TAB, "complete")
        key("y", R.string.palette_key_yes, "y", UsageActions.KEY_Y, "yes")
        key("n", R.string.palette_key_no, "n", UsageActions.KEY_N, "no")
        if (context.runsHerdr) {
            // As the host binds them; a key it binds to nothing Mercurio can send is left out.
            fun herdrKey(id: String, title: Int, action: HerdrKey, usageId: String, keywords: String) {
                context.herdrKeys.bytes(action)?.let { key(id, title, it, usageId, keywords) }
            }
            herdrKey("needs-you", R.string.herdr_key_needs_you, HerdrKey.OPEN_NOTIFICATION, UsageActions.HERDR_NEEDS_YOU, "herdr")
            herdrKey("goto", R.string.herdr_key_goto, HerdrKey.GOTO, UsageActions.HERDR_GOTO, "herdr")
            herdrKey("prev-tab", R.string.palette_key_prev_tab, HerdrKey.PREVIOUS_TAB, UsageActions.HERDR_PREV_TAB, "herdr")
            herdrKey("next-tab", R.string.palette_key_next_tab, HerdrKey.NEXT_TAB, UsageActions.HERDR_NEXT_TAB, "herdr")
            herdrKey("scroll", R.string.herdr_key_scroll, HerdrKey.COPY_MODE, UsageActions.HERDR_SCROLL, "herdr copy mode")
        }
    }

    // The agent's slash commands, and the menu that lists them.
    if (context.hasSession) {
        add(
            PaletteEntry(
                "open:slash",
                s(R.string.slash_menu_title),
                PaletteGroup.AGENT,
                PaletteAction.Open(PaletteScreen.SLASH_MENU),
                s(R.string.palette_subtitle_slash_menu),
                UsageActions.BAR_SLASH_MENU,
                "/",
            ),
        )
        slash.forEach { command ->
            val from = when (command.source) {
                SlashSource.BUILT_IN -> s(R.string.palette_slash_builtin)
                SlashSource.COMMAND -> s(R.string.slash_source_command)
                SlashSource.SKILL -> s(R.string.slash_source_skill)
                SlashSource.PROJECT -> s(R.string.slash_source_project)
            }
            add(
                PaletteEntry(
                    "slash:${command.source}:${command.name}",
                    command.text,
                    PaletteGroup.AGENT,
                    PaletteAction.Slash(command),
                    res.getString(R.string.palette_subtitle_slash, agentName, from),
                    keywords = command.description,
                ),
            )
        }
    }

    // Herdr: the sheet, every sheet action, and jumps to tabs and workspaces.
    if (context.herdrReady) {
        add(
            PaletteEntry(
                "open:herdr-sheet",
                s(R.string.herdr_key_commands),
                PaletteGroup.HERDR,
                PaletteAction.Open(PaletteScreen.HERDR_SHEET),
                usageId = UsageActions.HERDR_SHEET_OPEN,
                keywords = "commands sheet",
            ),
        )
        PALETTE_HERDR_ACTIONS.forEach { action ->
            val label = herdrLabels[action] ?: return@forEach
            if (action is HerdrAction.BoundKey && context.herdrKeys.bytes(action.key) == null) return@forEach
            add(PaletteEntry("herdr:$action", label, PaletteGroup.HERDR, PaletteAction.Herdr(action), s(R.string.palette_group_herdr), herdrSheetUsageId(action)))
        }
        val snapshot = context.snapshot
        snapshot?.tabs.orEmpty()
            .filter { it.workspaceId == snapshot?.focusedWorkspaceId }
            .sortedBy { it.number }
            .forEach { tab ->
                add(
                    PaletteEntry(
                        "herdr-tab:${tab.tabId}",
                        tab.label.ifBlank { tab.number.toString() },
                        PaletteGroup.HERDR,
                        PaletteAction.Herdr(HerdrAction.FocusTab(tab.tabId)),
                        s(R.string.herdr_jump_tabs),
                        UsageActions.HERDR_SHEET_JUMP_TAB,
                    ),
                )
            }
        snapshot?.workspaces.orEmpty().sortedBy { it.number }.forEach { workspace ->
            add(
                PaletteEntry(
                    "herdr-workspace:${workspace.workspaceId}",
                    res.getString(R.string.herdr_workspace_entry, workspace.number, workspace.label),
                    PaletteGroup.HERDR,
                    PaletteAction.Herdr(HerdrAction.FocusWorkspace(workspace.workspaceId)),
                    s(R.string.herdr_jump_workspaces),
                    UsageActions.HERDR_SHEET_JUMP_WORKSPACE,
                ),
            )
        }
    }

    // Open sessions, saved hosts and tailnet machines.
    context.sessions.forEach { session ->
        add(
            PaletteEntry(
                "session:${session.hostId}",
                session.nickname,
                PaletteGroup.SESSIONS,
                PaletteAction.SwitchSession(session.hostId),
                s(R.string.palette_subtitle_session),
                UsageActions.DRAWER_SWITCH,
            ),
        )
    }
    context.hosts.forEach { host ->
        add(
            PaletteEntry(
                "host:${host.id}",
                host.nickname,
                PaletteGroup.HOSTS,
                PaletteAction.ConnectHost(host.id),
                s(R.string.palette_subtitle_host),
                keywords = host.hostname,
            ),
        )
    }
    add(
        PaletteEntry(
            "open:machines",
            s(R.string.machines_title),
            PaletteGroup.MACHINES,
            PaletteAction.Open(PaletteScreen.MACHINES),
            usageId = UsageActions.DRAWER_OPEN,
            keywords = "tailscale tailnet",
        ),
    )
    context.machines.filterNot { it.isPhone }.forEach { device ->
        val status = s(if (device.online) R.string.machines_status_online else R.string.machines_status_offline)
        add(
            PaletteEntry(
                "machine:${device.dnsName}",
                device.shortName,
                PaletteGroup.MACHINES,
                PaletteAction.ConnectMachine(device),
                res.getString(R.string.palette_subtitle_machine, status),
                UsageActions.MACHINES_CONNECT,
                "tailscale",
            ),
        )
    }

    // Console screens and tools.
    fun open(id: String, title: String, screen: PaletteScreen, usageId: String?, keywords: String = "") = add(
        PaletteEntry("open:$id", title, PaletteGroup.SCREENS, PaletteAction.Open(screen), usageId = usageId, keywords = keywords),
    )
    if (context.herdrReady) open("herd", s(R.string.drawer_herd), PaletteScreen.HERD, UsageActions.HERD_OPEN, "agents")
    if (context.hasSession) {
        open("reader", s(R.string.reader_open), PaletteScreen.READER, UsageActions.READER_OPEN, "scroll history output")
        open("text-input", s(R.string.console_menu_text_input), PaletteScreen.TEXT_INPUT, UsageActions.CONSOLE_TEXT_INPUT)
        open("paste", s(R.string.console_menu_paste), PaletteScreen.PASTE, UsageActions.CONSOLE_PASTE, "clipboard")
        open("url-scan", s(R.string.console_menu_urlscan), PaletteScreen.URL_SCAN, UsageActions.MENU_URL_SCAN, "links")
        open("copy-details", s(R.string.copy_details), PaletteScreen.COPY_DETAILS, UsageActions.MENU_COPY_DETAILS, "diagnostics")
        open("send-error", s(R.string.wish_send_error), PaletteScreen.SEND_ERROR, UsageActions.MENU_SEND_ERROR, "diagnostics wish")
        if (!context.disconnected) open("resize", s(R.string.console_menu_resize), PaletteScreen.RESIZE, UsageActions.MENU_RESIZE)
        if (context.disconnected) open("reconnect", s(R.string.console_menu_reconnect), PaletteScreen.RECONNECT, UsageActions.MENU_RECONNECT)
        open("disconnect", s(R.string.list_host_disconnect), PaletteScreen.DISCONNECT, UsageActions.MENU_DISCONNECT, "close")
        if (context.canForwardPorts) {
            open("port-forwards", s(R.string.console_menu_portforwards), PaletteScreen.PORT_FORWARDS, UsageActions.MENU_PORT_FORWARDS)
        }
    }
    open("sessions", s(R.string.drawer_sessions_title), PaletteScreen.SESSIONS, UsageActions.DRAWER_OPEN, "drawer")
    open("wish", s(R.string.wish_menu_item), PaletteScreen.WISH, UsageActions.MENU_WISH, "wish list idea")
    open("fullscreen", s(R.string.pref_fullscreen_title), PaletteScreen.FULLSCREEN, UsageActions.MENU_FULLSCREEN)
    open("title-bar", s(R.string.pref_titlebarhide_title), PaletteScreen.TITLE_BAR, UsageActions.MENU_TITLE_BAR)

    // App screens and the settings sections (each opens Settings).
    fun go(id: String, title: String, route: String, usageId: String? = null, subtitle: String? = null, keywords: String = "") = add(
        PaletteEntry("go:$id", title, PaletteGroup.SETTINGS, PaletteAction.Navigate(route), subtitle, usageId, keywords),
    )
    go("settings", s(R.string.title_settings), NavDestinations.SETTINGS, UsageActions.HOSTLIST_SETTINGS, keywords = "preferences")
    val opensSettings = s(R.string.palette_subtitle_settings)
    listOf(
        R.string.pref_security_category,
        R.string.pref_emulation_category,
        R.string.pref_ui_category,
        R.string.pref_keyboard_category,
        R.string.pref_bell_category,
        R.string.pref_usage_category,
    ).forEach { category -> go("settings-$category", s(category), NavDestinations.SETTINGS, subtitle = opensSettings) }
    go("host-list", s(R.string.drawer_host_list), NavDestinations.HOST_LIST, UsageActions.DRAWER_HOST_LIST, keywords = "hosts")
    go("profiles", s(R.string.pref_profiles_category), NavDestinations.PROFILES)
    go("pubkeys", s(R.string.title_pubkey_list), NavDestinations.PUBKEY_LIST, keywords = "keys ssh")
    go("colors", s(R.string.title_colors), NavDestinations.COLORS, keywords = "theme palette")
    go("help", s(R.string.title_help), NavDestinations.HELP)
}
