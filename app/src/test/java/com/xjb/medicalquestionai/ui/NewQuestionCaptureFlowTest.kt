package com.xjb.medicalquestionai.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjb.medicalquestionai.MainViewModel
import com.xjb.medicalquestionai.ui.screens.NewQuestionScreen
import com.xjb.medicalquestionai.ui.theme.MedicalQuestionAiTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi", shadows = [AuditKeyStore::class])
class NewQuestionCaptureFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun importedImageIsDirectlyUsableWithoutCropPopupAndAllowsOptionalCrop() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = MainViewModel(app)
        val source = File(app.cacheDir, "direct-use-${System.nanoTime()}.jpg").apply {
            val bitmap = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
        }

        compose.setContent {
            CompositionLocalProvider(LocalContext provides AuditContext(compose.activity)) {
                MedicalQuestionAiTheme(darkTheme = false) {
                    NewQuestionScreen(
                        viewModel = viewModel,
                        navController = rememberNavController()
                    )
                }
            }
        }
        compose.waitForIdle()

        val captureFile = File(app.cacheDir, "captures/camera_direct.jpg").apply {
            parentFile?.mkdirs()
            source.copyTo(this, overwrite = true)
        }
        val token = viewModel.beginCameraCapture()
        compose.runOnIdle {
            viewModel.addCameraImage(
                Uri.fromFile(captureFile),
                captureFile,
                viewModel.draft.value.sessionId,
                token
            ) {}
        }

        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && viewModel.draft.value.images.isEmpty()) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        compose.waitForIdle()

        assertTrue(viewModel.draft.value.images.size == 1)
        val imageId = viewModel.draft.value.images.single().id

        // No post-import crop prompt pops up automatically.
        assertTrue(
            compose.onAllNodesWithTag("post-import-review").fetchSemanticsNodes().isEmpty()
        )
        compose.onNodeWithText("开始解析").assertIsDisplayed()

        // Tapping the card's crop button opens the crop editor only when requested.
        compose.onNodeWithTag("draft-crop-$imageId").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("crop-overlay")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("crop-overlay").assertIsDisplayed()

        // Cancelling direct crop returns cleanly to the draft screen.
        compose.onNodeWithText("取消").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("image-editor")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("开始解析").assertIsDisplayed()
    }
}
