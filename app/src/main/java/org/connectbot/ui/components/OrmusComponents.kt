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

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape

/** How a [StatusDot] is drawn: the shape is the second signal beside color. */
enum class DotStyle {
    /** Solid: blocked, done, connected, error. */
    FILLED,

    /** Thick ring: working. */
    RING,

    /** Thin outline: idle, offline, closed. */
    HOLLOW,
}

/** Round state marker. Dots stay round even though the brand has no pills. */
@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    style: DotStyle = DotStyle.FILLED,
    size: Dp = 10.dp,
) {
    val base = modifier.size(size)
    val drawn = when (style) {
        DotStyle.FILLED -> base.background(color, CircleShape)
        DotStyle.RING -> base.border(2.5.dp, color, CircleShape)
        DotStyle.HOLLOW -> base.border(1.5.dp, color, CircleShape)
    }
    Box(modifier = drawn)
}

/**
 * Lab-log micro-label: uppercase JetBrains Mono with the brand's visual-only "/ "
 * prefix. Screen readers hear the words without the slash.
 */
@Composable
fun MonoLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    style: TextStyle = MaterialTheme.typography.labelSmall,
) {
    Text(
        text = "/ " + text.uppercase(),
        style = style,
        color = color,
        maxLines = 1,
        modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
    )
}

/** Compact state chip: a dot and a mono label on a hairline outline. */
@Composable
fun StatusChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    style: DotStyle = DotStyle.FILLED,
    labelColor: Color = color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .border(1.dp, Ormus.extras.hairline, OrmusCornerShape)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        StatusDot(color = color, style = style, size = 7.dp)
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = labelColor,
            maxLines = 1,
        )
    }
}

/**
 * The brand card: surface container, hairline border, a gold top hairline, the one
 * 3dp radius, and a 2dp lift while pressed on the reveal curve.
 *
 * @param accent top hairline color; pass the status color to make a card speak
 * (for example gold at full strength for a blocked agent)
 */
@Composable
fun OrmusCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color = Ormus.extras.cardAccent,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val lift by animateDpAsState(
        targetValue = if (pressed) Ormus.elevation.pressed else Ormus.elevation.flat,
        animationSpec = tween(Ormus.motion.STANDARD_MS, easing = Ormus.motion.easing),
        label = "cardLift",
    )
    val border = BorderStroke(1.dp, Ormus.extras.hairline)
    val body: @Composable () -> Unit = {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(accent),
            )
            content()
        }
    }
    val lifted = modifier.offset { IntOffset(0, -lift.roundToPx()) }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = lifted,
            shape = OrmusCornerShape,
            color = container,
            border = border,
            interactionSource = interaction,
            content = body,
        )
    } else {
        Surface(modifier = lifted, shape = OrmusCornerShape, color = container, border = border, content = body)
    }
}

/** Gold top hairline for panels docked over live content (prompts, sheets). */
@Composable
fun Modifier.ormusPanelTop(): Modifier {
    val accent = Ormus.extras.cardAccent
    return drawBehind {
        drawLine(accent, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
    }
}
