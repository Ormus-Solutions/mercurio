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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val ARROW_UP = "\u001b[A"

/**
 * A key of the paged bar that repeats while held. It sends on release (a tap), or, held past
 * [REPEAT_START_MS], on a timer: a swipe that starts on it turns the page and sends nothing,
 * which a send-on-press key could not do. [resolve] turns the touch into the key's bytes once per
 * gesture (armed modifiers are read and spent there), and a hold repeats those same bytes.
 * [activate] is the one action a screen reader gets.
 */
@Composable
internal fun HoldKey(
    resolve: (at: Offset, size: IntSize) -> String,
    activate: () -> String,
    send: (String) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = keyColors(KeyTone.PLAIN)
    val resolveNow by rememberUpdatedState(resolve)
    val activateNow by rememberUpdatedState(activate)
    val sendNow by rememberUpdatedState(send)
    var pressed by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier
            .height(KEY_HEIGHT)
            .clip(KEY_SHAPE)
            .pointerInput(Unit) {
                var held = false
                detectTapGestures(
                    onPress = { at ->
                        held = false
                        pressed = true
                        try {
                            coroutineScope {
                                val repeating = launch {
                                    delay(REPEAT_START_MS)
                                    val bytes = resolveNow(at, size)
                                    held = true
                                    while (isActive) {
                                        sendNow(bytes)
                                        delay(REPEAT_EVERY_MS)
                                    }
                                }
                                tryAwaitRelease()
                                repeating.cancel()
                            }
                        } finally {
                            pressed = false
                        }
                    },
                    onTap = { at -> if (!held) sendNow(resolveNow(at, size)) },
                )
            }
            .semantics {
                contentDescription = description
                role = Role.Button
                onClick { sendNow(activateNow()).let { true } }
            },
        shape = KEY_SHAPE,
        color = if (pressed) colors.contentColor.copy(alpha = PRESSED_ALPHA).compositeOver(colors.containerColor) else colors.containerColor,
        contentColor = colors.contentColor,
        border = keyBorder(KeyTone.PLAIN),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

private const val PRESSED_ALPHA = 0.16f

/**
 * The Herdr app's one "arrows" key: four arrows in the space of a key, the tap position
 * choosing which ([ArrowPad]). A hold repeats. A screen reader gets one action, Up.
 */
@Composable
internal fun ArrowsKey(
    takeBytes: (String) -> String,
    send: (String) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val tint = keyColors(KeyTone.PLAIN).contentColor
    HoldKey(
        resolve = { at, size -> takeBytes(ArrowPad.sequenceAt(at.x, at.y, size.width.toFloat(), size.height.toFloat())) },
        activate = { takeBytes(ARROW_UP) },
        send = send,
        description = description,
        modifier = modifier,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val reach = size.height / 2
            val wing = reach / 3
            fun arrow(dx: Float, dy: Float) {
                val tip = Offset(c.x + dx * reach, c.y + dy * reach)
                val base = Offset(c.x + dx * (reach - wing), c.y + dy * (reach - wing))
                val side = Offset(-dy * wing, dx * wing)
                drawPath(
                    Path().apply {
                        moveTo(tip.x, tip.y)
                        lineTo(base.x + side.x, base.y + side.y)
                        lineTo(base.x - side.x, base.y - side.y)
                        close()
                    },
                    tint,
                )
            }
            arrow(0f, -1f)
            arrow(0f, 1f)
            arrow(-1f, 0f)
            arrow(1f, 0f)
        }
    }
}
