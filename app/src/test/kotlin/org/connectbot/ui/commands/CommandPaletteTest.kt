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

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.data.TailscaleDevice
import org.connectbot.data.entity.Host
import org.connectbot.ui.navigation.NavDestinations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import solutions.ormus.logos.commands.PaletteAction
import solutions.ormus.logos.commands.PaletteEntry
import solutions.ormus.logos.commands.PaletteGroup
import solutions.ormus.logos.commands.PaletteScreen
import solutions.ormus.logos.commands.PaletteSearch
import solutions.ormus.logos.commands.SlashCatalogs
import solutions.ormus.logos.commands.SlashCommand
import solutions.ormus.logos.commands.TerminalKeys
import solutions.ormus.logos.commands.snapshotFixture
import solutions.ormus.logos.herd.HerdrAction
import solutions.ormus.logos.herd.HerdrDirection
import solutions.ormus.logos.herd.HerdrKeys

/** A [PaletteTarget] that writes down every call. */
private class RecordingTarget : PaletteTarget {
    val calls = mutableListOf<String>()

    override fun sendKey(sequence: String) {
        calls += "key " + sequence.map { it.code }
    }

    override fun herdr(action: HerdrAction, text: String?) {
        calls += "herdr $action $text"
    }

    override fun slash(command: SlashCommand) {
        calls += "slash ${command.text}"
    }

    override fun connectHost(hostId: Long) {
        calls += "host $hostId"
    }

    override fun switchSession(hostId: Long) {
        calls += "session $hostId"
    }

    override fun connectMachine(device: TailscaleDevice) {
        calls += "machine ${device.hostName}"
    }

    override fun open(screen: PaletteScreen) {
        calls += "open $screen"
    }

    override fun navigate(route: String) {
        calls += "navigate $route"
    }
}

/** The palette's entries, its results running the right action, and its rendering. */
@RunWith(AndroidJUnit4::class)
class CommandPaletteTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val sun = TailscaleDevice(hostName = "Sun", dnsName = "sun.tail.ts.net", ipv4 = "100.100.1.1", online = true, os = "linux")
    private val phone = TailscaleDevice(hostName = "Pixel", dnsName = "pixel.tail.ts.net", ipv4 = null, online = true, os = "android")

    private val herdrContext = PaletteContext(
        hasSession = true,
        runsHerdr = true,
        herdrReady = true,
        snapshot = snapshotFixture("herdr/snapshot-sun-mixed.json"),
        // Sun's Herdr prefix, Ctrl+Space, which a terminal sends as NUL.
        herdrKeys = HerdrKeys.fromConfig("[keys]\nprefix = \"ctrl+space\"\n"),
        sessions = listOf(PaletteSession(3, "Build box")),
        hosts = listOf(Host(id = 1, nickname = "Sun", hostname = "100.100.1.1"), Host(id = 3, nickname = "Build box", hostname = "build-box")),
        machines = listOf(sun, phone),
    )

    private val labels = PALETTE_HERDR_ACTIONS.associateWith { it.toString() }

    private fun entries(context: PaletteContext = herdrContext) = buildPaletteEntries(res, context, "Claude Code", SlashCatalogs.CLAUDE.take(3), labels)

    @Test
    fun runningEachKindOfResult_reachesTheMatchingTargetCall() {
        val target = RecordingTarget()
        val device = sun
        listOf(
            PaletteAction.Key(TerminalKeys.ESCAPE),
            PaletteAction.Herdr(HerdrAction.SplitRight),
            PaletteAction.Slash(SlashCommand("compact", "")),
            PaletteAction.ConnectHost(1),
            PaletteAction.SwitchSession(3),
            PaletteAction.ConnectMachine(device),
            PaletteAction.Open(PaletteScreen.READER),
            PaletteAction.Navigate(NavDestinations.SETTINGS),
        ).forEach { runPaletteAction(it, target) }
        runPaletteAction(PaletteAction.Herdr(HerdrAction.RenameTab), target, "build")

        assertEquals(
            listOf(
                "key [27]",
                "herdr SplitRight null",
                "slash /compact",
                "host 1",
                "session 3",
                "machine Sun",
                "open READER",
                "navigate settings",
                "herdr RenameTab build",
            ),
            target.calls,
        )
    }

    @Test
    fun herdrKeysTheHostCannotTake_areLeftOut() {
        val off = herdrContext.copy(herdrKeys = HerdrKeys.fromConfig("[keys]\ngoto = \"cmd+g\"\ndetach = \"\"\n"))
        val keys = entries(off).map { it.key }
        assertTrue(keys.none { it == "key:goto" || it == "herdr:${HerdrAction.GOTO}" || it == "herdr:${HerdrAction.DETACH}" })
        assertTrue("herdr:${HerdrAction.HELP}" in keys)
        assertTrue("key:needs-you" in keys)
    }

    @Test
    fun herdrSession_offersEveryGroup() {
        val all = entries()
        val groups = all.map { it.group }.toSet()
        assertEquals(PaletteGroup.entries.toSet(), groups)

        val byKey = all.associateBy { it.key }
        assertEquals(PaletteAction.Key(TerminalKeys.UP), byKey.getValue("key:up").action)
        assertEquals(PaletteAction.Key(0.toChar().toString() + "o"), byKey.getValue("key:needs-you").action)
        // Without a Herdr side's keys: Herdr's defaults, Ctrl+B.
        val stock = entries(herdrContext.copy(herdrKeys = HerdrKeys.DEFAULT)).associateBy { it.key }
        assertEquals(PaletteAction.Key(2.toChar().toString() + "g"), stock.getValue("key:goto").action)
        assertEquals(PaletteAction.Slash(SlashCatalogs.CLAUDE[0]), byKey.getValue("slash:BUILT_IN:clear").action)
        assertEquals("Claude Code · built-in", byKey.getValue("slash:BUILT_IN:clear").subtitle)
        assertEquals(PaletteAction.Herdr(HerdrAction.FocusPane(HerdrDirection.LEFT)), byKey.getValue("herdr:FocusPane(direction=LEFT)").action)
        assertEquals(PaletteAction.Herdr(HerdrAction.FocusWorkspace("w1")), byKey.getValue("herdr-workspace:w1").action)
        assertEquals(PaletteAction.SwitchSession(3), byKey.getValue("session:3").action)
        assertEquals(PaletteAction.ConnectHost(1), byKey.getValue("host:1").action)
        assertEquals(PaletteAction.ConnectMachine(sun), byKey.getValue("machine:sun.tail.ts.net").action)
        // Phones run no SSH server worth listing.
        assertTrue(all.none { it.key == "machine:pixel.tail.ts.net" })
        assertEquals(PaletteAction.Open(PaletteScreen.HERD), byKey.getValue("open:herd").action)
        assertEquals(PaletteAction.Navigate(NavDestinations.SETTINGS), byKey.getValue("go:settings").action)
        assertEquals(all.size, all.map { it.key }.toSet().size)
    }

    @Test
    fun withoutASession_onlyPlacesAndSettingsShow() {
        val all = entries(PaletteContext(hosts = herdrContext.hosts))
        assertTrue(all.none { it.group == PaletteGroup.KEYS || it.group == PaletteGroup.AGENT || it.group == PaletteGroup.HERDR })
        assertTrue(all.any { it.key == "host:1" })
        assertTrue(all.any { it.key == "go:help" })
    }

    @Test
    fun plainSession_hasNoHerdrKeysOrActions() {
        val all = entries(herdrContext.copy(runsHerdr = false, herdrReady = false, snapshot = null))
        assertTrue(all.none { it.group == PaletteGroup.HERDR || it.key == "key:needs-you" || it.key == "open:herd" })
        assertTrue(all.any { it.key == "key:esc" })
    }

    @Test
    fun typedQuery_findsAndRunsTheResultInTwoTaps() {
        val ran = mutableListOf<Pair<PaletteEntry, String?>>()
        val all = entries()
        composeTestRule.setContent {
            var query by remember { mutableStateOf("") }
            MenuTheme {
                CommandPaletteContent(
                    query = query,
                    results = PaletteSearch.rank(all, query),
                    onQueryChange = { query = it },
                    onRun = { entry, text -> ran += entry to text },
                    focusSearch = false,
                )
            }
        }

        composeTestRule.onNodeWithTag(PALETTE_SEARCH_TAG).performTextInput("escape")
        composeTestRule.onNodeWithTag(PALETTE_ROW_TAG + "key:esc").performClick()

        assertEquals("key:esc", ran.single().first.key)
    }

    @Test
    fun herdrClose_asksOnceInsideThePalette() {
        val ran = mutableListOf<Pair<PaletteEntry, String?>>()
        val close = entries().first { it.key == "herdr:ClosePane" }
        composeTestRule.setContent {
            MenuTheme {
                CommandPaletteContent(query = "", results = listOf(close), onQueryChange = {}, onRun = { e, t -> ran += e to t }, focusSearch = false)
            }
        }

        composeTestRule.onNodeWithTag(PALETTE_ROW_TAG + close.key).performClick()
        assertTrue(ran.isEmpty())
        composeTestRule.onNodeWithText("Close this pane? What runs in it stops.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Yes, do it").performClick()

        assertEquals(listOf(close to null), ran)
    }

    @Test
    fun herdrRename_asksForTheNameInsideThePalette() {
        val ran = mutableListOf<Pair<PaletteEntry, String?>>()
        val rename = entries().first { it.key == "herdr:RenameTab" }
        composeTestRule.setContent {
            MenuTheme {
                CommandPaletteContent(query = "", results = listOf(rename), onQueryChange = {}, onRun = { e, t -> ran += e to t }, focusSearch = false)
            }
        }

        composeTestRule.onNodeWithTag(PALETTE_ROW_TAG + rename.key).performClick()
        composeTestRule.onNodeWithText("New name").performTextInput("build")
        composeTestRule.onNodeWithText("Rename").performClick()

        assertEquals(listOf(rename to "build"), ran)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun render_paletteWithEmptyQuery() {
        val all = entries()
        composeTestRule.setContent {
            MenuTheme {
                CommandPaletteContent(
                    query = "",
                    results = PaletteSearch.rank(all, "", recents = listOf("host:1", "key:esc")),
                    onQueryChange = {},
                    onRun = { _, _ -> },
                    focusSearch = false,
                )
            }
        }

        composeTestRule.onNodeWithText("Keys, commands, hosts, screens").assertIsDisplayed()
        composeTestRule.onNodeWithTag(PALETTE_ROW_TAG + "host:1").assertIsDisplayed()
        composeTestRule.onNodeWithTag(PALETTE_ROW_TAG + "key:esc").assertIsDisplayed()
        saveRender(composeTestRule.activity, "command-palette.png")
    }
}
