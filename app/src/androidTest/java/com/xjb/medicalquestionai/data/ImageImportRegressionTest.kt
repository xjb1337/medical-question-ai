package com.xjb.medicalquestionai.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level regression for the image import crash reported in REPAIR_PLAN R1.
 *
 * Run with a connected device/emulator:
 *   ./gradlew connectedDebugAndroidTest
 * If no device is attached, record the test as NOT EXECUTED rather than
 * substituting a successful assembleDebug for functional acceptance.
 */
@RunWith(AndroidJUnit4::class)
class ImageImportRegressionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = ImageStore(context)
    private val created = mutableListOf<String>()
    private val imagesDir = File(context.filesDir, "question_images")

    @After
    fun cleanUp() {
        store.deleteAll(created)
    }

    @Test
    fun importsGeneratedPngAndCropCopyPreservesOriginal() = runBlocking<Unit> {
        val source = File(context.cacheDir, "repair-source-${System.nanoTime()}.png")
        val bitmap = createQuadrantBitmap(1200, 900)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val imported = store.importImage(Uri.fromFile(source))
        created += imported.path
        assertTrue("imported file must exist", File(imported.path).isFile)
        assertEquals(1200, imported.width)
        assertEquals(900, imported.height)

        val originalFile = File(imported.path)
        val originalHash = sha256(originalFile)
        val originalBitmap = BitmapFactory.decodeFile(imported.path)
        try {
            assertEquals(1200, originalBitmap.width)
            assertEquals(900, originalBitmap.height)
        } finally {
            originalBitmap.recycle()
        }

        val cropped = store.cropCopy(imported.path, android.graphics.RectF(0f, 0f, 0.5f, 0.5f))
        created += cropped.path

        assertEquals("original bytes must not change after crop", originalHash, sha256(originalFile))
        assertTrue("original must survive crop", originalFile.isFile)
        assertNotEquals("crop must get a new id", imported.id, cropped.id)
        assertNotEquals(
            "crop must use an independent canonical path",
            originalFile.canonicalPath,
            File(cropped.path).canonicalPath
        )
        assertEquals(600, cropped.width)
        assertEquals(450, cropped.height)

        val croppedBitmap = BitmapFactory.decodeFile(cropped.path)
        try {
            assertEquals(600, croppedBitmap.width)
            assertEquals(450, croppedBitmap.height)
            // The top-left half contains the red quadrant of the synthetic source.
            val redSample = croppedBitmap.getPixel(
                (croppedBitmap.width * 0.1f).toInt(),
                (croppedBitmap.height * 0.1f).toInt()
            )
            assertTrue(
                "crop must contain the top-left red marker, was #${Integer.toHexString(redSample)}",
                Color.red(redSample) > 150 && Color.green(redSample) < 100 && Color.blue(redSample) < 100
            )
        } finally {
            croppedBitmap.recycle()
        }
    }

    @Test
    fun deletingOneCopyDoesNotAffectTheOther() = runBlocking<Unit> {
        val source = File(context.cacheDir, "repair-delete-${System.nanoTime()}.png")
        val bitmap = createQuadrantBitmap(1200, 900)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val imported = store.importImage(Uri.fromFile(source))
        created += imported.path
        val originalFile = File(imported.path)
        val originalHash = sha256(originalFile)

        val firstCrop = store.cropCopy(imported.path, android.graphics.RectF(0f, 0f, 0.5f, 0.5f))
        created += firstCrop.path
        store.delete(firstCrop.path)

        assertFalse("deleted crop file must be gone", File(firstCrop.path).exists())
        assertTrue("deleting crop must keep the original", originalFile.isFile)
        assertEquals(originalHash, sha256(originalFile))

        val secondCrop = store.cropCopy(imported.path, android.graphics.RectF(0.5f, 0.5f, 1f, 1f))
        created += secondCrop.path
        val secondHash = sha256(File(secondCrop.path))
        store.delete(imported.path)

        assertFalse("deleted original must be gone", originalFile.exists())
        assertTrue("deleting original must keep the crop", File(secondCrop.path).isFile)
        assertEquals(secondHash, sha256(File(secondCrop.path)))
    }

    @Test
    fun deletingQuestionImageSetRemovesBothFiles() = runBlocking<Unit> {
        val source = File(context.cacheDir, "repair-question-${System.nanoTime()}.png")
        val bitmap = createQuadrantBitmap(900, 900)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val imported = store.importImage(Uri.fromFile(source))
        created += imported.path
        val cropped = store.cropCopy(imported.path, android.graphics.RectF(0f, 0f, 0.5f, 0.5f))
        created += cropped.path

        store.deleteAll(listOf(imported.path, cropped.path))

        assertFalse("question deletion/clear must remove first image", File(imported.path).exists())
        assertFalse("question deletion/clear must remove cropped image", File(cropped.path).exists())
    }

    @Test
    fun jpegWithExifIsRotatedScaledAndMetadataIsStripped() = runBlocking<Unit> {
        val source = File(context.cacheDir, "repair-exif-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(2000, 1000, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(220, 230, 240))
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bitmap.recycle()
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_ARTIST, "SYNTHETIC_SECRET")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "39/1,54/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            saveAttributes()
        }

        val imported = store.importImage(Uri.fromFile(source))
        created += imported.path
        assertEquals("rotated portrait should be scaled to 1800 max side", 900, imported.width)
        assertEquals(1800, imported.height)

        val outputExif = ExifInterface(imported.path)
        assertEquals(
            ExifInterface.ORIENTATION_NORMAL,
            outputExif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        )
        assertNull("artist metadata must not leak", outputExif.getAttribute(ExifInterface.TAG_ARTIST))
        assertNull("GPS metadata must not leak", outputExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
    }

    @Test(expected = ImageImportException::class)
    fun corruptImageReportsImportErrorInsteadOfCrashingWholeImport() {
        runBlocking<Unit> {
            val source = File(context.cacheDir, "repair-corrupt-${System.nanoTime()}.jpg")
            source.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
            val before = imagesDir.listFiles()?.map { it.name }?.toSet().orEmpty()
            try {
                store.importImage(Uri.fromFile(source))
            } finally {
                val after = imagesDir.listFiles()?.map { it.name }?.toSet().orEmpty()
                assertEquals("failed import must not leave files behind", before, after)
            }
        }
    }

    @Test(expected = ImageImportException::class)
    fun unopenableUriReportsImportError() {
        runBlocking<Unit> {
            store.importImage(Uri.parse("content://com.example.missing/image"))
        }
    }

    private fun createQuadrantBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { style = Paint.Style.FILL }
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, width / 2f, height / 2f, paint)
        paint.color = Color.GREEN
        canvas.drawRect(width / 2f, 0f, width.toFloat(), height / 2f, paint)
        paint.color = Color.BLUE
        canvas.drawRect(0f, height / 2f, width / 2f, height.toFloat(), paint)
        paint.color = Color.YELLOW
        canvas.drawRect(width / 2f, height / 2f, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { it.toUByte().toString(16).padStart(2, '0') }
}
