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

package solutions.ormus.logos.home

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The sessions feed as the home reads it: every workboard, the machine and Herdr session it
 * lives on, how its agents stand, and its workspaces. The feed carries more (each workspace's
 * chats, the agents' rows, the moves); none of that is read, and none of it is kept.
 */
@Serializable
data class HomeBoards(
    /** Epoch seconds the feed was written; 0 when it does not say. */
    val generated: Long = 0L,
    val boards: List<HomeBoard> = emptyList(),
    /** Each machine's names on the tailnet (short name, DNS name, address), by the feed's alias for it. */
    val hosts: Map<String, List<String>> = emptyMap(),
    /** An SSH link per machine alias, for a machine the phone has no saved host for. */
    val links: Map<String, String> = emptyMap(),
) {
    /** How many agents, over every board, wait on the operator. */
    val blocked: Int
        get() = boards.sumOf { it.agents.blocked }

    companion object {
        // A null where the feed has a default (a board with no count) takes the default, not the whole feed down.
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        /**
         * Read a feed document.
         *
         * @throws IllegalArgumentException when [text] is not JSON or not the feed's shape. It
         * carries the reason and no cause: the parser's own message quotes the input.
         */
        fun parse(text: String): HomeBoards = try {
            json.decodeFromString<HomeBoards>(text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("not a sessions feed: " + e.message.orEmpty().substringBefore("\nJSON input:").take(MESSAGE_CHARS))
        }

        /** [boards] as JSON that [parse] reads back: what the phone keeps. */
        fun encode(boards: HomeBoards): String = json.encodeToString(boards)

        private const val MESSAGE_CHARS = 160
    }
}

/** One workboard: a Herdr session on a machine, as the feed names them. */
@Serializable
data class HomeBoard(
    val name: String,
    /** The feed's alias of the machine (a key of [HomeBoards.hosts]); null when the feed does not know. */
    val machine: String? = null,
    /** The Herdr session the board is; null when the feed does not know. */
    val session: String? = null,
    val agents: HomeAgents = HomeAgents(),
    /** How many of the board's chats wait on the operator. */
    val waiting: Int = 0,
    /** The board's Herdr workspaces, which the feed lists as its projects. */
    @SerialName("projects") val workspaces: List<HomeWorkspace> = emptyList(),
)

/** One workspace of a workboard; its machine and session are the board's unless the feed says otherwise. */
@Serializable
data class HomeWorkspace(
    val name: String,
    val machine: String? = null,
    val session: String? = null,
    val agents: HomeAgents = HomeAgents(),
)

/** How the agents of a board or a workspace stand. */
@Serializable
data class HomeAgents(
    val blocked: Int = 0,
    val working: Int = 0,
    val done: Int = 0,
    val idle: Int = 0,
) {
    val total: Int
        get() = blocked + working + done + idle
}
