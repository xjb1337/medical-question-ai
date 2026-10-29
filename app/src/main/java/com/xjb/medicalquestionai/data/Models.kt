package com.xjb.medicalquestionai.data

import com.xjb.medicalquestionai.toReadablePlainText
import org.json.JSONArray
import org.json.JSONObject

enum class QuestionType(val storageValue: String, val label: String, val hint: String) {
    AUTO("auto", "自动判断", "由模型识别题型"),
    SINGLE("single", "单选题", "只有一个正确选项"),
    MULTIPLE("multiple", "多选题", "可能有两个或更多正确选项"),
    FILL("fill", "填空题", "按空的顺序逐空作答"),
    SHORT("short", "简答题", "按要点分条作答");

    companion object {
        fun fromStorage(value: String?): QuestionType =
            entries.firstOrNull { it.storageValue == value } ?: AUTO
    }
}

data class DraftImage(
    val id: String,
    val path: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val warnings: List<String> = emptyList()
)

data class QuestionDraft(
    val questionType: QuestionType = QuestionType.AUTO,
    val text: String = "",
    val images: List<DraftImage> = emptyList(),
    val privacyConfirmed: Boolean = false
)

data class ReasoningStep(
    val title: String,
    val detail: String
)

data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0
) {
    val isAvailable: Boolean
        get() = totalTokens > 0 || promptTokens > 0 || completionTokens > 0

    val shortLabel: String
        get() = if (isAvailable) "本次用量 ${totalTokens} tokens（输入 ${promptTokens} / 输出 ${completionTokens}）" else ""
}

data class ImageObservation(
    val imageIndex: Int,
    val observation: String,
    val uncertainty: String = ""
) {
    val title: String
        get() = "图 $imageIndex"
}

data class OptionAnalysis(
    val option: String,
    val verdict: String,
    val reason: String
)

data class FollowUp(
    val id: String,
    val questionId: String,
    val role: String,
    val content: String,
    val createdAt: Long,
    val usage: Usage = Usage(),
    val failed: Boolean = false,
    /** 该追问所属的答案版本；旧答案的追问不得混入新答案。 */
    val answerRevision: Long = 0
)

data class AnalysisMetrics(
    val requestModel: String = "",
    val responseModel: String = "",
    val thinkingEnabled: Boolean = false,
    val streamed: Boolean = false,
    val finishReason: String = "",
    val transportClosed: Boolean = false,
    val saveStatus: String = "",
    val totalMs: Long = 0,
    val answerMode: String = "",
    val clickToImageReadyMs: Long? = null,
    val clickToRequestReadyMs: Long? = null,
    /** 请求体真正发送结束（OkHttp requestBodyEnd）；未知时为空。 */
    val clickToRequestBodySentMs: Long? = null,
    val clickToHeadersMs: Long? = null,
    val clickToFirstEventMs: Long? = null,
    val clickToFirstContentMs: Long? = null,
    val clickToFirstAnswerMs: Long? = null,
    val validationMs: Long? = null,
    val saveMs: Long? = null
)

private fun JSONObject.putNullable(key: String, value: Long?) {
    put(key, value ?: JSONObject.NULL)
}

data class AnalysisResult(
    val status: String = "answered",
    val questionType: String = "unknown",
    val recognizedQuestion: String = "",
    val answer: List<String> = emptyList(),
    val answerText: String = "",
    val imageObservations: List<ImageObservation> = emptyList(),
    val optionAnalyses: List<OptionAnalysis> = emptyList(),
    val reasoningSteps: List<ReasoningStep> = emptyList(),
    val keyPoints: List<String> = emptyList(),
    val pitfalls: List<String> = emptyList(),
    val confidence: String = "medium",
    val warnings: List<String> = emptyList(),
    val rawJson: String = "",
    val usage: Usage = Usage(),
    val metrics: AnalysisMetrics? = null
) {
    val needsConfirmation: Boolean
        get() = status != "out_of_scope" &&
            (status == "needs_confirmation" || (answer.isEmpty() && answerText.isBlank()))

    val answerDisplay: String
        get() = when {
            answer.isNotEmpty() -> answer.joinToString("、")
            answerText.isNotBlank() -> answerText.toReadablePlainText()
            else -> "未能确定答案"
        }

    val confidenceLabel: String
        get() = when (confidence.lowercase()) {
            "high" -> "把握较高"
            "low" -> "把握较低"
            else -> "把握中等"
        }

    fun toJson(): String = JSONObject().apply {
        put("status", status)
        put("question_type", questionType)
        put("recognized_question", recognizedQuestion)
        put("answer", JSONArray(answer))
        put("answer_text", answerText)
        put("image_observations", JSONArray().apply {
            imageObservations.forEach {
                put(JSONObject().apply {
                    put("image_index", it.imageIndex)
                    put("observation", it.observation)
                    put("uncertainty", it.uncertainty)
                })
            }
        })
        put("option_analysis", JSONArray().apply {
            optionAnalyses.forEach {
                put(JSONObject().apply {
                    put("option", it.option)
                    put("verdict", it.verdict)
                    put("reason", it.reason)
                })
            }
        })
        put("reasoning_steps", JSONArray().apply {
            reasoningSteps.forEach {
                put(JSONObject().apply {
                    put("title", it.title)
                    put("detail", it.detail)
                })
            }
        })
        put("key_points", JSONArray(keyPoints))
        put("pitfalls", JSONArray(pitfalls))
        put("confidence", confidence)
        put("warnings", JSONArray(warnings))
        put("usage", JSONObject().apply {
            put("prompt_tokens", usage.promptTokens)
            put("completion_tokens", usage.completionTokens)
            put("total_tokens", usage.totalTokens)
        })
        metrics?.let { m ->
            put("metrics", JSONObject().apply {
                put("request_model", m.requestModel)
                put("response_model", m.responseModel)
                put("thinking_enabled", m.thinkingEnabled)
                put("streamed", m.streamed)
                put("finish_reason", m.finishReason)
                put("transport_closed", m.transportClosed)
                put("save_status", m.saveStatus)
                put("total_ms", m.totalMs)
                put("answer_mode", m.answerMode)
                putNullable("click_to_image_ready_ms", m.clickToImageReadyMs)
                putNullable("click_to_request_ready_ms", m.clickToRequestReadyMs)
                putNullable("click_to_request_body_sent_ms", m.clickToRequestBodySentMs)
                putNullable("click_to_headers_ms", m.clickToHeadersMs)
                putNullable("click_to_first_event_ms", m.clickToFirstEventMs)
                putNullable("click_to_first_content_ms", m.clickToFirstContentMs)
                putNullable("click_to_first_answer_ms", m.clickToFirstAnswerMs)
                putNullable("validation_ms", m.validationMs)
                putNullable("save_ms", m.saveMs)
            })
        }
    }.toString()

    companion object {
        fun fromJson(raw: String): AnalysisResult {
            val obj = runCatching { JSONObject(raw) }.getOrElse { return AnalysisResult(rawJson = raw) }
            fun text(item: JSONObject, key: String, fallback: String = ""): String {
                val value = item.opt(key)
                return (value as? String)?.takeIf { it.isNotBlank() } ?: fallback
            }
            fun longOrNull(item: JSONObject, key: String): Long? =
                if (item.has(key) && !item.isNull(key)) item.optLong(key) else null
            fun stringList(key: String): List<String> {
                val value = obj.opt(key) ?: return emptyList()
                return when (value) {
                    is JSONArray -> (0 until value.length()).mapNotNull { index ->
                        (value.opt(index) as? String)?.takeIf(String::isNotBlank)
                    }
                    is String -> if (value.isBlank()) emptyList() else listOf(value)
                    else -> emptyList()
                }
            }
            val steps = mutableListOf<ReasoningStep>()
            obj.optJSONArray("reasoning_steps")?.let { array ->
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    if (item != null) {
                        steps += ReasoningStep(
                            title = text(item, "title", "步骤 ${i + 1}"),
                            detail = text(item, "detail", text(item, "content"))
                        )
                    } else {
                        val text = array.opt(i) as? String
                        if (!text.isNullOrBlank()) steps += ReasoningStep("步骤 ${i + 1}", text)
                    }
                }
            }
            val observations = mutableListOf<ImageObservation>()
            obj.optJSONArray("image_observations")?.let { array ->
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    if (item != null) {
                        val observation = text(item, "observation", text(item, "summary"))
                        if (observation.isNotBlank()) {
                            observations += ImageObservation(
                                imageIndex = item.optInt("image_index", i + 1),
                                observation = observation,
                                uncertainty = text(item, "uncertainty", text(item, "uncertain"))
                            )
                        }
                    } else {
                        val text = array.opt(i) as? String
                        if (!text.isNullOrBlank()) observations += ImageObservation(i + 1, text)
                    }
                }
            }
            val options = mutableListOf<OptionAnalysis>()
            fun addOption(item: JSONObject, fallbackLabel: String) {
                val option = text(item, "option", text(item, "label"))
                val reason = text(item, "reason", text(item, "detail"))
                if (option.isNotBlank() || reason.isNotBlank()) {
                    options += OptionAnalysis(
                        option = option.ifBlank { fallbackLabel },
                        verdict = text(item, "verdict", text(item, "status")),
                        reason = reason
                    )
                }
            }
            obj.optJSONArray("option_analysis")?.let { array ->
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { addOption(it, "选项 ${i + 1}") }
                }
            }
            obj.optJSONObject("option_analysis")?.let { map ->
                map.keys().forEach { key ->
                    val value = map.opt(key)
                    when (value) {
                        is JSONObject -> addOption(value, key)
                        is String -> options += OptionAnalysis(key, "", value)
                    }
                }
            }
            return AnalysisResult(
                status = text(obj, "status", "answered"),
                questionType = text(obj, "question_type", text(obj, "type", "unknown")),
                recognizedQuestion = text(obj, "recognized_question", text(obj, "question")),
                answer = stringList("answer"),
                answerText = text(obj, "answer_text", text(obj, "answerText")),
                imageObservations = observations,
                optionAnalyses = options,
                reasoningSteps = steps,
                keyPoints = stringList("key_points"),
                pitfalls = stringList("pitfalls"),
                confidence = text(obj, "confidence", "medium"),
                warnings = stringList("warnings"),
                rawJson = raw,
                usage = obj.optJSONObject("usage")?.let { usage ->
                    Usage(
                        promptTokens = usage.optInt("prompt_tokens", usage.optInt("input_tokens", 0)),
                        completionTokens = usage.optInt("completion_tokens", usage.optInt("output_tokens", 0)),
                        totalTokens = usage.optInt("total_tokens", 0)
                    )
                } ?: Usage(),
                metrics = obj.optJSONObject("metrics")?.let { m ->
                    AnalysisMetrics(
                        requestModel = text(m, "request_model"),
                        responseModel = text(m, "response_model"),
                        thinkingEnabled = m.optBoolean("thinking_enabled", false),
                        streamed = m.optBoolean("streamed", false),
                        finishReason = text(m, "finish_reason"),
                        transportClosed = m.optBoolean("transport_closed", false),
                        saveStatus = text(m, "save_status"),
                        totalMs = m.optLong("total_ms", 0),
                        answerMode = text(m, "answer_mode"),
                        clickToImageReadyMs = longOrNull(m, "click_to_image_ready_ms"),
                        clickToRequestReadyMs = longOrNull(m, "click_to_request_ready_ms"),
                        clickToRequestBodySentMs = longOrNull(m, "click_to_request_body_sent_ms"),
                        clickToHeadersMs = longOrNull(m, "click_to_headers_ms"),
                        clickToFirstEventMs = longOrNull(m, "click_to_first_event_ms"),
                        clickToFirstContentMs = longOrNull(m, "click_to_first_content_ms"),
                        clickToFirstAnswerMs = longOrNull(m, "click_to_first_answer_ms"),
                        validationMs = longOrNull(m, "validation_ms"),
                        saveMs = longOrNull(m, "save_ms")
                    )
                }
            )
        }
    }
}

data class Question(
    val id: String,
    val createdAt: Long,
    val questionType: String,
    val source: String,
    val questionText: String,
    val imagePaths: List<String>,
    val status: String,
    val result: AnalysisResult?,
    val model: String,
    val errorMessage: String?,
    val isFavorite: Boolean,
    val isEphemeral: Boolean = false,
    val mode: String = AnswerMode.STANDARD.storageValue,
    val durationMs: Long = 0,
    val promptVersion: String = "",
    val baseUrl: String = "",
    /** 题目输入版本：题干或图片每次修改都递增。 */
    val inputRevision: Long = 0,
    /** 当前展示答案对应的输入版本；旧记录均为 0，与 inputRevision 相同。 */
    val answerInputRevision: Long = 0,
    /** 答案版本：每次成功生成新答案递增，用于隔离旧追问。 */
    val answerRevision: Long = 0
) {
    val isAnswerStale: Boolean
        get() = result != null && answerInputRevision != inputRevision

    val modeLabel: String
        get() = AnswerMode.fromStorage(mode).label

    val modelLabel: String
        get() = ModelCatalog.displayName(model)

    val typeLabel: String
        get() = when (questionType) {
            "single" -> "单选"
            "multiple" -> "多选"
            "fill" -> "填空"
            "short" -> "简答"
            else -> "自动"
        }

    val sourceLabel: String
        get() = when (source) {
            "camera" -> "拍照"
            "gallery" -> "相册"
            "text" -> "文字"
            "mixed" -> "图文"
            else -> "题目"
        }

    val shortQuestion: String
        get() = questionText.replace('\n', ' ').trim().ifBlank {
            if (imagePaths.isNotEmpty()) "图片题目（${imagePaths.size} 张）" else "未命名题目"
        }
}

data class AppSettings(
    val apiKeyConfigured: Boolean = false,
    val apiKeyHint: String = "",
    val model: String = ModelCatalog.FLASH.id,
    val baseUrl: String = "https://api.deepseek.com",
    val saveHistory: Boolean = true,
    val answerMode: AnswerMode = AnswerMode.STANDARD,
    val settingsVersion: Int = SettingsMigrations.CURRENT_VERSION,
    val modelMigrationNote: String = "",
    /** 未知/自定义模型的能力声明；官方模型仍以官方能力表为准。 */
    val customModelSupportsImages: Boolean = false,
    val customModelStreaming: Boolean = false
) {
    val modelSupportsImages: Boolean
        get() = ModelCatalog.supportsImages(model, customModelSupportsImages)
    val modelLabel: String get() = ModelCatalog.displayName(model)
}
