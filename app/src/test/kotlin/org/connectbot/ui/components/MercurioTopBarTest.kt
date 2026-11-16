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

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.usage.LocalUsageLog
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shared top bar (`MercurioTopBar`) at the width of a phone (360 dp), with the real text measure (native graphics): the
 * Inbox icon at the left, a centred title and a grey second line, an optional screen slot, the gear, and the overflow only when
 * the screen has entries. Targets are 48 dp, one height for every screen, the title never under an icon, and the labels read
 * in English and in Spanish. Synthetic texts.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class MercurioTopBarTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val logged = mutableListOf<String>()
    private var homes = 0
    private var gears = 0

    private fun show(
        title: String = "Inbox",
        subtitle: String? = null,
        homeSelected: Boolean = false,
        homeIsInbox: Boolean = true,
        slot: Boolean = false,
        menu: Boolean = false,
        fontScale: Float = 1f,
    ) {
        rule.setContent {
            ConnectBotTheme {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale), LocalUsageLog provides UsageLog { logged += it }) {
                    MercurioTopBar(
                        title = title,
                        subtitle = subtitle,
                        homeSelected = homeSelected,
                        homeIsInbox = homeIsInbox,
                        onHome = { homes++ },
                        onSettings = { gears++ },
                        screenSlot = if (slot) {
                            { Box(Modifier.size(48.dp).testTag(SLOT_TAG)) }
                        } else {
                            null
                        },
                        menu = if (menu) {
                            { dismiss -> DropdownMenuItem(text = { Text("Reconnect") }, onClick = dismiss) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun dp(px: Number) = px.toFloat() / rule.density.density

    private fun node(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()

    private fun layoutOf(tag: String): TextLayoutResult {
        val out = mutableListOf<TextLayoutResult>()
        node(tag).config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        return out.first()
    }

    private fun description(tag: String) = node(tag).config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    private fun present(tag: String) = rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun bounds(n: SemanticsNode) = n.boundsInRoot

    @Test
    fun everyIcon_isAtLeast48dpSquare() {
        // The slot sizes its own targets (the KDoc says so): only the bar's own icons are checked here.
        show(subtitle = "second line", slot = true, menu = true)
        listOf(TOP_BAR_HOME_TAG, TOP_BAR_SETTINGS_TAG, TOP_BAR_MENU_TAG).forEach { tag ->
            val n = node(tag)
            assertTrue("$tag is ${dp(n.size.width)} x ${dp(n.size.height)} dp", dp(n.size.width) >= 47.9f && dp(n.size.height) >= 47.9f)
        }
    }

    // Three bars at once (a bare one, one with a second line, one with a slot and a menu): one height for all of them.
    private fun heightsOfThreeBars(fontScale: Float): List<Float> {
        rule.setContent {
            ConnectBotTheme {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                    androidx.compose.foundation.layout.Column {
                        MercurioTopBar(title = "Inbox", onHome = {}, onSettings = {})
                        MercurioTopBar(title = "Spaces", subtitle = "second line", onHome = {}, onSettings = {})
                        MercurioTopBar(title = "demo-workshop", subtitle = "alpha · LAB · 100.64.0.10", onHome = {}, onSettings = {}, screenSlot = { Box(Modifier.size(48.dp)) }, menu = { dismiss -> DropdownMenuItem(text = { Text("Reconnect") }, onClick = dismiss) })
                    }
                }
            }
        }
        rule.waitForIdle()
        return rule.onAllNodesWithTag(TOP_BAR_TAG, useUnmergedTree = true).fetchSemanticsNodes().map { dp(it.size.height) }
    }

    @Test
    fun theBar_hasOneHeight_whateverItShows_atNormalFont() {
        assertEquals(listOf(64f, 64f, 64f), heightsOfThreeBars(1f))
    }

    @Test
    fun theBar_hasOneHeight_whateverItShows_atFont13() {
        assertEquals(listOf(64f, 64f, 64f), heightsOfThreeBars(1.3f))
    }

    @Test
    fun theOverflow_isThereOnlyWhenTheScreenHasEntries_andOpensThem() {
        show(menu = false)
        assertFalse(present(TOP_BAR_MENU_TAG))
    }

    @Test
    fun theOverflow_opensTheScreensEntries() {
        show(menu = true)
        rule.onNodeWithTag(TOP_BAR_MENU_TAG, useUnmergedTree = true).performClick()
        rule.onNodeWithText("Reconnect").assertExists()
    }

    @Test
    fun theScreenSlot_sitsBeforeTheGear() {
        show(slot = true, menu = true)
        assertTrue(bounds(node(SLOT_TAG)).right <= bounds(node(TOP_BAR_SETTINGS_TAG)).left + 1f)
        assertTrue(bounds(node(TOP_BAR_SETTINGS_TAG)).right <= bounds(node(TOP_BAR_MENU_TAG)).left + 1f)
    }

    @Test
    fun aShortTitleAndSecondLine_areWhole_andCentredBetweenTheIcons() {
        show(title = "Inbox", subtitle = "3 need you")
        assertFalse(layoutOf(TOP_BAR_TITLE_TAG).isLineEllipsized(0))
        assertFalse(layoutOf(TOP_BAR_SUBTITLE_TAG).isLineEllipsized(0))
        val title = bounds(node(TOP_BAR_TITLE_TAG))
        val barCentre = bounds(node(TOP_BAR_TAG)).center.x
        assertEquals("the title is centred", barCentre, title.center.x, 2f)
    }

    @Test
    fun aLongTitleAndSecondLine_atFont13_areEllipsized_andNeverUnderAnIcon() {
        val long = "alpha · SYNTHETIC DEVELOPMENT WORKSPACE OF THE LONG NAME"
        show(title = long, subtitle = "box.example · session default · workspace one", slot = true, menu = true, fontScale = 1.3f)
        val home = bounds(node(TOP_BAR_HOME_TAG))
        val leftOfRight = bounds(node(SLOT_TAG)).left
        for (tag in listOf(TOP_BAR_TITLE_TAG, TOP_BAR_SUBTITLE_TAG)) {
            val b = bounds(node(tag))
            assertTrue("$tag is cut by nothing: ellipsized", layoutOf(tag).isLineEllipsized(0))
            assertTrue("$tag starts at ${dp(b.left)} dp, the home icon ends at ${dp(home.right)} dp", b.left >= home.right - 1f)
            assertTrue("$tag ends at ${dp(b.right)} dp, the first icon on the right starts at ${dp(leftOfRight)} dp", b.right <= leftOfRight + 1f)
        }
    }

    @Test
    fun aMidLengthTitle_atFont13_isWholeOrEllipsized_neverUnderAnIcon() {
        show(title = "alpha · MERCURIO", subtitle = "box · default", menu = true, fontScale = 1.3f)
        val home = bounds(node(TOP_BAR_HOME_TAG))
        val gear = bounds(node(TOP_BAR_SETTINGS_TAG))
        val b = bounds(node(TOP_BAR_TITLE_TAG))
        assertTrue(b.left >= home.right - 1f)
        assertTrue(b.right <= gear.left + 1f)
        assertFalse("a 16 character title is whole at 360 dp and font 1.3", layoutOf(TOP_BAR_TITLE_TAG).isLineEllipsized(0))
    }

    @Test
    fun theInboxIcon_goesHome_andLogsItsId() {
        show()
        rule.onNodeWithTag(TOP_BAR_HOME_TAG, useUnmergedTree = true).performClick()
        assertEquals(1, homes)
        assertEquals(listOf(UsageActions.TOPBAR_HOME), logged)
    }

    @Test
    fun theInboxIcon_isSelectedAndInert_onTheInbox() {
        show(homeSelected = true)
        assertEquals(true, node(TOP_BAR_HOME_TAG).config.getOrNull(SemanticsProperties.Selected))
        rule.onNodeWithTag(TOP_BAR_HOME_TAG, useUnmergedTree = true).performClick()
        assertEquals(0, homes)
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun theGear_goesToSettings_andLogsItsId() {
        show()
        rule.onNodeWithTag(TOP_BAR_SETTINGS_TAG, useUnmergedTree = true).performClick()
        assertEquals(1, gears)
        assertEquals(listOf(UsageActions.TOPBAR_SETTINGS), logged)
    }

    @Test
    fun theLabels_readInEnglish() {
        show(menu = true)
        assertEquals("Inbox", description(TOP_BAR_HOME_TAG))
        assertEquals("Settings", description(TOP_BAR_SETTINGS_TAG))
        assertEquals("More options", description(TOP_BAR_MENU_TAG))
    }

    @Test
    fun whenTheInboxIsOff_theHomeIconSaysHosts() {
        show(homeIsInbox = false)
        assertEquals("Hosts", description(TOP_BAR_HOME_TAG))
    }

    @Config(qualifiers = "+es")
    @Test
    fun theLabels_readInSpanish() {
        show(menu = true)
        assertEquals("Bandeja", description(TOP_BAR_HOME_TAG))
        assertEquals("Ajustes", description(TOP_BAR_SETTINGS_TAG))
    }

    private companion object {
        const val SLOT_TAG = "topbar_test_slot"
    }

    // Gate lane 3: a home button is not a selection. Only the Inbox's icon says it is selected; on any other screen the property is
    // absent, so TalkBack does not read "Not selected" (compose adds it when Selected is false).
    @Test
    fun theHomeIcon_hasNoSelectedProperty_whenItIsNotTheSelectedPlace() {
        show(homeSelected = false)
        assertEquals(null, node(TOP_BAR_HOME_TAG).config.getOrNull(SemanticsProperties.Selected))
    }

    // Gate lane 1: the bar pads for the display cutout as Material's TopAppBar does (a landscape punch hole at the left must not sit
    // under the home icon). A 100 px cutout inset is sent to the compose view.
    private fun composeView(v: android.view.View): android.view.View? {
        if (v.javaClass.simpleName == "AndroidComposeView") return v
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) composeView(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun sendCutout(left: Int, right: Int) {
        rule.runOnUiThread {
            val view = composeView(rule.activity.window.decorView)!!
            val insets = androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout(), androidx.core.graphics.Insets.of(left, 0, right, 0))
                .build()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        rule.waitForIdle()
    }

    @Test
    fun aDisplayCutoutAtTheLeft_pushesTheHomeIconClearOfIt() {
        show(title = "Spaces")
        val before = bounds(node(TOP_BAR_HOME_TAG)).left
        sendCutout(left = 100, right = 0)
        val after = bounds(node(TOP_BAR_HOME_TAG)).left
        assertTrue("the home icon starts at $after px with a 100 px cutout (was $before)", after >= 100f)
    }

    @Test
    fun aDisplayCutoutAtTheRight_pullsTheGearClearOfIt() {
        show(title = "Spaces")
        val width = rule.onNodeWithTag(TOP_BAR_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.right
        sendCutout(left = 0, right = 100)
        val gear = bounds(node(TOP_BAR_SETTINGS_TAG)).right
        assertTrue("the gear ends at $gear px of $width with a 100 px cutout at the right", gear <= width - 100f)
    }
}
