package com.xjb.medicalquestionai.data.update

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.xjb.medicalquestionai.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UpdateViewModel internal constructor(
    application: Application,
    private val checkRelease: suspend () -> UpdateCheckResult,
    private val isOnline: () -> Boolean
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application,
        { UpdateRepository().checkForUpdate(BuildConfig.VERSION_CODE.toLong(), android.os.Build.VERSION.SDK_INT) },
        UpdateNetworkMonitor(application)::isOnline
    )
    companion object {
        private const val PREFS = "update_preferences"
        private const val KEY_LAST_CHECK = "last_successful_check"
        private const val KEY_LAST_ATTEMPT_DAY = "last_attempt_local_day"
        private const val KEY_PENDING_NETWORK_DAY = "pending_network_check_day"
        private const val KEY_RECOVERY_ATTEMPT_DAY = "network_recovery_attempt_day"
        private const val KEY_IGNORED_VERSION = "ignored_version_code"
    }

    private val repository = UpdateRepository()
    private val networkMonitor = UpdateNetworkMonitor(application)
    internal val connectivity = networkMonitor.connectivity
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
    private var downloadJob: Job? = null
    private var activeDownloadCall: okhttp3.Call? = null
    private var downloadToken = 0L
    private var activeDownloadToken: Long? = null
    private var dismissedVersionCode: Long? = null

    fun checkManually() {
        if (checking) return
        check(manual = true)
    }

    fun autoCheckIfDue() {
        if (checking || downloading) return
        if (_state.value.status in setOf(
                UpdateStatus.READY_TO_INSTALL, UpdateStatus.INSTALLING, UpdateStatus.WAITING_INSTALL_PERMISSION
            )) return
        val today = DailyUpdateCheckPolicy.dayKey(System.currentTimeMillis())
        val attempt = DailyUpdateCheckPolicy.nextAttempt(
            prefs.getString(KEY_LAST_ATTEMPT_DAY, null), prefs.getString(KEY_PENDING_NETWORK_DAY, null),
            prefs.getString(KEY_RECOVERY_ATTEMPT_DAY, null), today, isOnline()
        ) ?: return
        check(manual = false, recovery = attempt == DailyUpdateCheckPolicy.Attempt.NETWORK_RECOVERY)
    }

    private fun check(manual: Boolean, recovery: Boolean = false) {
        checking = true
        val attemptDay = DailyUpdateCheckPolicy.dayKey(System.currentTimeMillis())
        // Persist the attempt before any asynchronous work, including the one recovery allowance.
        prefs.edit().putString(KEY_LAST_ATTEMPT_DAY, attemptDay).apply {
            if (recovery) putString(KEY_RECOVERY_ATTEMPT_DAY, attemptDay)
            remove(KEY_PENDING_NETWORK_DAY)
        }.apply()
        viewModelScope.launch {
            _state.update { it.copy(status = UpdateStatus.CHECKING, message = null) }
            val startedOffline = !isOnline()
            val result = if (startedOffline) {
                UpdateCheckResult.Failed("当前无网络，恢复网络后自动补查", networkFailure = true)
            } else checkRelease()
            if (result is UpdateCheckResult.Failed && result.networkFailure && !recovery &&
                (startedOffline || !isOnline())) {
                prefs.edit().putString(KEY_PENDING_NETWORK_DAY, attemptDay).apply()
            }
            val now = System.currentTimeMillis()
            when (result) {
                is UpdateCheckResult.Available -> {
                    prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                    val prompt = !manual && DailyUpdateCheckPolicy.shouldPrompt(
                        result.release.manifest.versionCode,
                        prefs.getLong(KEY_IGNORED_VERSION, -1L), dismissedVersionCode
                    )
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
        val token = ++downloadToken
        activeDownloadToken = token
        downloading = true
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    status = UpdateStatus.DOWNLOADING,
                    progressPercent = null,
                    downloadedBytes = 0L,
                    totalBytes = release.manifest.apkSizeBytes,
                    message = "正在下载更新",
                    showPrompt = false,
                    showProgress = true,
                    installReady = false,
                    downloadedFile = null
                )
            }
            try {
                val file = repository.downloadApk(
                    context = getApplication(),
                    release = release,
                    downloadId = "task-$token",
                    onCall = { call -> if (activeDownloadToken == token) activeDownloadCall = call }
                ) { downloaded, total ->
                    if (activeDownloadToken == token) {
                        _state.update {
                            it.copy(
                                downloadedBytes = downloaded,
                                totalBytes = total ?: release.manifest.apkSizeBytes,
                                progressPercent = if (total != null && total > 0) ((downloaded * 100) / total).toInt() else null
                            )
                        }
                    }
                }
                if (activeDownloadToken == token) {
                    _state.update { it.copy(status = UpdateStatus.VERIFYING, message = "正在校验更新包") }
                }
                withContext(Dispatchers.IO) {
                    ApkVerifier.verify(getApplication(), file, release.manifest, BuildConfig.VERSION_CODE.toLong())
                        .getOrThrow()
                }
                if (activeDownloadToken == token) {
                    _state.update {
                        it.copy(
                            status = UpdateStatus.READY_TO_INSTALL,
                            progressPercent = 100,
                            downloadedBytes = release.manifest.apkSizeBytes,
                            totalBytes = release.manifest.apkSizeBytes,
                            message = "更新包已校验，可安装",
                            installReady = true,
                            downloadedFile = file,
                            showProgress = true
                        )
                    }
                }
            } catch (e: CancellationException) {
                if (activeDownloadToken == token) {
                    _state.update {
                        it.copy(
                            status = if (it.update != null) UpdateStatus.AVAILABLE else UpdateStatus.IDLE,
                            message = "已取消下载",
                            progressPercent = null,
                            downloadedBytes = 0L,
                            installReady = false,
                            downloadedFile = null,
                            showProgress = false
                        )
                    }
                }
            } catch (e: Exception) {
                if (activeDownloadToken == token) {
                    _state.update {
                        it.copy(
                            status = UpdateStatus.FAILED,
                            message = e.message ?: "下载或校验失败，可重试",
                            installReady = false,
                            downloadedFile = null,
                            showProgress = true
                        )
                    }
                }
            } finally {
                if (activeDownloadToken == token) {
                    downloading = false
                    activeDownloadCall = null
                    downloadJob = null
                    activeDownloadToken = null
                }
            }
        }
    }

    fun cancelDownload() {
        val token = activeDownloadToken ?: return
        activeDownloadCall?.cancel()
        downloadJob?.cancel()
        if (activeDownloadToken == token) {
            activeDownloadToken = null
            downloading = false
            downloadJob = null
            activeDownloadCall = null
        }
        _state.update {
            it.copy(
                status = if (it.update != null) UpdateStatus.AVAILABLE else UpdateStatus.IDLE,
                message = "已取消下载",
                progressPercent = null,
                downloadedBytes = 0L,
                installReady = false,
                downloadedFile = null,
                showProgress = false
            )
        }
    }

    fun dismissProgress() {
        _state.update { it.copy(showProgress = false) }
    }

    fun install(hasActiveWork: Boolean = false) {
        val state = _state.value
        val file = state.downloadedFile
        val release = state.update
        if (file == null || release == null || !state.installReady) return
        if (hasActiveWork) {
            _state.update { it.copy(message = "当前有拍题或追问任务，完成后再安装更新", showProgress = true) }
            return
        }
        val context = getApplication<Application>()
        if (!UpdateInstaller.canRequestPackageInstalls(context)) {
            _state.update {
                it.copy(
                    status = UpdateStatus.WAITING_INSTALL_PERMISSION,
                    message = "请允许本应用安装未知来源应用",
                    showProgress = true
                )
            }
            return
        }
        viewModelScope.launch {
            try {
                _state.update { it.copy(status = UpdateStatus.INSTALLING, message = "正在校验并启动安装", showProgress = true) }
                withContext(Dispatchers.IO) {
                    ApkVerifier.verify(context, file, release.manifest, BuildConfig.VERSION_CODE.toLong()).getOrThrow()
                }
                _state.update { it.copy(status = UpdateStatus.INSTALLING, message = "请在系统界面完成安装") }
                withContext(Dispatchers.IO) {
                    UpdateInstaller.install(context, file).getOrThrow()
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(status = UpdateStatus.FAILED, message = e.message ?: "启动安装失败，可重试", showProgress = true)
                }
            }
        }
    }

    fun onAppResumed() {
        val state = _state.value
        if (state.status == UpdateStatus.INSTALLING && state.downloadedFile?.exists() == true) {
            _state.update {
                it.copy(
                    status = UpdateStatus.READY_TO_INSTALL,
                    message = "系统安装未完成，可重试",
                    installReady = true,
                    showProgress = true
                )
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

    fun ignoreCurrentVersion() {
        val version = _state.value.update?.manifest?.versionCode ?: return
        prefs.edit().putLong(KEY_IGNORED_VERSION, version).apply()
        dismissPrompt()
    }

    fun formattedLastCheckedAt(): String? = _state.value.lastCheckedAt?.let {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(it))
    }

    fun clearDownloadedFile() {
        val file: File? = _state.value.downloadedFile
        file?.delete()
        _state.update {
            it.copy(
                status = UpdateStatus.IDLE,
                installReady = false,
                downloadedFile = null,
                progressPercent = null,
                showProgress = false,
                message = null
            )
        }
    }
}
