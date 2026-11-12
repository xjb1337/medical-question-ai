package com.xjb.medicalquestionai.ui

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.*
import com.xjb.medicalquestionai.MainViewModel
import com.xjb.medicalquestionai.data.*
import com.xjb.medicalquestionai.data.update.UpdateViewModel
import com.xjb.medicalquestionai.ui.components.AppTopBar
import com.xjb.medicalquestionai.ui.screens.ResultLoadingSkeletonPage
import com.xjb.medicalquestionai.ui.theme.MedicalQuestionAiTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.*
import java.io.File

/** Native Robolectric rendering of production screens. No emulator, camera or API calls. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi", shadows = [AuditKeyStore::class])
@OptIn(ExperimentalRoborazziApi::class)
class UiUxScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun homeLight() = render("home", false)
    @Test fun homeDark() = render("home", true)
    @Test fun cameraLight() = render("camera", false)
    @Test fun cameraDark() = render("camera", true)
    @Test fun resultLight() = render("result", false)
    @Test fun resultDark() = render("result", true)
    @Test fun errorLight() = render("error", false)
    @Test fun errorDark() = render("error", true)
    @Test fun loadingLight() = renderLoading(false)
    @Test fun loadingDark() = renderLoading(true)
    @Test fun historyLight() = render("history", false)
    @Test fun historyDark() = render("history", true)
    @Test fun emptyLight() = render("empty", false)
    @Test fun emptyDark() = render("empty", true)
    @Test fun settingsLight() = render("settings", false)
    @Test fun settingsDark() = render("settings", true)
    @Test fun guideLight() = render("guide", false)
    @Test fun guideDark() = render("guide", true)

    private fun renderLoading(dark: Boolean) {
        compose.setContent {
            MedicalQuestionAiTheme(darkTheme = dark) {
                Scaffold(
                    topBar = { AppTopBar(title = "题目解析") },
                    containerColor = MaterialTheme.colorScheme.background
                ) { padding ->
                    ResultLoadingSkeletonPage(
                        modifier = Modifier.padding(padding),
                        statusText = "正在加载解析"
                    )
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("通常需要 10–30 秒", substring = true).assertIsDisplayed()
        capture("loading", dark)
    }

    private fun render(screen: String, dark: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        app.getSharedPreferences("app_settings", 0).edit().clear().commit()
        val db = AppDatabase.get(app)
        runBlocking {
            db.questionDao().deleteAll()
            db.followUpDao().deleteAll()
            if (screen in listOf("result", "history", "error")) {
                db.questionDao().insert(sample("sample", if (screen == "error") "failed" else "completed"))
                if (screen == "history") {
                    db.questionDao().insert(sample("failed", "failed", 1))
                    db.questionDao().insert(sample("cancelled", "cancelled", 2))
                }
            }
        }
        val vm = MainViewModel(app)
        val updateVm = UpdateViewModel(app)
        val route = when (screen) {
            "result", "error" -> "result/sample"
            "empty" -> "history"
            "guide" -> "settings"
            "camera" -> "camera?source=home"
            else -> screen
        }
        lateinit var nav: NavHostController
        compose.setContent {
            val auditContext = android.content.ContextWrapper(compose.activity).let {
                AuditContext(it)
            }
            CompositionLocalProvider(LocalContext provides auditContext) {
                MedicalQuestionAiTheme(darkTheme = dark) {
                    nav = rememberNavController()
                    MedicalQuestionApp(vm, updateVm, nav)
                }
            }
        }
        compose.waitUntil(15_000) { nav.currentDestination != null }
        compose.mainClock.autoAdvance = false
        if (route != "home") {
            compose.runOnUiThread { nav.navigate(route) }
            compose.mainClock.advanceTimeBy(1_000)
        }
        val readyText = when (screen) {
            "result" -> "识别到的题目"
            "error" -> "重新解析"
            "history" -> "文本题目示例：骨骼肌收缩的基本单位是什么？"
            "empty" -> "还没有历史记录"
            "settings", "guide" -> "外观"
            "camera" -> "允许使用相机"
            else -> "拍照解题"
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText(readyText, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        if (screen == "guide") {
            compose.mainClock.autoAdvance = true
            compose.onAllNodes(hasScrollAction()).onFirst()
                .performScrollToNode(hasText("DeepSeek 首次绑定教程"))
            compose.waitForIdle()
            compose.mainClock.autoAdvance = false
        }
        capture(screen, dark)
    }

    private fun capture(screen: String, dark: Boolean) {
        val dir = File(System.getenv("UIUX_SHOT_DIR") ?: "build/uiux-after")
        check(dir.exists() || dir.mkdirs()) { "Cannot create screenshot directory: $dir" }
        compose.onRoot().captureRoboImage(
            filePath = dir.resolve("${screen}_${if (dark) "dark" else "light"}.png").absolutePath,
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)
        )
    }

    private fun sample(id: String, status: String, offset: Int = 0): QuestionEntity {
        val text = "文本题目示例：骨骼肌收缩的基本单位是什么？"
        val result = AnalysisResult(
            status = "answered", questionType = "single", recognizedQuestion = text,
            answer = listOf("B"), answerText = "肌节",
            optionsSeen = listOf(
                SeenOption("A", "肌纤维"),
                SeenOption("B", "肌节"),
                SeenOption("C", "肌束")
            ),
            optionAnalyses = listOf(
                OptionAnalysis("A", "错误", "肌纤维由许多肌原纤维构成。"),
                OptionAnalysis("B", "正确", "肌节是相邻两条 Z 线之间的结构。"),
                OptionAnalysis("C", "错误", "肌束是多条肌纤维的集合。")
            ),
            reasoningSteps = listOf(ReasoningStep("定位结构层级", "从肌纤维到肌原纤维，再到重复排列的肌节。")),
            keyPoints = listOf("肌节由粗、细肌丝组成，是收缩的基本单位。"),
            confidence = "high"
        )
        return QuestionEntity(
            id = id, createdAt = 1793664000000L - offset * 86400000L,
            updatedAt = 1793664000000L, questionType = "single", source = "text",
            questionText = text, imagePaths = "[]", status = status,
            answerJson = if (status == "completed") result.toJson() else null,
            model = "deepseek-flash", errorMessage = if (status == "failed") "网络连接超时，请检查网络后重试。" else null,
            isFavorite = offset == 0
        )
    }
}

/** Material 3's library-local string IDs are not remapped by the direct Robolectric runner. */
internal class AuditContext(base: Context) : ContextWrapper(base) {
    private val auditResources = AuditResources(base.resources)
    override fun getResources(): Resources = auditResources
}

private class AuditResources(base: Resources) : Resources(
    base.assets,
    base.displayMetrics,
    base.configuration,
) {
    override fun getString(id: Int): String = runCatching { super.getString(id) }.getOrElse { "" }
    override fun getString(id: Int, vararg formatArgs: Any?): String =
        runCatching { super.getString(id, *formatArgs) }.getOrElse { "" }
    override fun getText(id: Int): CharSequence = runCatching { super.getText(id) }.getOrElse { "" }
}

/** Never access a real key or AndroidKeyStore in screenshot fixtures. */
@Implements(SecureKeyStore::class)
class AuditKeyStore {
    @Implementation fun getApiKey(): String? = null
    @Implementation fun saveApiKey(key: String) = Unit
    @Implementation fun clearApiKey() = Unit
}
