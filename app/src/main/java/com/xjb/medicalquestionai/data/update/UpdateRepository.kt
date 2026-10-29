package com.xjb.medicalquestionai.data.update

import android.content.Context
import com.xjb.medicalquestionai.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object ReleaseAssetSelector {
    private val DOWNLOAD_PREFIX = "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/download/"

    data class Selected(val updateJsonUrl: String, val apkUrl: String)

    fun select(root: JSONObject): Result<Selected> = runCatching {
        require(root.optBoolean("draft", false).not()) { "草稿 Release 不作为更新来源" }
        require(root.optBoolean("prerelease", false).not()) { "预发布 Release 不作为更新来源" }
        require(!root.opt("tag_name").let { it == null || it !is String || it.isBlank() }) { "Release tag 缺失" }
        val assets = root.optJSONArray("assets") ?: throw IllegalArgumentException("Release 资产缺失")
        val updateUrls = mutableListOf<String>()
        val apkUrls = mutableListOf<String>()
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.opt("name") as? String ?: continue
            val url = asset.opt("browser_download_url") as? String ?: continue
            if (!url.startsWith(DOWNLOAD_PREFIX)) continue
            when (name) {
                "update.json" -> updateUrls += url
                "medical-question-ai.apk" -> apkUrls += url
            }
        }
        require(updateUrls.size == 1 && apkUrls.size == 1) { "Release 必须且只能包含一个 update.json 与一个 APK" }
        Selected(updateUrls.single(), apkUrls.single())
    }
}

class UpdateRepository(
    private val client: OkHttpClient = defaultClient()
) {
    companion object {
        val LATEST_RELEASE_URL = "https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest"
        const val MAX_RELEASE_JSON_BYTES = 1024 * 1024L
        private val TRUSTED_ASSET_HOSTS = setOf(
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com"
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    suspend fun checkForUpdate(currentVersionCode: Long, deviceSdk: Int): UpdateCheckResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(LATEST_RELEASE_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "medical-question-ai-update-check")
                    .build()
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        404 -> return@withContext UpdateCheckResult.NoRelease
                        403, 429 -> return@withContext UpdateCheckResult.Failed("检查更新过于频繁，请稍后再试")
                    }
                    if (!response.isSuccessful) return@withContext UpdateCheckResult.Failed("检查更新失败（HTTP ${response.code}）")
                    val body = response.body ?: return@withContext UpdateCheckResult.Failed("更新服务返回为空")
                    val bytes = body.bytes()
                    if (bytes.size > MAX_RELEASE_JSON_BYTES) return@withContext UpdateCheckResult.Failed("更新元数据过大")
                    val selected = ReleaseAssetSelector.select(JSONObject(bytes.toString(Charsets.UTF_8))).getOrElse {
                        return@withContext UpdateCheckResult.Failed(it.message ?: "更新 Release 格式错误")
                    }
                    val manifestBytes = fetchBytes(selected.updateJsonUrl)
                        ?: return@withContext UpdateCheckResult.Failed("读取 update.json 失败")
                    val manifest = UpdateManifestParser.parse(manifestBytes).getOrElse {
                        return@withContext UpdateCheckResult.Failed(it.message ?: "更新信息格式错误")
                    }
                    if (manifest.applicationId != "com.xjb.medicalquestionai") {
                        return@withContext UpdateCheckResult.Failed("更新包应用标识不匹配")
                    }
                    if (!UpdateManifestParser.isCompatible(manifest.minSdk, deviceSdk)) {
                        return@withContext UpdateCheckResult.Failed("当前系统版本不支持该更新")
                    }
                    if (!UpdateManifestParser.isNewer(manifest.versionCode, currentVersionCode)) {
                        return@withContext UpdateCheckResult.UpToDate
                    }
                    val tag = JSONObject(bytes.toString(Charsets.UTF_8)).optString("tag_name", "")
                    UpdateCheckResult.Available(UpdateRelease(manifest, selected.apkUrl, tag))
                }
            } catch (e: Exception) {
                UpdateCheckResult.Failed(e.message ?: "检查更新失败")
            }
        }

    private fun fetchBytes(url: String): ByteArray? {
        var currentUrl = url
        repeat(6) {
            val parsed = currentUrl.toHttpUrlOrNull() ?: return null
            if (!parsed.isHttps || parsed.host !in TRUSTED_ASSET_HOSTS) return null
            val request = Request.Builder().url(parsed).header("User-Agent", "medical-question-ai-update-check").build()
            client.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    currentUrl = response.header("Location") ?: return null
                    return@repeat
                }
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val bytes = body.bytes()
                return if (bytes.size <= UpdateManifestParser.MAX_METADATA_BYTES) bytes else null
            }
        }
        return null
    }

    suspend fun downloadApk(
        context: Context,
        release: UpdateRelease,
        onProgress: (downloaded: Long, total: Long?) -> Unit
    ): File = withContext(Dispatchers.IO) {
        require(release.manifest.apkSizeBytes in 1..UpdateManifestParser.MAX_APK_BYTES) { "更新包大小非法" }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val finalFile = File(dir, "medical-question-ai.apk")
        val partFile = File(dir, "medical-question-ai.apk.part")
        if (partFile.exists()) partFile.delete()
        var currentUrl = release.apkUrl
        var redirects = 0
        var completedFile: File? = null
        try {
            while (completedFile == null) {
                val url = currentUrl.toHttpUrlOrNull() ?: throw IllegalStateException("更新下载地址非法")
                require(url.isHttps) { "更新下载必须使用 HTTPS" }
                require(url.host in TRUSTED_ASSET_HOSTS) { "更新下载域名不受信任" }
                val request = Request.Builder().url(url).header("User-Agent", "medical-question-ai-update-download").build()
                client.newCall(request).execute().use { response ->
                    if (response.isRedirect) {
                        val location = response.header("Location") ?: throw IllegalStateException("更新下载重定向缺少地址")
                        redirects++
                        require(redirects <= 5) { "更新下载重定向过多" }
                        currentUrl = location
                        return@use
                    }
                    if (!response.isSuccessful) throw IllegalStateException("下载失败（HTTP ${response.code}）")
                    val body = response.body ?: throw IllegalStateException("下载响应为空")
                    val total = body.contentLength().takeIf { it > 0 }
                    if (total != null && total > UpdateManifestParser.MAX_APK_BYTES) throw IllegalStateException("更新包超过大小上限")
                    var downloaded = 0L
                    val digest = MessageDigest.getInstance("SHA-256")
                    body.byteStream().use { input ->
                        partFile.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                digest.update(buffer, 0, read)
                                downloaded += read
                                if (downloaded > UpdateManifestParser.MAX_APK_BYTES) throw IllegalStateException("更新包超过大小上限")
                                onProgress(downloaded, total)
                            }
                        }
                    }
                    if (downloaded != release.manifest.apkSizeBytes) throw IllegalStateException("更新包大小校验失败")
                    val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!actualSha.equals(release.manifest.apkSha256, ignoreCase = true)) {
                        throw IllegalStateException("更新包摘要校验失败")
                    }
                    if (finalFile.exists()) finalFile.delete()
                    if (!partFile.renameTo(finalFile)) throw IllegalStateException("更新包保存失败")
                    completedFile = finalFile
                }
            }
            completedFile!!
        } finally {
            if (!finalFile.exists() && partFile.exists()) partFile.delete()
        }
    }
}
