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

package org.connectbot.ui.wish

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.connectbot.R
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.ui.theme.OrmusCornerShape
import org.connectbot.usage.InboxKind
import org.connectbot.usage.InboxStore
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageClock
import org.connectbot.usage.UsageTracker
import javax.inject.Inject

/** Saves wishes and error reports to the wish list, off the main thread. */
@HiltViewModel
class WishViewModel @Inject constructor(
    private val inbox: InboxStore,
    private val tracker: UsageTracker,
    private val dispatchers: CoroutineDispatchers,
    private val clock: UsageClock,
) : ViewModel() {
    /** Save [text] as a wish, stamped with the screen and host type it came from. */
    fun saveWish(text: String, onSaved: () -> Unit = {}) {
        if (text.isBlank()) return
        val (screen, herdr) = tracker.context()
        viewModelScope.launch {
            val saved = withContext(dispatchers.io) { inbox.add(InboxKind.WISH, text, clock.now(), screen, herdr) }
            if (saved != null) {
                tracker.log(UsageActions.WISH_SAVE)
                onSaved()
            }
        }
    }

    /**
     * Queue a connection report (the text "Copy details" copies, built by
     * DiagnosticsReporter) as an error entry. It waits on the phone and uploads on
     * the next Sun connection.
     */
    fun saveError(report: suspend () -> String?, herdr: Boolean?, onSaved: () -> Unit = {}) {
        val (screen, _) = tracker.context()
        viewModelScope.launch {
            val text = report() ?: return@launch
            val saved = withContext(dispatchers.io) { inbox.add(InboxKind.ERROR, text, clock.now(), screen, herdr) }
            if (saved != null) onSaved()
        }
    }
}

/**
 * One text field for a wish. It takes focus and raises the keyboard at once, so the
 * IME's voice input is one tap away; the IME's Send action or Save stores it.
 */
@Composable
fun WishDialog(
    onDismiss: () -> Unit,
    viewModel: WishViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val savedMessage = stringResource(R.string.wish_saved)
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    fun save() {
        if (text.isBlank()) return
        viewModel.saveWish(text) { Toast.makeText(context, savedMessage, Toast.LENGTH_SHORT).show() }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.wish_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.wish_dialog_hint)) },
                minLines = 3,
                maxLines = 8,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { save() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .testTag("wish_text"),
            )
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = text.isNotBlank(), shape = OrmusCornerShape) {
                Text(stringResource(R.string.wish_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = OrmusCornerShape) { Text(stringResource(R.string.button_cancel)) }
        },
    )
}

/** Test tag of every "Send to wish list" button. */
const val SEND_TO_WISH_LIST_TAG = "send_to_wish_list"

/** "Send to wish list" beside "Copy details" on the disconnected overlay. */
@Composable
fun SendToWishListButton(
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier.testTag(SEND_TO_WISH_LIST_TAG), shape = OrmusCornerShape) {
        Text(stringResource(R.string.wish_send_error), color = color)
    }
}
