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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class PasteUploadTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun out(stdout: String = "", stderr: String = "", code: Int? = 0) = CommandOutput(stdout, stderr, code)

    // Runs the folder script in a shell whose HOME is [home], as the host would run it for its user.
    private fun runScript(home: File) = runScriptWithHome(home.absolutePath)

    private fun runScriptWithHome(home: String, command: List<String> = listOf("bash", "-c", PasteUpload.dirScript())): Triple<Int, String, String> {
        val process = ProcessBuilder(command)
            .also { it.environment()["HOME"] = home }
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return Triple(process.waitFor(), stdout.trim(), stderr.trim())
    }

    private fun mode(file: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    @Test
    fun dirCommand_runsTheScriptInALoginShell() {
        assertEquals(HerdrApi.loginShell(PasteUpload.dirScript()), PasteUpload.dirCommand())
    }

    @Test
    fun dirScript_neverUsesAFixedSharedFolder() {
        val script = PasteUpload.dirScript()
        assertTrue(script, script.contains("\$HOME/.cache/mercurio-pastes"))
        assertTrue(script, !script.contains("/tmp"))
        assertTrue(script, script.contains("-O "))
    }

    @Test
    fun dirScript_makesAPrivateFolderOwnedByTheUser() {
        val home = tmp.newFolder("home")
        val (code, printed, _) = runScript(home)
        assertEquals(0, code)
        assertEquals(File(home, ".cache/mercurio-pastes").absolutePath, printed)
        assertEquals("rwx------", mode(File(printed)))
    }

    @Test
    fun dirScript_acceptsItsOwnPrivateFolderAgain() {
        val home = tmp.newFolder("home")
        assertEquals(0, runScript(home).first)
        assertEquals(0, runScript(home).first)
    }

    @Test
    fun dirScript_refusesAFolderOthersCanWrite() {
        for (perms in listOf("rwxrwxrwx", "rwxrwx---", "rwx---rwx")) {
            val home = tmp.newFolder()
            val dir = File(home, ".cache/mercurio-pastes").also { it.mkdirs() }
            Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString(perms))
            val (code, printed, _) = runScript(home)
            assertTrue("$perms must be refused", code != 0)
            assertEquals("$perms prints no path", "", printed)
        }
    }

    @Test
    fun dirScript_refusesASymlinkInsteadOfTheFolder() {
        val home = tmp.newFolder("home")
        val elsewhere = tmp.newFolder("elsewhere")
        File(home, ".cache").mkdirs()
        Files.createSymbolicLink(File(home, ".cache/mercurio-pastes").toPath(), elsewhere.toPath())
        assertTrue(runScript(home).first != 0)
    }

    @Test
    fun dirScript_acceptsAFolderOthersCanOnlyRead() {
        val home = tmp.newFolder("home")
        val dir = File(home, ".cache/mercurio-pastes").also { it.mkdirs() }
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-x---"))
        assertEquals(0, runScript(home).first)
    }

    @Test
    fun dirScript_refusesAnEmptyHomeWithItsOwnMessage() {
        val (code, printed, err) = runScriptWithHome("")
        assertTrue(code != 0)
        assertEquals("", printed)
        assertTrue(err, err.contains("HOME"))
    }

    @Test
    fun dirCommand_runsThroughAShellWithItsQuotingIntact() {
        val home = tmp.newFolder("home")
        val (code, printed, err) = runScriptWithHome(home.absolutePath, listOf("sh", "-c", PasteUpload.dirCommand()))
        assertEquals(err, 0, code)
        assertEquals(File(home, ".cache/mercurio-pastes").absolutePath, printed)
    }

    @Test
    fun readCapped_returnsTheBytesWithinTheCap() {
        assertEquals(listOf<Byte>(1, 2, 3), PasteUpload.readCapped(ByteArrayInputStream(byteArrayOf(1, 2, 3)), 3)!!.toList())
        assertEquals(0, PasteUpload.readCapped(ByteArrayInputStream(ByteArray(0)), 3)!!.size)
    }

    @Test
    fun readCapped_givesNullPastTheCapAndStopsReadingAnEndlessStream() {
        assertNull(PasteUpload.readCapped(ByteArrayInputStream(ByteArray(4)), 3))
        var served = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 7.also { served++ }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                served += len
                return len
            }
        }
        assertNull(PasteUpload.readCapped(endless, 1000))
        assertTrue("read $served bytes of an endless stream", served < 100_000)
    }

    @Test
    fun sizeDecision_sendsWhatFitsAndShrinksWhatDoesNot() {
        val core = CoreSend.MAX_PUT_BYTES
        assertEquals(SizeDecision.Send, PasteUpload.decide(core, coreTarget = true))
        assertEquals(SizeDecision.Shrink, PasteUpload.decide(core + 1, coreTarget = true))
        // A plain host has a larger limit than hc put's.
        assertEquals(SizeDecision.Send, PasteUpload.decide(core + 1, coreTarget = false))
        assertEquals(SizeDecision.Shrink, PasteUpload.decide(PasteUpload.limitFor(false) + 1, coreTarget = false))
        assertTrue(PasteUpload.fits(core, coreTarget = true))
        assertTrue(!PasteUpload.fits(core + 1, coreTarget = true))
    }

    @Test
    fun sampleSize_halvesUntilTheLongEdgeFits() {
        assertEquals(1, PasteUpload.sampleSize(2000, 1500, 2560))
        assertEquals(2, PasteUpload.sampleSize(4000, 3000, 2560))
        assertEquals(4, PasteUpload.sampleSize(8000, 6000, 2560))
        assertEquals(1, PasteUpload.sampleSize(0, 0, 2560))
        assertEquals(listOf(2560, 1600, 1024), PasteUpload.EDGE_STEPS)
    }

    @Test
    fun dirOf_takesThePrintedPath() {
        assertEquals(PasteOutcome.Landed("/home/alex/.cache/mercurio-pastes"), PasteUpload.dirOf(out("/home/alex/.cache/mercurio-pastes\n")))
    }

    @Test
    fun dirOf_failureSaysWhyAndGivesNoPath() {
        val refused = PasteUpload.dirOf(out(stderr = "not private", code = 1))
        assertTrue(refused is PasteOutcome.Failed)
        assertTrue((refused as PasteOutcome.Failed).reason.contains("not private"))
        assertTrue(PasteUpload.dirOf(null) is PasteOutcome.Failed)
        assertTrue(PasteUpload.dirOf(out("no path here\n")) is PasteOutcome.Failed)
    }

    @Test
    fun fileName_followsTheImageType() {
        assertEquals("photo-5.jpg", PasteUpload.fileName("image/jpeg", 5))
        assertEquals("photo-5.png", PasteUpload.fileName("image/png", 5))
        assertEquals("photo-5.webp", PasteUpload.fileName("image/webp", 5))
        assertEquals("photo-5.img", PasteUpload.fileName(null, 5))
        assertEquals("photo-5.img", PasteUpload.fileName("text/html; x", 5))
    }

    @Test
    fun putOutcome_usesThePathHcPrinted() {
        assertEquals(PasteOutcome.Landed("/work/t7/p.png"), PasteUpload.putOutcome(out("""{"path":"/work/t7/p.png"}""")))
    }

    @Test
    fun putOutcome_failureIsShownAndNeverAGuessedPath() {
        val refused = PasteUpload.putOutcome(out(stdout = """{"error":{"message":"no such terminal","hint":"run hc ls"}}""", code = 1))
        assertEquals(PasteOutcome.Failed("no such terminal. run hc ls"), refused)
        assertTrue(PasteUpload.putOutcome(null) is PasteOutcome.Failed)
        assertTrue(PasteUpload.putOutcome(out(stderr = "boom", code = 2)) is PasteOutcome.Failed)
        // Exit 0 with no path is a failure too: nothing says where the file is.
        assertTrue(PasteUpload.putOutcome(out("done\n")) is PasteOutcome.Failed)
    }
}
