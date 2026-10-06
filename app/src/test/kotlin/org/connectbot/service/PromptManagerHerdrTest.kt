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

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import solutions.ormus.logos.herd.HerdrAgentStatus
import solutions.ormus.logos.herd.WorkspaceChoice
import solutions.ormus.logos.herd.WorkspacePick

/** The connect picker's prompt: an answer comes back as the pick, a cancelled prompt as Back. */
class PromptManagerHerdrTest {
    private val choices = listOf(
        WorkspaceChoice("w1", 1, "tmp", focused = true, state = HerdrAgentStatus.IDLE),
        WorkspaceChoice("w2", 2, "beta", focused = false, state = HerdrAgentStatus.BLOCKED),
    )

    @Test
    fun answerReturnsThePick() = runTest {
        val prompts = PromptManager()
        val pick = async { prompts.requestHerdrWorkspace(choices) }
        runCurrent()
        assertEquals(PromptRequest.HerdrWorkspacePrompt(choices), prompts.promptState.value)
        prompts.respond(PromptResponse.HerdrWorkspaceResponse(WorkspacePick.Focus("w2")))
        assertEquals(WorkspacePick.Focus("w2"), pick.await())
        assertNull(prompts.promptState.value)
    }

    @Test
    fun cancelReadsAsBack() = runTest {
        val prompts = PromptManager()
        val pick = async { prompts.requestHerdrWorkspace(choices) }
        runCurrent()
        prompts.cancelPrompt()
        assertEquals(WorkspacePick.Dismiss, pick.await())
        assertNull(prompts.promptState.value)
    }
}
