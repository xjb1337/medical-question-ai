package com.xjb.medicalquestionai

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjb.medicalquestionai.data.ImageReviewSource
import com.xjb.medicalquestionai.data.PendingImageReview
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
    fun cameraImportCreatesReviewAndRetakeRemovesOnlyTheNewImage() {
        val viewModel = MainViewModel(app)
        val older = imageFile("older.jpg")
        viewModel.addImages(listOf(Uri.fromFile(older)))
        val olderImage = awaitReview(viewModel).let { review ->
            assertEquals(ImageReviewSource.GALLERY, review.source)
            viewModel.draft.value.images.single()
        }
        viewModel.useOriginalPendingImageReview()

        val target = viewModel.createCaptureTarget()
        writeImage(target.file, 700, 900)
        val token = viewModel.beginCameraCapture()
        var completed = false
        viewModel.addCameraImage(
            target.uri,
            target.file,
            viewModel.draft.value.sessionId,
            token
        ) { completed = true }

        val review = awaitReview(viewModel)
        assertEquals(ImageReviewSource.CAMERA, review.source)
        assertEquals(2, viewModel.draft.value.images.size)
        assertTrue(completed)
        val newImage = viewModel.draft.value.images.single { it.id != olderImage.id }
        assertTrue(File(newImage.path).exists())
        assertTrue(File(requireNotNull(newImage.sourcePath)).exists())

        viewModel.discardPendingImageReviewImages()

        assertNull(viewModel.pendingImageReview.value)
        assertEquals(listOf(olderImage.id), viewModel.draft.value.images.map { it.id })
        assertTrue(File(olderImage.path).exists())
        assertFalse(File(newImage.path).exists())
        assertFalse(File(requireNotNull(newImage.sourcePath)).exists())
    }

    @Test
    fun multiGalleryReselectRemovesEveryNewFileButPreservesOlderDraftImage() {
        val viewModel = MainViewModel(app)
        viewModel.addImages(listOf(Uri.fromFile(imageFile("older-gallery.jpg"))))
        val older = awaitReview(viewModel).let { viewModel.draft.value.images.single() }
        viewModel.useOriginalPendingImageReview()

        viewModel.addImages(
            listOf(
                Uri.fromFile(imageFile("new-gallery-1.jpg")),
                Uri.fromFile(imageFile("new-gallery-2.jpg"))
            )
        )
        val review = awaitReview(viewModel)
        val newImages = viewModel.draft.value.images.filter { it.id != older.id }
        assertEquals(ImageReviewSource.GALLERY, review.source)
        assertEquals(2, review.imageIds.size)
        assertEquals(2, newImages.size)

        viewModel.discardPendingImageReviewImages()

        assertEquals(listOf(older.id), viewModel.draft.value.images.map { it.id })
        assertTrue(File(older.path).exists())
        newImages.forEach {
            assertFalse(File(it.path).exists())
            assertFalse(File(requireNotNull(it.sourcePath)).exists())
        }
    }

    @Test
    fun clearingAParkedDraftRemovesBothImageCopies() = runBlocking {
        val viewModel = MainViewModel(app)
        viewModel.addImages(listOf(Uri.fromFile(imageFile("parked.jpg"))))
        val image = awaitReview(viewModel).let { viewModel.draft.value.images.single() }
        viewModel.useOriginalPendingImageReview()

        val prepared = viewModel.prepareNextQuestion("fictional-question")
        assertTrue(prepared.success)
        assertNotNull(viewModel.parkedDraft.value)

        viewModel.clearParkedDraft()

        assertNull(viewModel.parkedDraft.value)
        assertFalse(File(image.path).exists())
        assertFalse(File(requireNotNull(image.sourcePath)).exists())
    }

    private fun awaitReview(viewModel: MainViewModel): PendingImageReview {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            viewModel.pendingImageReview.value?.let { return it }
            Thread.sleep(10)
        }
        assertNotNull("Image import did not finish", viewModel.pendingImageReview.value)
        return requireNotNull(viewModel.pendingImageReview.value)
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
