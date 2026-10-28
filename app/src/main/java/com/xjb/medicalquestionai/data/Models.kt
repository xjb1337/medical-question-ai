package com.xjb.medicalquestionai.data

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
    val failed: Boolean = false
)

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
    val usage: Usage = Usage()
) {
    val needsConfirmation: Boolean
        get() = status != "out_of_scope" &&
            (status == "needs_confirmation" || (answer.isEmpty() && answerText.isBlank()))

    val answerDisplay: String
        get() = when {
            answer.isNotEmpty() -> answer.joinToString("、")
            answerText.isNotBlank() -> answerText
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
    }.toString()

    companion object {
        fun fromJson(raw: String): AnalysisResult {
            val obj = runCatching { JSONObject(raw) }.getOrElse { return AnalysisResult(rawJson = raw) }
            fun stringList(key: String): List<String> {
                val value = obj.opt(key) ?: return emptyList()
                return when (value) {
                    is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
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
                            title = item.optString("title", "步骤 ${i + 1}"),
                            detail = item.optString("detail", item.optString("content"))
                        )
                    } else {
                        val text = array.optString(i)
                        if (text.isNotBlank()) steps += ReasoningStep("步骤 ${i + 1}", text)
                    }
                }
            }
            val observations = mutableListOf<ImageObservation>()
            obj.optJSONArray("image_observations")?.let { array ->
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i)
                    if (item != null) {
                        val observation = item.optString("observation", item.optString("summary"))
                        if (observation.isNotBlank()) {
                            observations += ImageObservation(
                                imageIndex = item.optInt("image_index", i + 1),
                                observation = observation,
                                uncertainty = item.optString("uncertainty", item.optString("uncertain"))
                            )
                        }
                    } else {
                        val text = array.optString(i)
                        if (text.isNotBlank()) observations += ImageObservation(i + 1, text)
                    }
                }
            }
            val options = mutableListOf<OptionAnalysis>()
            fun addOption(item: JSONObject, fallbackLabel: String) {
                val option = item.optString("option", item.optString("label", ""))
                val reason = item.optString("reason", item.optString("detail", ""))
                if (option.isNotBlank() || reason.isNotBlank()) {
                    options += OptionAnalysis(
                        option = option.ifBlank { fallbackLabel },
                        verdict = item.optString("verdict", item.optString("status", "")),
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
                status = obj.optString("status", "answered"),
                questionType = obj.optString("question_type", obj.optString("type", "unknown")),
                recognizedQuestion = obj.optString("recognized_question", obj.optString("question", "")),
                answer = stringList("answer"),
                answerText = obj.optString("answer_text", obj.optString("answerText", "")),
                imageObservations = observations,
                optionAnalyses = options,
                reasoningSteps = steps,
                keyPoints = stringList("key_points"),
                pitfalls = stringList("pitfalls"),
                confidence = obj.optString("confidence", "medium"),
                warnings = stringList("warnings"),
                rawJson = raw,
                usage = obj.optJSONObject("usage")?.let { usage ->
                    Usage(
                        promptTokens = usage.optInt("prompt_tokens", usage.optInt("input_tokens", 0)),
                        completionTokens = usage.optInt("completion_tokens", usage.optInt("output_tokens", 0)),
                        totalTokens = usage.optInt("total_tokens", 0)
                    )
                } ?: Usage()
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
    val isEphemeral: Boolean = false
) {
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
    val model: String = "deepseek-flash",
    val baseUrl: String = "https://api.deepseek.com",
    val saveHistory: Boolean = true
)
