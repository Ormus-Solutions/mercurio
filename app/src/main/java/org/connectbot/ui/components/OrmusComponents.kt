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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
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
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.connectbot.ui.theme.ConnectBotTheme
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusContainerShape
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.ui.theme.OrmusTokens

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
 * The brand card: surface container, hairline border, a gold top hairline, the
 * container radius, and a 2dp lift while pressed on the reveal curve.
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
            shape = OrmusContainerShape,
            color = container,
            border = border,
            interactionSource = interaction,
            content = body,
        )
    } else {
        Surface(modifier = lifted, shape = OrmusContainerShape, color = container, border = border, content = body)
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

/** Builder for the rows of an [OrmusGroup]. */
class OrmusGroupScope internal constructor() {
    internal val rows = mutableListOf<@Composable () -> Unit>()

    /** One row; the group draws an inset hairline between rows. */
    fun row(content: @Composable () -> Unit) {
        rows += content
    }
}

/**
 * A grouped section: a mono `/ SECTION` label over one container at the container
 * radius, with the rows split by inset hairlines. Use one group per kind of thing;
 * the rows keep their own click handling and padding.
 */
@Composable
fun OrmusGroup(
    label: String,
    modifier: Modifier = Modifier,
    rows: OrmusGroupScope.() -> Unit,
) {
    val scope = OrmusGroupScope().apply(rows)
    Column(modifier = modifier.fillMaxWidth()) {
        MonoLabel(text = label, modifier = Modifier.padding(start = Ormus.spacing.xs, bottom = Ormus.spacing.sm))
        if (scope.rows.isNotEmpty()) {
            Surface(
                shape = OrmusContainerShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, Ormus.extras.hairline),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    scope.rows.forEachIndexed { index, row ->
                        if (index > 0) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = Ormus.spacing.lg),
                                thickness = 1.dp,
                                color = Ormus.extras.hairline,
                            )
                        }
                        row()
                    }
                }
            }
        }
    }
}

/** Who wrote a message. Each author has its own container tone, shape and side. */
enum class BubbleAuthor {
    /** The person using the app: right side, the highest surface tone with a gold tint. */
    YOU,

    /** The agent's reply: left side, the card tone. */
    AGENT,

    /** A tool step: a quiet mono line, the lowest tone. */
    TOOL,

    /** The pinned ask that waits for an answer: the one gold container. */
    ASK,
}

/** Container, text, label and border colors for one author on a scheme. */
internal data class BubbleTone(
    val container: Color,
    val content: Color,
    val label: Color,
    val border: Color,
)

internal fun bubbleTone(author: BubbleAuthor, scheme: ColorScheme): BubbleTone = when (author) {
    BubbleAuthor.YOU -> BubbleTone(scheme.primary.copy(alpha = YOU_GOLD_TINT).compositeOver(scheme.surfaceContainerHighest), scheme.onSurface, scheme.onSurfaceVariant, Color.Transparent)
    BubbleAuthor.AGENT -> BubbleTone(scheme.surfaceContainer, scheme.onSurface, scheme.onSurfaceVariant, scheme.outlineVariant)
    BubbleAuthor.TOOL -> BubbleTone(scheme.surfaceContainerLowest, scheme.onSurfaceVariant, scheme.onSurfaceVariant, scheme.outlineVariant)
    BubbleAuthor.ASK -> BubbleTone(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.onPrimaryContainer, scheme.primary.copy(alpha = 0.7f))
}

/** How much gold the YOU bubble takes over the highest surface: a tint, so the ASK stays the one gold container by its gold border and label. */
private const val YOU_GOLD_TINT = 0.16f

/** The tail corner of YOU and AGENT bubbles, half the key radius. */
private val BubbleTail = (OrmusTokens.RADIUS_DP / 2f).dp

private fun bubbleShape(author: BubbleAuthor): RoundedCornerShape {
    val big = OrmusTokens.RADIUS_CONTAINER_DP.dp
    return when (author) {
        BubbleAuthor.YOU -> RoundedCornerShape(big, big, BubbleTail, big)
        BubbleAuthor.AGENT -> RoundedCornerShape(big, big, big, BubbleTail)
        BubbleAuthor.TOOL -> OrmusCornerShape
        BubbleAuthor.ASK -> OrmusContainerShape
    }
}

/**
 * A message container by author. YOU sits on the right, the others on the left; a
 * bubble takes at most 88 percent of the row and shrinks to its content.
 *
 * @param label optional mono author line above the content
 * @param accent an identity accent that colors the label (what the author is, never state);
 * ignored for [BubbleAuthor.ASK], the gold container, where accents miss 4.5:1 on some planets
 * @param bubbleModifier applied to the bubble itself, not the full-width row: a test tag, a width cap, a tap
 */
@Composable
fun OrmusBubble(
    author: BubbleAuthor,
    modifier: Modifier = Modifier,
    bubbleModifier: Modifier = Modifier,
    label: String? = null,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tone = bubbleTone(author, MaterialTheme.colorScheme)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (author == BubbleAuthor.YOU) Arrangement.End else Arrangement.Start,
    ) {
        if (author == BubbleAuthor.YOU) Spacer(Modifier.weight(0.12f))
        Surface(
            shape = bubbleShape(author),
            color = tone.container,
            contentColor = tone.content,
            border = if (tone.border == Color.Transparent) null else BorderStroke(1.dp, tone.border),
            modifier = (if (author == BubbleAuthor.TOOL) Modifier.fillMaxWidth() else Modifier.weight(0.88f, fill = false)).then(bubbleModifier),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = Ormus.spacing.md, vertical = Ormus.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Ormus.spacing.xs),
            ) {
                if (label != null) MonoLabel(text = label, color = accent.takeIf { author != BubbleAuthor.ASK } ?: tone.label)
                val style = if (author == BubbleAuthor.TOOL) MaterialTheme.typography.labelMedium else LocalTextStyle.current
                ProvideTextStyle(style) { content() }
            }
        }
        if (author != BubbleAuthor.YOU && author != BubbleAuthor.TOOL) Spacer(Modifier.weight(0.12f))
    }
}

@Preview(name = "OrmusGroup", showBackground = true, backgroundColor = 0xFF0E1830)
@Composable
private fun OrmusGroupPreview() {
    ConnectBotTheme {
        OrmusGroup(label = "Machines", modifier = Modifier.padding(16.dp)) {
            row { Text("alpha", modifier = Modifier.padding(16.dp)) }
            row { Text("beta", modifier = Modifier.padding(16.dp)) }
            row { Text("gamma", modifier = Modifier.padding(16.dp)) }
        }
    }
}

@Preview(name = "OrmusBubble by author", showBackground = true, backgroundColor = 0xFF0E1830)
@Composable
private fun OrmusBubblePreview() {
    ConnectBotTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OrmusBubble(BubbleAuthor.YOU) { Text("Run the tests and tell me what fails.") }
            OrmusBubble(BubbleAuthor.AGENT, label = "agent", accent = OrmusTokens.Accents.Teal) {
                Text("Two tests fail, both in the parser. I am looking at the first one.")
            }
            OrmusBubble(BubbleAuthor.TOOL) { Text("Bash  ./gradlew test", style = MaterialTheme.typography.labelMedium) }
            OrmusBubble(BubbleAuthor.ASK, label = "asks you") { Text("Allow the agent to edit parser.kt?") }
        }
    }
}
