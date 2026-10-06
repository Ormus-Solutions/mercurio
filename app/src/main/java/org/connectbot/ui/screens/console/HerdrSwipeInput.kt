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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.connectbot.R
import org.connectbot.service.TerminalBridge
import org.connectbot.ui.components.MonoLabel
import org.connectbot.ui.theme.Ormus
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.usage.UsageActions
import solutions.ormus.logos.herd.HerdrAction
import solutions.ormus.logos.herd.HerdrKeys
import solutions.ormus.logos.herd.HerdrSwipe
import solutions.ormus.logos.herd.HerdrSwipes

// How long the hint stays after a swipe fires; a reason the swipe sent nothing stays longer, to be read.
private const val HINT_MS = 900L
private const val PROBLEM_HINT_MS = 3_000L

/**
 * Herdr swipes on the terminal ([HerdrSwipes]). The touch is watched on the Initial pass,
 * ahead of termlib, and taken only once [HerdrSwipes.claim] says swipe: from the next event
 * on it is consumed, so termlib's scroll stops where it was and its tap and long press
 * never fire (termlib reads consumed moves as no movement). Taps, long presses, one-finger
 * scrolls and pinches are never claimed and reach termlib untouched. [enabled] is read as
 * each touch starts and while it waits: false off Herdr, and while a selection is up so its
 * handles and drags stay termlib's.
 */
internal fun Modifier.herdrSwipes(enabled: () -> Boolean, onSwipe: (HerdrSwipe) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!enabled()) return@awaitEachGesture
        val starts = mutableMapOf(down.id to down.position)
        val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var pointers = 1
        var startSpan: Float? = null
        var claimed = false
        var travel = Offset.Zero
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.firstOrNull { it.id == down.id }?.let { velocity.addPointerInputChange(it) }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            // Each finger's travel from where it landed, averaged: two fingers moving together.
            pressed.forEach { starts.getOrPut(it.id) { it.position } }
            travel = pressed.fold(Offset.Zero) { sum, it -> sum + (it.position - starts.getValue(it.id)) } / pressed.size.toFloat()
            if (claimed) {
                event.changes.forEach { it.consume() }
                continue
            }
            pointers = maxOf(pointers, pressed.size)
            val spanChange = if (pressed.size == 2) {
                val span = span(pressed)
                span - (startSpan ?: span.also { startSpan = it })
            } else {
                0f
            }
            // The claiming event itself goes on to termlib, so its scroll or zoom has already
            // started (past touch slop) and the touch can never end as its tap.
            when (HerdrSwipes.claim(pointers, travel.x.toDp().value, travel.y.toDp().value, spanChange.toDp().value)) {
                HerdrSwipes.Claim.CLAIM -> claimed = true
                HerdrSwipes.Claim.RELEASE -> return@awaitEachGesture
                HerdrSwipes.Claim.WAIT -> if (!enabled()) return@awaitEachGesture // a long press began a selection
            }
        }
        if (!claimed) return@awaitEachGesture
        val v = velocity.calculateVelocity()
        HerdrSwipes.classify(pointers, travel.x.toDp().value, travel.y.toDp().value, v.x.toDp().value, v.y.toDp().value)
            ?.let(onSwipe)
    }
}

private fun span(pressed: List<PointerInputChange>): Float = (pressed[0].position - pressed[1].position).getDistance()

/**
 * Run [swipe] on [bridge]'s Herdr. The tab keys are the host's bindings (HerdSession.keys);
 * without a Herdr side (telnet) they are Herdr's defaults, as the compose bar's ← and → send
 * them. Returns why nothing was sent when the host binds the tab key to nothing Mercurio can
 * send; null otherwise.
 */
internal fun runHerdrSwipe(bridge: TerminalBridge, swipe: HerdrSwipe): String? {
    val herd = bridge.herd
    val action = HerdrSwipes.action(swipe, herd?.snapshot?.value) ?: return null
    if (action is HerdrAction.BoundKey) {
        val keys = herd?.keys?.value ?: HerdrKeys.DEFAULT
        keys.problem(action.key)?.let { return it }
        if (herd == null) {
            keys.bytes(action.key)?.let { bridge.injectString(it) }
            return null
        }
    }
    herd?.perform(action)
    return null
}

/** Usage-log id for each swipe. */
internal fun usageIdFor(swipe: HerdrSwipe): String = when (swipe) {
    HerdrSwipe.PREV_TAB -> UsageActions.SWIPE_PREV_TAB
    HerdrSwipe.NEXT_TAB -> UsageActions.SWIPE_NEXT_TAB
    HerdrSwipe.PANE_LEFT -> UsageActions.SWIPE_PANE_LEFT
    HerdrSwipe.PANE_RIGHT -> UsageActions.SWIPE_PANE_RIGHT
    HerdrSwipe.PREV_WORKSPACE -> UsageActions.SWIPE_PREV_WORKSPACE
    HerdrSwipe.NEXT_WORKSPACE -> UsageActions.SWIPE_NEXT_WORKSPACE
}

/**
 * A brief "/ NEXT TAB" over the terminal after a swipe fires. [swipe] is the last one fired;
 * [fired] counts them, so a repeat shows the hint again. [problem], when set, is why that
 * swipe sent nothing, shown instead.
 */
@Composable
internal fun HerdrSwipeHint(swipe: HerdrSwipe?, fired: Int, modifier: Modifier = Modifier, problem: String? = null) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(fired) {
        if (fired == 0) return@LaunchedEffect
        visible = true
        delay(if (problem != null) PROBLEM_HINT_MS else HINT_MS)
        visible = false
    }
    val label = when (swipe ?: return) {
        HerdrSwipe.PREV_TAB -> R.string.palette_key_prev_tab
        HerdrSwipe.NEXT_TAB -> R.string.palette_key_next_tab
        HerdrSwipe.PANE_LEFT -> R.string.swipe_hint_pane_left
        HerdrSwipe.PANE_RIGHT -> R.string.swipe_hint_pane_right
        HerdrSwipe.PREV_WORKSPACE -> R.string.swipe_hint_prev_workspace
        HerdrSwipe.NEXT_WORKSPACE -> R.string.swipe_hint_next_workspace
    }
    val box = Modifier
        .background(Ormus.extras.panelOverLive, OrmusCornerShape)
        .border(1.dp, Ormus.extras.hairline, OrmusCornerShape)
        .padding(horizontal = 10.dp, vertical = 6.dp)
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        if (problem != null) {
            Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = box)
        } else {
            MonoLabel(text = stringResource(label), color = MaterialTheme.colorScheme.primary, modifier = box)
        }
    }
}
