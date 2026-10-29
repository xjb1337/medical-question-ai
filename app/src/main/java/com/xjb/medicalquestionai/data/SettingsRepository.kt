package com.xjb.medicalquestionai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SecureKeyStore(context: Context) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getApiKey(): String? = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    fun saveApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    companion object {
        private const val FILE_NAME = "secure_settings"
        private const val KEY_API_KEY = "deepseek_api_key"
    }
}

class SettingsRepository(context: Context, private val keyStore: SecureKeyStore) {
    private val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        _settings.value = load()
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun refresh() {
        _settings.value = load()
    }

    fun apiKey(): String? = keyStore.getApiKey()

    fun saveApiKey(key: String) {
        keyStore.saveApiKey(key.trim())
        refresh()
    }

    fun clearApiKey() {
        keyStore.clearApiKey()
        refresh()
    }

    fun saveModel(model: String) {
        // 用户主动保存模型就是一次明确选择；标记迁移版本已到最新，避免下次启动把旧模型名再次迁移。
        prefs.edit()
            .putString(KEY_MODEL, model.trim())
            .putInt(KEY_SETTINGS_VERSION, SettingsMigrations.CURRENT_VERSION)
            .commit()
    }

    fun saveAnswerMode(mode: AnswerMode) {
        prefs.edit()
            .putString(KEY_ANSWER_MODE, mode.storageValue)
            .putInt(KEY_SETTINGS_VERSION, SettingsMigrations.CURRENT_VERSION)
            .commit()
    }

    fun saveBaseUrl(url: String): Boolean {
        val normalized = normalizeBaseUrl(url)
        if (validateBaseUrl(normalized) != null) return false
        prefs.edit().putString(KEY_BASE_URL, normalized).apply()
        return true
    }

    fun setSaveHistory(value: Boolean) {
        prefs.edit().putBoolean(KEY_SAVE_HISTORY, value).apply()
    }

    fun saveCustomModelCapabilities(supportsImages: Boolean, streaming: Boolean) {
        prefs.edit()
            .putBoolean(KEY_CUSTOM_MODEL_IMAGES, supportsImages)
            .putBoolean(KEY_CUSTOM_MODEL_STREAMING, streaming)
            .commit()
    }

    fun dismissModelMigrationNote() {
        prefs.edit().remove(KEY_MIGRATION_NOTE).apply()
        refresh()
    }

    private fun load(): AppSettings {
        val key = keyStore.getApiKey()
        val storedModel = prefs.getString(KEY_MODEL, null)
        val baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        val storedMode = prefs.getString(KEY_ANSWER_MODE, null)
        val storedVersion = prefs.getInt(KEY_SETTINGS_VERSION, 0)
        val migration = SettingsMigrations.migrate(storedModel, baseUrl, storedMode, storedVersion)
        if (migration.changed) {
            val editor = prefs.edit()
                .putString(KEY_MODEL, migration.model)
                .putString(KEY_ANSWER_MODE, migration.answerMode.storageValue)
                .putInt(KEY_SETTINGS_VERSION, migration.version)
            if (migration.note.isNotBlank()) editor.putString(KEY_MIGRATION_NOTE, migration.note)
            // 迁移是低频一次性写入，用 commit 保证内存状态与磁盘版本号一致，避免重复迁移。
            editor.commit()
        }
        return AppSettings(
            apiKeyConfigured = !key.isNullOrBlank(),
            apiKeyHint = key?.takeLast(4)?.let { "末尾 $it" }.orEmpty(),
            model = migration.model,
            baseUrl = baseUrl,
            saveHistory = prefs.getBoolean(KEY_SAVE_HISTORY, true),
            answerMode = migration.answerMode,
            settingsVersion = migration.version,
            modelMigrationNote = prefs.getString(KEY_MIGRATION_NOTE, "").orEmpty(),
            customModelSupportsImages = prefs.getBoolean(KEY_CUSTOM_MODEL_IMAGES, false),
            customModelStreaming = prefs.getBoolean(KEY_CUSTOM_MODEL_STREAMING, false)
        )
    }

    companion object {
        const val DEFAULT_MODEL = "deepseek-flash"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_SAVE_HISTORY = "save_history"
        private const val KEY_ANSWER_MODE = "answer_mode"
        private const val KEY_SETTINGS_VERSION = "settings_version"
        private const val KEY_MIGRATION_NOTE = "model_migration_note"
        private const val KEY_CUSTOM_MODEL_IMAGES = "custom_model_images"
        private const val KEY_CUSTOM_MODEL_STREAMING = "custom_model_streaming"

        fun normalizeBaseUrl(url: String): String = url.trim().trimEnd('/')

        /** Returns a user-facing error, or null when the base URL is syntactically usable. */
        fun validateBaseUrl(url: String): String? {
            val value = url.trim()
            if (value.isEmpty()) return "接口地址不能为空"
            val uri = runCatching { java.net.URI(value) }.getOrNull()
                ?: return "接口地址格式不正确"
            val scheme = uri.scheme?.lowercase()
            if (scheme != "https") return "Android 版仅允许 https:// 接口地址（禁止明文 HTTP）"
            if (uri.host.isNullOrBlank()) return "接口地址缺少有效主机名"
            return null
        }
    }
}
