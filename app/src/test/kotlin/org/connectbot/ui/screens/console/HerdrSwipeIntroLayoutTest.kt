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

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.connectbot.ui.theme.ConnectBotTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The one-time swipe hint at the width of a phone (360 dp), with the real text measure: the whole sentence, on at most two
 * lines, and its "Got it" key, on the screen, in English and in Spanish, at font scale 1.0 and 1.3. The first version had one
 * line and no ellipsis, so a sentence of 37 characters squeezed the key to 28 dp and one of 42 to nothing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xhdpi")
class HerdrSwipeIntroLayoutTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(fontScale: Float) {
        rule.setContent {
            ConnectBotTheme {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                    Box(Modifier.fillMaxWidth()) { HerdrSwipeIntro(onDismiss = {}, modifier = Modifier.align(Alignment.TopCenter)) }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun assertWhole(sentence: String) {
        val dn = rule.density.density
        val hint = rule.onNodeWithTag(HERDR_SWIPE_INTRO_TAG).fetchSemanticsNode().boundsInRoot
        val key = rule.onNodeWithText("Got it").fetchSemanticsNode().boundsInRoot
        val label = rule.onNode(hasContentDescription(sentence)).fetchSemanticsNode()
        val out = mutableListOf<TextLayoutResult>()
        label.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(out)
        val layout = out.first()
        val info = "hint ${hint.left / dn}..${hint.right / dn} dp, key ${key.left / dn}..${key.right / dn} dp, ${layout.lineCount} lines"
        assertTrue("the hint reaches the right edge: $info", hint.right / dn <= 360f && hint.left >= 0f)
        assertTrue("the key keeps its width: $info", key.width / dn >= 48f && key.right / dn <= 360f)
        assertTrue("at most two lines: $info", layout.lineCount <= 2)
        assertFalse("the text is whole: $info", layout.hasVisualOverflow || (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
    }

    @Test
    fun theHint_isWhole_andItsKeyFits_at360dp() {
        show(1f)
        assertWhole("←→ tabs. 2 fingers: ←→ panes, ↑↓ spaces")
    }

    @Test
    fun theHint_isWhole_andItsKeyFits_atFont13() {
        show(1.3f)
        assertWhole("←→ tabs. 2 fingers: ←→ panes, ↑↓ spaces")
    }

    @Config(qualifiers = "+es")
    @Test
    fun inSpanish_theHint_isWhole_andItsKeyFits_at360dp() {
        show(1f)
        assertWhole("←→ pestañas. 2 dedos: ←→ paneles, ↑↓ espacios")
    }

    @Config(qualifiers = "+es")
    @Test
    fun inSpanish_theHint_isWhole_andItsKeyFits_atFont13() {
        show(1.3f)
        assertWhole("←→ pestañas. 2 dedos: ←→ paneles, ↑↓ espacios")
    }
}
