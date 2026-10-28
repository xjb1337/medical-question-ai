package com.xjb.medicalquestionai.data

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
        created.forEach { runCatching { store.delete(it) } }
    }

    @Test
    fun importsGeneratedPngAndCropCopyPreservesOriginal() = runBlocking {
        val source = File(context.cacheDir, "repair-source-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(15, 118, 110))
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val imported = store.importImage(Uri.fromFile(source))
        created += imported.path
        assertTrue("imported file must exist", File(imported.path).isFile)
        assertEquals(1200, imported.width)
        assertEquals(900, imported.height)

        val cropped = store.cropCopy(imported.path, android.graphics.RectF(0.1f, 0.1f, 0.6f, 0.6f))
        created += cropped.path
        assertTrue("source must survive crop", File(imported.path).isFile)
        assertTrue("crop file must exist", File(cropped.path).isFile)
        assertTrue("crop must be smaller than source", cropped.width < imported.width)
    }

    @Test
    fun jpegWithExifIsRotatedScaledAndMetadataIsStripped() = runBlocking {
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
    fun corruptImageReportsImportErrorInsteadOfCrashingWholeImport() = runBlocking {
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

    @Test(expected = ImageImportException::class)
    fun unopenableUriReportsImportError() = runBlocking {
        store.importImage(Uri.parse("content://com.example.missing/image"))
    }
}
