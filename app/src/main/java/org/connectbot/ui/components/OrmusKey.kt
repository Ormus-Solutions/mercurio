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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.OrmusMono

// The brand's key: one 3px corner, a hairline edge, a JetBrains Mono label. Gold is
// the only accent (docs: ormus-brand STYLE.md). The compose bar and the Herdr panel
// both draw their keys from here, so they read as one surface.

internal val KEY_HEIGHT = 46.dp
internal val KEY_SHAPE = OrmusCornerShape
internal val KEY_PADDING = PaddingValues(horizontal = 2.dp, vertical = 0.dp)

/** Padding for a key whose label is a word or two rather than a glyph. */
internal val WORD_KEY_PADDING = PaddingValues(horizontal = 12.dp, vertical = 0.dp)

/** How a key reads: plain keys sit quietly, accent keys carry a gold tint, ON is a gold fill. */
internal enum class KeyTone { PLAIN, ACCENT, ON }

@Composable
internal fun keyColors(tone: KeyTone): ButtonColors = when (tone) {
    KeyTone.PLAIN -> ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
    )

    KeyTone.ACCENT -> ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )

    KeyTone.ON -> ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    )
}

@Composable
internal fun keyBorder(tone: KeyTone): BorderStroke = BorderStroke(
    1.dp,
    if (tone == KeyTone.PLAIN) Ormus.extras.hairline else Ormus.extras.cardAccent,
)

/** Key labels read as keycaps: JetBrains Mono, medium. */
@Composable
internal fun KeyLabel(label: String, symbolSize: TextUnit = TextUnit.Unspecified) {
    Text(label, maxLines = 1, fontSize = symbolSize, fontWeight = FontWeight.Medium, fontFamily = OrmusMono)
}

/**
 * A brand key that runs [onClick]. [description] names it when its label alone would not
 * (an arrow glyph). [onLongClick], when set, runs on a long press.
 */
@Composable
internal fun OrmusKey(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: KeyTone = KeyTone.PLAIN,
    enabled: Boolean = true,
    symbolSize: TextUnit = TextUnit.Unspecified,
    description: String? = null,
    contentPadding: PaddingValues = KEY_PADDING,
    onLongClick: (() -> Unit)? = null,
) {
    if (onLongClick != null) {
        LongPressKey(label, onClick, onLongClick, tone, enabled, symbolSize, description, contentPadding, modifier)
        return
    }
    Button(
        onClick = onClick,
        modifier = modifier
            .height(KEY_HEIGHT)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        enabled = enabled,
        shape = KEY_SHAPE,
        colors = keyColors(tone),
        border = keyBorder(tone),
        contentPadding = contentPadding,
    ) { KeyLabel(label, symbolSize) }
}

// Button takes no long press, so this draws the same key (shape, colors, hairline, size) on
// a Surface that takes both.
@Composable
private fun LongPressKey(
    label: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    tone: KeyTone,
    enabled: Boolean,
    symbolSize: TextUnit,
    description: String?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val colors = keyColors(tone)
    Surface(
        modifier = modifier
            .height(KEY_HEIGHT)
            .clip(KEY_SHAPE)
            // An off key still takes its long press (its menu can change or hide it); only the tap is off.
            .combinedClickable(role = Role.Button, onLongClick = onLongClick, onClick = { if (enabled) onClick() })
            .semantics {
                if (!enabled) disabled()
                if (description != null) contentDescription = description
            },
        shape = KEY_SHAPE,
        color = if (enabled) colors.containerColor else colors.disabledContainerColor,
        border = keyBorder(tone),
    ) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) colors.contentColor else colors.disabledContentColor) {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) {
                Row(
                    modifier = Modifier
                        .defaultMinSize(minWidth = ButtonDefaults.MinWidth, minHeight = ButtonDefaults.MinHeight)
                        .padding(contentPadding),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) { KeyLabel(label, symbolSize) }
            }
        }
    }
}
