package com.xjb.medicalquestionai.data.update

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xjb.medicalquestionai.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val PREFS = "update_preferences"
        private const val KEY_LAST_CHECK = "last_successful_check"
        private const val KEY_LAST_ATTEMPT = "last_attempt"
        private const val CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L
        private const val FAILURE_BACKOFF_MS = 30L * 60L * 1000L
    }

    private val repository = UpdateRepository()
    private val prefs = application.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        UpdateUiState(
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            currentVersionName = BuildConfig.VERSION_NAME,
            lastCheckedAt = prefs.getLong(KEY_LAST_CHECK, 0L).takeIf { it > 0L }
        )
    )
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var checking = false
    private var downloading = false
    private var dismissedVersionCode: Long? = null

    fun checkManually() {
        if (checking) return
        check(manual = true)
    }

    fun autoCheckIfDue() {
        if (checking || downloading) return
        if (_state.value.status == UpdateStatus.AVAILABLE || _state.value.status == UpdateStatus.READY_TO_INSTALL) return
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return
        val lastAttempt = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
        if (lastAttempt > 0L && System.currentTimeMillis() - lastAttempt < FAILURE_BACKOFF_MS) return
        check(manual = false)
    }

    private fun check(manual: Boolean) {
        checking = true
        prefs.edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis()).apply()
        viewModelScope.launch {
            _state.update { it.copy(status = UpdateStatus.CHECKING, message = null) }
            val result = repository.checkForUpdate(
                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                deviceSdk = android.os.Build.VERSION.SDK_INT
            )
            val now = System.currentTimeMillis()
            when (result) {
                is UpdateCheckResult.Available -> {
                    prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                    val prompt = !manual && dismissedVersionCode != result.release.manifest.versionCode
                    _state.update {
                        it.copy(
                            status = UpdateStatus.AVAILABLE,
                            update = result.release,
                            message = "发现新版本 ${result.release.manifest.versionName}",
                            lastCheckedAt = now,
                            showPrompt = prompt,
                            installReady = false
                        )
                    }
                }
                UpdateCheckResult.UpToDate -> {
                    prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                    _state.update {
                        it.copy(
                            status = UpdateStatus.UP_TO_DATE,
                            update = null,
                            message = "已是最新版本",
                            lastCheckedAt = now,
                            showPrompt = false
                        )
                    }
                }
                UpdateCheckResult.NoRelease -> _state.update {
                    it.copy(status = UpdateStatus.NO_RELEASE, message = "暂无已发布更新", showPrompt = false)
                }
                is UpdateCheckResult.Failed -> _state.update {
                    it.copy(
                        status = UpdateStatus.FAILED,
                        message = if (manual) "检查失败，可重试" else null,
                        showPrompt = false
                    )
                }
            }
            checking = false
        }
    }

    fun download() {
        val release = _state.value.update ?: return
        if (downloading) return
        downloading = true
        viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    status = UpdateStatus.DOWNLOADING,
                    progressPercent = null,
                    downloadedBytes = 0L,
                    totalBytes = release.manifest.apkSizeBytes,
                    message = "正在下载更新",
                    showPrompt = false
                )
            }
            try {
                val file = repository.downloadApk(getApplication(), release) { downloaded, total ->
                    _state.update {
                        it.copy(
                            downloadedBytes = downloaded,
                            totalBytes = total ?: release.manifest.apkSizeBytes,
                            progressPercent = if (total != null && total > 0) ((downloaded * 100) / total).toInt() else null
                        )
                    }
                }
                _state.update { it.copy(status = UpdateStatus.VERIFYING, message = "正在校验更新包") }
                ApkVerifier.verify(getApplication(), file, release.manifest, BuildConfig.VERSION_CODE.toLong())
                    .getOrThrow()
                _state.update {
                    it.copy(
                        status = UpdateStatus.READY_TO_INSTALL,
                        progressPercent = 100,
                        downloadedBytes = release.manifest.apkSizeBytes,
                        totalBytes = release.manifest.apkSizeBytes,
                        message = "更新包已校验，可安装",
                        installReady = true,
                        downloadedFile = file
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        status = UpdateStatus.FAILED,
                        message = e.message ?: "下载或校验失败，可重试",
                        installReady = false,
                        downloadedFile = null
                    )
                }
            } finally {
                downloading = false
            }
        }
    }

    fun install() {
        val state = _state.value
        val file = state.downloadedFile
        val release = state.update
        if (file == null || release == null || !state.installReady) return
        val context = getApplication<Application>()
        if (!UpdateInstaller.canRequestPackageInstalls(context)) {
            _state.update {
                it.copy(status = UpdateStatus.WAITING_INSTALL_PERMISSION, message = "请允许本应用安装未知来源应用")
            }
            return
        }
        viewModelScope.launch {
            try {
                ApkVerifier.verify(context, file, release.manifest, BuildConfig.VERSION_CODE.toLong()).getOrThrow()
                _state.update { it.copy(status = UpdateStatus.INSTALLING, message = "请在系统界面完成安装") }
                UpdateInstaller.install(context, file).getOrThrow()
            } catch (e: Exception) {
                _state.update { it.copy(status = UpdateStatus.FAILED, message = e.message ?: "启动安装失败，可重试") }
            }
        }
    }

    fun openInstallPermissionSettings() {
        runCatching { getApplication<Application>().startActivity(UpdateInstaller.unknownSourcesSettingsIntent(getApplication())) }
    }

    fun dismissPrompt() {
        dismissedVersionCode = _state.value.update?.manifest?.versionCode
        _state.update { it.copy(showPrompt = false) }
    }

    fun formattedLastCheckedAt(): String? = _state.value.lastCheckedAt?.let {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(it))
    }

    fun clearDownloadedFile() {
        val file: File? = _state.value.downloadedFile
        file?.delete()
        _state.update { it.copy(status = UpdateStatus.IDLE, installReady = false, downloadedFile = null, progressPercent = null) }
    }
}
