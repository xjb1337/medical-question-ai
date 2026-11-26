package com.xjb.medicalquestionai

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xjb.medicalquestionai.data.SecureKeyStore
import com.xjb.medicalquestionai.ui.components.DeepSeekGuideLink
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** No real keys, billing, login, or model requests. Browser intents are intercepted. */
@RunWith(AndroidJUnit4::class)
class DeepSeekBindingGuideTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun openUnconfiguredSettings() {
        // Never remove or replace a key that belongs to the device owner.
        assumeFalse(SecureKeyStore(instrumentation.targetContext).getApiKey() != null)
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("DeepSeek 首次绑定教程").assertIsDisplayed()
    }

    @Test fun firstBindingShortcutFocusesKeyAndCollapseSurvivesRecreation() {
        step(1).performScrollTo().assertIsDisplayed()
        screenshot("guide-start")
        compose.onNodeWithTag("guide-existing-key").performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and hasText("粘贴 API 密钥")).assertIsFocused()
        screenshot("key-entry")
        compose.onNode(hasSetTextAction() and hasText("粘贴 API 密钥"))
            .performTextInput("synthetic-unsaved-key")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("查看详细绑定步骤").performScrollTo().assertIsDisplayed()
        // Tutorial state survives; plaintext draft keys deliberately do not.
        compose.onNode(hasSetTextAction() and hasText("粘贴 API 密钥"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertNull(SecureKeyStore(instrumentation.targetContext).getApiKey())
        compose.onNodeWithText("查看详细绑定步骤").performClick()
        step(1).performScrollTo().assertIsDisplayed()
    }

    @Test fun officialLinksUseBrowserAndKeepTutorialAvailable() {
        val intents = mutableListOf<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_VIEW) return null
                intents.add(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            val buttons = listOf(
                Triple(1, "打开官方平台 / 登录", DeepSeekGuideLink.PLATFORM),
                Triple(2, "查看余额 / 充值", DeepSeekGuideLink.BALANCE),
                Triple(3, "打开 API keys 页面", DeepSeekGuideLink.API_KEYS),
                Triple(5, "查看官方使用文档", DeepSeekGuideLink.DOCUMENTATION)
            )
            buttons.forEach { (index, label, link) ->
                if (index != 1) step(index).performScrollTo().performClick()
                compose.onNodeWithText(label).performScrollTo().performClick()
                compose.waitForIdle()
                assertEquals(link.url, intents.last().dataString)
                assertEquals(setOf(Intent.CATEGORY_BROWSABLE), intents.last().categories)
                assertNull(intents.last().extras)
            }
            assertEquals(4, intents.size)
            step(4).performScrollTo().performClick()
            screenshot("guide-return")
            compose.onNodeWithText("我已复制，去粘贴密钥").performScrollTo().performClick()
            compose.onNode(hasSetTextAction() and hasText("粘贴 API 密钥")).assertIsFocused()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun expandedTutorialAndScrollSurviveActivityRecreation() {
        step(3).performScrollTo().performClick()
        compose.onNodeWithText("打开 API keys 页面").performScrollTo().assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("打开 API keys 页面").assertIsDisplayed()
        step(5).performScrollTo().assertIsDisplayed()
        screenshot("guide-checks")
    }

    private fun step(index: Int) = compose.onNodeWithTag("guide-step-$index")

    @Test fun unavailableBrowserOffersCopyableOfficialAddress() {
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_VIEW) throw ActivityNotFoundException("test: no browser")
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithText("打开官方平台 / 登录").performScrollTo().performClick()
            compose.onNodeWithText("暂时无法打开浏览器").assertIsDisplayed()
            compose.onNodeWithText(DeepSeekGuideLink.PLATFORM.url).assertIsDisplayed()
            compose.onNodeWithText("复制官方地址").performClick()
            compose.onNodeWithText("暂时无法打开浏览器").assertDoesNotExist()
            compose.onNodeWithText("打开官方平台 / 登录").assertIsDisplayed()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        // UiAutomation captures the display; allow the next display frame after recomposition.
        android.os.SystemClock.sleep(250)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "key-guide/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
