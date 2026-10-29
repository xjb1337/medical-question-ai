package com.xjb.medicalquestionai.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class AnalysisPreview(
    val status: String = "",
    val questionType: String = "unknown",
    val recognizedQuestion: String = "",
    val answer: List<String> = emptyList(),
    val answerText: String = "",
    val confidence: String = "",
    val warnings: List<String> = emptyList(),
    val statusFieldPresent: Boolean = true,
    val questionTypeFieldPresent: Boolean = true,
    val warningsFieldPresent: Boolean = true,
    val warningsFieldValid: Boolean = true,
    val rootClosed: Boolean = false,
    val semanticIssue: String? = null,
    val selectedType: QuestionType = QuestionType.AUTO
) {
    val hasUsefulContent: Boolean
        get() = recognizedQuestion.isNotBlank() || answer.any(String::isNotBlank) || answerText.isNotBlank()

    /**
     * 预览必须等 status、question_type、answer/answer_text、warnings 都完整到达，
     * 并且通过与最终解析相同的语义校验。缺字段、字段类型错误、题型冲突、
     * 单选多答案、填空/简答无 answer_text 或 needs_confirmation/out_of_scope
     * 都不能提前伪装成确定答案。
     */
    val isComplete: Boolean
        get() = statusFieldPresent &&
            (questionTypeFieldPresent || selectedType != QuestionType.AUTO) &&
            warningsFieldPresent &&
            warningsFieldValid &&
            rootClosed &&
            semanticIssue == null &&
            status == "answered" &&
            (answer.any(String::isNotBlank) || answerText.isNotBlank())

    fun toResult(): AnalysisResult = AnalysisResult(
        status = status,
        questionType = questionType,
        recognizedQuestion = recognizedQuestion,
        answer = answer,
        answerText = answerText,
        confidence = confidence.ifBlank { "medium" },
        warnings = warnings
    )
}

/**
 * 从流式累积的 JSON 文本中提取“已经完整写完”的顶层字段。
 * 不要求整个 JSON 合法，也不依赖字段顺序；用于答案先到先显示。
 */
class PreviewJsonParser {
    fun feed(
        accumulatedText: String,
        selectedType: QuestionType = QuestionType.AUTO
    ): AnalysisPreview? {
        val fields = scanCompletedTopLevelFields(accumulatedText)
        if (fields.isEmpty()) return null
        val rootStart = accumulatedText.indexOf('{')
        val rootClosed = rootStart >= 0 && findCompositeEnd(accumulatedText, rootStart) >= 0
        val status = fields.stringValue("status")
        val type = fields.stringValue("question_type").ifBlank { "unknown" }
        val question = fields.stringValue("recognized_question")
        val answer = fields.arrayValue("answer")
        val answerText = fields.stringValue("answer_text")
        val confidence = fields.stringValue("confidence")
        val warnings = fields.arrayValue("warnings")
        val optionAnalysisOptions = fields.optionAnalysisOptions()
        val warningsFieldPresent = fields.containsKey("warnings")
        val warningsFieldValid = fields["warnings"]?.let { raw ->
            runCatching { JSONArray(raw) }.isSuccess
        } ?: false
        val evaluation = AnswerSemantics.evaluate(
            status = status,
            questionType = type,
            answer = answer,
            answerText = answerText,
            warningsFieldPresent = warningsFieldPresent,
            warningsFieldValid = warningsFieldValid,
            selectedType = selectedType,
            optionAnalysisOptions = optionAnalysisOptions
        )
        val preview = AnalysisPreview(
            status = status,
            questionType = evaluation.normalizedType,
            recognizedQuestion = question,
            answer = evaluation.normalizedAnswer,
            answerText = answerText,
            confidence = confidence,
            warnings = warnings,
            statusFieldPresent = fields.containsKey("status"),
            questionTypeFieldPresent = fields.containsKey("question_type"),
            warningsFieldPresent = warningsFieldPresent,
            warningsFieldValid = warningsFieldValid,
            rootClosed = rootClosed,
            semanticIssue = evaluation.previewIssue,
            selectedType = selectedType
        )
        // 运行中先展示“预览”，完整性与语义问题由 isComplete/semanticIssue 标记；
        // 不能等到根对象闭合才显示答案，否则答案卡会永远停在占位状态。
        return preview.takeIf { it.hasUsefulContent }
    }

    /**
     * 扫描顶层对象，返回 key -> 已完成值的原始 JSON 文本。
     * 字符串/对象/数组未闭合时停止，避免把半个值解析成错误结果。
     */
    fun scanCompletedTopLevelFields(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val start = text.indexOf('{')
        if (start < 0) return result
        var index = start + 1
        while (index < text.length) {
            index = skipWhitespaceAndCommas(text, index)
            if (index >= text.length || text[index] == '}') break
            if (text[index] != '"') break
            val keyEnd = findStringEnd(text, index)
            if (keyEnd < 0) break
            val key = decodeJsonString(text.substring(index, keyEnd + 1)) ?: break
            index = skipWhitespace(text, keyEnd + 1)
            if (index >= text.length || text[index] != ':') break
            index = skipWhitespace(text, index + 1)
            val valueEnd = findValueEnd(text, index)
            if (valueEnd < 0) break
            result[key] = text.substring(index, valueEnd + 1).trim()
            index = valueEnd + 1
        }
        return result
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    private fun skipWhitespaceAndCommas(text: String, start: Int): Int {
        var i = start
        while (i < text.length && (text[i].isWhitespace() || text[i] == ',')) i++
        return i
    }

    /** 返回结束引号的下标；未闭合返回 -1。 */
    private fun findStringEnd(text: String, startQuote: Int): Int {
        var escaped = false
        var i = startQuote + 1
        while (i < text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '"') {
                return i
            }
            i++
        }
        return -1
    }

    /** 返回顶层值的最后一个字符下标；未闭合返回 -1。 */
    private fun findValueEnd(text: String, start: Int): Int {
        if (start >= text.length) return -1
        return when (text[start]) {
            '"' -> findStringEnd(text, start)
            '{', '[' -> findCompositeEnd(text, start)
            else -> {
                var i = start
                while (i < text.length && text[i] != ',' && text[i] != '}' && !text[i].isWhitespace()) i++
                if (i == start) -1 else i - 1
            }
        }
    }

    private fun findCompositeEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        var i = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    private fun decodeJsonString(raw: String): String? {
        return runCatching {
            JSONTokener(raw).nextValue() as? String
        }.getOrNull()
    }

    private fun Map<String, String>.stringValue(key: String): String {
        val raw = this[key] ?: return ""
        return runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
    }

    private fun Map<String, String>.optionAnalysisOptions(): List<String> {
        val raw = this["option_analysis"] ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val letter = item.opt("option") as? String
            if (letter.isNullOrBlank()) null else letter.trim()
        }
    }

    private fun Map<String, String>.arrayValue(key: String): List<String> {
        val raw = this[key] ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.opt(index).let { value ->
                when (value) {
                    is String -> value.trim().takeIf(String::isNotBlank)
                    is Number -> value.toString()
                    is JSONObject -> ((value.opt("value") as? String)?.takeIf(String::isNotBlank)
                        ?: (value.opt("option") as? String)?.takeIf(String::isNotBlank))
                    else -> null
                }
            }
        }
    }
}
