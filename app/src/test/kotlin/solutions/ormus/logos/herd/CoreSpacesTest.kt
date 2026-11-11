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

package solutions.ormus.logos.herd

import org.connectbot.transport.CommandOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CoreSpaces: the spaces document of one host as a model and a box map, from synthetic JSON only. */
class CoreSpacesTest {
    private fun block(terminal: String, size: String = "") = """{"id":"p$terminal","type":"block",$size"content":{"terminal":"$terminal"}}"""

    private fun row(vararg kids: String, size: String = "") = """{"id":"r","type":"row",$size"children":[${kids.joinToString(",")}]}"""

    private fun col(vararg kids: String, size: String = "") = """{"id":"c","type":"column",$size"children":[${kids.joinToString(",")}]}"""

    private fun tab(id: String, name: String, root: String) = """{"id":"$id","name":"$name","root":$root}"""

    private fun space(id: String, name: String, vararg tabs: String) = """{"id":"$id","name":"$name","folder":"/w/$id","tabs":[${tabs.joinToString(",")}]}"""

    private fun doc(vararg spaces: String) = """{"id":1,"jsonrpc":"2.0","result":{"version":4,"value":{"spaces":[${spaces.joinToString(",")}]}}}"""

    private fun out(text: String, code: Int = 0) = CommandOutput(text, "", code)

    private fun spaces(r: CoreSpaces.Result) = assertIs<CoreSpaces.Result.Spaces>(r).spaces

    private fun read(o: CommandOutput?, log: MutableList<String> = mutableListOf()) = CoreSpaces.read(true) {
        log += it
        o
    }

    private val two = doc(
        space("s1", "alpha · ONE", tab("t1", "main", row(block("t7"))), tab("t2", "work", row(block("t8"), block("t9")))),
        space("s2", "beta · TWO", tab("t3", "", row(block("t10")))),
    )

    @Test
    fun `off runs nothing`() {
        val log = mutableListOf<String>()
        assertEquals(
            CoreSpaces.Result.Off,
            CoreSpaces.read(false) {
                log += it
                out(two)
            },
        )
        assertTrue(log.isEmpty())
    }

    @Test
    fun `parses spaces and tabs in the desktop order`() {
        val s = spaces(read(out(two)))
        assertEquals(listOf("alpha · ONE", "beta · TWO"), s.map { it.name })
        assertEquals(listOf("main", "work"), s[0].tabs.map { it.name })
        assertEquals("/w/s1", s[0].folder)
    }

    @Test
    fun `asks for the herdr-app document with one line through the login shell`() {
        val log = mutableListOf<String>()
        read(out(two), log)
        assertEquals(1, log.size)
        assertTrue(log[0].startsWith("bash -lc "), log[0])
        assertTrue("doc.get" in log[0] && "herdr-app" in log[0] && "herdr-core connect" in log[0], log[0])
        assertTrue("doc.set" !in log[0] && "hello" !in log[0])
    }

    @Test
    fun `first terminal is the first block depth first`() {
        val t = spaces(read(out(doc(space("s", "a · B", tab("t", "x", row(col(block("t5"), block("t6")), block("t7")))))))).single().tabs.single()
        assertEquals("t5", CoreSpaces.firstTerminal(t))
    }

    @Test
    fun `a tab with no terminal has no first terminal`() {
        val mod = """{"id":"m","type":"block","content":{"mod":"clock","props":{}}}"""
        val t = spaces(read(out(doc(space("s", "a · B", tab("t", "x", row(mod))))))).single().tabs.single()
        assertNull(CoreSpaces.firstTerminal(t))
    }

    @Test
    fun `a row splits left to right by weight`() {
        val root = row(block("t1", """"size":{"weight":2.0},"""), block("t2", """"size":{"weight":1.0},"""))
        val t = spaces(read(out(doc(space("s", "a · B", tab("t", "x", root)))))).single().tabs.single()
        val r = CoreSpaces.boxes(t)
        assertEquals(2, r.size)
        assertEquals(0f, r[0].x, 1e-4f)
        assertEquals(2f / 3f, r[0].w, 1e-4f)
        assertEquals(2f / 3f, r[1].x, 1e-4f)
        assertEquals(1f / 3f, r[1].w, 1e-4f)
        assertEquals(1f, r[0].h, 1e-4f)
    }

    @Test
    fun `a column splits top to bottom and a missing weight counts one`() {
        val t = spaces(read(out(doc(space("s", "a · B", tab("t", "x", col(block("t1"), block("t2")))))))).single().tabs.single()
        val r = CoreSpaces.boxes(t)
        assertEquals(0.5f, r[0].h, 1e-4f)
        assertEquals(0.5f, r[1].y, 1e-4f)
        assertEquals(1f, r[1].w, 1e-4f)
    }

    @Test
    fun `nested splits tile the unit square`() {
        val t = spaces(read(out(doc(space("s", "a · B", tab("t", "x", row(block("t1"), col(block("t2"), block("t3"))))))))).single().tabs.single()
        val r = CoreSpaces.boxes(t)
        assertEquals(3, r.size)
        assertEquals(1f, r.sumOf { (it.w * it.h).toDouble() }.toFloat(), 1e-4f)
        assertEquals(0.5f, r[2].x, 1e-4f)
        assertEquals(0.5f, r[2].y, 1e-4f)
    }

    @Test
    fun `a pixel size or a bad weight counts one`() {
        val root = row(block("t1", """"size":{"px":300},"""), block("t2", """"size":{"weight":-4},"""), block("t3", """"size":"wide","""))
        val r = CoreSpaces.boxes(spaces(read(out(doc(space("s", "a · B", tab("t", "x", root)))))).single().tabs.single())
        assertEquals(3, r.size)
        r.forEach { assertEquals(1f / 3f, it.w, 1e-4f) }
    }

    @Test
    fun `a stack draws its active child only`() {
        val stack = """{"id":"k","type":"stack","active":"pt2","children":[${block("t1")},${block("t2")}]}"""
        val t = spaces(read(out(doc(space("s", "a · B", tab("t", "x", row(stack, block("t3")))))))).single().tabs.single()
        assertEquals(2, CoreSpaces.boxes(t).size)
        assertEquals("t2", CoreSpaces.firstTerminal(t))
    }

    @Test
    fun `hidden nodes are skipped`() {
        val hidden = """{"id":"h","type":"block","hidden":true,"content":{"terminal":"t1"}}"""
        val t = spaces(read(out(doc(space("s", "a · B", tab("t", "x", row(hidden, block("t2")))))))).single().tabs.single()
        assertEquals(1, CoreSpaces.boxes(t).size)
        assertEquals("t2", CoreSpaces.firstTerminal(t))
    }

    @Test
    fun `an empty tab still draws one box`() {
        val t = spaces(read(out(doc(space("s", "a · B", """{"id":"t","name":"x"}""", tab("t2", "y", row())))))).single().tabs
        t.forEach {
            assertEquals(1, CoreSpaces.boxes(it).size)
            assertNull(CoreSpaces.firstTerminal(it))
        }
    }

    @Test
    fun `missing fields are tolerated and unknown fields stay out of the model`() {
        val text = """{"result":{"value":{"spaces":[{"id":"s","color":"teal","secret":"x","tabs":[{"id":"t","floating":[1],"root":{"type":"row","children":[{"type":"block","content":{"terminal":"t4","cwd":"/private"}}]}}]},{"name":"no id"}]}}}"""
        val s = spaces(read(out(text)))
        assertEquals(1, s.size)
        assertEquals("s", s[0].name)
        assertEquals("", s[0].folder)
        assertEquals("", s[0].tabs[0].name)
        assertEquals("t4", CoreSpaces.firstTerminal(s[0].tabs[0]))
        assertTrue("/private" !in s.toString() && "secret" !in s.toString())
    }

    @Test
    fun `a login banner before the line is skipped`() {
        assertEquals(2, spaces(read(out("Welcome\nlast login now\n$two\n"))).size)
    }

    @Test
    fun `a missing host, a failed command and bad json are Unavailable with a reason`() {
        assertIs<CoreSpaces.Result.Unavailable>(read(null))
        assertIs<CoreSpaces.Result.Unavailable>(read(out("", 127)))
        assertIs<CoreSpaces.Result.Unavailable>(read(out("not json")))
        assertIs<CoreSpaces.Result.Unavailable>(read(out("""{"id":1,"error":{"code":-1,"message":"nope"}}""")))
        assertIs<CoreSpaces.Result.Unavailable>(read(out("""{"id":1,"result":{}}""")))
        assertNotEquals("", assertIs<CoreSpaces.Result.Unavailable>(read(out("", 127))).reason)
    }

    @Test
    fun `no spaces is an empty list not an error`() {
        assertEquals(emptyList(), spaces(read(out(doc()))))
    }

    @Test
    fun `a layout deeper than the cap is cut off`() {
        val deep = (1..60).fold(block("t1")) { inner, _ -> row(inner) }
        val t = spaces(read(out(doc(space("s", "a · B", tab("t", "x", deep)))))).single().tabs.single()
        assertEquals(1, CoreSpaces.boxes(t).size)
        assertNull(CoreSpaces.firstTerminal(t))
    }

    @Test
    fun `a reply nested thousands deep is unreadable, not a crash`() {
        val deep = (1..5000).fold(block("t1")) { inner, _ -> row(inner) }
        assertIs<CoreSpaces.Result.Unavailable>(read(out(doc(space("s", "a · B", tab("t", "x", deep))))))
    }

    @Test
    fun `weights that overflow when summed still share the length`() {
        val root = row(block("t1", """"size":{"weight":3e38},"""), block("t2", """"size":{"weight":3e38},"""))
        val r = CoreSpaces.boxes(spaces(read(out(doc(space("s", "a · B", tab("t", "x", root)))))).single().tabs.single())
        assertEquals(0.5f, r[0].w, 1e-4f)
        assertEquals(0.5f, r[1].w, 1e-4f)
    }

    @Test
    fun `a null exit code is not reported as a failure with a number`() {
        val r = CoreSpaces.read(true) { CommandOutput("", "", null) }
        assertTrue(!(r as CoreSpaces.Result.Unavailable).reason.contains("null"))
    }
}
