package com.xjb.medicalquestionai

import com.xjb.medicalquestionai.data.ApiException
import com.xjb.medicalquestionai.data.AppSettings
import com.xjb.medicalquestionai.data.AnswerMode
import com.xjb.medicalquestionai.data.DeepSeekClient
import com.xjb.medicalquestionai.data.ModelCapability
import com.xjb.medicalquestionai.data.ModelCatalog
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
    private val _isTestingKey = MutableStateFlow(false)
    val isTestingKey: StateFlow<Boolean> = _isTestingKey.asStateFlow()

    private val _isCheckingConnection = MutableStateFlow(false)
    val isCheckingConnection: StateFlow<Boolean> = _isCheckingConnection.asStateFlow()

    private val _isVerifyingModel = MutableStateFlow(false)
    val isVerifyingModel: StateFlow<Boolean> = _isVerifyingModel.asStateFlow()

    fun saveApiKey(key: String, onDone: (String?) -> Unit) {
        val trimmed = key.trim()
        if (trimmed.isBlank()) {
            onDone("请输入 API 密钥")
            return
        }
        settingsRepository.saveApiKey(trimmed)
        onDone(null)
    }

    fun testSavedConnection(onDone: (String?) -> Unit) {
        val key = settingsRepository.apiKey()
        if (key.isNullOrBlank()) {
            onDone("尚未保存 API 密钥")
            return
        }
        if (_isTestingKey.value) return
        _isTestingKey.value = true
        scope.launch {
            try {
                onDone(
                    client.verifyConnection(
                        baseUrl = settings.value.baseUrl,
                        apiKey = key,
                        model = settings.value.model
                    )
                )
            } finally {
                _isTestingKey.value = false
            }
        }
    }

    /** 检查连接：只请求 /models，报告接口是否可达、Key 是否可用、当前模型是否在列表中。 */
    fun checkSavedConnection(onDone: (message: String, ok: Boolean) -> Unit) {
        val key = settingsRepository.apiKey()
        if (key.isNullOrBlank()) {
            onDone("尚未保存 API 密钥", false)
            return
        }
        if (_isCheckingConnection.value) return
        _isCheckingConnection.value = true
        scope.launch {
            try {
                val result = client.checkConnection(
                    baseUrl = settings.value.baseUrl,
                    apiKey = key,
                    model = settings.value.model
                )
                val message = when {
                    !result.modelListAvailable ->
                        "接口可达，密钥可用；接口未返回模型列表，无法确认当前模型是否存在，请点击“验证模型”确认模型可用"
                    result.selectedModelFound ->
                        "接口可达，密钥可用；当前模型可见"
                    else ->
                        "接口可达，但当前模型不在 /models 返回列表中，请检查模型名或账号权限"
                }
                onDone(message, result.modelListAvailable && result.selectedModelFound)
            } catch (e: ApiException) {
                onDone(e.message ?: "连接检查失败", false)
            } finally {
                _isCheckingConnection.value = false
            }
        }
    }

    /** 验证模型：发一次极短文字请求，确认当前模型可以真正生成内容。 */
    fun verifySavedModel(onDone: (message: String, ok: Boolean) -> Unit) {
        val key = settingsRepository.apiKey()
        if (key.isNullOrBlank()) {
            onDone("尚未保存 API 密钥", false)
            return
        }
        if (_isVerifyingModel.value) return
        _isVerifyingModel.value = true
        scope.launch {
            try {
                val error = client.verifyModel(
                    baseUrl = settings.value.baseUrl,
                    apiKey = key,
                    model = settings.value.model
                )
                if (error == null) {
                    val capability = when (ModelCatalog.capability(settings.value.model)) {
                        ModelCapability.SUPPORTED -> "支持图片"
                        ModelCapability.UNSUPPORTED -> "仅文字"
                        ModelCapability.UNKNOWN -> "图片能力未知"
                    }
                    onDone("模型验证通过（$capability）；图片链路仍需真机实拍验证", true)
                } else {
                    onDone(error, false)
                }
            } finally {
                _isVerifyingModel.value = false
            }
        }
    }

    fun removeApiKey() {
        settingsRepository.clearApiKey()
    }

    fun saveModel(model: String) {
        settingsRepository.saveModel(model)
    }

    fun saveAnswerMode(mode: AnswerMode) {
        settingsRepository.saveAnswerMode(mode)
    }

    fun saveBaseUrl(url: String): Boolean = settingsRepository.saveBaseUrl(url)

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
