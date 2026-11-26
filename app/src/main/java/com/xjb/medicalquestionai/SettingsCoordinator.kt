package com.xjb.medicalquestionai

import com.xjb.medicalquestionai.data.ApiException
import com.xjb.medicalquestionai.data.AppSettings
import com.xjb.medicalquestionai.data.AnswerMode
import com.xjb.medicalquestionai.data.DeepSeekClient
import com.xjb.medicalquestionai.data.SettingsRepository
import com.xjb.medicalquestionai.data.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns settings persistence and connection/model checks so MainViewModel stays focused on
 * question and analysis orchestration.
 */
class SettingsCoordinator(
    private val settingsRepository: SettingsRepository,
    private val client: DeepSeekClient,
    private val settings: StateFlow<AppSettings>,
    private val scope: CoroutineScope
) {
    private val _isCheckingConnection = MutableStateFlow(false)
    val isCheckingConnection: StateFlow<Boolean> = _isCheckingConnection.asStateFlow()

    private val _isVerifyingModel = MutableStateFlow(false)
    val isVerifyingModel: StateFlow<Boolean> = _isVerifyingModel.asStateFlow()
    private var connectionSettingsVersion = 0L

    fun saveApiKey(key: String, onDone: (String?) -> Unit) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) {
            onDone("请输入 API 密钥")
            return
        }
        settingsRepository.saveApiKey(trimmed)
        connectionSettingsVersion++
        onDone(null)
    }

    /** 检查连接：只请求 /models，报告接口是否可达、Key 是否可用、当前模型是否在列表中。 */
    fun checkSavedConnection(onDone: (message: String, ok: Boolean) -> Unit) =
        runDiagnostic(_isCheckingConnection, onDone) { config, key ->
            val result = client.checkConnection(config.baseUrl, key, config.model)
            when {
                !result.modelListAvailable ->
                    "接口已响应，但未返回模型列表。可点击“验证模型”确认文字生成是否可用。" to true
                result.selectedModelFound ->
                    "接口可达，当前模型出现在返回列表中。" to true
                else ->
                    "接口可达，当前模型未出现在返回列表中。请检查模型名称或账号权限。" to false
            }
        }

    /** 验证模型：发一次极短文字请求，确认当前模型可以真正生成内容。 */
    fun verifySavedModel(onDone: (message: String, ok: Boolean) -> Unit) =
        runDiagnostic(_isVerifyingModel, onDone) { config, key ->
            val error = client.verifyModel(config.baseUrl, key, config.model)
            if (error == null) "当前模型已通过文字生成验证。图片识别需另行验证。" to true
            else error to false
        }

    private fun runDiagnostic(
        running: MutableStateFlow<Boolean>,
        onDone: (String, Boolean) -> Unit,
        request: suspend (AppSettings, String) -> Pair<String, Boolean>
    ) {
        if (_isCheckingConnection.value || _isVerifyingModel.value) return
        val key = settingsRepository.apiKey()
        if (key.isNullOrBlank()) {
            onDone("尚未保存 API 密钥", false)
            return
        }
        val config = settings.value
        val version = connectionSettingsVersion
        running.value = true
        scope.launch {
            try {
                val (message, ok) = try {
                    request(config, key)
                } catch (e: ApiException) {
                    (e.message ?: "接口请求失败，请重试") to false
                }
                if (connectionSettingsVersion == version) onDone(message, ok)
            } finally {
                running.value = false
            }
        }
    }

    fun removeApiKey() {
        settingsRepository.clearApiKey()
        connectionSettingsVersion++
    }

    fun saveModel(model: String) {
        val previous = settings.value.model
        settingsRepository.saveModel(model)
        if (settings.value.model != previous) connectionSettingsVersion++
    }

    fun saveAnswerMode(mode: AnswerMode) {
        settingsRepository.saveAnswerMode(mode)
    }

    fun saveBaseUrl(url: String): Boolean {
        val previous = settings.value
        val saved = settingsRepository.saveBaseUrl(url)
        if (saved && (settings.value.baseUrl != previous.baseUrl || settings.value.model != previous.model)) {
            connectionSettingsVersion++
        }
        return saved
    }

    fun saveHistoryPreference(enabled: Boolean) {
        settingsRepository.setSaveHistory(enabled)
    }

    fun dismissModelMigrationNote() {
        settingsRepository.dismissModelMigrationNote()
    }

    fun saveThemeMode(mode: ThemeMode) {
        settingsRepository.saveThemeMode(mode)
    }

    fun saveCustomModelSupportsImages(enabled: Boolean) {
        settingsRepository.saveCustomModelCapabilities(enabled, settings.value.customModelStreaming)
    }

    fun saveCustomModelStreaming(enabled: Boolean) {
        settingsRepository.saveCustomModelCapabilities(settings.value.customModelSupportsImages, enabled)
    }
}
