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

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import org.connectbot.transport.CommandOutput

/**
 * The spaces of one herdr-core host: the `herdr-app` document the desktop app keeps, read with one
 * JSON-RPC line through the login shell (no hello; measured 12 ms on the spike). Read only: nothing
 * here writes the document.
 *
 * Only what the Spaces list draws is kept: a space's id, name and folder, a tab's id and name, and
 * the shape of its layout. A terminal's cwd or any other field of the document never enters the
 * model, and a tab's layout is a [PaneBox] tree with no screen text in it (client data does not
 * show on this screen). A missing field is tolerated; a space with no id is dropped.
 */
object CoreSpaces {
    sealed interface Result {
        /** The setting is off: nothing was run. */
        data object Off : Result

        /** The host's spaces in the desktop's order, possibly none. */
        data class Spaces(val spaces: List<CoreSpace>) : Result

        /** No herdr-core here, or it did not answer: [reason] is for the user. */
        data class Unavailable(val reason: String) : Result
    }

    /** One space. [name] falls back to the id when the document gives none. */
    data class CoreSpace(val id: String, val name: String, val folder: String, val tabs: List<CoreTab>)

    /** One tab and the layout of its panes. [root] is null when the document gave none. */
    data class CoreTab(val id: String, val name: String, val root: PaneBox?)

    /** A node of a tab's layout. [weight] is its share of the parent's length. */
    sealed interface PaneBox {
        val weight: Float

        /** A row ([vertical] false: children left to right) or a column (top to bottom). */
        data class Split(val vertical: Boolean, val children: List<PaneBox>, override val weight: Float) : PaneBox

        /** One pane; [terminal] is the core's id of its terminal (`t7`), null for a mod. */
        data class Pane(val terminal: String?, override val weight: Float) : PaneBox
    }

    /** One pane's place in the tab, as shares of its width and height, 0 to 1. */
    data class BoxRect(val x: Float, val y: Float, val w: Float, val h: Float)

    private const val MAX_DEPTH = 32

    private const val MAX_NESTING = 256

    private const val DOC_LINE = """{"jsonrpc":"2.0","id":1,"method":"doc.get","params":{"name":"herdr-app"}}"""

    /** The command: one request line piped to `herdr-core connect`. Exec has no `~/.local/bin`, hence the login shell. */
    val COMMAND: String = HerdrApi.loginShell("printf '%s\\n' '$DOC_LINE' | herdr-core connect")

    /**
     * Read the host's spaces.
     *
     * @param enabled the setting; off runs nothing
     * @param run runs one command on the host's connection; null when it never ran
     */
    fun read(
        enabled: Boolean,
        run: (String) -> CommandOutput?,
    ): Result {
        if (!enabled) return Result.Off
        val out = run(COMMAND) ?: return Result.Unavailable("could not reach the host")
        if (out.exitCode != null && out.exitCode != 0) return Result.Unavailable("herdr-core is not installed or not running (exit ${out.exitCode})")
        return parse(out.stdout) ?: Result.Unavailable("herdr-core answered something unreadable")
    }

    /** The spaces of a `doc.get` reply, or null when the text is not one. */
    fun parse(text: String): Result.Spaces? {
        // A login shell may print a banner before the reply line.
        val reply = text.lineSequence().firstNotNullOfOrNull { line ->
            val start = line.indexOf('{')
            if (start < 0 || nesting(line) > MAX_NESTING) {
                null
            } else {
                try {
                    Json.parseToJsonElement(line.substring(start)) as? JsonObject
                } catch (_: SerializationException) {
                    null
                }
            }
        } ?: return null
        val list = (((reply["result"] as? JsonObject)?.get("value") as? JsonObject)?.get("spaces")) as? JsonArray ?: return null
        return Result.Spaces(list.filterIsInstance<JsonObject>().mapNotNull(::space))
    }

    /** How deep the brackets of [line] go, strings aside: the JSON parser recurses on it, so a line too deep is not parsed. */
    private fun nesting(line: String): Int {
        var depth = 0
        var max = 0
        var inString = false
        var escaped = false
        for (c in line) {
            when {
                escaped -> escaped = false

                inString -> if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    inString = false
                }

                c == '"' -> inString = true

                c == '{' || c == '[' -> max = maxOf(max, ++depth)

                c == '}' || c == ']' -> depth--
            }
        }
        return max
    }

    /** The first terminal of [tab] in drawing order (the active child of a stack), or null. */
    fun firstTerminal(tab: CoreTab): String? = tab.root?.let(::firstTerminal)

    private fun firstTerminal(box: PaneBox): String? = when (box) {
        is PaneBox.Pane -> box.terminal
        is PaneBox.Split -> box.children.firstNotNullOfOrNull(::firstTerminal)
    }

    /** The panes of [tab] as rectangles that tile the unit square; a tab with none draws one box. */
    fun boxes(tab: CoreTab): List<BoxRect> {
        val out = ArrayList<BoxRect>()
        tab.root?.let { place(it, BoxRect(0f, 0f, 1f, 1f), out) }
        return out.ifEmpty { listOf(BoxRect(0f, 0f, 1f, 1f)) }
    }

    private fun place(box: PaneBox, into: BoxRect, out: MutableList<BoxRect>) {
        when (box) {
            is PaneBox.Pane -> out += into

            is PaneBox.Split -> {
                val total = box.children.sumOf { it.weight.toDouble() }
                var at = 0f
                for (child in box.children) {
                    val share = (child.weight / total).toFloat()
                    val rect = if (box.vertical) {
                        BoxRect(into.x, into.y + at * into.h, into.w, share * into.h)
                    } else {
                        BoxRect(into.x + at * into.w, into.y, share * into.w, into.h)
                    }
                    place(child, rect, out)
                    at += share
                }
            }
        }
    }

    private fun space(o: JsonObject): CoreSpace? {
        val id = o.str("id") ?: return null
        val tabs = (o["tabs"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull(::tab)
        return CoreSpace(id, o.str("name")?.takeIf { it.isNotBlank() } ?: id, o.str("folder").orEmpty(), tabs)
    }

    private fun tab(o: JsonObject): CoreTab? {
        val id = o.str("id") ?: return null
        return CoreTab(id, o.str("name").orEmpty(), (o["root"] as? JsonObject)?.let { node(it, 0) })
    }

    /** A node as a [PaneBox], or null when it draws nothing (hidden, or a split with no visible child). */
    private fun node(o: JsonObject, depth: Int): PaneBox? {
        // A real layout is a few levels deep; a deeper one is cut off so no walk of it can overflow the stack.
        if (depth > MAX_DEPTH) return null
        if ((o["hidden"] as? JsonPrimitive)?.booleanOrNull == true) return null
        val weight = weight(o["size"])
        val kids = (o["children"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        return when (o.str("type")) {
            "row", "column" -> kids.mapNotNull { node(it, depth + 1) }.takeIf { it.isNotEmpty() }
                ?.let { PaneBox.Split(vertical = o.str("type") == "column", children = it, weight = weight) }

            // A stack shows one child at a time: the active one, else the first that draws.
            "stack" -> {
                val active = o.str("active")
                (kids.firstOrNull { it.str("id") == active }?.let { node(it, depth + 1) } ?: kids.firstNotNullOfOrNull { node(it, depth + 1) })
                    ?.let { withWeight(it, weight) }
            }

            else -> PaneBox.Pane(((o["content"] as? JsonObject)?.str("terminal")), weight)
        }
    }

    private fun withWeight(box: PaneBox, weight: Float): PaneBox = when (box) {
        is PaneBox.Pane -> box.copy(weight = weight)
        is PaneBox.Split -> box.copy(weight = weight)
    }

    /** `{"weight": n}` with n above zero; anything else (a pixel size, a bad value) counts one. */
    private fun weight(size: JsonElement?): Float = ((size as? JsonObject)?.get("weight") as? JsonPrimitive)
        ?.takeIf { !it.isString }?.floatOrNull?.takeIf { it > 0f && it.isFinite() } ?: 1f

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
