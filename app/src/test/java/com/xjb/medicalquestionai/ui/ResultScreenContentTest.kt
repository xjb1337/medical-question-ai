package com.xjb.medicalquestionai.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import com.xjb.medicalquestionai.MainViewModel
import com.xjb.medicalquestionai.data.AnalysisResult
import com.xjb.medicalquestionai.data.AppDatabase
import com.xjb.medicalquestionai.data.QuestionEntity
import com.xjb.medicalquestionai.data.SeenOption
import com.xjb.medicalquestionai.data.update.UpdateViewModel
import com.xjb.medicalquestionai.ui.theme.MedicalQuestionAiTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Production result UI with local fictional fixtures only. No API or real exam content. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi", shadows = [AuditKeyStore::class])
@OptIn(ExperimentalRoborazziApi::class)
class ResultScreenContentTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val db get() = AppDatabase.get(app)
    private lateinit var nav: NavHostController

    @Before
    fun reset() {
        app.getSharedPreferences("app_settings", 0).edit().clear().commit()
        runBlocking {
            db.questionDao().deleteAll()
            db.followUpDao().deleteAll()
        }
    }

    @After
    fun cleanup() {
        runBlocking {
            db.questionDao().deleteAll()
            db.followUpDao().deleteAll()
        }
    }

    @Test
    fun completedResultShowsRecognizedQuestionOutsideCollapsedDetails() {
        val stem = "急性心肌梗死首选检查是什么？\nA. 胸部 X 线 B. 心电图 C. 血常规 D. 肝功能"
        insert(
            status = "completed",
            result = result(
                status = "answered",
                stem = stem,
                answer = listOf("B"),
                options = listOf(
                    SeenOption("A", "胸部 X 线"),
                    SeenOption("B", "心电图"),
                    SeenOption("C", "血常规"),
                    SeenOption("D", "肝功能")
                )
            )
        )
        openResult()

        compose.onNodeWithText("参考答案").assertIsDisplayed()
        compose.onNodeWithText("识别到的题目").assertIsDisplayed()
        compose.onNodeWithText(stem).assertIsDisplayed()
        compose.onNodeWithTag("recognized-question-card").assertIsDisplayed()
        assertTrue(
            compose.onAllNodes(hasTestTag("capture-completeness-card"))
                .fetchSemanticsNodes().isEmpty()
        )
        capture("result_stem_persistent")
    }

    @Test
    fun multipleQuestionsShowCaptureGuidanceAndRecoveryActions() {
        insert(
            status = "completed",
            result = result(
                status = "needs_confirmation",
                stem = "虚构页面包含两道题，当前只能确认其中一道。",
                answer = listOf("B"),
                options = listOf(
                    SeenOption("A", "常规心电图"),
                    SeenOption("B", "胸部 X 线")
                ),
                questionCount = 2
            )
        )
        openResult()

        compose.onNodeWithTag("result-list")
            .performScrollToNode(hasTestTag("capture-completeness-card"))
        compose.onNodeWithTag("capture-completeness-card").assertIsDisplayed()
        compose.onNodeWithText("检测到画面中可能有 2 道题", substring = true).assertIsDisplayed()
        compose.onNodeWithText("只保留一道题", substring = true).assertIsDisplayed()

        compose.onNodeWithTag("result-list").performScrollToNode(hasText("修改题干"))
        compose.onNodeWithText("修改题干").assertIsDisplayed()
        compose.onNodeWithText("补充图片").assertIsDisplayed()
        compose.onNodeWithText("重新解析").assertIsDisplayed()
    }

    @Test
    fun truncatedPageShowsCaptureGuidance() {
        insert(
            status = "completed",
            result = result(
                status = "needs_confirmation",
                stem = "虚构题干最后一行被截断，选项可能没有拍全。",
                answer = listOf("A"),
                options = listOf(
                    SeenOption("A", "常规心电图"),
                    SeenOption("B", "胸部 X 线")
                ),
                pageComplete = false
            )
        )
        openResult()

        compose.onNodeWithTag("result-list")
            .performScrollToNode(hasTestTag("capture-completeness-card"))
        compose.onNodeWithTag("capture-completeness-card").assertIsDisplayed()
        compose.onNodeWithText("题干或选项可能被截断、拍漏", substring = true).assertIsDisplayed()
        compose.onNodeWithText("补拍或裁剪完整页面", substring = true).assertIsDisplayed()
    }

    @Test
    fun needsConfirmationKeepsTheFullLongQuestionStemVisible() {
        val stem = "病历摘要：一名虚构患者因胸痛就诊。\n" +
            "心电图提示相邻导联 ST 段抬高，心肌损伤标志物升高。\n" +
            "以下关于该题最可能的诊断与首选检查，说法正确的是哪一项？"
        insert(
            status = "completed",
            result = result(
                status = "needs_confirmation",
                stem = stem,
                answer = listOf("B"),
                confidence = "low",
                options = listOf(
                    SeenOption("A", "胸部 X 线"),
                    SeenOption("B", "心电图"),
                    SeenOption("C", "血常规"),
                    SeenOption("D", "肝功能")
                )
            )
        )
        openResult()

        compose.onNodeWithText("需要核对").assertIsDisplayed()
        compose.onNodeWithTag("result-list").performScrollToNode(hasText(stem))
        compose.onNodeWithText(stem).assertIsDisplayed()
    }

    @Test
    fun answerOutsideSeenOptionsIsCalledOutInTheComparisonCard() {
        val stem = "急性心肌梗死首选检查是什么？\nA. 胸部 X 线 B. 心电图"
        insert(
            status = "completed",
            result = result(
                status = "needs_confirmation",
                stem = stem,
                answer = listOf("C"),
                options = listOf(
                    SeenOption("A", "胸部 X 线"),
                    SeenOption("B", "心电图")
                )
            )
        )
        openResult()

        compose.onNodeWithTag("result-list")
            .performScrollToNode(hasText("不在识别到的选项中", substring = true))
        compose.onNodeWithText("答案 C 不在识别到的选项中，请核对。").assertIsDisplayed()
    }

    @Test
    fun failedAnalysisStillShowsCheckpointPreviewWithoutFormalResultActions() {
        val stem = "急性心肌梗死首选检查是什么？\nA. 胸部 X 线 B. 心电图 C. 血常规"
        insert(
            status = "failed",
            result = null,
            checkpoint = result(
                status = "needs_confirmation",
                stem = stem,
                answer = listOf("B"),
                confidence = "low",
                options = listOf(
                    SeenOption("A", "胸部 X 线"),
                    SeenOption("B", "心电图"),
                    SeenOption("C", "血常规")
                )
            )
        )
        openResult()

        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("未完成预览", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("解析失败").assertIsDisplayed()
        compose.onNodeWithText("未完成预览", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("result-list").performScrollToNode(hasText(stem))
        compose.onNodeWithText(stem).assertIsDisplayed()
        capture("result_failed_checkpoint")

        assertTrue(compose.onAllNodes(hasText("复制答案")).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText("分享")).fetchSemanticsNodes().isEmpty())
        assertTrue(
            compose.onAllNodes(hasText("解析完成后可继续追问…", substring = true))
                .fetchSemanticsNodes().isEmpty()
        )
    }

    private fun openResult() {
        val viewModel = MainViewModel(app)
        val updateViewModel = UpdateViewModel(app)
        compose.setContent {
            CompositionLocalProvider(LocalContext provides AuditContext(compose.activity)) {
                MedicalQuestionAiTheme {
                    nav = rememberNavController()
                    MedicalQuestionApp(viewModel, updateViewModel, nav)
                }
            }
        }
        compose.waitUntil(15_000) { nav.currentDestination != null }
        compose.runOnUiThread { nav.navigate("result/$QUESTION_ID") }
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("result-list")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun insert(
        status: String,
        result: AnalysisResult?,
        checkpoint: AnalysisResult? = null
    ) {
        runBlocking {
            db.questionDao().insert(
                QuestionEntity(
                    id = QUESTION_ID,
                    createdAt = 1_793_664_000_000L,
                    updatedAt = 1_793_664_000_000L,
                    questionType = "single",
                    source = "text",
                    questionText = "虚构题干",
                    imagePaths = "[]",
                    status = status,
                    answerJson = result?.toJson(),
                    model = "deepseek-flash",
                    errorMessage = if (status == "failed") "网络连接中断，请重试。" else null,
                    isFavorite = false,
                    checkpointJson = checkpoint?.toJson()
                )
            )
        }
    }

    private fun result(
        status: String,
        stem: String,
        answer: List<String>,
        options: List<SeenOption>,
        confidence: String = "high",
        questionCount: Int = 1,
        pageComplete: Boolean = true
    ): AnalysisResult = AnalysisResult(
        status = status,
        questionType = "single",
        recognizedQuestion = stem,
        questionCount = questionCount,
        pageComplete = pageComplete,
        optionsSeen = options,
        answer = answer,
        answerText = "心电图是首选检查。",
        confidence = confidence,
        warnings = if (status == "needs_confirmation") listOf("解析尚未完成，请核对") else emptyList()
    )

    private fun capture(name: String) {
        val dir = File(System.getenv("UIUX_SHOT_DIR") ?: "build/uiux-after")
        check(dir.exists() || dir.mkdirs()) { "Cannot create screenshot directory: $dir" }
        compose.onRoot().captureRoboImage(
            filePath = dir.resolve("$name.png").absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)
        )
    }

    private companion object {
        const val QUESTION_ID = "result-content-fixture"
    }
}
