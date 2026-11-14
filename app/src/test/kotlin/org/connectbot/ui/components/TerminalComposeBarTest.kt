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

package org.connectbot.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.any
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.stub
import org.robolectric.annotation.GraphicsMode
import solutions.ormus.logos.herd.CoreWatchTarget
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrAgentInfo
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.HerdrKeys
import solutions.ormus.logos.herd.HerdrSessionSnapshot
import solutions.ormus.logos.herd.HerdrTabInfo
import solutions.ormus.logos.herd.HerdrWorkspaceInfo
import solutions.ormus.logos.herd.JumpTarget
import solutions.ormus.logos.herd.SendResult
import solutions.ormus.logos.herd.parseHerdrSnapshot

/**
 * The two-row compose bar and its tray: what row 1 holds on Herdr and plain hosts,
 * how the tray opens and closes, that every tray key still sends the bytes it sent
 * before the tray existed, and that the dictation draft survives the tray.
 */
@RunWith(AndroidJUnit4::class)
class TerminalComposeBarTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val logged = mutableListOf<String>()

    // What the bar asked the phone to open, in place of the browser.
    private val opened = mutableListOf<String>()
    private val uriHandler = object : UriHandler {
        override fun openUri(uri: String) {
            opened += uri
        }
    }

    private fun bridgeWithPostLogin(postLogin: String?): TerminalBridge {
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.host).thenReturn(Host(nickname = "Sun", postLogin = postLogin))
        return bridge
    }

    /**
     * A Herdr bridge whose latest snapshot has the focused pane holding
     * [maxOffsetFromBottom] rows of scrollback (`herdr api snapshot`, panes[].scroll).
     */
    private fun herdrBridgeWithScrollback(maxOffsetFromBottom: Int, postLogin: String? = "herdr\n"): TerminalBridge {
        val bridge = bridgeWithPostLogin(postLogin)
        val snapshot = parseHerdrSnapshot(
            """{"id":"1","result":{"snapshot":{"focused_pane_id":"w1:p1","panes":[""" +
                """{"pane_id":"w1:p1","focused":true,"scroll":{"max_offset_from_bottom":$maxOffsetFromBottom,""" +
                """"offset_from_bottom":0,"viewport_rows":40}},""" +
                """{"pane_id":"w1:p2","focused":false,"scroll":{"max_offset_from_bottom":999,""" +
                """"offset_from_bottom":0,"viewport_rows":40}}]}}}""",
        )
        val herd = mock(HerdSession::class.java)
        `when`(herd.snapshot).thenReturn(MutableStateFlow(snapshot))
        `when`(herd.result).thenReturn(MutableStateFlow(null))
        `when`(herd.failure).thenReturn(MutableStateFlow(null))
        `when`(herd.sessions).thenReturn(MutableStateFlow(listOf("default")))
        `when`(herd.chosen).thenReturn(MutableStateFlow("default"))
        `when`(herd.others).thenReturn(MutableStateFlow(emptyMap()))
        `when`(herd.attached).thenReturn(MutableStateFlow("default"))
        // Sun's prefix, read from its Herdr config.
        `when`(herd.keys).thenReturn(MutableStateFlow(HerdrKeys.fromConfig("[keys]\nprefix = \"ctrl+space\"\n")))
        `when`(bridge.herd).thenReturn(herd)
        return bridge
    }

    /**
     * A Herdr bridge whose default session has a blocked claude in w1 and the focus on a
     * working one in w2, and whose "work" session (attached: [attached]) has a blocked grok.
     */
    private fun herdrBridgeWithWaitingAgents(blockedInDefault: Boolean = true, blockedInWork: Boolean = true, attached: String = "default"): TerminalBridge {
        val bridge = bridgeWithPostLogin("herdr\n")
        fun agent(pane: String, tab: String, kind: String, blocked: Boolean) = HerdrAgentInfo(paneId = pane, workspaceId = tab.substringBefore(':'), tabId = tab, agent = kind, agentStatus = if (blocked) HerdrAgentStatus.BLOCKED else HerdrAgentStatus.WORKING)
        val main = HerdrSessionSnapshot(
            workspaces = listOf(HerdrWorkspaceInfo("w1", label = "alpha", number = 1), HerdrWorkspaceInfo("w2", label = "beta", number = 2)),
            tabs = listOf(HerdrTabInfo("w1:t1", workspaceId = "w1", label = "build", number = 1), HerdrTabInfo("w2:t1", workspaceId = "w2", label = "tests", number = 1)),
            agents = listOf(agent("w1:p1", "w1:t1", "claude", blockedInDefault), agent("w2:p1", "w2:t1", "codex", false)),
            focusedWorkspaceId = "w2",
            focusedTabId = "w2:t1",
            focusedPaneId = "w2:p1",
        )
        val work = HerdrSessionSnapshot(
            workspaces = listOf(HerdrWorkspaceInfo("w1", label = "gamma", number = 1)),
            tabs = listOf(HerdrTabInfo("w1:t1", workspaceId = "w1", label = "review", number = 1)),
            agents = listOf(agent("w1:p1", "w1:t1", "grok", blockedInWork)),
            focusedWorkspaceId = "w1",
            focusedTabId = "w1:t1",
            focusedPaneId = "w1:p1",
        )
        val herd = mock(HerdSession::class.java)
        `when`(herd.snapshot).thenReturn(MutableStateFlow(main))
        `when`(herd.result).thenReturn(MutableStateFlow(null))
        `when`(herd.failure).thenReturn(MutableStateFlow(null))
        `when`(herd.sessions).thenReturn(MutableStateFlow(listOf("default", "work")))
        `when`(herd.chosen).thenReturn(MutableStateFlow(attached))
        `when`(herd.others).thenReturn(MutableStateFlow(mapOf("work" to work)))
        `when`(herd.attached).thenReturn(MutableStateFlow(attached))
        `when`(herd.keys).thenReturn(MutableStateFlow(HerdrKeys.fromConfig("[keys]\nprefix = \"ctrl+space\"\n")))
        `when`(bridge.herd).thenReturn(herd)
        return bridge
    }

    /** Every string the bar wrote to the PTY, in order. */
    private fun sentBytes(bridge: TerminalBridge): List<String> {
        val captor = ArgumentCaptor.forClass(String::class.java)
        verify(bridge, atLeast(0)).injectString(captor.capture())
        return captor.allValues
    }

    /** The bar on a 411dp-wide phone (a Pixel), docked at the bottom like the console. */
    private fun setBar(
        bridge: TerminalBridge,
        herdrPanel: Boolean = false,
        onRead: (() -> Unit)? = null,
        onPasteScreenshot: (suspend () -> String?)? = null,
        onSlashMenu: (() -> Unit)? = null,
        draft: DictationDraft = DictationDraft(),
        recentOutput: String = "",
        lastReply: String? = null,
        onBoard: (() -> Unit)? = null,
        onInbox: (() -> Unit)? = null,
        enterNewLine: Boolean = false,
        keyBar: KeyBarLayout? = null,
        hisKeys: Boolean = true,
    ) {
        composeTestRule.setContent {
            ConnectBotTheme {
                CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }, LocalUriHandler provides uriHandler) {
                    // The console owns whether the Herdr panel is open; this stands in for it.
                    var herdrOpen by remember { mutableStateOf(false) }
                    Box(Modifier.width(PHONE_WIDTH).fillMaxSize()) {
                        TerminalComposeBar(
                            bridge = bridge,
                            modifier = Modifier.align(Alignment.BottomCenter).testTag(BAR),
                            onRead = onRead,
                            herdrPanelOpen = herdrOpen,
                            onHerdrPanelChange = if (herdrPanel) {
                                { open -> herdrOpen = open }
                            } else {
                                null
                            },
                            onPasteScreenshot = onPasteScreenshot,
                            onSlashMenu = onSlashMenu,
                            onBoard = onBoard,
                            onInbox = onInbox,
                            draft = draft,
                            enterNewLine = enterNewLine,
                            keyBar = keyBar,
                            readRecentOutput = { recentOutput },
                            readLastReply = { lastReply },
                        )
                    }
                }
            }
        }
        composeTestRule.waitForIdle()
        // With his keys on, the bar's first page is the bottom row; his first page is the next one.
        if (keyBar != null && hisKeys) toPage(1)
    }

    private fun toPage(page: Int) {
        composeTestRule.onNodeWithTag("key_bar_dot_$page").performClick()
        composeTestRule.waitForIdle()
    }

    private fun key(label: String): SemanticsNodeInteraction = composeTestRule.onNode(hasClickAction() and hasLabel(label))

    private fun hasLabel(label: String) = SemanticsMatcher("text or description is $label") { node ->
        val texts = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
        val descriptions = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
        label in texts || label in descriptions
    }

    private fun more() = key(MORE)

    private fun openTray() {
        more().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TRAY_TEST_TAG).assertIsDisplayed()
    }

    private fun assertTrayOpen() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TRAY_TEST_TAG).assertIsDisplayed()
    }

    private fun assertTrayClosed() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TRAY_TEST_TAG).assertDoesNotExist()
    }

    /** The labels sit left to right in this order, on one row. */
    private fun assertRowOrder(vararg labels: String) {
        val bounds = labels.map { key(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
        bounds.zipWithNext().forEachIndexed { i, (a, b) ->
            assertTrue("'${labels[i]}' before '${labels[i + 1]}'", a.right <= b.left + 0.5f)
            assertEquals("'${labels[i + 1]}' on the same row", a.center.y, b.center.y, 0.5f)
        }
    }

    /** The dictation field sits between [before] and [after] on row 2, and keeps a usable width. */
    private fun assertFieldBetween(before: String, after: String) {
        val field = composeTestRule.onNode(hasSetTextAction()).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val left = key(before).fetchSemanticsNode().boundsInRoot
        val right = key(after).fetchSemanticsNode().boundsInRoot
        assertTrue("field after '$before'", left.right <= field.left + 0.5f)
        assertTrue("field before '$after'", field.right <= right.left + 0.5f)
        val widthDp = field.width / composeTestRule.density.density
        assertTrue("field is $widthDp dp wide, needs 80", widthDp >= 80f)
    }

    private fun assertComfortable(label: String) {
        val node = key(label).fetchSemanticsNode()
        val density = composeTestRule.density.density
        val w = node.boundsInRoot.width / density
        val h = node.boundsInRoot.height / density
        assertTrue("'$label' is $w dp wide, needs 44", w >= 44f)
        assertTrue("'$label' is $h dp tall, needs 46", h >= 46f)
    }

    // Row 1.

    @Test
    fun herdrHost_row1_pageKeysMoreThenNeedsYou() {
        setBar(bridgeWithPostLogin("herdr\n"), herdrPanel = true, onSlashMenu = {})

        assertRowOrder(PAGE_UP, PAGE_DOWN, "Herdr", MORE, CHAT, NEEDS_YOU)
        // The tab arrows are gone: swipes switch tabs, Jump to sits in the Herdr panel.
        composeTestRule.onAllNodesWithContentDescription("Previous tab").assertCountEquals0()
        composeTestRule.onAllNodesWithContentDescription("Next tab").assertCountEquals0()
        assertComfortable(NEEDS_YOU)
        // PgUp and PgDn page, and say so.
        key(PAGE_UP).assertIsDisplayed()
        key(PAGE_DOWN).assertIsDisplayed()
        // / waits in the tray.
        composeTestRule.onAllNodesWithText("/").assertCountEquals0()
        composeTestRule.onAllNodesWithText("Ctrl").assertCountEquals0()
        composeTestRule.onAllNodesWithText("Goto").assertCountEquals0()
        composeTestRule.onAllNodesWithText("Mode").assertCountEquals0()
    }

    @Test
    fun plainHost_row1_sameLayout_herdrKeysDimmed() {
        setBar(bridgeWithPostLogin(null), herdrPanel = true)

        // The same row as on Sun; the Herdr keys wait, dimmed, for Herdr to answer.
        assertRowOrder(PAGE_UP, PAGE_DOWN, "Herdr", MORE, CHAT, NEEDS_YOU)
        key("Herdr").assertIsNotEnabled()
        key(CHAT).assertIsNotEnabled()
        key(NEEDS_YOU).assertIsNotEnabled()
        // Ctrl lives in the tray on every host.
        composeTestRule.onAllNodesWithText("Ctrl").assertCountEquals0()
    }

    @Test
    fun herdrFoundOnTheHost_withoutAPostLoginCommand_wakesTheHerdrKeys() {
        // lab: Herdr runs there, but its host entry has no post-login command. A Herdr
        // snapshot answering is enough.
        val bridge = herdrBridgeWithScrollback(0, postLogin = null)
        setBar(bridge, herdrPanel = true)

        key("Herdr").assertIsEnabled()
        key(NEEDS_YOU).assertIsEnabled()
        key("Herdr").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun plainHost_row2_escTabFieldEnter_noMicNoTabSwitchers() {
        setBar(bridgeWithPostLogin(null), onRead = {})

        assertRowOrder("Esc", "Tab", ENTER)
        assertFieldBetween("Tab", ENTER)
        // One ⏎ key: no separate Send button.
        composeTestRule.onAllNodesWithText("⏎").assertCountEquals(1)
        composeTestRule.onAllNodesWithContentDescription("Start dictation").assertCountEquals0()
        // Read lives in the tray now.
        composeTestRule.onAllNodesWithContentDescription(READ).assertCountEquals0()
    }

    @Test
    fun herdrHost_row2_escTabFieldEnter_needsYouSitsBelowEnter() {
        setBar(bridgeWithPostLogin("herdr\n"), onSlashMenu = {})

        assertRowOrder("Esc", "Tab", ENTER)
        assertFieldBetween("Tab", ENTER)
        val needsYou = key(NEEDS_YOU).fetchSemanticsNode().boundsInRoot
        val enter = key(ENTER).fetchSemanticsNode().boundsInRoot
        assertTrue("Needs you is below ⏎ (the compose box sits above the key group)", enter.bottom <= needsYou.top + 0.5f)
        assertTrue("Needs you lines up under ⏎", needsYou.right > enter.left && needsYou.left < enter.right)
    }

    @Test
    fun chat_tap_readsTheFocusedPane_andLogsTheKey() {
        // The console's onRead shows an agent pane as Chat View and any other pane as its reader.
        var reads = 0
        setBar(herdrBridgeWithWaitingAgents(), onRead = { reads++ }, onBoard = {})

        key(CHAT).assertIsEnabled().assertTextEquals("Chat")
        assertComfortable(CHAT)
        key(CHAT).performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, reads)
        assertEquals(listOf(UsageActions.CHAT_KEY), logged)
    }

    @Test
    fun chat_withNothingToRead_isDimmed() {
        setBar(herdrBridgeWithWaitingAgents(), onBoard = {})

        key(CHAT).assertIsNotEnabled()
    }

    @Test
    fun needsYou_countsWaitingAgentsInEverySession_andGoesGold() {
        setBar(herdrBridgeWithWaitingAgents(), onBoard = {})

        key("Needs you, 2 waiting").assertIsEnabled().assertTextEquals("Needs you · 2")
    }

    @Test
    fun needsYou_tap_jumpsToTheNextWaitingAgentAfterTheFocus_andSendsNothing() {
        // The focus is on beta's working codex: the default's waiting claude is behind it, so
        // the next one is the grok waiting in work.
        val bridge = herdrBridgeWithWaitingAgents()
        var boards = 0
        setBar(bridge, onBoard = { boards++ })

        key("Needs you, 2 waiting").performClick()
        composeTestRule.waitForIdle()

        val target = ArgumentCaptor.forClass(JumpTarget::class.java)
        val pane = ArgumentCaptor.forClass(String::class.java)
        verify(bridge).jumpToHerdrTab(target.capture() ?: JUMP, pane.capture() ?: "")
        assertEquals("work", target.value.session)
        assertEquals("w1:t1", target.value.tabId)
        assertEquals("w1:p1", pane.value)
        assertEquals(0, boards)
        assertEquals(emptyList<String>(), sentBytes(bridge))
        assertEquals(listOf(UsageActions.NEEDS_YOU_JUMP), logged)
    }

    @Test
    fun needsYou_tapWithNoneWaiting_opensTheBoard() {
        val bridge = herdrBridgeWithWaitingAgents(blockedInDefault = false, blockedInWork = false)
        var boards = 0
        setBar(bridge, onBoard = { boards++ })

        key("Needs you, 0 waiting").assertTextEquals("Needs you").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, boards)
        verify(bridge, never()).jumpToHerdrTab(any(JumpTarget::class.java) ?: JUMP, any())
        assertEquals(listOf(UsageActions.NEEDS_YOU_BOARD), logged)
    }

    @Test
    fun needsYou_longPress_opensTheBoard_evenWithAgentsWaiting() {
        val bridge = herdrBridgeWithWaitingAgents()
        var boards = 0
        setBar(bridge, onBoard = { boards++ })

        key("Needs you, 2 waiting").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        assertEquals(1, boards)
        assertEquals(listOf(UsageActions.NEEDS_YOU_BOARD), logged)
    }

    @Test
    fun needsYou_withTheInbox_longPressOpensTheInbox_notTheBoard() {
        val bridge = herdrBridgeWithWaitingAgents()
        var boards = 0
        var inboxes = 0
        setBar(bridge, onBoard = { boards++ }, onInbox = { inboxes++ })

        key("Needs you, 2 waiting").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        assertEquals(1, inboxes)
        assertEquals(0, boards)
    }

    @Test
    fun needsYou_withTheInbox_tapWithNoneWaiting_stillOpensTheBoard() {
        val bridge = herdrBridgeWithWaitingAgents(blockedInDefault = false, blockedInWork = false)
        var boards = 0
        var inboxes = 0
        setBar(bridge, onBoard = { boards++ }, onInbox = { inboxes++ })

        key("Needs you, 0 waiting").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, boards)
        assertEquals(0, inboxes)
    }

    @Test
    fun needsYou_onAPlainHost_takesNoTapAndNoLongPress() {
        var boards = 0
        setBar(bridgeWithPostLogin(null), onBoard = { boards++ })

        key(NEEDS_YOU).performClick()
        key(NEEDS_YOU).performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        assertEquals(0, boards)
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun slashKey_hiddenWithoutACallback() {
        setBar(bridgeWithPostLogin(null), onRead = {})
        openTray()

        composeTestRule.onAllNodesWithText("/").assertCountEquals0()
    }

    @Test
    fun traySlashKey_opensTheMenu() {
        var opened = false
        setBar(bridgeWithPostLogin(null), onRead = {}, onSlashMenu = { opened = true })
        composeTestRule.onAllNodesWithText("/").assertCountEquals0()
        openTray()
        assertComfortable("/")

        key("/").performClick()
        assertTrue(opened)
        assertEquals(listOf(UsageActions.BAR_TRAY_OPEN, UsageActions.BAR_SLASH_MENU), logged)
    }

    @Test
    fun herdrKey_onRow1_opensAndClosesTheHerdrPanel() {
        setBar(herdrBridgeWithScrollback(0), herdrPanel = true)
        composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).assertDoesNotExist()

        key("Herdr").assertIsEnabled().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).assertIsDisplayed()
        // One group at a time, Agents first.
        composeTestRule.onNodeWithText("Next waiting agent").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Split right").assertCountEquals0()

        key("Herdr").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).assertDoesNotExist()
        assertEquals(listOf(UsageActions.HERDR_SHEET_OPEN), logged)
    }

    @Test
    fun herdrKey_sendsHerdrsPrefixAsThePanelOpens() {
        val bridge = herdrBridgeWithScrollback(0)
        setBar(bridge, herdrPanel = true)

        key("Herdr").performClick()
        composeTestRule.waitForIdle()

        verify(checkNotNull(bridge.herd)).armPrefix()
    }

    @Test
    fun herdrPanel_isCompact_andMovesTheTerminalUp() {
        setBar(herdrBridgeWithScrollback(0), herdrPanel = true)
        val closed = composeTestRule.onNodeWithTag(BAR).fetchSemanticsNode().boundsInRoot

        key("Herdr").performClick()
        composeTestRule.waitForIdle()
        val open = composeTestRule.onNodeWithTag(BAR).fetchSemanticsNode().boundsInRoot
        val panel = composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).fetchSemanticsNode().boundsInRoot
        val density = composeTestRule.density.density

        // The panel adds its own height to the bar, so the terminal moves up, and it stays
        // well short of the screen: it is a panel, not a full-screen sheet.
        assertEquals(panel.height, open.height - closed.height, 1f)
        assertTrue("the panel is ${panel.height / density} dp tall", panel.height / density <= 330f)
    }

    @Test
    fun herdrPanel_andTray_closeEachOther() {
        setBar(herdrBridgeWithScrollback(0), herdrPanel = true)

        openTray()
        key("Herdr").performClick()
        composeTestRule.waitForIdle()
        assertTrayClosed()
        composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).assertIsDisplayed()

        more().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).assertDoesNotExist()
        assertTrayOpen()
    }

    @Test
    fun herdrPanel_backCloses() {
        setBar(herdrBridgeWithScrollback(0), herdrPanel = true)
        key("Herdr").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(HERDR_PANEL_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun herdrKey_disabledUntilTheHerdrSideIsUp_rowDoesNotShift() {
        setBar(bridgeWithPostLogin("herdr\n"))

        key("Herdr").assertIsNotEnabled()
        assertRowOrder(PAGE_UP, PAGE_DOWN, "Herdr", MORE, CHAT, NEEDS_YOU)
        assertComfortable("Herdr")
    }

    @Test
    fun row1Up_tapPagesUpOnce() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge)

        key(PAGE_UP).performClick()
        composeTestRule.waitForIdle()

        verify(bridge, times(1)).injectString(ESC + "[5~")
        verify(bridge, never()).injectString(ESC + "[A")
    }

    @Test
    fun row1Up_holdRepeatsPageUp() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge)

        key(PAGE_UP).performTouchInput {
            down(center)
            advanceEventTime(1_000)
        }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        key(PAGE_UP).performTouchInput { up() }

        // One on press, then repeats every 60 ms after the first 400 ms.
        verify(bridge, atLeast(5)).injectString(ESC + "[5~")
    }

    @Test
    fun row1Down_holdRepeatsPageDown() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge)

        key(PAGE_DOWN).performTouchInput {
            down(center)
            advanceEventTime(1_000)
        }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        key(PAGE_DOWN).performTouchInput { up() }

        verify(bridge, atLeast(5)).injectString(ESC + "[6~")
    }

    @Test
    fun trayArrows_upAndDownSendTheArrowKeys() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge)
        openTray()
        logged.clear()

        key("Up").performClick()
        key("Down").performClick()

        assertEquals(listOf(ESC + "[A", ESC + "[B"), sentBytes(bridge))
        assertEquals(listOf(UsageActions.KEY_UP, UsageActions.KEY_DOWN), logged)
    }

    @Test
    fun theDraftViewsSend_goesOutLikeEnter() {
        val bridge = bridgeWithPostLogin(null)
        val draft = DictationDraft()
        setBar(bridge, draft = draft)
        composeTestRule.onNode(hasSetTextAction()).performTextInput("a long voice note")

        composeTestRule.runOnUiThread { draft.requestSend() }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(100)

        assertEquals(listOf("a long voice note", CR), sentBytes(bridge))
        assertEquals(listOf(UsageActions.DRAFT_SEND), logged)
        assertEquals("", draft.text)
    }

    @Test
    fun typedText_isStillInTheField_whenTheTerminalOpensAgain() {
        val store = ComposeDraftStore()
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, draft = store.draft("1|host"))
        composeTestRule.onNode(hasSetTextAction()).performTextInput("half a thought")

        // Back and the same tab again: the console builds a new draft for the same terminal.
        val reopened = store.draft("1|host")
        assertEquals("half a thought", reopened.text)
        assertEquals(TextRange("half a thought".length), reopened.value.selection)
    }

    @Test
    fun aSentDraft_isNotBroughtBack_whenTheTerminalOpensAgain() {
        val store = ComposeDraftStore()
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, draft = store.draft("1|host"))
        composeTestRule.onNode(hasSetTextAction()).performTextInput("run the tests")
        assertEquals("run the tests", store.kept("1|host"))

        composeTestRule.onNode(hasSetTextAction()).performImeAction()
        composeTestRule.mainClock.advanceTimeBy(100)
        composeTestRule.waitForIdle()

        assertEquals("", store.kept("1|host"))
    }

    private fun coreBridge(send: suspend () -> SendResult): TerminalBridge {
        val bridge = bridgeWithPostLogin(null)
        `when`(bridge.watch).thenReturn(CoreWatchTarget("t7", "work", "main"))
        bridge.stub { onBlocking { composeSend(any() ?: "") } doSuspendableAnswer { send() } }
        return bridge
    }

    @Test
    fun aCoreSendStillWaiting_hasNoStoredDraft_soLeavingNowCannotBringItBack() {
        val store = ComposeDraftStore()
        val bridge = coreBridge { CompletableDeferred<SendResult>().await() }
        setBar(bridge, draft = store.draft("1|watch"))
        composeTestRule.onNode(hasSetTextAction()).performTextInput("deploy it")
        assertEquals("deploy it", store.kept("1|watch"))

        composeTestRule.onNode(hasSetTextAction()).performImeAction()
        composeTestRule.waitForIdle()

        // hc send may already have pasted it: a console that closes now must not offer it again.
        assertEquals("", store.kept("1|watch"))
    }

    @Test
    fun aCoreSendThatFailed_putsTheTextBackInTheFieldAndTheStore() {
        val store = ComposeDraftStore()
        val gate = CompletableDeferred<SendResult>()
        val bridge = coreBridge { gate.await() }
        val draft = store.draft("1|watch")
        setBar(bridge, draft = draft)
        composeTestRule.onNode(hasSetTextAction()).performTextInput("deploy it")
        composeTestRule.onNode(hasSetTextAction()).performImeAction()
        composeTestRule.waitForIdle()

        gate.complete(SendResult.Failed("no connection"))
        composeTestRule.waitForIdle()

        assertEquals("deploy it", draft.text)
        assertEquals("deploy it", store.kept("1|watch"))
    }

    @Test
    fun aDraftWithLineBreaks_goesAsOnePaste_whenTheProgramAskedForBracketedPaste() {
        val bridge = bridgeWithPostLogin(null)
        `when`(bridge.bracketedPaste).thenReturn(true)
        val draft = DictationDraft()
        setBar(bridge, draft = draft)
        composeTestRule.runOnUiThread { draft.value = TextFieldValue("fix the header\nthen run the tests") }

        composeTestRule.runOnUiThread { draft.requestSend() }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(100)

        // One paste, then one Enter: the agent gets one message, not one per line.
        assertEquals(listOf(ESC + "[200~fix the header\nthen run the tests" + ESC + "[201~", CR), sentBytes(bridge))
    }

    @Test
    fun theKeyboardsEnter_sendsTheDraft_byDefault() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge)
        val field = composeTestRule.onNode(hasSetTextAction())
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Send))
        field.performTextInput("run the tests")

        field.performImeAction()
        composeTestRule.mainClock.advanceTimeBy(100)

        assertEquals(listOf("run the tests", CR), sentBytes(bridge))
        assertEquals(listOf(UsageActions.SEND_IME), logged)
    }

    @Test
    fun withEnterNewLineOn_theKeyboardsEnterIsANewLine_andTheBarKeySendsTheLinesAsOnePaste() {
        val bridge = bridgeWithPostLogin(null)
        `when`(bridge.bracketedPaste).thenReturn(true)
        setBar(bridge, enterNewLine = true)
        val field = composeTestRule.onNode(hasSetTextAction())
        // Default on a multi-line field: the keyboard shows a return key that types a line break.
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Default))
        field.performTextInput("fix the header\nthen run the tests")
        assertEquals(emptyList<String>(), sentBytes(bridge))

        key(SEND).performClick()
        composeTestRule.mainClock.advanceTimeBy(100)

        assertEquals(listOf(ESC + "[200~fix the header\nthen run the tests" + ESC + "[201~", CR), sentBytes(bridge))
        assertEquals(listOf(UsageActions.SEND), logged)
    }

    @Test
    fun keyBar_offLeavesTheDefaultBarAlone() {
        setBar(bridgeWithPostLogin(null))

        composeTestRule.onAllNodesWithTag(KEY_BAR_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun keyBar_showsHisKeysInOrderAndADotTurnsThePage() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl-c", "up"), listOf("ctrl-z"))))

        composeTestRule.onNodeWithTag(KEY_BAR_TEST_TAG).assertIsDisplayed()
        key("Ctrl+C").performClick()
        verify(bridge).injectString("\u0003")

        toPage(2)
        key("Ctrl+Z").performClick()
        verify(bridge).injectString("\u001a")
    }

    @Test
    fun keyBar_showsOneDotPerPage_theBottomRowsPageToo() {
        setBar(bridgeWithPostLogin(null), keyBar = KeyBarLayout(listOf(listOf("esc"), listOf("tab"), listOf("up"))))
        (0..3).forEach { composeTestRule.onNodeWithTag("key_bar_dot_$it").assertExists() }
        composeTestRule.onAllNodesWithTag("key_bar_dot_4").assertCountEquals(0)
    }

    @Test
    fun keyBar_pgUpAndPgDnKeys_areDescribedAsPageUpAndPageDownForTalkBack() {
        setBar(bridgeWithPostLogin(null), keyBar = KeyBarLayout(listOf(listOf("ctrl-c"))), hisKeys = false)
        composeTestRule.onNode(hasClickAction() and hasLabel(PAGE_UP)).assertIsDisplayed()
        composeTestRule.onNode(hasClickAction() and hasLabel(PAGE_DOWN)).assertIsDisplayed()
    }

    @Test
    fun keyBar_anOverFullSavedPageStillShowsEveryKey() {
        val six = listOf("esc", "tab", "slash", "dash", "pipe", "tilde")
        setBar(bridgeWithPostLogin(null), keyBar = KeyBarLayout(listOf(six)))
        listOf("Esc", "Tab", "/", "-", "|", "~").forEach {
            // Inside the key bar, on screen: the same label may also sit in a row above.
            val bar = bounds(KEY_BAR_TEST_TAG)
            val onScreen = composeTestRule.onAllNodes(hasClickAction() and hasLabel(it)).fetchSemanticsNodes()
                .count { node -> node.boundsInRoot.top >= bar.top && node.boundsInRoot.left >= 0f && node.boundsInRoot.right <= bar.right + 0.5f }
            assertEquals("'$it' is in the key bar once", 1, onScreen)
        }
    }

    @Test
    fun keyBar_theFirstPageShowsPgUpAndPgDnAsWords_notArrows() {
        setBar(bridgeWithPostLogin(null), keyBar = KeyBarLayout(listOf(listOf("ctrl-c"))), hisKeys = false)
        composeTestRule.onAllNodesWithText(PAGE_UP_LABEL, useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onAllNodesWithText(PAGE_DOWN_LABEL, useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onAllNodesWithText("\u2191", useUnmergedTree = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("\u2193", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun keyBar_theShiftTabKeyIsStillCalledShiftTabForAScreenReader() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("shift-tab"))))
        key("Shift+Tab").performClick()
        verify(bridge).injectString(ESC + "[Z")
    }

    @Test
    fun keyBar_pageDotsDoNotReachIntoTheKeysAboveAndAreWideEnough() {
        setBar(bridgeWithPostLogin(null), keyBar = KeyBarLayout(listOf(listOf("esc", "tab"), listOf("ctrl-z"))))
        val density = composeTestRule.density.density
        val keysBottom = composeTestRule.onAllNodes(hasLabel("Tab")).fetchSemanticsNodes().maxOf { it.boundsInRoot.bottom }
        (0..2).forEach { i ->
            val touch = composeTestRule.onNodeWithTag("key_bar_dot_$i").fetchSemanticsNode().touchBoundsInRoot
            assertTrue("dot $i touch box starts at ${touch.top}, keys end at $keysBottom", touch.top >= keysBottom - 0.5f)
            assertTrue("dot $i touch box is ${touch.width / density} dp wide, needs 32", touch.width / density >= 32f)
        }
    }

    @Test
    fun keyBar_theCurrentPageDotIsSelectedForTalkBack() {
        setBar(bridgeWithPostLogin(null), keyBar = KeyBarLayout(listOf(listOf("esc"), listOf("tab"))))
        val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
        composeTestRule.onNodeWithTag("key_bar_dot_1").assert(selected)
        composeTestRule.onNodeWithTag("key_bar_dot_0").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        composeTestRule.onNodeWithTag("key_bar_dot_1").assertContentDescriptionEquals("Page 2 of 3")
    }

    @Test
    fun keyBar_theBottomRowIsTheFirstPage_andASwipeShowsHisKeys() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl-c"))), hisKeys = false)

        val bar = bounds(KEY_BAR_TEST_TAG)
        listOf(PAGE_UP, PAGE_DOWN, MORE).forEach { assertInside(it, bar) }
        key("Ctrl+C").assertDoesNotExist()

        composeTestRule.onNodeWithTag(KEY_BAR_TEST_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        key("Ctrl+C").performClick()
        verify(bridge).injectString("\u0003")
        // One key row: the bottom row's keys turned away with its page, no second row stayed.
        key(MORE).assertDoesNotExist()
        key(PAGE_UP).assertDoesNotExist()
    }

    @Test
    fun keyBar_aSwipeStartedOnPgUpTurnsThePage_andScrollsNothing() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl-c"))), hisKeys = false)

        // The thumb rests on PgUp past the press delay of a key in a scrolling row, then swipes.
        key(PAGE_UP).performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(150)
        composeTestRule.onNodeWithTag(KEY_BAR_TEST_TAG).performTouchInput {
            repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(-width / 12f, 0f)) }
            up()
        }
        composeTestRule.waitForIdle()
        verify(bridge, never()).injectString("\u001b[5~")
        key("Ctrl+C").assertExists()
    }

    @Test
    fun keyBar_aTapOnPgUpOnTheFirstPageSendsItOnce() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl-c"))), hisKeys = false)

        key(PAGE_UP).performClick()
        composeTestRule.waitForIdle()
        verify(bridge, times(1)).injectString("\u001b[5~")
    }

    @Test
    fun keyBar_theSpaceKeyTypesASpace_andIsTwoKeysWide() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("space", "slash"))))

        key("Space").performClick()
        verify(bridge).injectString(" ")
        val space = key("Space").fetchSemanticsNode().boundsInRoot
        val slash = key("/").fetchSemanticsNode().boundsInRoot
        assertTrue("Space is about two keys wide: ${space.width} against ${slash.width}", space.width > slash.width * 1.8f)
    }

    @Test
    fun keyBar_aSwipeTurnsThePage() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl-c"), listOf("ctrl-z"))))

        composeTestRule.onNodeWithTag(KEY_BAR_TEST_TAG).performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        key("Ctrl+Z").performClick()
        verify(bridge).injectString("\u001a")
    }

    /**
     * A slow, deliberate swipe: the finger goes down on [startKey], drags by [fraction] of the bar's width over
     * [durationMs] (to the left, or to the right when [toRight]), and rests 300 ms before it lifts, so the pager
     * sees no fling and only the distance counts.
     */
    private fun slowSwipeFromKey(startKey: String, fraction: Float, durationMs: Long, toRight: Boolean = false) {
        val barWidth = bounds(KEY_BAR_TEST_TAG).width
        val steps = 30
        val sign = if (toRight) 1f else -1f
        key(startKey).performTouchInput {
            down(center)
            repeat(steps) { moveBy(androidx.compose.ui.geometry.Offset(sign * barWidth * fraction / steps, 0f), delayMillis = durationMs / steps) }
            advanceEventTime(300)
            up()
        }
        composeTestRule.waitForIdle()
    }

    private fun slowSwipeLeftFromKey(startKey: String, fraction: Float, durationMs: Long) = slowSwipeFromKey(startKey, fraction, durationMs)

    /** The page dot that is selected (what TalkBack reads as the current page) is [page]'s. */
    private fun assertOnPage(page: Int, of: Int) {
        (0 until of).forEach {
            composeTestRule.onNodeWithTag("key_bar_dot_$it").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, it == page))
        }
    }

    @Test
    fun keyBar_aSlowSwipeOfAThirdOfTheBarFromAKeyTurnsThePage() {
        // Measured on the phone and the emulator: 30 percent of the bar over 900 ms left the page where it was.
        setBar(bridgeWithPostLogin(null), keyBar = KeyBarLayout(listOf(listOf("ctrl-c"))), hisKeys = false)

        slowSwipeLeftFromKey(MORE, fraction = 0.30f, durationMs = 900)

        assertOnPage(1, of = 2)
    }

    @Test
    fun keyBar_aSlowSwipeOfTwoFifthsOfTheBarFromAKeyTurnsThePage() {
        setBar(bridgeWithPostLogin(null), keyBar = KeyBarLayout(listOf(listOf("ctrl-c"))), hisKeys = false)

        slowSwipeLeftFromKey(MORE, fraction = 0.38f, durationMs = 900)

        assertOnPage(1, of = 2)
    }

    // The two tests below pin the turn threshold (PAGE_TURN_THRESHOLD, 20 percent): 15 stays, 25 turns.
    @Test
    fun keyBar_aSlowDragOfThreeTwentiethsOfTheBarLeavesThePage() {
        setBar(bridgeWithPostLogin(null), keyBar = KeyBarLayout(listOf(listOf("ctrl-c"))), hisKeys = false)

        slowSwipeLeftFromKey(MORE, fraction = 0.15f, durationMs = 900)

        assertOnPage(0, of = 2)
    }

    @Test
    fun keyBar_aSlowSwipeOfAQuarterOfTheBarFromAKeyTurnsThePage() {
        setBar(bridgeWithPostLogin(null), keyBar = KeyBarLayout(listOf(listOf("ctrl-c"))), hisKeys = false)

        slowSwipeLeftFromKey(MORE, fraction = 0.25f, durationMs = 900)

        assertOnPage(1, of = 2)
    }

    @Test
    fun keyBar_aSlowSwipeToTheRightFromTheSecondPageTurnsBack() {
        setBar(bridgeWithPostLogin(null), keyBar = KeyBarLayout(listOf(listOf("ctrl-c"))), hisKeys = false)
        toPage(1)
        assertOnPage(1, of = 2)

        slowSwipeFromKey("Ctrl+C", fraction = 0.30f, durationMs = 900, toRight = true)

        assertOnPage(0, of = 2)
        key(PAGE_UP).assertExists()
    }

    @Test
    fun keyBar_aSlowDragOfATenthOfTheBarLeavesThePage() {
        // A thumb that drifts a little while it rests on a key is not a swipe.
        setBar(bridgeWithPostLogin(null), keyBar = KeyBarLayout(listOf(listOf("ctrl-c"))), hisKeys = false)

        slowSwipeLeftFromKey(MORE, fraction = 0.10f, durationMs = 900)

        assertOnPage(0, of = 2)
    }

    @Test
    fun keyBar_aTappedCtrlIsArmedForTheNextKeyOnly() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl", "left"))))

        key("Ctrl").performClick()
        key("Ctrl, armed for the next key").assertExists()
        key("\u2190").performClick()
        verify(bridge).injectString("\u001b[1;5D")
        key("Ctrl").assertExists()
        key("\u2190").performClick()
        verify(bridge).injectString("\u001b[D")
    }

    @Test
    fun keyBar_aLongPressLocksCtrlUntilItIsTapped() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl", "left"))))

        key("Ctrl").performTouchInput { longClick() }
        key("Ctrl, locked").assertExists()
        key("\u2190").performClick()
        key("\u2190").performClick()
        verify(bridge, times(2)).injectString("\u001b[1;5D")

        key("Ctrl, locked").performClick()
        key("Ctrl").assertExists()
        key("\u2190").performClick()
        verify(bridge).injectString("\u001b[D")
    }

    @Test
    fun keyBar_anArmedCtrlSurvivesATextKeyAndTakesTheNextKey() {
        val bridge = bridgeWithPostLogin(null)
        val clear = KeyBarCatalog.textKeyId("/clear", enter = false)!!
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl", clear, "left"))))

        key("Ctrl").performClick()
        key("/clear").performClick()
        verify(bridge).injectString("/clear")
        key("Ctrl, armed for the next key").assertExists()
        key("\u2190").performClick()
        verify(bridge).injectString("\u001b[1;5D")
    }

    @Test
    fun keyBar_ctrlSpaceSendsNul() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl", "space"))))

        key("Ctrl").performClick()
        key("Space").performClick()
        verify(bridge).injectString("\u0000")
    }

    @Test
    fun keyBar_theBottomPageShowsAnArmedOrLockedModifierAndNothingWhenOff() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl", "alt"))))

        toPage(0)
        composeTestRule.onAllNodesWithTag(MOD_MARK).assertCountEquals(0)

        toPage(1)
        key("Ctrl").performClick()
        toPage(0)
        composeTestRule.onNodeWithTag(MOD_MARK).assertExists()
        composeTestRule.onNodeWithContentDescription("Ctrl armed, tap to clear").assertExists()

        toPage(1)
        key("Ctrl, armed for the next key").performTouchInput { longClick() }
        toPage(0)
        composeTestRule.onNodeWithContentDescription("Ctrl locked, tap to clear").assertExists()

        toPage(1)
        key("Ctrl, locked").performClick()
        toPage(0)
        composeTestRule.onAllNodesWithTag(MOD_MARK).assertCountEquals(0)
    }

    @Test
    fun keyBar_anArmedCtrlIsSpentByAKeyItDoesNotChange() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl", "dash", "left"))))

        key("Ctrl").performClick()
        key("-").performClick()
        verify(bridge).injectString("-")
        key("Ctrl").assertExists()
        key("\u2190").performClick()
        verify(bridge).injectString("\u001b[D")
    }

    @Test
    fun keyBar_altLeavesATextMacroAloneAndStaysArmed() {
        val bridge = bridgeWithPostLogin(null)
        val clear = KeyBarCatalog.textKeyId("/clear", enter = false)!!
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("alt", clear, "slash"))))

        key("Alt").performClick()
        key("/clear").performClick()
        verify(bridge).injectString("/clear")
        key("Alt, armed for the next key").assertExists()
        key("/").performClick()
        verify(bridge).injectString("\u001b/")
    }

    @Test
    fun keyBar_tappingTheMarkClearsTheModifiersAndTheBarKeepsItsHeight() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl", "alt"))))
        val height = { composeTestRule.onNodeWithTag(KEY_BAR_TEST_TAG).getUnclippedBoundsInRoot().height }
        toPage(0)
        val before = height()

        toPage(1)
        key("Ctrl").performTouchInput { longClick() }
        key("Alt").performClick()
        toPage(0)
        composeTestRule.onNodeWithContentDescription("Ctrl locked, Alt armed, tap to clear").assertExists()
        assertEquals(before, height())

        composeTestRule.onNodeWithTag(MOD_MARK).performClick()
        composeTestRule.onAllNodesWithTag(MOD_MARK).assertCountEquals(0)
        toPage(1)
        key("Ctrl").assertExists()
        key("Alt").assertExists()
    }

    @Test
    fun keyBar_switchingTheTerminalClearsALockedModifier() {
        val first = bridgeWithPostLogin(null)
        val second = bridgeWithPostLogin(null)
        var current by mutableStateOf(first)
        composeTestRule.setContent {
            ConnectBotTheme {
                CompositionLocalProvider(LocalUsageLog provides UsageLog { logged += it }) {
                    Box(Modifier.width(PHONE_WIDTH).fillMaxSize()) {
                        TerminalComposeBar(
                            bridge = current,
                            modifier = Modifier.align(Alignment.BottomCenter).testTag(BAR),
                            keyBar = KeyBarLayout(listOf(listOf("ctrl", "left"))),
                        )
                    }
                }
            }
        }
        toPage(1)
        key("Ctrl").performTouchInput { longClick() }
        key("Ctrl, locked").assertExists()

        composeTestRule.runOnIdle { current = second }
        composeTestRule.waitForIdle()
        toPage(1)
        key("Ctrl").assertExists()
        key("\u2190").performClick()
        verify(second).injectString("\u001b[D")
    }

    @Test
    fun keyBar_altArmsAcrossPagesAndPrefixesEscape() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("alt"), listOf("slash"))))

        key("Alt").performClick()
        toPage(2)
        key("/").performClick()
        verify(bridge).injectString("\u001b/")
    }

    @Test
    fun keyBar_theArrowsKeySendsTheArrowUnderTheTapAndTakesAnArmedCtrl() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl", "arrows"))))

        composeTestRule.onNodeWithContentDescription("Arrows").performTouchInput { click(topCenter) }
        verify(bridge).injectString("\u001b[A")
        key("Ctrl").performClick()
        composeTestRule.onNodeWithContentDescription("Arrows").performTouchInput { click(centerRight) }
        verify(bridge).injectString("\u001b[1;5C")
    }

    @Test
    fun keyBar_aTextKeyTypesItsTextAndEnterAfterAShortPause() {
        val bridge = bridgeWithPostLogin(null)
        val plain = KeyBarCatalog.textKeyId("/clear", enter = false)!!
        val withEnter = KeyBarCatalog.textKeyId("/compact", enter = true)!!
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf(plain, withEnter))))

        key("/clear").performClick()
        verify(bridge).injectString("/clear")
        verify(bridge, never()).injectString(CR)

        key("/compact").performClick()
        verify(bridge).injectString("/compact")
        composeTestRule.mainClock.advanceTimeBy(200)
        verify(bridge).injectString(CR)
    }

    @Test
    fun keyBar_aTextKeyUsesAnArmedModifierWithoutChangingItsText() {
        val bridge = bridgeWithPostLogin(null)
        val id = KeyBarCatalog.textKeyId("/clear", enter = false)!!
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl", id))))

        key("Ctrl").performClick()
        key("/clear").performClick()
        verify(bridge).injectString("/clear")
        key("Ctrl").assertExists()
    }

    @Test
    fun keyBar_ctrlBNeverReachesTheTerminalFromATextKey() {
        val bridge = bridgeWithPostLogin(null)
        val id = KeyBarCatalog.textKeyId("b", enter = false)!!
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl", id))))

        key("Ctrl").performClick()
        key("b").performClick()
        verify(bridge, never()).injectString("\u0002")
        verify(bridge, never()).injectString("b")
    }

    @Test
    fun keyBar_aHeldArrowKeepsTheArmedModifierOnEveryRepeat() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("ctrl", "arrows"))))

        key("Ctrl").performClick()
        composeTestRule.onNodeWithContentDescription("Arrows").performTouchInput { down(topCenter) }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.onNodeWithContentDescription("Arrows").performTouchInput { up() }

        verify(bridge, atLeast(3)).injectString("\u001b[1;5A")
        verify(bridge, never()).injectString("\u001b[A")
    }

    @Test
    fun keyBar_aSwipeStartedOnARepeatingKeySendsNothing() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("left"), listOf("ctrl-z"))))

        key("\u2190").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        verify(bridge, never()).injectString("\u001b[D")
        key("Ctrl+Z").assertExists()
    }

    @Test
    fun keyBar_aHeldRepeatingKeyRepeatsAndATapSendsOnce() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("left", "backspace"))))

        key("Bksp").performClick()
        verify(bridge, times(1)).injectString("\u007f")
        key("\u2190").performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        key("\u2190").performTouchInput { up() }
        verify(bridge, atLeast(3)).injectString("\u001b[D")
    }

    @Test
    fun keyBar_pasteDropsAPrefixByte() {
        val bridge = bridgeWithPostLogin(null)
        val clipboard = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("t", "a\u0002b"))
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("paste"))))

        key("Paste").performClick()
        composeTestRule.waitForIdle()
        verify(bridge).injectString("ab")
    }

    @Test
    fun keyBar_functionKeysSendTheirSequences() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("f1", "f5"))))

        key("F1").performClick()
        key("F5").performClick()
        verify(bridge).injectString("\u001bOP")
        verify(bridge).injectString("\u001b[15~")
    }

    @Test
    fun keyBar_composeKeyFocusesTheComposeField() {
        val draft = DictationDraft()
        setBar(bridgeWithPostLogin(null), draft = draft, keyBar = KeyBarLayout(listOf(listOf("compose"))))

        key("Compose").performClick()
        assertEquals(1, draft.focusRequests)
    }

    @Test
    fun keyBar_pasteKeyWritesTheClipboardToTheTerminal() {
        val bridge = bridgeWithPostLogin(null)
        val clipboard = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSystemService(android.content.ClipboardManager::class.java)
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("t", "ls -la"))
        setBar(bridge, keyBar = KeyBarLayout(listOf(listOf("paste"))))

        key("Paste").performClick()
        composeTestRule.waitForIdle()
        verify(bridge).injectString("ls -la")
    }

    @Test
    fun barKeys_emptyEnterEscAndTabSendTheirBytes() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge)

        key(ENTER).performClick()
        key("Esc").performClick()
        key("Tab").performClick()

        verify(bridge).injectString(CR)
        verify(bridge).injectString(ESC)
        verify(bridge).injectString(TAB)
        assertEquals(listOf(UsageActions.KEY_ENTER, UsageActions.KEY_ESC, UsageActions.KEY_TAB), logged)
    }

    @Test
    fun enterKey_withText_isSend_thenGoesBackToEnter() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge)
        composeTestRule.onNode(hasSetTextAction()).performTextInput("git status")

        // With text in the field the one ⏎ key reads as Send and sends the text, then Enter.
        composeTestRule.onAllNodesWithContentDescription(ENTER).assertCountEquals0()
        key(SEND).performClick()
        composeTestRule.mainClock.advanceTimeBy(100)

        assertEquals(listOf("git status", CR), sentBytes(bridge))
        assertEquals(listOf(UsageActions.SEND), logged)
        composeTestRule.onNode(hasSetTextAction()).assertTextContains("")
        key(ENTER).assertIsDisplayed()
    }

    // The tray.

    @Test
    fun tray_closedAtFirst_moreOpensAndCloses() {
        setBar(bridgeWithPostLogin("herdr\n"), onRead = {})
        assertTrayClosed()

        openTray()
        listOf("Left", "Up", "Down", "Right", "Mode", READ, "Sidebar").forEach {
            key(it).assertIsDisplayed()
        }
        // The keys Ormus never used are gone from the tray (Goto and copy mode live in the
        // Herdr panel; Needs you sits on row 1, outside the tray).
        listOf("Stop", "Ctrl", "y", "n", "Goto", "Scroll").forEach {
            composeTestRule.onAllNodesWithText(it).assertCountEquals0()
        }
        composeTestRule.onNodeWithTag(TRAY_TEST_TAG).assert(hasAnyDescendant(hasText("Needs you")).not())

        more().performClick()
        assertTrayClosed()
        assertEquals(listOf(UsageActions.BAR_TRAY_OPEN, UsageActions.BAR_TRAY_CLOSE), logged)
    }

    @Test
    fun tray_takesItsOwnHeight_soTheTerminalMovesUp() {
        setBar(bridgeWithPostLogin("herdr\n"), onRead = {})
        val closed = composeTestRule.onNodeWithTag(BAR).fetchSemanticsNode().boundsInRoot
        val terminalFloorClosed = closed.top

        openTray()
        val open = composeTestRule.onNodeWithTag(BAR).fetchSemanticsNode().boundsInRoot
        val tray = composeTestRule.onNodeWithTag(TRAY_TEST_TAG).fetchSemanticsNode().boundsInRoot

        // The console pads the terminal by the bar's height, so a taller bar moves the
        // terminal up by the tray's height instead of the tray covering it.
        assertEquals(tray.height, open.height - closed.height, 1f)
        assertEquals(open.top, tray.top, 0.5f)
        assertTrue("terminal floor moves up", open.top < terminalFloorClosed)
    }

    @Test
    fun trayRead_opensTheReader() {
        var opened = false
        setBar(bridgeWithPostLogin(null), onRead = { opened = true })
        openTray()

        key(READ).performClick()

        assertTrue(opened)
        assertEquals(listOf(UsageActions.BAR_TRAY_OPEN, UsageActions.READER_OPEN), logged)
    }

    @Test
    fun tray_swipeUpOnBarOpens_swipeDownCloses() {
        setBar(bridgeWithPostLogin(null))

        composeTestRule.onNodeWithTag(BAR).performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(TRAY_TEST_TAG).assertIsDisplayed()

        composeTestRule.onNodeWithTag(TRAY_TEST_TAG).performTouchInput { swipeDown() }
        assertTrayClosed()

        composeTestRule.onNodeWithTag(BAR).performTouchInput { swipeUp() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag(BAR).performTouchInput { swipeDown() }
        assertTrayClosed()

        assertEquals(
            listOf(
                UsageActions.BAR_TRAY_SWIPE,
                UsageActions.BAR_TRAY_CLOSE,
                UsageActions.BAR_TRAY_SWIPE,
                UsageActions.BAR_TRAY_CLOSE,
            ),
            logged,
        )
    }

    @Test
    fun tray_backCloses() {
        setBar(bridgeWithPostLogin(null))
        openTray()

        composeTestRule.runOnUiThread { composeTestRule.activity.onBackPressedDispatcher.onBackPressed() }

        assertTrayClosed()
        assertEquals(listOf(UsageActions.BAR_TRAY_OPEN, UsageActions.BAR_TRAY_CLOSE), logged)
    }

    @Test
    fun trayKeys_sendTheSameBytesAsBefore_andKeepTheTrayOpen() {
        // Label, the bytes the key sent before the tray, and its unchanged usage id.
        val keys = listOf(
            Triple("Mode", ESC + "[Z", UsageActions.KEY_MODE),
            Triple("Left", ESC + "[D", UsageActions.KEY_LEFT),
            Triple("Up", ESC + "[A", UsageActions.KEY_UP),
            Triple("Down", ESC + "[B", UsageActions.KEY_DOWN),
            Triple("Right", ESC + "[C", UsageActions.KEY_RIGHT),
            Triple("Sidebar", CTRL_B + "b", UsageActions.HERDR_SIDEBAR),
            Triple("Clear", 21.toChar().toString(), UsageActions.KEY_CLEAR_LINE),
        )
        val bridge = bridgeWithPostLogin("herdr\n")
        setBar(bridge)
        openTray()

        // One open, then every key in a row: the tray stays up between presses.
        for ((label, bytes, id) in keys) {
            logged.clear()
            key(label).performClick()
            verify(bridge).injectString(bytes)
            assertTrayOpen()
            assertEquals("$label logs its own id", listOf(id), logged)
        }
    }

    @Test
    fun trayPaste_addsTheScreenshotPathToTheDraft() {
        setBar(bridgeWithPostLogin(null), onPasteScreenshot = { "/home/alex/.cache/mercurio-pastes/photo-1.png" })
        composeTestRule.onNode(hasSetTextAction()).performTextInput("look at")
        openTray()

        key(PASTE).performClick()
        assertTrayOpen()

        composeTestRule.onNode(hasSetTextAction()).assertTextContains("look at read /home/alex/.cache/mercurio-pastes/photo-1.png ")
        assertEquals(listOf(UsageActions.BAR_TRAY_OPEN, UsageActions.PASTE_SCREENSHOT), logged)
    }

    @Test
    fun plainHost_trayHerdrKeysDimmed() {
        setBar(bridgeWithPostLogin(null), onPasteScreenshot = { null })
        openTray()

        key(PASTE).assertIsEnabled()
        key("Sidebar").assertIsNotEnabled()
    }

    @Test
    fun trayBottom_sendsOneBurstOfPageDowns_loggedOnce() {
        val bridge = bridgeWithPostLogin("herdr\n")
        setBar(bridge)
        openTray()
        logged.clear()

        key("Jump to bottom").performClick()

        assertEquals(listOf((ESC + "[6~").repeat(40)), sentBytes(bridge))
        assertEquals(listOf(UsageActions.KEY_BOTTOM), logged)
        assertTrayOpen()
    }

    @Test
    fun trayReplyKey_copiesTheAgentsLastReplyToThePhone() {
        setBar(bridgeWithPostLogin("herdr\n"), lastReply = "Done: all green.")
        openTray()
        logged.clear()

        key("Copy last reply").performClick()
        composeTestRule.waitForIdle()

        val clipboard = composeTestRule.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("Done: all green.", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(listOf(UsageActions.BAR_REPLY_COPY), logged)
    }

    @Test
    fun trayUrlKey_copiesTheLatestUrlToThePhone() {
        setBar(bridgeWithPostLogin("herdr\n"), recentOutput = "PR: https://github.com/o/r/pull/7\nlive at https://app.example.com/x.")
        openTray()
        logged.clear()

        key("Copy latest URL").performClick()
        composeTestRule.waitForIdle()

        val clipboard = composeTestRule.activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("https://app.example.com/x", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(listOf(UsageActions.BAR_URL_COPY), logged)
        assertTrayOpen()
    }

    @Test
    fun trayUrlKey_held_opensTheLatestUrl_wholeEvenWhenItWrappedAtTheEdge() {
        val url = "https://accounts.example.com/o/oauth2/auth?access_type=offline&client_id=1234567890.apps.example.com&state=Na1bJMqwsS"
        val rule = "\u2500".repeat(40)
        val screen = (listOf("Open the URL below in your browser:", rule) + url.chunked(40) + listOf(rule, "authorization code...")).joinToString("\n")
        setBar(bridgeWithPostLogin("herdr\n"), recentOutput = screen)
        openTray()
        logged.clear()

        key("Copy latest URL").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        assertEquals(listOf(url), opened)
        assertEquals(listOf(UsageActions.BAR_URL_OPEN), logged)
    }

    @Test
    fun traySidebar_togglesHerdrsSidePanel_andKeepsTheTrayOpen() {
        val bridge = bridgeWithPostLogin("herdr\n")
        setBar(bridge)
        openTray()
        logged.clear()

        key("Sidebar").performClick()
        key("Sidebar").performClick()

        // Herdr's default prefix (no Herdr side here), then b: Herdr's own default for the side panel.
        assertEquals(listOf(CTRL_B + "b", CTRL_B + "b"), sentBytes(bridge))
        assertEquals(listOf(UsageActions.HERDR_SIDEBAR, UsageActions.HERDR_SIDEBAR), logged)
        assertTrayOpen()
    }

    @Test
    fun traySidebar_sendsTheHostsPrefix() {
        val bridge = herdrBridgeWithScrollback(0)
        setBar(bridge)
        openTray()
        logged.clear()

        key("Sidebar").performClick()

        // Sun's config sets prefix = "ctrl+space": NUL, then b.
        assertEquals(listOf(NUL + "b"), sentBytes(bridge))
        assertEquals(listOf(UsageActions.HERDR_SIDEBAR), logged)
    }

    @Test
    fun draft_survivesTrayUse() {
        val bridge = bridgeWithPostLogin("herdr\n")
        setBar(bridge)
        val field = composeTestRule.onNode(hasSetTextAction())
        field.performTextInput("run the four checks")

        openTray()
        key("Mode").performClick()
        composeTestRule.onNodeWithTag(TRAY_TEST_TAG).performTouchInput { swipeDown() }
        openTray()
        more().performClick()
        assertTrayClosed()

        field.assertTextContains("run the four checks")
        verify(bridge, never()).injectString("run the four checks")
        composeTestRule.onNodeWithContentDescription(SEND).performClick()
        verify(bridge).injectString("run the four checks")
    }

    @Test
    fun everyKey_isAtLeast44dpWideAnd46dpTall_onA411dpPhone() {
        setBar(
            bridgeWithPostLogin("herdr\n"),
            herdrPanel = true,
            onRead = {},
            onPasteScreenshot = { null },
            onSlashMenu = {},
        )
        listOf(PAGE_UP, PAGE_DOWN, "Herdr", MORE, NEEDS_YOU, "Esc", "Tab", ENTER).forEach { assertComfortable(it) }
        openTray()
        listOf("Left", "Up", "Down", "Right", "Jump to bottom", "Mode", "Clear", READ, "Copy latest URL", "Copy last reply", PASTE, "Sidebar", "/")
            .forEach { assertComfortable(it) }
    }

    @Test
    fun plainHost_everyBarKey_isAtLeast44dpWide() {
        setBar(bridgeWithPostLogin(null))
        listOf(PAGE_UP, PAGE_DOWN, "Herdr", MORE, NEEDS_YOU, "Esc", "Tab", ENTER).forEach { assertComfortable(it) }
    }

    // The compose box and the key bar are two separate parts.

    private fun bounds(tag: String) = composeTestRule.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot

    private fun assertInside(label: String, group: androidx.compose.ui.geometry.Rect) {
        val b = key(label).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("'$label' sits inside its group", b.left >= group.left - 0.5f && b.right <= group.right + 0.5f && b.top >= group.top - 0.5f && b.bottom <= group.bottom + 0.5f)
    }

    @Test
    fun composeBox_andKeyGroup_areTwoPartsWithSpaceBetween() {
        setBar(bridgeWithPostLogin("herdr\n"), onSlashMenu = {})

        val box = bounds(COMPOSE_BOX_TEST_TAG)
        val keys = bounds(KEY_GROUP_TEST_TAG)
        val gap = (keys.top - box.bottom) / composeTestRule.density.density
        assertTrue("the key group is $gap dp clear under the compose box, needs 10", gap >= 10f)
    }

    @Test
    fun keysInBothGroups_stillSendTheirBytes() {
        val bridge = bridgeWithPostLogin(null)
        setBar(bridge)

        key("Esc").performClick()
        key(PAGE_DOWN).performClick()

        assertEquals(listOf(ESC, ESC + "[6~"), sentBytes(bridge))
    }

    @Test
    fun composeBox_holdsTheFieldAndItsKeys_keyGroupHoldsThePageKeys() {
        setBar(bridgeWithPostLogin("herdr\n"), onSlashMenu = {})

        val box = bounds(COMPOSE_BOX_TEST_TAG)
        val keys = bounds(KEY_GROUP_TEST_TAG)
        listOf("Esc", "Tab", ENTER).forEach { assertInside(it, box) }
        val field = composeTestRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("the field sits inside the compose box", field.left >= box.left && field.right <= box.right && field.top >= box.top && field.bottom <= box.bottom)
        listOf(PAGE_UP, PAGE_DOWN, "Herdr", MORE, CHAT, NEEDS_YOU).forEach { assertInside(it, keys) }
    }

    @Test
    fun editableKeyRow_staysInTheKeyGroup_apartFromTheComposeBox() {
        setBar(bridgeWithPostLogin(null), keyBar = KeyBarLayout(listOf(listOf("ctrl-c", "up"), listOf("ctrl-z"))))

        val keys = bounds(KEY_GROUP_TEST_TAG)
        val row = bounds(KEY_BAR_TEST_TAG)
        val box = bounds(COMPOSE_BOX_TEST_TAG)
        assertTrue("the editable row is inside the key group", row.top >= keys.top - 0.5f && row.bottom <= keys.bottom + 0.5f)
        assertTrue("the compose box sits above the key group without overlap", box.bottom <= keys.top)
    }

    // The key row has no pill of its own: the keys sit on the bar, flush with the key group.

    private fun assertKeysFlushWithTheKeyGroup(first: String, last: String) {
        val group = bounds(KEY_GROUP_TEST_TAG)
        val start = key(first).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val end = key(last).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val density = composeTestRule.density.density
        assertEquals("'$first' starts at the left edge of the key group (no pill padding), ${(start.left - group.left) / density} dp in", group.left, start.left, 0.5f)
        assertEquals("'$last' ends at the right edge of the key group (no pill padding), ${(group.right - end.right) / density} dp short", group.right, end.right, 0.5f)
        assertEquals("the keys start at the top of the key group (no pill padding), ${(start.top - group.top) / density} dp down", group.top, start.top, 0.5f)
    }

    @Test
    fun keyRow_hasNoPillAroundIt_theKeysReachBothEndsOfTheKeyGroup() {
        setBar(bridgeWithPostLogin("herdr\n"))

        assertKeysFlushWithTheKeyGroup(PAGE_UP, NEEDS_YOU)
        val group = bounds(KEY_GROUP_TEST_TAG)
        val bottom = key(PAGE_UP).fetchSemanticsNode().boundsInRoot.bottom
        assertEquals("the key group ends where the keys end (no pill padding)", group.bottom, bottom, 0.5f)
    }

    @Test
    fun keyBar_hasNoPillAroundIt_theKeysReachBothEndsOfTheKeyGroup_andTheDotsStayUnderThem() {
        setBar(bridgeWithPostLogin("herdr\n"), keyBar = KeyBarLayout(listOf(listOf("ctrl-c", "up"))), hisKeys = false)

        assertKeysFlushWithTheKeyGroup(PAGE_UP, NEEDS_YOU)
        val group = bounds(KEY_GROUP_TEST_TAG)
        val keysBottom = key(PAGE_UP).fetchSemanticsNode().boundsInRoot.bottom
        val dots = bounds("key_bar_dot_0")
        assertEquals("the page dots sit right under the keys", keysBottom, dots.top, 0.5f)
        assertEquals("the key group ends where the dots end (no pill padding)", group.bottom, dots.bottom, 0.5f)
    }

    @Test
    fun keyRow_theKeysKeepTheirHeight_andTheSpaceBetweenTheGroupsStays() {
        setBar(bridgeWithPostLogin("herdr\n"))

        val density = composeTestRule.density.density
        listOf(PAGE_UP, PAGE_DOWN, "Herdr", MORE, CHAT, NEEDS_YOU).forEach {
            assertEquals("'$it' is 46 dp tall", 46f, key(it).fetchSemanticsNode().boundsInRoot.height / density, 0.5f)
        }
        val gap = (bounds(KEY_GROUP_TEST_TAG).top - bounds(COMPOSE_BOX_TEST_TAG).bottom) / density
        assertEquals("the gap between the compose box and the key group", 10f, gap, 0.5f)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun keyRow_aGapBetweenTwoKeysShowsTheBar_notAPillFill() {
        setBar(bridgeWithPostLogin("herdr\n"))

        val left = key(PAGE_UP).fetchSemanticsNode().boundsInWindow
        val right = key(PAGE_DOWN).fetchSemanticsNode().boundsInWindow
        val box = composeTestRule.onNodeWithTag(COMPOSE_BOX_TEST_TAG).fetchSemanticsNode().boundsInWindow
        val group = composeTestRule.onNodeWithTag(KEY_GROUP_TEST_TAG).fetchSemanticsNode().boundsInWindow
        val view = composeTestRule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        // The bar itself: the clear space between the compose box and the key group.
        val bar = bitmap.getPixel(box.center.x.toInt(), ((box.bottom + group.top) / 2).toInt())
        // The space between two keys on the key row: it showed the pill's fill before.
        val between = bitmap.getPixel(((left.right + right.left) / 2).toInt(), left.center.y.toInt())
        assertEquals("between two keys: #${Integer.toHexString(between)}, the bar is #${Integer.toHexString(bar)}", bar, between)
        // The draw is real: a blank bitmap would give the bar and the gap the same pixel (0) and pass
        // the check above with nothing drawn. A key paints its own fill, so a pixel just inside the
        // first key (left of its label) must differ from the bar.
        val insideKey = bitmap.getPixel(left.left.toInt() + 4, left.center.y.toInt())
        assertNotEquals("inside a key: #${Integer.toHexString(insideKey)}, the bar is #${Integer.toHexString(bar)}", bar, insideKey)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEquals0() {
        assertEquals(0, fetchSemanticsNodes().size)
    }

    private companion object {
        const val BAR = "composeBar"
        const val MOD_MARK = "key_bar_modifier_mark"
        const val MORE = "More keys"
        const val PAGE_UP = "Page up"
        const val PAGE_DOWN = "Page down"
        const val PAGE_UP_LABEL = "PgUp"
        const val PAGE_DOWN_LABEL = "PgDn"
        const val READ = "Read output"
        const val SEND = "Send"
        const val ENTER = "Enter"
        const val PASTE = "Paste a photo"
        val PHONE_WIDTH = 411.dp
        const val NEEDS_YOU = "Needs you"
        const val CHAT = "Chat"

        // Stands in for Mockito's matchers, which return null for Kotlin's non-null parameters.
        val JUMP = JumpTarget("", "", "", 0, "", 0, null, HerdrAgentStatus.UNKNOWN, false)

        val NUL: String = 0.toChar().toString()
        val CTRL_B: String = 2.toChar().toString()
        val ETX: String = 3.toChar().toString()
        val TAB: String = 9.toChar().toString()
        val CR: String = 13.toChar().toString()
        val ESC: String = 27.toChar().toString()
    }
}
