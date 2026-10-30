package com.xjb.medicalquestionai

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xjb.medicalquestionai.data.update.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the actual ViewModel and Android preferences, with controlled network/release responses. */
@RunWith(AndroidJUnit4::class)
class UpdateCheckIntegrationTest {
    private val application get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
    private val prefs get() = application.getSharedPreferences("update_preferences", Context.MODE_PRIVATE)
    private var backup: Map<String, *> = emptyMap<String, Any>()
    private val stores = mutableListOf<ViewModelStore>()
    private var online = false
    private var requests = 0
    private var response: UpdateCheckResult = UpdateCheckResult.UpToDate

    @Before fun prepare() { backup = prefs.all.toMap(); prefs.edit().clear().commit() }

    @After fun restore(): Unit = runBlocking {
        withContext(Dispatchers.Main) { stores.forEach { it.clear() } }
        val editor = prefs.edit().clear()
        backup.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
            }
        }
        editor.commit()
        Unit
    }

    private suspend fun model(): UpdateViewModel = withContext(Dispatchers.Main) {
        val store = ViewModelStore().also { stores.add(it) }
        ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                UpdateViewModel(application, { requests++; response }, { online }) as T
        })[UpdateViewModel::class.java]
    }

    private suspend fun check(model: UpdateViewModel, manual: Boolean = false) {
        withContext(Dispatchers.Main) { if (manual) model.checkManually() else model.autoCheckIfDue() }
        withTimeout(5_000) {
            while (model.state.value.status == UpdateStatus.CHECKING) delay(10)
        }
    }

    @Test fun offlineEntryRecoversOnceAndSurvivesRecreation(): Unit = runBlocking {
        val first = model()
        check(first)
        assertEquals(UpdateStatus.FAILED, first.state.value.status)
        assertEquals(0, requests)
        assertNotNull(prefs.getString("pending_network_check_day", null))
        online = true
        val recreated = model()
        check(recreated)
        assertEquals(UpdateStatus.UP_TO_DATE, recreated.state.value.status)
        assertEquals(1, requests)
        repeat(3) { check(recreated) }
        check(model())
        assertEquals(1, requests)
        check(recreated, manual = true)
        assertEquals(2, requests)
    }

    @Test fun failedRecoveryIsBoundedButNextDayChecksAgain(): Unit = runBlocking {
        val model = model()
        check(model)
        online = true
        response = UpdateCheckResult.Failed("test network failure", networkFailure = true)
        check(model)
        assertEquals(1, requests)
        repeat(3) { check(model) }
        check(model())
        assertEquals(1, requests)
        prefs.edit().putString("last_attempt_local_day", "2000-01-01").commit()
        response = UpdateCheckResult.UpToDate
        check(model)
        assertEquals(2, requests)
    }

    @Test fun failureWhileStillOnlineDoesNotConsumeRecoveryAllowance(): Unit = runBlocking {
        online = true
        response = UpdateCheckResult.Failed("server timeout", networkFailure = true)
        val model = model()
        check(model)
        assertNull(prefs.getString("pending_network_check_day", null))
        repeat(3) { check(model) }
        assertEquals(1, requests)
    }

    @Test fun ignoredVersionPersistsAndNewVersionPrompts(): Unit = runBlocking {
        online = true
        fun release(version: Long) = UpdateCheckResult.Available(UpdateRelease(
            UpdateManifest(1, "com.xjb.medicalquestionai", version, "test-$version", 26,
                "medical-question-ai.apk", 1, "a".repeat(64), "b".repeat(64), "c".repeat(40), "", "Test"),
            "https://example.invalid/test.apk", "test-$version"
        ))
        response = release(200000)
        val first = model()
        check(first)
        assertTrue(first.state.value.showPrompt)
        withContext(Dispatchers.Main) { first.ignoreCurrentVersion() }
        assertFalse(first.state.value.showPrompt)
        prefs.edit().putString("last_attempt_local_day", "2000-01-01").commit()
        val recreated = model()
        check(recreated)
        assertFalse(recreated.state.value.showPrompt)
        assertEquals(UpdateStatus.AVAILABLE, recreated.state.value.status)
        response = release(200001)
        prefs.edit().putString("last_attempt_local_day", "2000-01-01").commit()
        check(recreated)
        assertTrue(recreated.state.value.showPrompt)
        check(recreated, manual = true)
        assertEquals(UpdateStatus.AVAILABLE, recreated.state.value.status)
    }
}
