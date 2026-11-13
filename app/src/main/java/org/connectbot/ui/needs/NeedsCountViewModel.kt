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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import solutions.ormus.logos.home.NeedsStore
import solutions.ormus.logos.home.StoreReply
import javax.inject.Inject

/**
 * The count behind the Inbox's Unblock key: how many open questions of the questions store the view walks
 * (one that takes several answers is left to the page, so it is not counted).
 * Null (no key) with no store address, before the first read, and once the store refused this
 * phone; a read that fails keeps the last count. Read while the Inbox shows.
 */
@HiltViewModel
class NeedsCountViewModel @Inject constructor(
    private val store: NeedsStore,
) : ViewModel() {
    private val _count = MutableStateFlow<Int?>(null)

    /** The open questions of the store, or null when the key is hidden. */
    val count: StateFlow<Int?> = _count.asStateFlow()

    /** Read the store now and every [PERIOD_MS], for as long as the caller stays: the Inbox while it is visible. */
    suspend fun watch() {
        while (store.address.isNotEmpty()) {
            when (val reply = store.asks()) {
                is StoreReply.Asks -> _count.value = reply.asks.count { it.open && !it.multi }

                StoreReply.Refused -> {
                    _count.value = null
                    return
                }

                else -> Unit
            }
            delay(PERIOD_MS)
        }
        _count.value = null
    }

    companion object {
        /** How long the Inbox waits between two reads of the store while it is visible, milliseconds. */
        const val PERIOD_MS = 30_000L
    }
}
