package com.xjb.medicalquestionai

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xjb.medicalquestionai.ui.components.QuestionImageViewer
import java.io.File
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuestionImageViewerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val files = mutableListOf<File>()

    @After fun cleanFiles() { files.forEach { it.delete() } }

    @Test fun localImagesZoomResetNavigateAndClose() {
        val paths = (1..2).map { index ->
            val file = File(compose.activity.cacheDir, "image-viewer-test-$index.png")
            files.add(file)
            val bitmap = Bitmap.createBitmap(600, 900, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (index == 1) android.graphics.Color.BLUE else android.graphics.Color.GREEN)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            file.absolutePath
        }
        val open = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (open.value) QuestionImageViewer(paths, onDismiss = { open.value = false })
            }
        }
        compose.onNodeWithText("原题图片 1/2").assertIsDisplayed()
        compose.onNodeWithContentDescription("放大").performClick()
        compose.onNodeWithText("150%").assertIsDisplayed()
        compose.onNodeWithContentDescription("还原").performClick()
        compose.onNodeWithText("100%").assertIsDisplayed()
        compose.onNodeWithContentDescription("放大查看的原题图片").performTouchInput { doubleClick() }
        compose.onNodeWithText("300%").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一张").performClick()
        compose.onNodeWithText("原题图片 2/2").assertIsDisplayed()
        compose.onNodeWithText("100%").assertIsDisplayed()
        compose.onNodeWithContentDescription("上一张").performClick()
        compose.onNodeWithText("原题图片 1/2").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭图片查看").performClick()
        compose.onNodeWithText("原题图片 1/2").assertDoesNotExist()
    }

    @Test fun missingImageShowsAnErrorAndCanClose() {
        compose.setContent {
            MaterialTheme { QuestionImageViewer(listOf("/missing/question.png"), onDismiss = {}) }
        }
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("图片无法读取，可能已被删除"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("图片无法读取，可能已被删除").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭图片查看").assertIsDisplayed()
    }
}
