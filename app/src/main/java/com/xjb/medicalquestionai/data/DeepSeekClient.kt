package com.xjb.medicalquestionai.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ApiException(message: String, val code: Int? = null, val raw: String? = null) : Exception(message)

data class ChatMessage(val role: String, val content: String)

data class ChatResponse(
    val content: String,
    val usage: Usage = Usage(),
    val model: String = ""
)

data class ThinkingConfig(
    val enabled: Boolean,
    val effort: String? = null
)

data class ChatRequest(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val messages: List<ChatMessage>,
    val imagesBase64: List<String> = emptyList(),
    val maxTokens: Int = 4096,
    val temperature: Double? = 0.15,
    val thinking: ThinkingConfig = ThinkingConfig(enabled = false),
    /**
     * 未知/自定义接口不能假设支持 DeepSeek 的 thinking 字段；为 false 时完全不发送该参数。
     * 已知官方模型才显式发送 disabled/enabled。
     */
    val sendThinkingParam: Boolean = true,
    val jsonMode: Boolean = false
)

sealed interface ChatStreamEvent {
    data object ResponseStarted : ChatStreamEvent
    data class ResponseModel(val model: String) : ChatStreamEvent
    data class ReasoningDelta(val text: String) : ChatStreamEvent
    data class ContentDelta(val text: String) : ChatStreamEvent
    data class UsageUpdate(val usage: Usage) : ChatStreamEvent
    data class Finished(val finishReason: String?) : ChatStreamEvent
    data object StreamDone : ChatStreamEvent
}

data class ConnectionCheckResult(
    val modelIds: List<String>,
    val selectedModelFound: Boolean,
    /** /models 是否真正返回了可用的模型列表；空列表时无法据此核对模型名。 */
    val modelListAvailable: Boolean = true
)

/**
 * 构造请求体。独立成顶层函数，供单元测试检查三档参数，不依赖网络。
 */
internal fun buildChatPayload(request: ChatRequest, stream: Boolean = true): JSONObject {
    val messageArray = JSONArray().apply {
        request.messages.forEachIndexed { index, message ->
            val isLastUser = index == request.messages.lastIndex && message.role == "user"
            val content: Any = if (isLastUser && request.imagesBase64.isNotEmpty()) {
                JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", message.content))
                    request.imagesBase64.forEach { base64 ->
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
    return JSONObject().apply {
        put("model", request.model)
        put("messages", messageArray)
        put("max_tokens", request.maxTokens)
        put("stream", stream)
        if (request.sendThinkingParam) {
            if (request.thinking.enabled) {
                put("thinking", JSONObject().put("type", "enabled"))
                request.thinking.effort?.takeIf { it.isNotBlank() }?.let { put("reasoning_effort", it) }
            } else {
                put("thinking", JSONObject().put("type", "disabled"))
                request.temperature?.let { put("temperature", it) }
            }
        } else {
            // 未知/自定义接口不假设支持 DeepSeek thinking 参数。
            request.temperature?.let { put("temperature", it) }
        }
        if (request.jsonMode) {
            put("response_format", JSONObject().put("type", "json_object"))
        }
        if (stream && ModelCatalog.isOfficialBaseUrl(request.baseUrl)) {
            put("stream_options", JSONObject().put("include_usage", true))
        }
    }
}

class DeepSeekClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // 流式回答的耗时由调用侧 withTimeout 按快速/标准/详细档位控制；
        // 这里不能设 45s 固定读超时，否则长答案会在模式超时前被 OkHttp 截断。
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 真正的流式请求：OkHttp enqueue + callbackFlow。
     * 协程取消时 awaitClose 触发 call.cancel()，阻塞中的 body 读取会立即中断。
     */
    fun stream(request: ChatRequest): Flow<ChatStreamEvent> = callbackFlow {
        val httpRequest = buildHttpRequest(request, stream = true)
        val call = client.newCall(httpRequest)
        val closed = AtomicBoolean(false)
        val startedAt = System.currentTimeMillis()
        val firstEventAt = AtomicLong(0L)
        val lastProgressAt = AtomicLong(startedAt)

        fun observe(events: List<ChatStreamEvent>) {
            if (events.isEmpty()) return
            val now = System.currentTimeMillis()
            firstEventAt.compareAndSet(0L, now)
            if (events.any { it is ChatStreamEvent.ContentDelta || it is ChatStreamEvent.ReasoningDelta }) {
                lastProgressAt.set(now)
            }
        }

        // 45 秒内没有任何非 keep-alive 事件，或已有事件后连续 45 秒没有正文/推理增量，
        // 都主动取消底层请求并抛出可读错误；keep-alive 注释在 SseParser 中已被忽略。
        val watchdog = launch {
            while (isActive) {
                delay(1_000)
                val now = System.currentTimeMillis()
                if (firstEventAt.get() == 0L) {
                    if (now - startedAt >= FIRST_EVENT_TIMEOUT_MS) {
                        close(ApiException("等待模型响应超时（超过 45 秒），请检查网络或稍后重试。"))
                        return@launch
                    }
                } else if (now - lastProgressAt.get() >= NO_PROGRESS_TIMEOUT_MS) {
                    close(ApiException("模型输出中断（超过 45 秒没有有效内容），请重试。"))
                    return@launch
                }
            }
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (closed.get()) return
                close(
                    if (call.isCanceled()) CancellationException("请求已取消")
                    else ApiException("网络连接失败，请检查网络后重试。", raw = e.message)
                )
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            val text = resp.body?.string().orEmpty()
                            close(mapHttpError(resp.code, text))
                            return
                        }
                        val body = resp.body
                        if (body == null) {
                            close(ApiException("接口没有返回内容。"))
                            return
                        }
                        trySend(ChatStreamEvent.ResponseStarted)
                        val parser = SseParser()
                        val input = body.byteStream()
                        val buffer = ByteArray(8192)
                        var receivedDone = false
                        while (!receivedDone) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            val events = parser.feed(buffer, 0, read)
                            for (event in events) {
                                if (event.data.trim() == "[DONE]") {
                                    receivedDone = true
                                    trySend(ChatStreamEvent.StreamDone)
                                    break
                                }
                                val parsed = parseStreamEvent(event.data)
                                observe(parsed)
                                parsed.forEach { trySend(it) }
                            }
                        }
                        if (!receivedDone) {
                            parser.finish().forEach { event ->
                                if (event.data.trim() == "[DONE]") {
                                    trySend(ChatStreamEvent.StreamDone)
                                    return@forEach
                                }
                                val parsed = parseStreamEvent(event.data)
                                observe(parsed)
                                parsed.forEach { trySend(it) }
                            }
                        }
                    }
                    close()
                } catch (e: CancellationException) {
                    close()
                } catch (e: IOException) {
                    if (closed.get() || call.isCanceled()) close()
                    else close(ApiException("网络连接中断，请检查网络后重试。", raw = e.message))
                } catch (e: Exception) {
                    close(e)
                }
            }
        })

        awaitClose {
            closed.set(true)
            watchdog.cancel()
            call.cancel()
        }
    }.buffer(Channel.UNLIMITED)

    /** 非流式调用，仅用于轻量验证与追问；超时由调用方给出，取消会真正中断底层连接。 */
    suspend fun chatOnce(request: ChatRequest, timeoutMs: Long = CHAT_ONCE_TIMEOUT_MS): ChatResponse {
        val response = try {
            withTimeout(timeoutMs) { executeTextRequest(buildHttpRequest(request, stream = false)) }
        } catch (e: TimeoutCancellationException) {
            throw ApiException("请求超时（超过 ${timeoutMs / 1000} 秒），请稍后重试或改用快速档。")
        }
        if (response.code !in 200..299) throw mapHttpError(response.code, response.body)
        val json = runCatching { JSONObject(response.body) }.getOrElse {
            throw ApiException("接口返回了无法识别的数据。", raw = response.body.take(500))
        }
        json.optJSONObject("error")?.let {
            throw ApiException(it.optString("message", "接口返回错误"), raw = response.body.take(1000))
        }
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            throw ApiException("接口没有返回内容。", raw = response.body.take(1000))
        }
        val message = choices.getJSONObject(0).optJSONObject("message")
            ?: throw ApiException("接口返回结构异常。", raw = response.body.take(1000))
        return ChatResponse(
            content = message.optString("content", ""),
            usage = json.optJSONObject("usage").toUsage(),
            model = json.optString("model", "")
        )
    }

    /**
     * “检查连接”：只拉取 /models，证明接口可达、Key 可用、选中模型是否出现在列表中。
     * 不对模型做生成调用；网络失败不修改本地设置。
     */
    suspend fun checkConnection(baseUrl: String, apiKey: String, model: String): ConnectionCheckResult {
        if (apiKey.isBlank()) throw ApiException("API 密钥为空")
        val endpoint = buildEndpoint(baseUrl, "/models")
            ?: throw ApiException("接口地址无效：Android 版仅允许 https:// 开头的完整地址。")
        val modelsRequest = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()
        val response = try {
            withTimeout(CHECK_TIMEOUT_MS) { executeTextRequest(modelsRequest) }
        } catch (e: TimeoutCancellationException) {
            throw ApiException("连接检查超时，请检查网络后重试。")
        }
        if (response.code !in 200..299) throw mapHttpError(response.code, response.body)
        val modelIds = runCatching {
            val data = JSONObject(response.body).optJSONArray("data") ?: JSONArray()
            (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }
                .filter { it.isNotBlank() }
        }.getOrDefault(emptyList())
        return ConnectionCheckResult(
            modelIds = modelIds,
            selectedModelFound = modelIds.isNotEmpty() && model in modelIds,
            modelListAvailable = modelIds.isNotEmpty()
        )
    }

    private data class HttpTextResponse(val code: Int, val body: String)

    /** 用 OkHttp enqueue 实现可取消的非流式请求，避免 execute() 阻塞协程导致超时失效。 */
    private suspend fun executeTextRequest(request: Request): HttpTextResponse =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!continuation.isActive) return
                    continuation.resumeWithException(
                        if (call.isCanceled()) CancellationException("请求已取消")
                        else ApiException("网络连接失败，请检查网络后重试。", raw = e.message)
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    try {
                        response.use {
                            continuation.resume(HttpTextResponse(it.code, it.body?.string().orEmpty()))
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    /**
     * “验证模型”：用户主动触发一次极短文字请求，显式关闭思考，输出预算 48 token。
     * 返回 null 表示拿到可读回答；只验证文字链路，不能声明图片解析已验证。
     */
    suspend fun verifyModel(baseUrl: String, apiKey: String, model: String): String? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) return@withContext "API 密钥为空"
            try {
                val response = chatOnce(
                    ChatRequest(
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
                        messages = listOf(
                            ChatMessage("system", "你是连通性检测助手，只回复 OK。"),
                            ChatMessage("user", "ping")
                        ),
                        maxTokens = 48,
                        temperature = 0.0,
                        thinking = ThinkingConfig(enabled = false),
                        sendThinkingParam = ModelCatalog.capability(model) != ModelCapability.UNKNOWN
                    )
                )
                if (response.content.isBlank()) "模型返回空内容，请检查模型是否可用。" else null
            } catch (e: ApiException) {
                e.message
            }
        }

    /** 兼容旧调用：先检查 /models，再做一次极短文字验证。 */
    suspend fun verifyConnection(baseUrl: String, apiKey: String, model: String): String? {
        val check = try {
            checkConnection(baseUrl, apiKey, model)
        } catch (e: ApiException) {
            return e.message
        }
        if (!check.modelListAvailable) {
            // /models 为空不能说明模型一定不存在；继续用一次极短生成请求验证真实可用性。
            return verifyModel(baseUrl, apiKey, model)
        }
        if (!check.selectedModelFound) {
            return "接口可用，但模型 $model 不在返回列表中。请检查模型名或账号权限。"
        }
        return verifyModel(baseUrl, apiKey, model)
    }

    suspend fun verifyKey(baseUrl: String, apiKey: String): String? =
        verifyConnection(baseUrl, apiKey, ModelCatalog.FLASH.id)

    private fun buildHttpRequest(request: ChatRequest, stream: Boolean): Request {
        val endpoint = buildEndpoint(request.baseUrl, "/chat/completions")
            ?: throw ApiException("接口地址无效：Android 版仅允许 https:// 开头的完整地址。")
        val payload = buildChatPayload(request, stream = stream)
        val payloadText = payload.toString()
        if (payloadText.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) {
            throw ApiException("题目图片总量过大（超过 28MB），请减少图片数量或重新裁剪后再试。")
        }
        return Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer ${request.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(payloadText.toRequestBody(JSON_TYPE))
            .build()
    }

    /**
     * 一个 SSE data 块可能同时携带 usage、正文增量和 finish_reason，必须按列表返回，
     * 不能因为先读到 usage 就丢掉同一块的结束原因。
     */
    private fun parseStreamEvent(data: String): List<ChatStreamEvent> {
        if (data.isBlank()) return emptyList()
        val json = runCatching { JSONObject(data) }.getOrElse {
            throw ApiException("流式接口返回了无法识别的数据。", raw = data.take(500))
        }
        json.optJSONObject("error")?.let {
            throw ApiException(it.optString("message", "接口返回错误"), raw = data.take(1000))
        }
        val events = mutableListOf<ChatStreamEvent>()
        json.optString("model", "").takeIf { it.isNotBlank() }?.let { events += ChatStreamEvent.ResponseModel(it) }
        val usage = json.optJSONObject("usage").toUsage()
        if (usage.isAvailable) events += ChatStreamEvent.UsageUpdate(usage)
        val choices = json.optJSONArray("choices") ?: return events
        if (choices.length() == 0) return events
        val choice = choices.optJSONObject(0) ?: return events
        val delta = choice.optJSONObject("delta")
        val reasoning = delta?.optString("reasoning_content", "").orEmpty()
            .ifBlank { delta?.optString("reasoning", "").orEmpty() }
        val content = delta?.optString("content", "").orEmpty()
        if (reasoning.isNotBlank()) events += ChatStreamEvent.ReasoningDelta(reasoning)
        if (content.isNotBlank()) events += ChatStreamEvent.ContentDelta(content)
        val finishReason = choice.optString("finish_reason", "").takeIf { it.isNotBlank() }
        if (finishReason != null) events += ChatStreamEvent.Finished(finishReason)
        return events
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
            404 -> "接口地址或模型名称不正确。${apiMessage.take(160)}"
            422 -> "请求内容不符合接口要求，请检查图片或文字。"
            429 -> "请求过于频繁或额度已用尽，请稍等片刻再试。"
            in 500..599 -> "DeepSeek 服务暂时不可用，请稍后重试。"
            else -> "接口请求失败（HTTP $code）。${apiMessage.take(160)}"
        }
        return ApiException(message, code, body.take(1000))
    }

    private fun JSONObject?.toUsage(): Usage {
        if (this == null) return Usage()
        return Usage(
            promptTokens = optInt("prompt_tokens", 0),
            completionTokens = optInt("completion_tokens", 0),
            totalTokens = optInt("total_tokens", 0)
        )
    }

    companion object {
        private const val CHAT_ONCE_TIMEOUT_MS = 120_000L
        private const val CHECK_TIMEOUT_MS = 30_000L
        private const val FIRST_EVENT_TIMEOUT_MS = 45_000L
        private const val NO_PROGRESS_TIMEOUT_MS = 45_000L
        private const val MAX_REQUEST_BYTES = 28 * 1024 * 1024
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
