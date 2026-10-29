package com.xjb.medicalquestionai.data.update

import org.json.JSONObject

object UpdateManifestParser {
    const val MAX_METADATA_BYTES = 64 * 1024
    const val MAX_APK_BYTES = 150L * 1024 * 1024
    private const val EXPECTED_APP_ID = "com.xjb.medicalquestionai"
    private val SHA256 = Regex("^[0-9a-fA-F]{64}$")
    private val COMMIT = Regex("^[0-9a-fA-F]{40}$")

    fun parse(bytes: ByteArray): Result<UpdateManifest> {
        if (bytes.isEmpty() || bytes.size > MAX_METADATA_BYTES) {
            return Result.failure(IllegalArgumentException("更新元数据大小超过限制"))
        }
        return runCatching { parse(JSONObject(bytes.toString(Charsets.UTF_8))) }
    }

    fun parse(json: String): Result<UpdateManifest> {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_METADATA_BYTES) {
            return Result.failure(IllegalArgumentException("更新元数据大小超过限制"))
        }
        return runCatching { parse(JSONObject(json)) }
    }

    private fun parse(root: JSONObject): UpdateManifest {
        val schemaVersion = root.requiredInt("schemaVersion")
        require(schemaVersion == 1) { "当前版本暂不支持该更新信息（schemaVersion=$schemaVersion）" }
        val applicationId = root.requiredString("applicationId")
        require(applicationId == EXPECTED_APP_ID) { "更新包应用标识不匹配" }
        val versionCode = root.requiredLong("versionCode")
        require(versionCode > 0) { "versionCode 必须为正数" }
        val versionName = root.requiredString("versionName")
        require(versionName.length <= 80) { "versionName 过长" }
        val minSdk = root.requiredInt("minSdk")
        require(minSdk >= 0) { "minSdk 非法" }
        val apkAssetName = root.requiredString("apkAssetName")
        require(apkAssetName == "medical-question-ai.apk") { "更新 APK 文件名不匹配" }
        val apkSizeBytes = root.requiredLong("apkSizeBytes")
        require(apkSizeBytes in 1..MAX_APK_BYTES) { "更新包大小超出限制" }
        val apkSha256 = root.requiredString("apkSha256")
        require(SHA256.matches(apkSha256)) { "APK SHA-256 格式非法" }
        val signingCertSha256 = root.requiredString("signingCertSha256")
        require(SHA256.matches(signingCertSha256)) { "签名证书摘要格式非法" }
        val commitSha = root.requiredString("commitSha")
        require(COMMIT.matches(commitSha)) { "commitSha 格式非法" }
        val publishedAt = root.requiredString("publishedAt")
        require(publishedAt.length <= 80) { "publishedAt 过长" }
        val releaseNotes = root.requiredString("releaseNotes")
        require(releaseNotes.length <= 4_000) { "更新说明过长" }

        // JSON null must not become the literal string "null".
        listOf("applicationId", "versionName", "apkAssetName", "apkSha256", "signingCertSha256", "commitSha", "publishedAt", "releaseNotes")
            .forEach { key -> require(root.opt(key) is String) { "$key 类型非法" } }

        return UpdateManifest(
            schemaVersion = schemaVersion,
            applicationId = applicationId,
            versionCode = versionCode,
            versionName = versionName,
            minSdk = minSdk,
            apkAssetName = apkAssetName,
            apkSizeBytes = apkSizeBytes,
            apkSha256 = apkSha256.lowercase(),
            signingCertSha256 = signingCertSha256.lowercase(),
            commitSha = commitSha.lowercase(),
            publishedAt = publishedAt,
            releaseNotes = releaseNotes
        )
    }

    private fun JSONObject.requiredString(key: String): String {
        val value = opt(key)
        require(value is String && value.isNotBlank()) { "$key 缺失或类型非法" }
        return value
    }

    private fun JSONObject.requiredInt(key: String): Int {
        val value = opt(key)
        require(value is Int || value is Long || value is Double) { "$key 类型非法" }
        val number = (value as Number).toDouble()
        require(number % 1.0 == 0.0 && number in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) { "$key 类型非法" }
        return number.toInt()
    }

    private fun JSONObject.requiredLong(key: String): Long {
        val value = opt(key)
        require(value is Int || value is Long) { "$key 类型非法" }
        return if (value is Int) value.toLong() else value as Long
    }

    fun versionCodeOf(manifest: UpdateManifest): Long = manifest.versionCode

    fun isNewer(candidate: Long, current: Long): Boolean = candidate > current

    fun isCompatible(minSdk: Int, deviceSdk: Int): Boolean = minSdk <= deviceSdk
}
