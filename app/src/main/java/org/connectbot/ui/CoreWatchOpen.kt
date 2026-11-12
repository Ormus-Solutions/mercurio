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

package org.connectbot.ui

import android.net.Uri
import org.connectbot.data.entity.Host
import solutions.ormus.logos.herd.CoreWatchTarget
import timber.log.Timber

/**
 * Whether a core notice's tap may open a watch. MainActivity takes ssh:// intents from any app, so the
 * host id in the extras is a claim: the watch opens only on the saved host the intent's own URI finds.
 */
internal object CoreWatchOpen {
    /**
     * The result of [openWatch] for [request], or null when the tap falls through to the host console:
     * [findHost] (the lookup the ssh:// path uses) finds no saved host for [uri], finds another one than
     * the request names, or [openWatch] gives nothing or throws.
     */
    suspend fun <T : Any> open(
        uri: Uri,
        request: MainActivity.CoreWatchRequest,
        findHost: suspend (Uri) -> Host?,
        openWatch: suspend (Long, CoreWatchTarget) -> T?,
    ): T? = runCatching {
        if (findHost(uri)?.id != request.hostId) return null
        openWatch(request.hostId, request.target)
    }.onFailure { Timber.w(it, "core watch did not open") }.getOrNull()
}
