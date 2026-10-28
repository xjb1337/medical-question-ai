package com.xjb.medicalquestionai.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String, val code: Int? = null, val raw: String? = null) : Exception(message)

data class ChatMessage(val role: String, val content: String)

data class ChatResponse(
    val content: String,
    val usage: Usage = Usage()
)

class DeepSeekClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        userPrompt: String,
        imagesBase64: List<String>
    ): ChatResponse = chatConversation(
        baseUrl = baseUrl,
        apiKey = apiKey,
        model = model,
        messages = listOf(
            ChatMessage("system", systemPrompt),
            ChatMessage("user", userPrompt)
        ),
        imagesBase64 = imagesBase64
    )

    suspend fun chatConversation(
        baseUrl: String,
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        imagesBase64: List<String> = emptyList(),
        maxTokens: Int = 4096
    ): ChatResponse = withContext(Dispatchers.IO) {
        val messageArray = JSONArray().apply {
            messages.forEachIndexed { index, message ->
                val isLastUser = index == messages.lastIndex && message.role == "user"
                val content: Any = if (isLastUser && imagesBase64.isNotEmpty()) {
                    JSONArray().apply {
                        put(JSONObject().put("type", "text").put("text", message.content))
                        imagesBase64.forEach { base64 ->
                            put(
                                JSONObject()
                                    .put("type", "image_url")
                                    .put(
                                        "image_url",
                                        JSONObject().put("url", "data:image/jpeg;base64,$base64")
                                    )
                            )
                        }
                    }
                } else {
                    message.content
                }
                put(JSONObject().put("role", message.role).put("content", content))
            }
        }
        val payload = JSONObject().apply {
            put("model", model)
            put("messages", messageArray)
            put("temperature", 0.15)
            put("max_tokens", maxTokens)
            put("stream", false)
        }
        val endpoint = buildEndpoint(baseUrl, "/chat/completions")
            ?: throw ApiException("接口地址无效：Android 版仅允许 https:// 开头的完整地址。")
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .build()

        val body = try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw mapHttpError(response.code, text)
                text
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException("网络连接失败，请检查网络后重试。", raw = e.message)
        }

        val json = runCatching { JSONObject(body) }.getOrElse {
            throw ApiException("接口返回了无法识别的数据。", raw = body.take(500))
        }
        val error = json.optJSONObject("error")
        if (error != null) {
            throw ApiException(error.optString("message", "接口返回错误"), raw = body.take(1000))
        }
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw ApiException("接口没有返回解答内容。", raw = body.take(1000))
        }
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw ApiException("接口返回结构异常。", raw = body.take(1000))
        val content = message.optString("content", "")
        if (content.isBlank()) throw ApiException("模型返回了空内容，请重试。", raw = body.take(1000))
        val usage = json.optJSONObject("usage")?.let {
            Usage(
                promptTokens = it.optInt("prompt_tokens", 0),
                completionTokens = it.optInt("completion_tokens", 0),
                totalTokens = it.optInt("total_tokens", 0)
            )
        } ?: Usage()
        ChatResponse(content = content, usage = usage)
    }

    suspend fun verifyKey(baseUrl: String, apiKey: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext "API 密钥为空"
        val endpoint = buildEndpoint(baseUrl, "/models")
            ?: return@withContext "接口地址无效：Android 版仅允许 https:// 开头的完整地址。"
        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) null
                else mapHttpError(response.code, response.body?.string().orEmpty()).message
            }
        } catch (e: IOException) {
            "网络连接失败，请检查网络后重试。"
        }
    }

    private fun buildEndpoint(baseUrl: String, path: String): String? {
        val base = baseUrl.trim().trimEnd('/')
        val uri = runCatching { java.net.URI(base) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return base + path
    }

    private fun mapHttpError(code: Int, body: String): ApiException {
        val apiMessage = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        val message = when (code) {
            400 -> "请求格式被接口拒绝。${apiMessage.takeIf { it.isNotBlank() }?.let { "（$it）" } ?: ""}"
            401 -> "API 密钥无效或已失效，请到设置中重新填写。"
            402 -> "账户余额不足，请到 DeepSeek 开放平台充值后重试。"
            422 -> "请求内容不符合接口要求，请检查图片或文字。"
            429 -> "请求过于频繁或额度已用尽，请稍等片刻再试。"
            in 500..599 -> "DeepSeek 服务暂时不可用，请稍后重试。"
            else -> "接口请求失败（HTTP $code）。${apiMessage.take(160)}"
        }
        return ApiException(message, code, body.take(1000))
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
