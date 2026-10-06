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

package org.connectbot.ui.screens.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.usage.InboxEntry
import org.connectbot.usage.InboxStore
import org.connectbot.usage.SunSync
import org.connectbot.usage.UploadResult
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageClock
import org.connectbot.usage.UsageSettings
import org.connectbot.usage.UsageStore
import org.connectbot.usage.UsageSummary
import org.connectbot.usage.UsageTracker
import javax.inject.Inject

data class UsageSettingsUiState(
    val record: Boolean = UsageSettings.RECORD_DEFAULT,
    val sunHosts: String = UsageSettings.SUN_HOSTS_DEFAULT,
    val sunHostKey: String = UsageSettings.SUN_HOST_KEY_DEFAULT,
    val lastUploadAt: Long = 0,
    val pendingInbox: Int = 0,
    val uploading: Boolean = false,
    val lastResult: UploadResult? = null,
    /** Summary text while its dialog is open. */
    val summary: String? = null,
)

/** Backs the "Usage and wish list" section of Settings. */
@HiltViewModel
class UsageSettingsViewModel @Inject constructor(
    private val prefs: SharedPreferences,
    private val store: UsageStore,
    private val inbox: InboxStore,
    private val tracker: UsageTracker,
    private val sunSync: SunSync,
    private val dispatchers: CoroutineDispatchers,
    private val clock: UsageClock,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        UsageSettingsUiState(
            record = prefs.getBoolean(UsageSettings.KEY_RECORD, UsageSettings.RECORD_DEFAULT),
            sunHosts = prefs.getString(UsageSettings.KEY_SUN_HOSTS, null) ?: UsageSettings.SUN_HOSTS_DEFAULT,
            sunHostKey = prefs.getString(UsageSettings.KEY_SUN_HOST_KEY, null) ?: UsageSettings.SUN_HOST_KEY_DEFAULT,
        ),
    )
    val uiState: StateFlow<UsageSettingsUiState> = _uiState.asStateFlow()

    /** Wishes and error reports, newest first. */
    val wishes: StateFlow<List<InboxEntry>> = inbox.entries

    init {
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            val (last, pending) = withContext(dispatchers.io) {
                inbox.all()
                store.lastUploadAt() to inbox.pendingCount()
            }
            _uiState.update { it.copy(lastUploadAt = last, pendingInbox = pending) }
        }
    }

    fun setRecord(value: Boolean) {
        prefs.edit { putBoolean(UsageSettings.KEY_RECORD, value) }
        _uiState.update { it.copy(record = value) }
    }

    fun setSunHosts(value: String) {
        val clean = value.trim().ifEmpty { UsageSettings.SUN_HOSTS_DEFAULT }
        prefs.edit { putString(UsageSettings.KEY_SUN_HOSTS, clean) }
        _uiState.update { it.copy(sunHosts = clean) }
    }

    fun setSunHostKey(value: String) {
        val clean = value.trim().ifEmpty { UsageSettings.SUN_HOST_KEY_DEFAULT }
        prefs.edit { putString(UsageSettings.KEY_SUN_HOST_KEY, clean) }
        _uiState.update { it.copy(sunHostKey = clean) }
    }

    fun showSummary() {
        tracker.log(UsageActions.SETTINGS_USAGE_SUMMARY)
        viewModelScope.launch {
            val text = withContext(dispatchers.io) { UsageSummary.build(store.snapshot()) }
            _uiState.update { it.copy(summary = text) }
        }
    }

    fun dismissSummary() {
        _uiState.update { it.copy(summary = null) }
    }

    fun clearUsage() {
        viewModelScope.launch {
            withContext(dispatchers.io) { store.clear() }
            tracker.log(UsageActions.SETTINGS_USAGE_CLEAR)
        }
    }

    fun uploadNow() {
        tracker.log(UsageActions.SETTINGS_UPLOAD_NOW)
        _uiState.update { it.copy(uploading = true, lastResult = null) }
        viewModelScope.launch {
            val result = sunSync.uploadNow()
            _uiState.update { it.copy(uploading = false, lastResult = result) }
            refresh()
        }
    }

    fun openWishes() = tracker.log(UsageActions.SETTINGS_WISHES)

    fun editWish(id: String, text: String) = inboxChange(UsageActions.WISH_EDIT) { inbox.edit(id, text, clock.now()) }

    fun setWishDone(id: String, done: Boolean) = inboxChange(UsageActions.WISH_DONE) { inbox.setDone(id, done, clock.now()) }

    fun deleteWish(id: String) = inboxChange(UsageActions.WISH_DELETE) { inbox.delete(id, clock.now()) }

    private fun inboxChange(actionId: String, change: () -> Unit) {
        tracker.log(actionId)
        viewModelScope.launch {
            withContext(dispatchers.io) { change() }
            refresh()
        }
    }
}
