package com.xjb.medicalquestionai.data.update

import android.content.Context
import com.xjb.medicalquestionai.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

object ReleaseAssetSelector {
    private val REPOSITORY_SEGMENTS = BuildConfig.UPDATE_REPOSITORY.split("/")

    data class Selected(val updateJsonUrl: String, val apkUrl: String, val tagName: String)

    fun select(root: JSONObject): Result<Selected> = runCatching {
        require(root.optBoolean("draft", false).not()) { "草稿 Release 不作为更新来源" }
        require(root.optBoolean("prerelease", false).not()) { "预发布 Release 不作为更新来源" }
        val tag = root.opt("tag_name") as? String
        require(!tag.isNullOrBlank()) { "Release tag 缺失" }
        require(!tag.contains("..") && !tag.startsWith("/")) { "Release tag 非法" }
        val assets = root.optJSONArray("assets") ?: throw IllegalArgumentException("Release 资产缺失")
        val named = mutableMapOf<String, MutableList<String>>()
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.opt("name") as? String ?: continue
            if (name != "update.json" && name != "medical-question-ai.apk") continue
            val type = asset.opt("content_type") as? String
            require(!type.isNullOrBlank()) { "Release 资产类型缺失" }
            val expectedType = if (name == "update.json") "application/json" else "application/vnd.android.package-archive"
            require(type == expectedType || (name.endsWith(".apk") && type == "application/octet-stream")) {
                "Release 资产类型不匹配"
            }
            val url = asset.opt("browser_download_url") as? String
            require(!url.isNullOrBlank()) { "Release 资产地址缺失" }
            named.getOrPut(name) { mutableListOf() } += url
        }
        val updateUrls = named["update.json"].orEmpty()
        val apkUrls = named["medical-question-ai.apk"].orEmpty()
        require(updateUrls.size == 1 && apkUrls.size == 1) { "Release 必须且只能包含一个 update.json 与一个 APK" }
        require(urlBelongsTo(rootTag = tag, assetName = "update.json", rawUrl = updateUrls.single())) { "update.json 不属于当前 Release" }
        require(urlBelongsTo(rootTag = tag, assetName = "medical-question-ai.apk", rawUrl = apkUrls.single())) { "APK 不属于当前 Release" }
        Selected(updateUrls.single(), apkUrls.single(), tag)
    }

    private fun urlBelongsTo(rootTag: String, assetName: String, rawUrl: String): Boolean {
        val parsed = rawUrl.toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps || parsed.host != "github.com") return false
        val expectedSegments = REPOSITORY_SEGMENTS + listOf("releases", "download") + rootTag.split("/") + listOf(assetName)
        return parsed.pathSegments == expectedSegments
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
                val releaseCall = client.newCall(request)
                coroutineContext[Job]?.invokeOnCompletion { releaseCall.cancel() }
                releaseCall.execute().use { response ->
                    when (response.code) {
                        404 -> return@withContext UpdateCheckResult.NoRelease
                        403, 429 -> return@withContext UpdateCheckResult.Failed("检查更新过于频繁，请稍后再试")
                    }
                    if (!response.isSuccessful) return@withContext UpdateCheckResult.Failed("检查更新失败（HTTP ${response.code}）")
                    val body = response.body ?: return@withContext UpdateCheckResult.Failed("更新服务返回为空")
                    val bytes = readBounded(body, MAX_RELEASE_JSON_BYTES)
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
                    UpdateCheckResult.Available(UpdateRelease(manifest, selected.apkUrl, selected.tagName))
                }
            } catch (e: Exception) {
                UpdateCheckResult.Failed(e.message ?: "检查更新失败")
            }
        }

    private suspend fun fetchBytes(url: String): ByteArray? {
        var currentUrl = url
        repeat(6) {
            val parsed = currentUrl.toHttpUrlOrNull() ?: return null
            if (!parsed.isHttps || parsed.host !in TRUSTED_ASSET_HOSTS) return null
            val request = Request.Builder().url(parsed).header("User-Agent", "medical-question-ai-update-check").build()
            val call = client.newCall(request)
            coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            call.execute().use { response ->
                if (response.isRedirect) {
                    currentUrl = response.header("Location") ?: return null
                    return@repeat
                }
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                return readBounded(body, UpdateManifestParser.MAX_METADATA_BYTES.toLong())
            }
        }
        return null
    }

    private fun readBounded(body: ResponseBody, limit: Long): ByteArray {
        val declared = body.contentLength()
        if (declared > limit) throw IllegalArgumentException("更新元数据过大")
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        body.byteStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw IllegalArgumentException("更新元数据过大")
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    suspend fun downloadApk(
        context: Context,
        release: UpdateRelease,
        onCall: (okhttp3.Call?) -> Unit = {},
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
                val call = client.newCall(request)
                onCall(call)
                coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
                call.execute().use { response ->
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
            onCall(null)
            if (partFile.exists()) partFile.delete()
        }
    }
}
