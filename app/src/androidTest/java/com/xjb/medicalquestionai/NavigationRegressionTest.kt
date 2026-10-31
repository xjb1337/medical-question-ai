package com.xjb.medicalquestionai

import android.Manifest
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xjb.medicalquestionai.data.AnalysisResult
import com.xjb.medicalquestionai.data.AppDatabase
import com.xjb.medicalquestionai.data.FollowUp
import com.xjb.medicalquestionai.data.FollowUpRepository
import com.xjb.medicalquestionai.data.Question
import com.xjb.medicalquestionai.data.QuestionRepository
import com.xjb.medicalquestionai.ui.MedicalQuestionApp
import com.xjb.medicalquestionai.ui.openDetail
import com.xjb.medicalquestionai.ui.openTopLevel
import com.xjb.medicalquestionai.ui.theme.MedicalQuestionAiTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses local fixtures only; never starts analysis or calls a paid model. */
@RunWith(AndroidJUnit4::class)
class NavigationRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository get() = QuestionRepository(AppDatabase.get(context).questionDao())
    private val followUps get() = FollowUpRepository(AppDatabase.get(context).followUpDao())
    private val fixtureId = "navigation-regression-fixture"
    private lateinit var nav: NavHostController
    private lateinit var model: MainViewModel

    @Before fun setup(): Unit = runBlocking {
        repository.insert(Question(
            id = fixtureId, createdAt = System.currentTimeMillis(), questionType = "single",
            source = "text", questionText = "导航验证样例", imagePaths = emptyList(),
            status = "completed", result = AnalysisResult(questionType = "single", answer = listOf("A")),
            model = "local UI fixture", errorMessage = null, isFavorite = true
        ))
        compose.activityRule.scenario.onActivity { activity ->
            model = ViewModelProvider(activity)[MainViewModel::class.java]
            activity.setContent {
                nav = rememberNavController()
                MedicalQuestionAiTheme { MedicalQuestionApp(viewModel = model, navController = nav) }
            }
        }
        compose.waitForIdle()
    }

    @After fun cleanup(): Unit = runBlocking {
        followUps.deleteForQuestion(fixtureId)
        repository.delete(fixtureId)
        repository.delete("$fixtureId-export")
        repository.delete("$fixtureId-hidden")
    }

    @Test fun repeatedTabShortcutsKeepOneHomeAndSystemBackReturnsHome() {
        repeat(5) {
            compose.runOnIdle { nav.openTopLevel("settings"); nav.openTopLevel("settings") }
            compose.onNodeWithText("学习设置").assertIsDisplayed()
            compose.runOnIdle { nav.openTopLevel("history") }
            compose.onNodeWithText("我的题库").assertIsDisplayed()
            compose.runOnIdle { nav.openTopLevel("home") }
            compose.onNodeWithText("医学拍题").assertIsDisplayed()
            compose.runOnIdle { assertNull(nav.previousBackStackEntry) }
        }
        compose.runOnIdle { nav.openTopLevel("settings") }
        systemBack()
        compose.onNodeWithText("医学拍题").assertIsDisplayed()
        compose.runOnIdle { assertNull(nav.previousBackStackEntry) }
    }

    @Test fun historyDoubleTapAndBothBackActionsReturnToSameSearch() {
        compose.runOnIdle { model.setHistoryQuery("导航验证样例"); nav.openTopLevel("history") }
        val card = hasText("导航验证样例") and !hasSetTextAction()
        compose.waitUntil(5_000) { compose.onAllNodes(card).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(card).performTouchInput { doubleClick() }
        compose.onNodeWithText("题目解析").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("我的题库").assertIsDisplayed()
        compose.runOnIdle { assertEquals("导航验证样例", model.historyQuery.value) }
        compose.onNode(card).performClick()
        compose.onNodeWithText("题目解析").assertIsDisplayed()
        systemBack()
        compose.onNodeWithText("我的题库").assertIsDisplayed()
        compose.runOnIdle { assertEquals("home", nav.previousBackStackEntry?.destination?.route) }
    }

    @Test fun exportIncludesRecordsOutsideSearchButExcludesEphemeralDrafts(): Unit = runBlocking {
        val original = repository.getById(fixtureId)!!
        repository.insert(original.copy(id = "$fixtureId-export", questionText = "另一条导出记录"))
        repository.insert(original.copy(id = "$fixtureId-hidden", questionText = "不应导出的临时记录", isEphemeral = true))
        compose.runOnIdle { model.setHistoryQuery("没有匹配项的搜索") }
        val text = model.exportHistoryText()!!
        assertTrue(text.contains("导航验证样例"))
        assertTrue(text.contains("另一条导出记录"))
        assertFalse(text.contains("不应导出的临时记录"))
    }

    @Test fun longFollowUpHistoryRemainsLazyAndLastMessageCanBeReached(): Unit = runBlocking {
        repeat(100) { index ->
            followUps.insert(FollowUp(
                id = "$fixtureId-$index", questionId = fixtureId, role = "assistant",
                content = "本地追问样例 $index：用于检查长列表渲染，不是真实模型回复。",
                createdAt = System.currentTimeMillis() + index
            ))
        }
        compose.runOnIdle { nav.openDetail("result/$fixtureId") }
        compose.onNodeWithText("题目解析").assertIsDisplayed()
        compose.onNodeWithTag("result-list").performScrollToNode(hasText("继续追问"))
        val last = "本地追问样例 99：用于检查长列表渲染，不是真实模型回复。"
        compose.onNodeWithText(last).assertDoesNotExist()
        compose.onNodeWithTag("result-list").performScrollToNode(hasText(last))
        compose.onNodeWithText(last).assertIsDisplayed()
        systemBack()
        compose.onNodeWithText("医学拍题").assertIsDisplayed()
    }

    @Test fun nextQuestionThenCameraBackPreservesParkedDraftAndRemovesOldResult() {
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        compose.runOnIdle {
            model.updateDraftText("应保留的首页草稿")
            nav.openDetail("result/$fixtureId")
        }
        compose.onNodeWithText("拍摄下一题").performClick()
        compose.waitUntil(5_000) { nav.currentDestination?.route?.startsWith("camera?") == true }
        systemBack()
        compose.onNodeWithText("医学拍题").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("应保留的首页草稿", model.parkedDraft.value?.text)
            assertTrue(model.restoreParkedDraft())
            assertEquals("应保留的首页草稿", model.draft.value.text)
            assertNull(nav.previousBackStackEntry)
        }
    }

    @Test fun toolbarAndSystemBackWaitForOwnedTaskCleanup(): Unit = runBlocking {
        // Synthetic registered jobs exercise cancellation ordering, without a network/model call.
        repeat(2) { mode ->
            val stopped = AtomicBoolean(false)
            val work = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally {
                    withContext(NonCancellable) { delay(200); stopped.set(true) }
                }
            }
            try {
                compose.runOnIdle {
                    setField("analysisJob", work)
                    setField("activeQuestionId", fixtureId)
                    setField("activeRequestId", "synthetic-$mode")
                    @Suppress("UNCHECKED_CAST")
                    val task = readField("_analysisTask") as MutableStateFlow<AnalysisTaskState?>
                    task.value = AnalysisTaskState("synthetic-$mode", fixtureId, AnalysisTaskStatus.RECEIVING, emptyList())
                    nav.openDetail("result/$fixtureId")
                }
                compose.onNodeWithText("题目解析").assertIsDisplayed()
                if (mode == 0) compose.onNodeWithContentDescription("返回").performClick() else systemBack()
                compose.waitUntil(5_000) { nav.currentDestination?.route == "home" }
                assertTrue("Navigation must wait for cancellation cleanup", stopped.get())
            } finally {
                work.cancelAndJoin()
                compose.runOnIdle {
                    setField("analysisJob", null)
                    setField("activeQuestionId", null)
                    setField("activeRequestId", null)
                    @Suppress("UNCHECKED_CAST")
                    val task = readField("_analysisTask") as MutableStateFlow<AnalysisTaskState?>
                    task.value = null
                }
            }
        }
    }

    private fun setField(name: String, value: Any?) {
        MainViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(model, value)
    }

    private fun readField(name: String): Any? =
        MainViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(model)

    private fun systemBack() {
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
