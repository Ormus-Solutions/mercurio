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

package org.connectbot.service

object DisconnectPolicy {
    /**
     * What a console does when its session ends for [reason]. [endsWithItsProgram] is a console whose
     * session runs one program and ends with it (a herdr-core attach, `exec hc attach`): a clean end
     * there is the program's own, so it keeps the program's last words on screen and never attaches
     * again by itself, which would loop on a terminal that is gone.
     */
    fun decide(
        reason: DisconnectReason,
        quickDisconnect: Boolean,
        stayConnected: Boolean,
        endsWithItsProgram: Boolean = false,
    ): DisconnectAction {
        if (reason == DisconnectReason.USER_REQUESTED) return DisconnectAction.CloseImmediately
        if (endsWithItsProgram && reason == DisconnectReason.REMOTE_EOF) return DisconnectAction.ShowReconnectOverlay
        if (quickDisconnect) return DisconnectAction.CloseImmediately
        // Never auto-reconnect on auth failures — looping would lock accounts
        if (reason == DisconnectReason.AUTH_FAIL) return DisconnectAction.ShowReconnectOverlay
        if (stayConnected) return DisconnectAction.AutoReconnect
        return DisconnectAction.ShowReconnectOverlay
    }
}
