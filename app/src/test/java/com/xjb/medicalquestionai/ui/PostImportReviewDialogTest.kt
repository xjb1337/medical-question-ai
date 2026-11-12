package com.xjb.medicalquestionai.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.xjb.medicalquestionai.data.DraftImage
import com.xjb.medicalquestionai.data.ImageReviewSource
import com.xjb.medicalquestionai.ui.screens.PostImportReviewDialog
import com.xjb.medicalquestionai.ui.theme.MedicalQuestionAiTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class PostImportReviewDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val croppedIds = mutableListOf<String>()
    private var usedOriginal = 0
    private var retaken = 0
    private var dismissed = 0

    @Test
    fun cameraImportOffersCropOriginalAndRetake() {
        val image = image("camera-image")
        render(listOf(image), ImageReviewSource.CAMERA)

        compose.onNodeWithText("照片已导入").assertIsDisplayed()
        compose.onNodeWithText("裁剪本题").assertIsDisplayed()
        compose.onNodeWithText("使用原图").assertIsDisplayed()
        compose.onNodeWithText("重拍").assertIsDisplayed()
        capture("post-import-camera")

        compose.onNodeWithText("使用原图").performClick()
        assertEquals(0, croppedIds.size)
        assertEquals(1, usedOriginal)
    }

    @Test
    fun cropChoiceTargetsTheImportedImageWithoutOpeningNormalEditorFirst() {
        val image = image("camera-image")
        render(listOf(image), ImageReviewSource.CAMERA)

        compose.onNodeWithText("裁剪本题").performClick()
        assertEquals(listOf(image.id), croppedIds)
    }

    @Test
    fun multiGalleryImportUsesReselectAndOffersEachNewImage() {
        val first = image("gallery-first")
        val second = image("gallery-second")
        render(listOf(first, second), ImageReviewSource.GALLERY)

        compose.onNodeWithText("图片已导入").assertIsDisplayed()
        compose.onNodeWithText("本次导入 2 张，可逐张裁剪。").assertIsDisplayed()
        compose.onNodeWithText("重选").assertIsDisplayed()
        compose.onAllNodesWithText("裁剪这张").assertCountEquals(2)
        capture("post-import-gallery-multiple")

        compose.onNodeWithTag("review-crop-${second.id}").performClick()
        assertEquals(listOf(second.id), croppedIds)
    }

    @Test
    fun retakeAndDismissCallbacksAreDistinct() {
        render(listOf(image("camera-image")), ImageReviewSource.CAMERA)
        compose.onNodeWithText("重拍").performClick()
        assertEquals(1, retaken)
        assertTrue(dismissed == 0)
    }

    private fun render(images: List<DraftImage>, source: ImageReviewSource) {
        compose.setContent {
            CompositionLocalProvider(LocalContext provides AuditContext(compose.activity)) {
                MedicalQuestionAiTheme(darkTheme = false) {
                    PostImportReviewDialog(
                        images = images,
                        source = source,
                        onCropImage = { croppedIds += it },
                        onUseOriginal = { usedOriginal++ },
                        onRetakeOrReselect = { retaken++ },
                        onDismiss = { dismissed++ }
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun image(id: String) = DraftImage(
        id = id,
        path = "/tmp/$id.jpg",
        width = 1000,
        height = 1400,
        sizeBytes = 100
    )

    private fun capture(name: String) {
        val directory = File(System.getenv("UIUX_SHOT_DIR") ?: "build/uiux-after").apply { mkdirs() }
        compose.onNode(isDialog()).captureRoboImage(
            filePath = directory.resolve("$name.png").absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)
        )
    }
}
