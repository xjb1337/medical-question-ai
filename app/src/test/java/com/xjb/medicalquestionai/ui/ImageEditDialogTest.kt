package com.xjb.medicalquestionai.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.Coil
import coil.request.ImageRequest
import com.github.takahirom.roborazzi.*
import com.xjb.medicalquestionai.data.DraftImage
import com.xjb.medicalquestionai.ui.components.rememberFileImageRequest
import com.xjb.medicalquestionai.ui.screens.CropGeometry
import com.xjb.medicalquestionai.ui.screens.CropRectKey
import com.xjb.medicalquestionai.ui.screens.ImageEditDialog
import com.xjb.medicalquestionai.ui.theme.MedicalQuestionAiTheme
import java.io.File
import java.time.Duration
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
@OptIn(ExperimentalRoborazziApi::class)
class ImageEditDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val crops = mutableListOf<Pair<RectF, Boolean>>()
    private val rotations = mutableListOf<Int>()
    private var moves = 0
    private var deletes = 0
    private var dismisses = 0

    @Test
    fun editorButtonsAreVisibleAndInvokeTheirActions() {
        render()
        assertNormalButtons()
        capture("editor-normal")
        compose.onNodeWithText("前移").performClick()
        compose.onNodeWithText("后移").performClick()
        compose.onNodeWithText("左转").performClick()
        compose.onNodeWithText("右转").performClick()
        assertEquals(2, moves)
        assertEquals(listOf(-90, 90), rotations)
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("删除这张图片？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assertEquals(0, deletes)
        compose.onNodeWithText("删除").performClick()
        compose.onAllNodesWithText("删除").onLast().performClick()
        assertEquals(1, deletes)
        compose.onNodeWithText("完成").performClick()
        assertEquals(1, dismisses)
    }

    @Test
    fun multiStepCornerDragsAccumulateAndHitTestTheLatestRect() {
        render()
        compose.onNodeWithText("裁剪").performClick()
        assertCropButtons()
        val initial = currentRect()
        capture("crop-before")
        dragBottomRight(0.24f)
        val afterFirst = currentRect()
        assertTrue(afterFirst.right < initial.right - 0.15f)
        assertTrue(afterFirst.bottom < initial.bottom - 0.15f)
        assertEquals(initial.left, afterFirst.left, 0.001f)
        dragBottomRight(0.16f)
        val afterSecond = currentRect()
        assertTrue(afterSecond.right < afterFirst.right - 0.1f)
        assertTrue(afterSecond.bottom < afterFirst.bottom - 0.1f)
        assertEquals(initial.left, afterSecond.left, 0.001f)
        assertEquals(initial.top, afterSecond.top, 0.001f)
        capture("crop-after")
        compose.onNodeWithText("保留原图并添加").performClick()
        assertEquals(1, crops.size)
        assertTrue(crops.single().second)
        assertEquals(afterSecond.right, crops.single().first.right, 0.001f)
        assertNormalButtons()
    }

    @Test
    fun moveClampsAndResetCancelAndReplaceRemainUsable() {
        render()
        compose.onNodeWithText("裁剪").performClick()
        dragBottomRight(0.3f)
        val resized = currentRect()
        compose.onNodeWithTag("crop-overlay").performTouchInput {
            down(Offset(width * resized.center.x, height * resized.center.y))
            repeat(6) { moveBy(Offset(width * 0.1f, height * 0.1f)) }
            up()
        }
        val moved = currentRect()
        assertEquals(resized.width, moved.width, 0.001f)
        assertTrue(moved.left > resized.left + 0.2f)
        assertEquals(1f, moved.right, 0.001f)
        assertEquals(1f, moved.bottom, 0.001f)
        compose.onNodeWithText("重置").performClick()
        assertEquals(CropGeometry.defaultRect, currentRect())
        compose.onNodeWithText("取消").performClick()
        assertTrue(crops.isEmpty())
        compose.onNodeWithText("裁剪").performClick()
        dragBottomRight(0.2f)
        val replacement = currentRect()
        compose.onNodeWithText("替换当前图").performClick()
        assertFalse(crops.single().second)
        assertEquals(replacement.bottom, crops.single().first.bottom, 0.001f)
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-xxhdpi")
    fun narrowScreenWithLargeFontKeepsEveryButtonVisibleAndClickable() {
        render(fontScale = 1.3f)
        val editor = compose.onNodeWithTag("image-editor").fetchSemanticsNode()
        assertEquals(360f, editor.boundsInRoot.width / compose.density.density, 1f)
        assertNormalButtons()
        capture("editor-narrow-font130")
        compose.onNodeWithText("裁剪").performClick()
        assertCropButtons()
        capture("crop-narrow-font130")
        dragBottomRight(0.2f)
        compose.onNodeWithText("重置").performClick()
        assertEquals(CropGeometry.defaultRect, currentRect())
        compose.onNodeWithText("替换当前图").performClick()
        assertFalse(crops.single().second)
        compose.onNodeWithText("裁剪").performClick()
        compose.onNodeWithText("保留原图并添加").performClick()
        assertTrue(crops.last().second)
    }

    @Test
    fun explicitRevisionInvalidatesBothCacheKeysWhenFileMetadataDoesNotChange() {
        val image = fixtureImage()
        val revision = mutableStateOf(0L)
        var request: ImageRequest? = null
        compose.setContent {
            val current = rememberFileImageRequest(image.path, revision.value)
            SideEffect { request = current }
        }
        compose.waitForIdle()
        val initial = requireNotNull(request)
        compose.runOnIdle { revision.value++ }
        compose.waitForIdle()
        assertNotEquals(initial.memoryCacheKey, requireNotNull(request).memoryCacheKey)
        assertNotEquals(initial.diskCacheKey, requireNotNull(request).diskCacheKey)
        assertEquals(initial.data, requireNotNull(request).data)
    }

    private fun render(fontScale: Float = 1f) {
        val image = fixtureImage()
        val context = compose.activity
        var imageRequest: ImageRequest? = null
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides AuditContext(context),
                LocalDensity provides Density(LocalDensity.current.density, fontScale)
            ) {
                val request = rememberFileImageRequest(image.path, image.revision)
                SideEffect { imageRequest = request }
                MedicalQuestionAiTheme(darkTheme = false) {
                    ImageEditDialog(
                        image = image, index = 2, enableMoveLeft = true, enableMoveRight = true,
                        onDismiss = { dismisses++ }, onMoveLeft = { moves++ }, onMoveRight = { moves++ },
                        onRotate = { rotations.add(it) },
                        onCrop = { rect, keepOriginal -> crops.add(rect to keepOriginal) },
                        onDelete = { deletes++ }
                    )
                }
            }
        }
        compose.waitUntil(10_000) {
            imageRequest?.memoryCacheKey?.let { Coil.imageLoader(context).memoryCache?.get(it) } != null
        }
        compose.waitForIdle()
    }

    private fun assertNormalButtons() = assertButtons("前移", "后移", "左转", "右转", "裁剪", "删除", "完成")
    private fun assertCropButtons() = assertButtons("取消", "重置", "替换当前图", "保留原图并添加")

    private fun assertButtons(vararg labels: String) {
        val editorBounds = compose.onNodeWithTag("image-editor").fetchSemanticsNode().boundsInRoot
        labels.forEach { label ->
            val button = compose.onNode(hasText(label) and hasClickAction())
            button.assertIsDisplayed().assertIsEnabled()
            val bounds = button.fetchSemanticsNode().boundsInRoot
            assertTrue("$label must fit inside the editor: $bounds", editorBounds.contains(bounds.topLeft) && editorBounds.contains(bounds.bottomRight))
            assertTrue("$label must have a 48dp touch target", bounds.height / compose.density.density >= 48f)
        }
    }

    private fun currentRect(): Rect = compose.onNodeWithTag("crop-overlay").fetchSemanticsNode().config[CropRectKey]

    private fun dragBottomRight(distance: Float) {
        val rect = currentRect()
        compose.onNodeWithTag("crop-overlay").performTouchInput {
            down(Offset(width * rect.right, height * rect.bottom))
            repeat(6) { moveBy(Offset(-width * distance / 6, -height * distance / 6)) }
            up()
        }
        compose.waitForIdle()
    }

    private fun capture(name: String) {
        val directory = File(System.getenv("CROP_SHOT_DIR") ?: "build/crop-after").apply { mkdirs() }
        fun record() = compose.onNode(isDialog()).captureRoboImage(
            filePath = directory.resolve("$name.png").absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)
        )
        record()
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        record()
    }

    private fun fixtureImage(): DraftImage {
        val file = File(compose.activity.cacheDir, "editor-fixture.jpg")
        val bitmap = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(250, 249, 243))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(23, 43, 58); textSize = 38f }
        canvas.drawText("MEDICAL STUDY / 02", 80f, 130f, paint)
        paint.textSize = 30f
        listOf("Which structure is the basic functional", "unit of skeletal muscle contraction?", "", "A. Muscle fiber", "B. Sarcomere", "C. Fascicle", "D. Tendon").forEachIndexed { index, text ->
            canvas.drawText(text, 80f, 250f + index * 75f, paint)
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return DraftImage("fixture", file.absolutePath, 1000, 1400, file.length())
    }
}
