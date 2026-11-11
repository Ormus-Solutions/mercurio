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

package org.connectbot.ui.screens.console

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.connectbot.data.entity.Host
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.commands.PALETTE_HERDR_ACTIONS
import org.connectbot.ui.commands.PaletteContext
import org.connectbot.ui.commands.buildPaletteEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import solutions.ormus.logos.commands.PaletteAction
import solutions.ormus.logos.commands.SlashCatalogs
import solutions.ormus.logos.herd.CoreWatchTarget
import solutions.ormus.logos.herd.HerdSession
import solutions.ormus.logos.herd.HerdrAction
import solutions.ormus.logos.herd.HerdrKey
import solutions.ormus.logos.herd.HerdrKeys
import solutions.ormus.logos.herd.HerdrStroke
import solutions.ormus.logos.herd.HerdrSwipe

/** On a core terminal's console nothing writes the Herdr TUI prefix or a HerdrKey binding; elsewhere nothing changed. */
@RunWith(AndroidJUnit4::class)
class CoreNoHerdrKeysTest {
    private val sent = mutableListOf<String>()

    private fun herd(keysOff: Boolean): HerdSession = HerdSession(
        hostName = "box",
        runner = { null },
        sendKeys = { sent += it },
        scope = CoroutineScope(Dispatchers.Unconfined),
        io = Dispatchers.Unconfined,
    ).also { it.keysOff = keysOff }

    private fun touchEveryKeyPath(herd: HerdSession) {
        herd.armPrefix()
        herd.perform(HerdrAction.BoundKey(HerdrKey.GOTO))
        herd.perform(HerdrAction.ClientKey("x"))
        herd.switchWorkspace(down = true)
        herd.sendStrokes(listOf(HerdrStroke.Bytes(HerdrKeys.DEFAULT.prefix.bytes + "g")))
    }

    @Test
    fun aSessionWithItsKeysOff_typesNothing() {
        touchEveryKeyPath(herd(keysOff = true))
        assertEquals(emptyList<String>(), sent)
    }

    @Test
    fun anOrdinarySession_stillTypesItsKeys() {
        touchEveryKeyPath(herd(keysOff = false))
        assertTrue(sent.any { it.startsWith(HerdrKeys.DEFAULT.prefix.bytes) })
    }

    private fun bridge(watch: CoreWatchTarget?, herd: HerdSession): TerminalBridge {
        val bridge = mock(TerminalBridge::class.java)
        `when`(bridge.herd).thenReturn(herd)
        `when`(bridge.watch).thenReturn(watch)
        `when`(bridge.herdrOnScreen).thenReturn(true)
        return bridge
    }

    @Test
    fun swipesOnACoreWatch_writeNothing() {
        val watched = bridge(CoreWatchTarget("box/t1", "alpha · ONE", "build"), herd(keysOff = false))
        for (swipe in HerdrSwipe.entries) runHerdrSwipe(watched, swipe)
        assertEquals(emptyList<String>(), sent)
        verify(watched, never()).injectString(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun swipesOnAnOrdinaryConsole_stillWrite() {
        val plain = bridge(null, herd(keysOff = false))
        runHerdrSwipe(plain, HerdrSwipe.PREV_TAB)
        assertTrue(sent.isNotEmpty())
    }

    private val res = ApplicationProvider.getApplicationContext<Context>().resources
    private val labels = PALETTE_HERDR_ACTIONS.associateWith { it.toString() }

    private fun entries(coreView: Boolean) = buildPaletteEntries(
        res,
        PaletteContext(
            hasSession = true,
            runsHerdr = true,
            herdrReady = true,
            herdrKeys = HerdrKeys.fromConfig("[keys]\nprefix = \"ctrl+space\"\n"),
            hosts = listOf(Host(id = 1, nickname = "box", hostname = "box")),
            coreView = coreView,
        ),
        "Claude Code",
        SlashCatalogs.CLAUDE.take(1),
        labels,
    )

    @Test
    fun theCoreViewPalette_hasNoEntryThatTypesHerdrKeys() {
        val ids = entries(coreView = true).map { it.key }
        for (id in listOf("key:needs-you", "key:goto", "key:prev-tab", "key:next-tab", "key:scroll", "open:herdr-sheet")) {
            assertFalse(id, id in ids)
        }
        assertTrue(entries(coreView = true).none { (it.action as? PaletteAction.Herdr)?.action.let { a -> a is HerdrAction.BoundKey || a is HerdrAction.ClientKey } })
        // Plain keys stay.
        assertTrue("key:esc" in ids)
        // The check above is not vacuous: the ordinary palette does hold such actions.
        assertTrue(entries(coreView = false).any { (it.action as? PaletteAction.Herdr)?.action.let { a -> a is HerdrAction.BoundKey } })
    }

    @Test
    fun theOrdinaryPalette_keepsThem() {
        val ids = entries(coreView = false).map { it.key }
        for (id in listOf("key:needs-you", "key:goto", "open:herdr-sheet")) assertTrue(id, id in ids)
    }
}
