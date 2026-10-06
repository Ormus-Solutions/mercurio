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
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Registration with the user's UnifiedPush distributor (the ntfy app, which keeps one
 * connection to a self-hosted server so Mercurio can sleep). No distributor installed
 * means no push: nothing registers and the Herd screen says how to get it.
 */
object PushRegistration {
    /** The ntfy app, preferred when several distributors are installed. */
    const val NTFY = "io.heckel.ntfy"

    /** Whether a distributor is installed. */
    fun hasDistributor(context: Context): Boolean = UnifiedPush.getDistributors(context).any { it != context.packageName }

    /**
     * Register (again) with the saved distributor, or save one first: ntfy when it is
     * there, else the first installed. The new endpoint arrives in [MercurioPushService].
     * Blocking (the connector keeps a small database); call off the main thread.
     */
    fun start(context: Context) {
        if (UnifiedPush.getSavedDistributor(context) == null) {
            val distributor = pick(UnifiedPush.getDistributors(context).filter { it != context.packageName }) ?: return
            UnifiedPush.saveDistributor(context, distributor)
        }
        UnifiedPush.register(context)
    }

    /** ntfy first, else the first of [distributors]; null when there are none. */
    fun pick(distributors: List<String>): String? = distributors.firstOrNull { it == NTFY } ?: distributors.firstOrNull()
}
