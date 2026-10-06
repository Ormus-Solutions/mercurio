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

package solutions.ormus.logos.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug builds only: hand [HerdPushNotices] a push as if the distributor had delivered
 * it, to try the alert without a phone, server or ntfy app. Only the shell can send it
 * (the receiver requires DUMP):
 *
 * ```
 * adb shell am broadcast -a solutions.ormus.logos.debug.TEST_PUSH \
 *   -n solutions.ormus.logos.debug/solutions.ormus.logos.push.DebugPushReceiver --es json '<alert>'
 * ```
 */
@AndroidEntryPoint
class DebugPushReceiver : BroadcastReceiver() {
    @Inject
    internal lateinit var notices: HerdPushNotices

    override fun onReceive(context: Context, intent: Intent) {
        val json = intent.getStringExtra("json") ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                notices.post(context, json.toByteArray())
            } finally {
                pending.finish()
            }
        }
    }
}
