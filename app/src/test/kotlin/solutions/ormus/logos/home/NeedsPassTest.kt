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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** One pass over the open asks: order, Later, answered and closed, and what a new read changes. */
class NeedsPassTest {
    private fun ask(id: String, state: String = "open") = Ask(id = id, question = "q-$id", options = listOf(AskOption("a")), state = state)

    private val a = ask("a")
    private val b = ask("b")
    private val c = ask("c")

    @Test
    fun aRead_startsThePass_inTheStoresOrder_withoutTheClosedOnes() {
        val pass = NeedsPass().read(listOf(a, ask("x", "answered"), b, c))

        assertEquals(listOf("a", "b", "c"), pass.queue.map { it.id })
        assertEquals(a, pass.current)
        assertEquals(1, pass.position)
        assertEquals(3, pass.total)
    }

    @Test
    fun aRead_dropsEveryClosedWordOfTheStoresContract_withdrawnIncluded() {
        val closed = listOf("answered", "withdrawn", "expired").map { ask("x-$it", it) }

        val pass = NeedsPass().read(listOf(a) + closed + b)

        assertEquals(listOf("a", "b"), pass.queue.map { it.id })
        assertEquals(2, pass.total)
    }

    @Test
    fun aRead_leavesTheMultiAsksOutOfThePass_untilTheyCanBeAnswered() {
        val pass = NeedsPass().read(listOf(a, ask("m").copy(multi = true), b))

        assertEquals(listOf("a", "b"), pass.queue.map { it.id })
        assertEquals(1, pass.position)
        assertEquals(2, pass.total)
    }

    @Test
    fun aRead_countsTheOpenMultiAsksItLeftOut_andOnlyThose() {
        val multi = ask("m").copy(multi = true)
        val closedMulti = ask("n", "answered").copy(multi = true)

        val pass = NeedsPass().read(listOf(a, multi, closedMulti, ask("o").copy(multi = true)))

        assertEquals(2, pass.onPage)
        assertEquals(listOf("a"), pass.queue.map { it.id })
    }

    @Test
    fun whenOnlyMultiAsksAreOpen_thePassIsOver_andTheyAreCounted() {
        val pass = NeedsPass().read(listOf(ask("m").copy(multi = true)))

        assertNull(pass.current)
        assertEquals(1, pass.onPage)
    }

    @Test
    fun aNewRead_updatesTheCount_whenTheMultiAskWasAnsweredOnThePage() {
        val multi = ask("m").copy(multi = true)
        val pass = NeedsPass().read(listOf(a, multi))

        assertEquals(0, pass.read(listOf(a, ask("m", "answered").copy(multi = true))).onPage)
    }

    @Test
    fun answer_showsTheNext_andCounts() {
        val pass = NeedsPass().read(listOf(a, b, c)).answer()

        assertEquals(b, pass.current)
        assertEquals(1, pass.answered)
        assertEquals(2, pass.position)
        assertEquals(3, pass.total)
    }

    @Test
    fun later_sendsTheAskToTheEndOfThePass() {
        val pass = NeedsPass().read(listOf(a, b)).putOff()

        assertEquals(b, pass.current)
        assertEquals(listOf(a), pass.later)
        assertEquals(2, pass.position)
        assertNull(pass.answer().current)
    }

    @Test
    fun closed_showsTheNext_withoutCountingItAnswered() {
        val pass = NeedsPass().read(listOf(a, b)).closed()

        assertEquals(b, pass.current)
        assertEquals(0, pass.answered)
        assertEquals(2, pass.position)
    }

    @Test
    fun aNewRead_dropsWhatClosedElsewhere_keepsTheOneOnScreen_andAddsTheNewAtTheEnd() {
        val pass = NeedsPass().read(listOf(a, b, c)).putOff()
        // b is on screen; a (put off) and b both closed elsewhere, c too; d is new.
        val next = pass.read(listOf(ask("a", "answered"), ask("d")))

        assertEquals(listOf("b", "d"), next.queue.map { it.id })
        assertEquals(b, next.current)
        assertEquals(emptyList<Ask>(), next.later)
    }

    @Test
    fun aNewRead_doesNotBringBackAnAnsweredAsk() {
        val pass = NeedsPass().read(listOf(a, b)).answer()

        assertEquals(listOf("b"), pass.read(listOf(a, b)).queue.map { it.id })
    }
}
