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

import android.content.Context
import org.connectbot.data.HostRepository
import org.connectbot.service.ConnectionNotifier
import org.connectbot.usage.UsageActions
import org.connectbot.usage.UsageTracker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a host's push into the notification the live Herdr monitor would post: same
 * channel, same tag (host id and pane id), Approve and Deny where [solutions.ormus.logos.herd.HerdApproval]
 * allows. A notice already showing for that pane is left alone, so the monitor and the
 * push do not alert twice.
 */
@Singleton
class HerdPushNotices @Inject constructor(
    private val hostRepository: HostRepository,
    private val notifier: ConnectionNotifier,
    private val usageTracker: UsageTracker,
) {
    /** Post [content]'s notice; false (and logged) when it is unusable or from an unknown host. */
    suspend fun post(context: Context, content: ByteArray): Boolean {
        val alert = HerdPushAlert.parse(content)
        val notice = alert?.notice()
        val host = alert?.let { HerdPushAlert.matchHost(it.host, hostRepository.getSshHosts()) }
        if (notice == null || host == null) {
            usageTracker.log(UsageActions.PUSH_IGNORED)
            return false
        }
        if (!notifier.isHerdNoticeShowing(context, host.id, notice)) notifier.showHerdNotification(context, host, notice)
        return true
    }
}
