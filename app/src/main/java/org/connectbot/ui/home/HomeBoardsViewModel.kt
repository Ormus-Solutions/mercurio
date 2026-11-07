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

package org.connectbot.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import solutions.ormus.logos.home.HomeFeed
import solutions.ormus.logos.home.HomeState
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** Backs the home's boards and the "Home feed" setting: the feed's state, and when it is read. */
@HiltViewModel
class HomeBoardsViewModel @Inject constructor(
    private val feed: HomeFeed,
) : ViewModel() {
    /** The feed URL, the boards last read, their time and the last failure. */
    val state: StateFlow<HomeState> = feed.state

    private val reading = AtomicBoolean(false)

    init {
        viewModelScope.launch { feed.load() }
    }

    /** Save the feed URL (blank clears it) and read it at once. */
    fun setUrl(value: String) {
        viewModelScope.launch {
            feed.setUrl(value)
            read()
        }
    }

    /** Read the feed now and again every [PERIOD_MS], for as long as the caller stays: the home while it shows. */
    suspend fun watch() {
        while (true) {
            read()
            delay(PERIOD_MS)
        }
    }

    /** One read at a time: a read under way sees a URL that changed meanwhile and reads it itself. */
    private suspend fun read() {
        if (!reading.compareAndSet(false, true)) return
        try {
            feed.refresh()
        } finally {
            reading.set(false)
        }
    }

    companion object {
        /** How long the home waits between two reads while it shows. */
        const val PERIOD_MS = 60_000L
    }
}
