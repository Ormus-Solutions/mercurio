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

package org.connectbot.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import kotlin.math.abs

/**
 * [shrinkPhoto] applies a photo's EXIF orientation before it drops the tag. The source is a synthetic
 * JPEG of four solid quadrants (red, green, blue, yellow), so a turn or a flip shows in the corners.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoShrinkTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val red = Color.RED
    private val green = Color.GREEN
    private val blue = Color.BLUE
    private val yellow = Color.YELLOW

    /** A JPEG of [width] x [height]: top left red, top right green, bottom left blue, bottom right yellow. */
    private fun quadrantJpeg(width: Int, height: Int, orientation: Int?): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val top = y < height / 2
                val left = x < width / 2
                bitmap.setPixel(x, y, if (top) (if (left) red else green) else (if (left) blue else yellow))
            }
        }
        val file = folder.newFile()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        if (orientation != null) {
            val exif = ExifInterface(file.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            exif.saveAttributes()
        }
        return file.readBytes()
    }

    /** The index just after the "Exif" header (and its two zero bytes) of the APP1 segment: the start of the TIFF block. */
    private fun tiffStart(bytes: ByteArray): Int {
        val mark = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
        for (i in 0..bytes.size - mark.size) {
            if (mark.indices.all { bytes[i + it] == mark[it] }) return i + mark.size
        }
        error("no Exif header in the test JPEG")
    }

    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun near(expected: Int, actual: Int): Boolean = abs(Color.red(expected) - Color.red(actual)) <= 48 &&
        abs(Color.green(expected) - Color.green(actual)) <= 48 &&
        abs(Color.blue(expected) - Color.blue(actual)) <= 48

    /** Asserts the colour at the middle of each quadrant of [bitmap]. */
    private fun assertQuadrants(bitmap: Bitmap, topLeft: Int, topRight: Int, bottomLeft: Int, bottomRight: Int, label: String) {
        val w = bitmap.width
        val h = bitmap.height
        val actual = listOf(
            bitmap.getPixel(w / 4, h / 4),
            bitmap.getPixel(3 * w / 4, h / 4),
            bitmap.getPixel(w / 4, 3 * h / 4),
            bitmap.getPixel(3 * w / 4, 3 * h / 4),
        )
        val expected = listOf(topLeft, topRight, bottomLeft, bottomRight)
        val names = listOf("top left", "top right", "bottom left", "bottom right")
        for (i in 0 until 4) {
            assertTrue(
                "$label, ${names[i]}: expected #${Integer.toHexString(expected[i])} but was #${Integer.toHexString(actual[i])}",
                near(expected[i], actual[i]),
            )
        }
    }

    private fun shrunk(width: Int, height: Int, orientation: Int?): Bitmap {
        val out = shrinkPhoto(quadrantJpeg(width, height, orientation), coreTarget = true)
        assertNotNull("the photo must be re-encoded", out)
        return decode(out!!)
    }

    @Test
    fun noOrientationTagLeavesThePhotoAsItIs() {
        val bitmap = shrunk(64, 32, null)
        assertEquals(64, bitmap.width)
        assertEquals(32, bitmap.height)
        assertQuadrants(bitmap, red, green, blue, yellow, "no tag")
    }

    @Test
    fun orientationOneLeavesThePhotoAsItIs() {
        val bitmap = shrunk(64, 32, ExifInterface.ORIENTATION_NORMAL)
        assertEquals(64, bitmap.width)
        assertEquals(32, bitmap.height)
        assertQuadrants(bitmap, red, green, blue, yellow, "orientation 1")
    }

    @Test
    fun orientationSixTurnsThePhotoNinetyDegreesClockwise() {
        val bitmap = shrunk(64, 32, ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(32, bitmap.width)
        assertEquals(64, bitmap.height)
        assertQuadrants(bitmap, blue, red, yellow, green, "orientation 6")
    }

    @Test
    fun orientationThreeTurnsThePhotoUpsideDown() {
        val bitmap = shrunk(64, 32, ExifInterface.ORIENTATION_ROTATE_180)
        assertEquals(64, bitmap.width)
        assertEquals(32, bitmap.height)
        assertQuadrants(bitmap, yellow, blue, green, red, "orientation 3")
    }

    @Test
    fun orientationEightTurnsThePhotoTwoHundredSeventyDegreesClockwise() {
        val bitmap = shrunk(64, 32, ExifInterface.ORIENTATION_ROTATE_270)
        assertEquals(32, bitmap.width)
        assertEquals(64, bitmap.height)
        assertQuadrants(bitmap, green, yellow, red, blue, "orientation 8")
    }

    @Test
    fun orientationTwoMirrorsThePhotoLeftToRight() {
        val bitmap = shrunk(64, 32, ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        assertEquals(64, bitmap.width)
        assertEquals(32, bitmap.height)
        assertQuadrants(bitmap, green, red, yellow, blue, "orientation 2")
    }

    @Test
    fun orientationFourMirrorsThePhotoTopToBottom() {
        val bitmap = shrunk(64, 32, ExifInterface.ORIENTATION_FLIP_VERTICAL)
        assertEquals(64, bitmap.width)
        assertEquals(32, bitmap.height)
        assertQuadrants(bitmap, blue, yellow, red, green, "orientation 4")
    }

    @Test
    fun orientationFiveMirrorsThePhotoAcrossItsMainDiagonal() {
        val bitmap = shrunk(64, 32, ExifInterface.ORIENTATION_TRANSPOSE)
        assertEquals(32, bitmap.width)
        assertEquals(64, bitmap.height)
        assertQuadrants(bitmap, red, blue, green, yellow, "orientation 5")
    }

    @Test
    fun orientationSevenMirrorsThePhotoAcrossItsOtherDiagonal() {
        val bitmap = shrunk(64, 32, ExifInterface.ORIENTATION_TRANSVERSE)
        assertEquals(32, bitmap.width)
        assertEquals(64, bitmap.height)
        assertQuadrants(bitmap, yellow, green, blue, red, "orientation 7")
    }

    @Test
    fun anOrientationValueOutsideOneToEightLeavesThePhotoAsItIs() {
        val bitmap = shrunk(64, 32, 99)
        assertEquals(64, bitmap.width)
        assertEquals(32, bitmap.height)
        assertQuadrants(bitmap, red, green, blue, yellow, "orientation 99")
    }

    @Test
    fun theResultCarriesNoOrientationTagSoNoViewerTurnsItAgain() {
        val out = shrinkPhoto(quadrantJpeg(64, 32, ExifInterface.ORIENTATION_ROTATE_90), coreTarget = true)
        assertNotNull(out)
        // The platform reader gives 0 (undefined) for a missing tag; any of 2 to 8 would turn the photo again.
        val tag = ExifInterface(ByteArrayInputStream(out!!)).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
        assertTrue("orientation tag $tag", tag !in ExifInterface.ORIENTATION_FLIP_HORIZONTAL..ExifInterface.ORIENTATION_ROTATE_270)
    }

    @Test
    fun aPhotoThatIsScaledDownIsStillTurned() {
        // 3000 px is over the first edge step (2560), so the decode halves it; the result is still turned.
        val bitmap = shrunk(3000, 1500, ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(750, bitmap.width)
        assertEquals(1500, bitmap.height)
        assertQuadrants(bitmap, blue, red, yellow, green, "orientation 6 on a scaled down photo")
    }

    @Test
    fun aGarbledExifHeaderIsNotARefusal() {
        val bytes = quadrantJpeg(64, 32, ExifInterface.ORIENTATION_ROTATE_90)
        val tiff = tiffStart(bytes)
        // The byte order mark and the 42 become garbage, so the block cannot be read: no turn, but a result.
        for (i in 0 until 4) bytes[tiff + i] = 0x5A
        val out = shrinkPhoto(bytes, coreTarget = true)
        assertNotNull("a photo with an unreadable EXIF block must still be sent", out)
        val bitmap = decode(out!!)
        assertEquals(64, bitmap.width)
        assertEquals(32, bitmap.height)
        assertQuadrants(bitmap, red, green, blue, yellow, "garbled header")
    }

    @Test
    fun anExifBlockWithAnImpossibleEntryCountIsNotARefusal() {
        val bytes = quadrantJpeg(64, 32, ExifInterface.ORIENTATION_ROTATE_90)
        // The first IFD starts 8 bytes into the TIFF block; its entry count becomes 0xFFFF.
        val ifd = tiffStart(bytes) + 8
        bytes[ifd] = 0xFF.toByte()
        bytes[ifd + 1] = 0xFF.toByte()
        val out = shrinkPhoto(bytes, coreTarget = true)
        assertNotNull("a photo with a bad EXIF entry count must still be sent", out)
        val bitmap = decode(out!!)
        // Turned or not, depending on how much of the block the reader trusts; never refused.
        assertEquals(96, bitmap.width + bitmap.height)
    }

    @Test
    fun bytesThatAreNoImageGiveNull() {
        assertNull(shrinkPhoto(ByteArray(64) { it.toByte() }, coreTarget = true))
    }
}
