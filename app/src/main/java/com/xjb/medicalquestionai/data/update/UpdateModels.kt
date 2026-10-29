package com.xjb.medicalquestionai.data.update

import java.io.File

data class UpdateManifest(
    val schemaVersion: Int,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkAssetName: String,
    val apkSizeBytes: Long,
    val apkSha256: String,
    val signingCertSha256: String,
    val commitSha: String,
    val publishedAt: String,
    val releaseNotes: String
)

data class UpdateRelease(
    val manifest: UpdateManifest,
    val apkUrl: String,
    val tagName: String
)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data object NoRelease : UpdateCheckResult
    data class Available(val release: UpdateRelease) : UpdateCheckResult
    data class Failed(val message: String) : UpdateCheckResult
}

enum class UpdateStatus {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    READY_TO_INSTALL,
    WAITING_INSTALL_PERMISSION,
    INSTALLING,
    FAILED,
    NO_RELEASE
}

data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val currentVersionCode: Long = 0L,
    val currentVersionName: String = "",
    val update: UpdateRelease? = null,
    val progressPercent: Int? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val message: String? = null,
    val lastCheckedAt: Long? = null,
    val showPrompt: Boolean = false,
    val installReady: Boolean = false,
    val downloadedFile: File? = null
)
