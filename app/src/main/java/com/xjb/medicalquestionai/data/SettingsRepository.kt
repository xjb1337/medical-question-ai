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
        prefs.edit().putString(KEY_MODEL, model).apply()
    }

    fun saveBaseUrl(url: String) {
        prefs.edit().putString(KEY_BASE_URL, url.trim().trimEnd('/')).apply()
    }

    fun setSaveHistory(value: Boolean) {
        prefs.edit().putBoolean(KEY_SAVE_HISTORY, value).apply()
    }

    private fun load(): AppSettings {
        val key = keyStore.getApiKey()
        return AppSettings(
            apiKeyConfigured = !key.isNullOrBlank(),
            apiKeyHint = key?.takeLast(4)?.let { "末尾 $it" }.orEmpty(),
            model = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
            baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL,
            saveHistory = prefs.getBoolean(KEY_SAVE_HISTORY, true)
        )
    }

    companion object {
        const val DEFAULT_MODEL = "deepseek-flash"
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_SAVE_HISTORY = "save_history"
    }
}
