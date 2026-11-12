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

import android.content.Intent
import solutions.ormus.logos.herd.CoreNotices
import solutions.ormus.logos.herd.HerdAnswer
import solutions.ormus.logos.herd.HerdrCommands

/**
 * What an Approve or Deny tap on a "Needs you" notification carries: which pane, which answer, and
 * the marks of the prompt the notice showed, so the answer can refuse a prompt it did not show.
 *
 * [seq] is the Herdr `state_change_seq` of a plain Herdr agent raised from a snapshot; [prompt] is the hash
 * of the prompt the notice showed ([CoreNotices.fingerprint]); [turn] is a core agent's turn. A mark the
 * notice did not have (a push has no seq; an earlier build had none) is null. [tag] and [hostName] let the answer replace the notice when the host row is gone.
 */
data class HerdAnswerRequest(
    val hostId: Long,
    val paneId: String,
    val session: String,
    val agentKind: String?,
    val answer: HerdAnswer,
    val turn: Int?,
    val prompt: Int?,
    val seq: Long?,
    val tag: String?,
    val hostName: String?,
) {
    val isCore: Boolean get() = CoreNotices.isCore(session)

    /** A plain Herdr request with no seq and no prompt hash (a notice from an earlier build): nothing to hold the answer to. */
    val unchecked: Boolean get() = !isCore && seq == null && prompt == null

    companion object {
        /** The request in [intent], or null when it names no pane or no known answer. */
        fun from(intent: Intent): HerdAnswerRequest? {
            val paneId = intent.getStringExtra(ConnectionNotifier.EXTRA_HERD_PANE) ?: return null
            val answer = HerdAnswer.entries.firstOrNull { it.name == intent.getStringExtra(ConnectionNotifier.EXTRA_HERD_ANSWER) } ?: return null
            return HerdAnswerRequest(
                hostId = intent.getLongExtra(ConnectionNotifier.EXTRA_HERD_HOST, 0L),
                paneId = paneId,
                session = intent.getStringExtra(ConnectionNotifier.EXTRA_HERD_SESSION) ?: HerdrCommands.DEFAULT_SESSION,
                agentKind = intent.getStringExtra(ConnectionNotifier.EXTRA_HERD_AGENT),
                answer = answer,
                turn = if (intent.hasExtra(ConnectionNotifier.EXTRA_HERD_TURN)) intent.getIntExtra(ConnectionNotifier.EXTRA_HERD_TURN, 0) else null,
                prompt = if (intent.hasExtra(ConnectionNotifier.EXTRA_HERD_PROMPT)) intent.getIntExtra(ConnectionNotifier.EXTRA_HERD_PROMPT, 0) else null,
                seq = if (intent.hasExtra(ConnectionNotifier.EXTRA_HERD_SEQ)) intent.getLongExtra(ConnectionNotifier.EXTRA_HERD_SEQ, 0L) else null,
                tag = intent.getStringExtra(ConnectionNotifier.EXTRA_HERD_TAG),
                hostName = intent.getStringExtra(ConnectionNotifier.EXTRA_HERD_HOST_NAME),
            )
        }
    }
}
