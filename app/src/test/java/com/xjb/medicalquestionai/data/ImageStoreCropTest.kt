package com.xjb.medicalquestionai.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    private fun sourceImage(): File {
        val source = temporaryFolder.newFile("source.jpg")
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return source
    }

    private fun assertDimensions(path: String, width: Int, height: Int) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        assertEquals(width, options.outWidth)
        assertEquals(height, options.outHeight)
    }
}
