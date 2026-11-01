package com.xjb.medicalquestionai.ui.screens

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.xjb.medicalquestionai.MainViewModel
import com.xjb.medicalquestionai.data.SecureKeyStore
import com.xjb.medicalquestionai.ui.theme.MedicalQuestionAiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [34],
    qualifiers = "w390dp-h844dp-xxhdpi",
    shadows = [NewQuestionScreenScrollSecureKeyStore::class]
)
class NewQuestionScreenScrollTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun submitButtonAppearsAfterScrollingDownAndHidesWhenScrollingBackUp() {
        val viewModel = MainViewModel(application())

        composeTestRule.setContent {
            MedicalQuestionAiTheme(darkTheme = false) {
                NewQuestionScreen(
                    viewModel = viewModel,
                    navController = rememberNavController()
                )
            }
        }
        settle()

        // Expanding the empty text field adds enough content while keeping the empty-draft label.
        composeTestRule.onNodeWithText("展开").performClick()
        settle()

        composeTestRule.onNodeWithText("添加题目后开始解析").assertDoesNotExist()

        composeTestRule.onAllNodes(hasScrollAction(), useUnmergedTree = true)
            .get(0)
            .performTouchInput { swipeUp() }
        settle()
        composeTestRule.onNodeWithText("添加题目后开始解析").assertIsDisplayed()

        composeTestRule.onAllNodes(hasScrollAction(), useUnmergedTree = true)
            .get(0)
            .performTouchInput { swipeDown() }
        settle()
        composeTestRule.onNodeWithText("添加题目后开始解析").assertDoesNotExist()
    }

    private fun application(): Application = ApplicationProvider.getApplicationContext()

    private fun settle() {
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.advanceTimeBy(1_000L)
        composeTestRule.waitForIdle()
    }
}

/** Robolectric does not provide AndroidKeyStore; keep this UI fixture key-free. */
@org.robolectric.annotation.Implements(SecureKeyStore::class)
class NewQuestionScreenScrollSecureKeyStore {
    private val preferences
        get() = ApplicationProvider.getApplicationContext<Application>()
            .getSharedPreferences("new_question_scroll_secure_settings", 0)

    @org.robolectric.annotation.Implementation
    fun getApiKey(): String? = preferences.getString("deepseek_api_key", null)?.takeIf { it.isNotBlank() }

    @org.robolectric.annotation.Implementation
    fun saveApiKey(key: String) {
        preferences.edit().putString("deepseek_api_key", key).apply()
    }

    @org.robolectric.annotation.Implementation
    fun clearApiKey() {
        preferences.edit().remove("deepseek_api_key").apply()
    }
}
