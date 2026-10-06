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

import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageTracker
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import timber.log.Timber
import javax.inject.Inject

/**
 * What the distributor tells Mercurio: a new endpoint (kept in [PushEndpoints], which
 * hands it to each Herdr host on the next connect), a failed or dropped registration,
 * and push messages, which become "Needs you" notices ([HerdPushNotices]).
 */
@AndroidEntryPoint
class MercurioPushService : PushService() {
    @Inject
    internal lateinit var endpoints: PushEndpoints

    @Inject
    internal lateinit var usageTracker: UsageTracker

    @Inject
    internal lateinit var notices: HerdPushNotices

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        if (endpoints.update(endpoint.url)) {
            usageTracker.log(UsageActions.PUSH_REGISTERED)
        } else {
            Timber.w("Push endpoint refused: not a plain http(s) URL")
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        usageTracker.log(UsageActions.PUSH_RECEIVED)
        // The connector calls this off the main thread and unbinds (destroying the service)
        // as soon as it returns, so the notice is posted before returning, not launched.
        runBlocking { notices.post(this@MercurioPushService, message.content) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Timber.w("Push registration failed: %s", reason)
        usageTracker.log(UsageActions.PUSH_FAILED)
    }

    override fun onUnregistered(instance: String) {
        endpoints.update(null)
        usageTracker.log(UsageActions.PUSH_UNREGISTERED)
    }
}
