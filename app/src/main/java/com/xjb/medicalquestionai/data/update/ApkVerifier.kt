package com.xjb.medicalquestionai.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

object ApkVerifier {
    fun verify(
        context: Context,
        file: File,
        manifest: UpdateManifest,
        currentVersionCode: Long
    ): Result<Unit> = runCatching {
        require(file.isFile) { "更新包不存在" }
        require(file.length() == manifest.apkSizeBytes) { "更新包大小与更新信息不一致" }
        val actualSha = sha256(file)
        require(actualSha.equals(manifest.apkSha256, ignoreCase = true)) { "更新包摘要校验失败" }

        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: throw IllegalArgumentException("无法解析更新 APK")
        require(archive.packageName == manifest.applicationId) { "更新包应用标识不匹配" }
        val archiveVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archive.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            archive.versionCode.toLong()
        }
        require(archiveVersion > currentVersionCode) { "更新包版本未高于当前版本" }
        require(archiveVersion == manifest.versionCode) { "更新包版本与更新信息不一致" }
        val minSdk = archive.applicationInfo?.minSdkVersion ?: 0
        require(minSdk <= Build.VERSION.SDK_INT) { "当前系统版本不支持该更新" }
        require(minSdk == manifest.minSdk) { "更新包 minSdk 与更新信息不一致" }

        val archiveCert = certificateSha256(archive)
            ?: throw IllegalArgumentException("无法读取更新包签名")
        require(archiveCert.equals(manifest.signingCertSha256, ignoreCase = true)) { "更新包签名证书与更新信息不一致" }

        val installedFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val installed = pm.getPackageInfo(context.packageName, installedFlags)
        val installedCert = certificateSha256(installed)
            ?: throw IllegalArgumentException("无法读取当前应用签名")
        require(archiveCert.equals(installedCert, ignoreCase = true)) { "更新包签名与当前安装版本不兼容，不能覆盖安装" }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun certificateSha256(packageInfo: PackageInfo): String? {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        } ?: return null
        val signature = signatures.firstOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
