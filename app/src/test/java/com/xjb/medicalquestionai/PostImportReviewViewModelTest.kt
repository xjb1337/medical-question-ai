package com.xjb.medicalquestionai

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjb.medicalquestionai.data.ImageCropResult
import com.xjb.medicalquestionai.ui.AuditKeyStore
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], shadows = [AuditKeyStore::class])
class PostImportReviewViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun galleryAndCameraImportsUseImagesDirectlyWithoutPopupReview() {
        val viewModel = MainViewModel(app)
        val older = imageFile("older.jpg")
        viewModel.addImages(listOf(Uri.fromFile(older)))
        awaitImageCount(viewModel, 1)
        assertNull(viewModel.pendingImageReview.value)
        val olderImage = viewModel.draft.value.images.single()

        val captureFile = File(app.cacheDir, "captures/camera_test.jpg").apply {
            parentFile?.mkdirs()
            writeImage(this, 700, 900)
        }
        val token = viewModel.beginCameraCapture()
        var completed = false
        viewModel.addCameraImage(
            Uri.fromFile(captureFile),
            captureFile,
            viewModel.draft.value.sessionId,
            token
        ) { completed = true }

        awaitImageCount(viewModel, 2)
        assertNull(viewModel.pendingImageReview.value)
        assertTrue(completed)
        val newImage = viewModel.draft.value.images.single { it.id != olderImage.id }
        assertTrue(File(newImage.path).exists())
        assertTrue(File(requireNotNull(newImage.sourcePath)).exists())
        assertFalse(captureFile.exists())
    }

    @Test
    fun optionalCropInPlaceReplacesOnlySelectedImageWhenChosen() = runBlocking {
        val viewModel = MainViewModel(app)
        viewModel.addImages(listOf(Uri.fromFile(imageFile("direct-crop.jpg"))))
        awaitImageCount(viewModel, 1)
        val target = viewModel.draft.value.images.single()

        val result = viewModel.cropImage(target.id, RectF(0.1f, 0.1f, 0.6f, 0.6f), keepOriginal = false)
        assertEquals(ImageCropResult.Success, result)
        val updated = viewModel.draft.value.images.single()
        assertEquals(target.id, updated.id)
        assertEquals(400, updated.width)
        assertEquals(300, updated.height)
    }

    @Test
    fun clearingAParkedDraftRemovesBothImageCopies() = runBlocking {
        val viewModel = MainViewModel(app)
        viewModel.addImages(listOf(Uri.fromFile(imageFile("parked.jpg"))))
        awaitImageCount(viewModel, 1)
        val image = viewModel.draft.value.images.single()

        val prepared = viewModel.prepareNextQuestion("fictional-question")
        assertTrue(prepared.success)
        assertNotNull(viewModel.parkedDraft.value)

        viewModel.clearParkedDraft()

        assertNull(viewModel.parkedDraft.value)
        assertFalse(File(image.path).exists())
        assertFalse(File(requireNotNull(image.sourcePath)).exists())
    }

    private fun awaitImageCount(viewModel: MainViewModel, expectedCount: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!viewModel.isImporting.value && viewModel.draft.value.images.size >= expectedCount) {
                return
            }
            Thread.sleep(10)
        }
        assertEquals("Image import did not reach $expectedCount", expectedCount, viewModel.draft.value.images.size)
    }

    private fun imageFile(name: String): File =
        File(app.cacheDir, name).also { writeImage(it, 800, 600) }

    private fun writeImage(file: File, width: Int, height: Int) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
    }
}
