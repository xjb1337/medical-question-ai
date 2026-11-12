package com.xjb.medicalquestionai.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ImageStoreCropTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val store = ImageStore(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun cropCopyShrinksDimensionsAndPreservesOriginalBytes() = runBlocking {
        val source = sourceImage()
        val original = source.readBytes()
        val result = store.cropCopy(source.absolutePath, RectF(0.25f, 0.25f, 0.75f, 0.75f))
        try {
            assertNotEquals(source.absolutePath, result.path)
            assertEquals(400, result.width)
            assertEquals(300, result.height)
            assertTrue(result.sizeBytes > 0)
            assertDimensions(result.path, 400, 300)
            assertDimensions(source.absolutePath, 800, 600)
            assertArrayEquals(original, source.readBytes())
        } finally {
            store.delete(result.path)
        }
    }

    @Test
    fun cropInPlaceShrinksDimensionsWithoutChangingThePath() = runBlocking {
        val source = sourceImage()
        val result = store.cropInPlace(source.absolutePath, RectF(0.25f, 0.25f, 0.75f, 0.75f))

        assertEquals(400, result.width)
        assertEquals(300, result.height)
        assertTrue(result.sizeBytes > 0)
        assertDimensions(source.absolutePath, 400, 300)
    }

    @Test
    fun cropBoundsAreClampedToTheImage() = runBlocking {
        val source = sourceImage()
        store.cropInPlace(source.absolutePath, RectF(-1f, 0.25f, 2f, 0.75f))
        assertDimensions(source.absolutePath, 800, 300)
    }

    @Test
    fun cropUsesTheHighResolutionSourceInsteadOfThe1800pxDerivedCopy() = runBlocking {
        val source = sourceImage(2400, 1600)
        val imported = store.importImage(Uri.fromFile(source))
        try {
            val sourcePath = requireNotNull(imported.sourcePath)
            assertDimensions(imported.path, 1800, 1200)
            assertDimensions(sourcePath, 2400, 1600)

            val cropped = store.cropCopy(imported.path, RectF(0f, 0f, 0.25f, 0.25f))
            try {
                assertEquals(600, cropped.width)
                assertEquals(400, cropped.height)
                assertDimensions(cropped.path, 600, 400)
                assertDimensions(cropped.sourcePath!!, 600, 400)
            } finally {
                store.delete(cropped.path)
            }
        } finally {
            store.delete(imported.path)
        }
    }

    @Test
    fun base64UploadCopyIsAlwaysConstrainedTo1800Px() = runBlocking {
        val imported = store.importImage(Uri.fromFile(sourceImage(2400, 1600)))
        try {
            listOf(imported.path, requireNotNull(imported.sourcePath)).forEach { path ->
                val bitmap = decodeBase64(store.readBase64(path))
                try {
                    assertTrue(maxOf(bitmap.width, bitmap.height) <= ImageStore.MAX_DIMENSION)
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            store.delete(imported.path)
        }
    }

    @Test
    fun deletingADisplayImageRemovesItsSourceAndDerivedCopiesButNotACrop() = runBlocking {
        val imported = store.importImage(Uri.fromFile(sourceImage(2400, 1600)))
        val cropped = store.cropCopy(imported.path, RectF(0f, 0f, 0.5f, 0.5f))
        try {
            val sourcePath = requireNotNull(imported.sourcePath)
            val cropSource = requireNotNull(cropped.sourcePath)
            assertTrue(File(imported.path).exists())
            assertTrue(File(sourcePath).exists())

            store.delete(imported.path)

            assertFalse(File(imported.path).exists())
            assertFalse(File(sourcePath).exists())
            assertTrue(File(cropped.path).exists())
            assertTrue(File(cropSource).exists())

            store.delete(cropped.path)
            assertFalse(File(cropped.path).exists())
            assertFalse(File(cropSource).exists())
        } finally {
            store.delete(imported.path)
            store.delete(cropped.path)
        }
    }

    @Test
    fun cropsWithAShortSideBelow400PxSurfaceAWarning() = runBlocking {
        val imported = store.importImage(Uri.fromFile(sourceImage(1200, 800)))
        try {
            val copy = store.cropCopy(imported.path, RectF(0.05f, 0.05f, 0.25f, 0.25f))
            try {
                assertTrue(copy.width < ImageStore.MIN_CROP_SHORT_SIDE)
                assertTrue(copy.warnings.any { it.contains(ImageStore.MIN_CROP_SHORT_SIDE.toString()) })
            } finally {
                store.delete(copy.path)
            }

            val replaced = store.cropInPlace(imported.path, RectF(0.1f, 0.1f, 0.35f, 0.35f))
            assertTrue(replaced.width < ImageStore.MIN_CROP_SHORT_SIDE)
            assertTrue(replaced.warnings.any { it.contains(ImageStore.MIN_CROP_SHORT_SIDE.toString()) })
        } finally {
            store.delete(imported.path)
        }
    }

    @Test
    fun importedSourceAndDerivedCopyAreStoredWithExifOrientationApplied() = runBlocking {
        val source = sourceImage(2000, 1000)
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_ARTIST, "SYNTHETIC_ONLY")
            saveAttributes()
        }
        val imported = store.importImage(Uri.fromFile(source))
        try {
            val sourcePath = requireNotNull(imported.sourcePath)
            assertDimensions(imported.path, 900, 1800)
            assertDimensions(sourcePath, 1000, 2000)
            val orientation = ExifInterface(sourcePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED
            )
            assertTrue(
                orientation == ExifInterface.ORIENTATION_NORMAL ||
                    orientation == ExifInterface.ORIENTATION_UNDEFINED
            )
        } finally {
            store.delete(imported.path)
        }
    }

    @Test
    fun rotatingUpdatesTheHighResolutionSourceAndItsUploadCopy() = runBlocking {
        val imported = store.importImage(Uri.fromFile(sourceImage(2400, 1600)))
        try {
            val sourcePath = requireNotNull(imported.sourcePath)
            val meta = store.rotate(imported.path, 90)

            assertEquals(1600, meta.width)
            assertEquals(2400, meta.height)
            assertDimensions(sourcePath, 1600, 2400)
            assertDimensions(imported.path, 1200, 1800)
            assertEquals(sourcePath, meta.sourcePath)
        } finally {
            store.delete(imported.path)
        }
    }

    private fun sourceImage(): File {
        return sourceImage(800, 600)
    }

    private fun sourceImage(width: Int, height: Int): File {
        val source = temporaryFolder.newFile("source-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return source
    }

    private fun decodeBase64(value: String): Bitmap {
        val bytes = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun assertDimensions(path: String, width: Int, height: Int) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        assertEquals(width, options.outWidth)
        assertEquals(height, options.outHeight)
    }
}
