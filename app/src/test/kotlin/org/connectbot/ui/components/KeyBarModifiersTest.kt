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

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyBarModifiersTest {
    private val esc = 27.toChar().toString()
    private val none = KeyBarModifiers()

    private fun armed(ctrl: Boolean = false, alt: Boolean = false) = KeyBarModifiers(
        ctrl = if (ctrl) ModLevel.ARMED else ModLevel.OFF,
        alt = if (alt) ModLevel.ARMED else ModLevel.OFF,
    )

    @Test
    fun aTapArmsTheNextKeyOnly() {
        val armed = none.tap(BarModifier.CTRL)
        assertEquals(ModLevel.ARMED, armed.level(BarModifier.CTRL))
        assertEquals(ModLevel.OFF, armed.level(BarModifier.ALT))
        assertEquals(none, armed.afterKey())
    }

    @Test
    fun tappingAnArmedModifierDisarmsIt() {
        assertEquals(none, none.tap(BarModifier.ALT).tap(BarModifier.ALT))
    }

    @Test
    fun aLongPressLocksUntilTappedAndSurvivesKeys() {
        val locked = none.longPress(BarModifier.CTRL)
        assertEquals(ModLevel.LOCKED, locked.level(BarModifier.CTRL))
        assertEquals(locked, locked.afterKey().afterKey())
        assertEquals(none, locked.tap(BarModifier.CTRL))
    }

    @Test
    fun aLongPressOnAnArmedModifierLocksIt() {
        assertEquals(ModLevel.LOCKED, none.tap(BarModifier.ALT).longPress(BarModifier.ALT).level(BarModifier.ALT))
    }

    @Test
    fun aLongPressOnALockedModifierKeepsItLocked() {
        assertEquals(ModLevel.LOCKED, none.longPress(BarModifier.ALT).longPress(BarModifier.ALT).level(BarModifier.ALT))
    }

    @Test
    fun ctrlAndAltAreIndependent() {
        val both = none.tap(BarModifier.CTRL).longPress(BarModifier.ALT)
        val next = both.afterKey()
        assertEquals(ModLevel.OFF, next.level(BarModifier.CTRL))
        assertEquals(ModLevel.LOCKED, next.level(BarModifier.ALT))
    }

    @Test
    fun noModifierSendsTheKeyAsIs() {
        listOf("$esc[A", "\t", "/", esc, "${esc}OP").forEach { assertEquals(it, none.encode(it)) }
    }

    @Test
    fun ctrlOnALetterIsTheControlCode() {
        assertEquals("\u0001", armed(ctrl = true).encode("a"))
        assertEquals("\u001a", armed(ctrl = true).encode("Z"))
    }

    @Test
    fun altOnACharacterPrefixesEscape() {
        assertEquals("${esc}b", armed(alt = true).encode("b"))
        assertEquals("$esc/", armed(alt = true).encode("/"))
    }

    @Test
    fun ctrlAndAltOnALetterIsEscThenTheControlCode() {
        assertEquals("${esc}\u0003", armed(ctrl = true, alt = true).encode("c"))
    }

    @Test
    fun ctrlOnASymbolWithNoControlCodeIsUnchanged() {
        assertEquals("|", armed(ctrl = true).encode("|"))
        assertEquals("~", armed(ctrl = true).encode("~"))
    }

    @Test
    fun arrowsCarryTheXtermModifierParameter() {
        assertEquals("$esc[1;5A", armed(ctrl = true).encode("$esc[A"))
        assertEquals("$esc[1;3D", armed(alt = true).encode("$esc[D"))
        assertEquals("$esc[1;7C", armed(ctrl = true, alt = true).encode("$esc[C"))
        assertEquals("$esc[1;5H", armed(ctrl = true).encode("$esc[H"))
        assertEquals("$esc[1;3F", armed(alt = true).encode("$esc[F"))
    }

    @Test
    fun tildeKeysCarryTheModifierAfterTheNumber() {
        assertEquals("$esc[5;5~", armed(ctrl = true).encode("$esc[5~"))
        assertEquals("$esc[3;3~", armed(alt = true).encode("$esc[3~"))
        assertEquals("$esc[15;5~", armed(ctrl = true).encode("$esc[15~"))
    }

    @Test
    fun functionKeysOneToFourUseTheCsiFormWhenModified() {
        assertEquals("$esc[1;5P", armed(ctrl = true).encode("${esc}OP"))
        assertEquals("$esc[1;3S", armed(alt = true).encode("${esc}OS"))
    }

    @Test
    fun altOnEscAndTabPrefixesEscape() {
        assertEquals("${esc}$esc", armed(alt = true).encode(esc))
        assertEquals("${esc}\t", armed(alt = true).encode("\t"))
        assertEquals("\t", armed(ctrl = true).encode("\t"))
    }

    @Test
    fun shiftTabKeepsItsFormUnderCtrlAndPrefixesEscapeUnderAlt() {
        assertEquals("$esc[Z", armed(ctrl = true).encode("$esc[Z"))
        assertEquals("${esc}$esc[Z", armed(alt = true).encode("$esc[Z"))
    }

    @Test
    fun lockedModifiersEncodeLikeArmedOnes() {
        assertEquals("$esc[1;5A", none.longPress(BarModifier.CTRL).encode("$esc[A"))
    }

    @Test
    fun ctrlBIsNeverSent() {
        assertEquals("", armed(ctrl = true).encode("b"))
        assertEquals("", armed(ctrl = true).encode("B"))
        assertEquals("", armed(ctrl = true, alt = true).encode("b"))
        assertEquals("\u0002".length, 1)
    }
}
