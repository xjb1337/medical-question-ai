package com.xjb.medicalquestionai

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xjb.medicalquestionai.data.AnalysisResult
import com.xjb.medicalquestionai.data.AppDatabase
import com.xjb.medicalquestionai.data.OptionAnalysis
import com.xjb.medicalquestionai.data.Question
import com.xjb.medicalquestionai.data.QuestionRepository
import com.xjb.medicalquestionai.data.ReasoningStep
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator UI coverage only. Seeded answers do not represent a real API response. */
@RunWith(AndroidJUnit4::class)
class UiRefreshSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = QuestionRepository(AppDatabase.get(context).questionDao())
    private val sampleId = "ui-refresh-local-sample"

    @After fun removeSample() = runBlocking { repository.delete(sampleId) }

    @Test fun navigationAndTextEntryRemainUsable() {
        compose.onNodeWithText("医学拍题").assertIsDisplayed()
        screenshot("home")
        compose.onNodeWithText("题库").performClick()
        compose.onNodeWithText("我的题库").assertIsDisplayed()
        screenshot("history-empty")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("学习设置").assertIsDisplayed()
        compose.onNodeWithText("让学习更顺手").assertIsDisplayed()
        screenshot("settings")
        compose.onNodeWithText("拍题").performClick()
        compose.onNodeWithText("展开").performScrollTo().performClick()
        compose.onNode(hasSetTextAction()).performTextInput("UI 验证：可输入完整题干与选项")
        compose.onNodeWithText("UI 验证：可输入完整题干与选项").assertIsDisplayed()
        screenshot("text-keyboard")
        compose.onNode(hasSetTextAction()).performTextClearance()
    }

    @Test fun savedAnswerAndNextCaptureEntryRemainVisible() = runBlocking {
        val title = "UI 验证样例：以下哪项属于生命体征？"
        repository.insert(Question(
            id = sampleId, createdAt = System.currentTimeMillis(), questionType = "single",
            source = "text", questionText = title, imagePaths = emptyList(),
            status = "completed", model = "UI local fixture", errorMessage = null, isFavorite = true,
            result = AnalysisResult(
                questionType = "single", recognizedQuestion = "$title\nA. 体温 B. 血型 C. 身高 D. 体重",
                answer = listOf("A"), answerText = "体温属于常用生命体征。",
                confidence = "high",
                optionAnalyses = listOf(OptionAnalysis("A", "正确", "体温是常用生命体征之一。")),
                reasoningSteps = listOf(ReasoningStep("识别概念", "区分生命体征与其他身体资料。")),
                keyPoints = listOf("此题仅用于界面验证，并非真实模型返回。")
            )
        ))
        compose.onNodeWithText("题库").performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(title, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("history-sample")
        compose.onNodeWithText(title, substring = true).performClick()
        compose.onNodeWithText("参考答案").assertIsDisplayed()
        compose.onNodeWithText("A").assertIsDisplayed()
        compose.onNodeWithText("拍摄下一题").assertIsDisplayed()
        screenshot("result")
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(context.getExternalFilesDir(null), "ui-refresh/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
