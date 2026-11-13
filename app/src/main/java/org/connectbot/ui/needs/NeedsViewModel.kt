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

package org.connectbot.ui.needs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import solutions.ormus.logos.home.NeedsPass
import solutions.ormus.logos.home.NeedsStore
import solutions.ormus.logos.home.StoreReply
import javax.inject.Inject

/** The one line the view reads after an answer that did not land. */
enum class NeedsNotice { CLOSED, REFUSED }

/** What the Unblock view shows: the pass, whether the store was read yet, and the last line. */
data class NeedsUi(
    val pass: NeedsPass = NeedsPass(),
    val read: Boolean = false,
    val notice: NeedsNotice? = null,
    /** Why the last call failed (a status or the kind of failure, never its content); null after one that worked. */
    val error: String? = null,
    /** An answer is on its way: the keys take no second tap. */
    val sending: Boolean = false,
    /** A new question just took the screen: its keys stay off for [NeedsViewModel.SETTLE_MS], so a late tap meant for the last one lands on nothing. */
    val settling: Boolean = false,
)

/**
 * Backs the Unblock view: reads the store's open asks while the view shows, and sends one
 * answer per tap. A 403 stops every call: the store refused this phone and asking again would
 * not change it.
 */
@HiltViewModel
class NeedsViewModel @Inject constructor(
    private val store: NeedsStore,
) : ViewModel() {
    private val _state = MutableStateFlow(NeedsUi())
    val state: StateFlow<NeedsUi> = _state.asStateFlow()

    private val _address = MutableStateFlow(store.address)

    /** The store's address from Settings; blank when none is set. */
    val address: StateFlow<String> = _address.asStateFlow()

    /** Save the store's address (blank clears it). */
    fun setAddress(value: String) {
        store.setAddress(value)
        _address.value = store.address
    }

    private val refused: Boolean
        get() = _state.value.notice == NeedsNotice.REFUSED

    /** Read the store now and every [PERIOD_MS], for as long as the caller stays: the view while it is visible. */
    suspend fun watch() {
        while (!refused && store.address.isNotEmpty()) {
            read()
            delay(PERIOD_MS)
        }
    }

    private suspend fun read() {
        when (val reply = store.asks()) {
            is StoreReply.Asks -> {
                val before = _state.value.pass.current
                _state.update { it.copy(pass = it.pass.read(reply.asks), read = true, error = null) }
                // A question that a read puts on the screen (the first one, or one after the pass ended) settles like any other.
                if (before == null && _state.value.pass.current != null) settle()
            }

            StoreReply.Refused -> _state.update { it.copy(notice = NeedsNotice.REFUSED) }

            is StoreReply.Failed -> _state.update { it.failed(reply.why) }

            else -> Unit
        }
    }

    // A failure is the latest word: the "already closed" line of an earlier answer gives way to it. A refusal stays.
    private fun NeedsUi.failed(why: String) = copy(error = why, notice = notice.takeIf { it == NeedsNotice.REFUSED })

    private var settleJob: Job? = null

    // The next question's keys stay off for a short time: a tap meant for the one before must not answer this one.
    private fun settle() {
        settleJob?.cancel()
        _state.update { it.copy(settling = true) }
        settleJob = viewModelScope.launch {
            delay(SETTLE_MS)
            _state.update { it.copy(settling = false) }
        }
    }

    /**
     * Answer the ask [askId] with [label], one call per tap; the next ask shows once it landed. A tap for
     * another ask than the one on screen, with a label it does not have, while a send runs or while the
     * keys settle, is dropped: it was meant for something that is gone.
     */
    fun choose(askId: String, label: String) {
        val now = _state.value
        val ask = now.pass.current ?: return
        if (now.sending || now.settling || refused) return
        if (ask.id != askId || ask.options.none { it.label == label }) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            val reply = store.answer(ask.id, choice = listOf(label))
            _state.update {
                // Reads never move the ask on screen, so it is still the one answered.
                when (reply) {
                    StoreReply.Answered -> it.copy(pass = it.pass.answer(), notice = null, error = null)
                    StoreReply.Closed -> it.copy(pass = it.pass.closed(), notice = NeedsNotice.CLOSED, error = null)
                    StoreReply.Refused -> it.copy(notice = NeedsNotice.REFUSED)
                    is StoreReply.Failed -> it.failed(reply.why)
                    is StoreReply.Asks -> it
                }.copy(sending = false)
            }
            if (reply == StoreReply.Answered || reply == StoreReply.Closed) settle()
        }
    }

    /** Later: the ask on screen goes to the end of the pass; nothing is sent. */
    fun later() {
        if (_state.value.sending || _state.value.settling) return
        _state.update { it.copy(pass = it.pass.putOff(), notice = null) }
        settle()
    }

    companion object {
        /** How long the view waits between two reads of the store while it is visible. */
        const val PERIOD_MS = 30_000L

        /** How long the keys of a newly shown question stay off, milliseconds. */
        const val SETTLE_MS = 600L
    }
}
