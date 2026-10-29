package com.xjb.medicalquestionai.data

import org.json.JSONObject

/**
 * 最终结果的严格解析器。独立成纯 Kotlin 对象，便于 JVM 单测覆盖：
 * 前后夹带解释、Markdown 代码块、字符串里出现花括号、末尾被截断等失败路径。
 */
object AnalysisResultParser {

    fun parse(raw: String, draft: QuestionDraft): AnalysisResult {
        val jsonText = extractJsonObject(raw)
        val obj = runCatching { JSONObject(jsonText) }.getOrElse {
            throw ApiException("模型返回的 JSON 无法解析，请重试。", raw = raw.take(1000))
        }
        val source = obj.optJSONObject("result") ?: obj
        val parsed = AnalysisResult.fromJson(source.toString())
        val warnings = parsed.warnings.toMutableList()
        val warningsFieldPresent = source.has("warnings")
        val warningsRaw = source.opt("warnings")
        val warningsFieldValid = warningsRaw is org.json.JSONArray
        val evaluation = AnswerSemantics.evaluate(
            status = parsed.status,
            questionType = parsed.questionType,
            answer = parsed.answer,
            answerText = parsed.answerText,
            warningsFieldPresent = warningsFieldPresent,
            warningsFieldValid = warningsFieldValid,
            selectedType = draft.questionType,
            optionAnalysisOptions = parsed.optionAnalyses.map { it.option }
        )
        val normalizedType = evaluation.normalizedType
        val statusValue = source.opt("status") as? String
        val statusFieldValid = !statusValue.isNullOrBlank() && evaluation.statusValid
        var answerInvalid = evaluation.isAnswerInvalid

        if (!statusFieldValid) {
            warnings += "模型返回的状态字段无效，已按待确认处理，请核对答案。"
        }
        if (parsed.needsConfirmation) {
            warnings += "题干、选项或图片信息可能不完整，当前答案需要你对照原题核对。"
        }
        if (!evaluation.typeValid) {
            warnings += "未能确定题型，请手动选择题型或补充题干。"
        }
        if (evaluation.typeConflict) {
            warnings += "模型识别题型与用户选择不一致，请核对题型后重试。"
        }
        if (evaluation.warningsIssue != null) {
            warnings += "模型返回的 warnings 字段格式异常，请核对结果。"
        }
        evaluation.answerIssue?.let { issue ->
            warnings += issue
        }
        if (parsed.status == "answered" && parsed.answer.isEmpty() && parsed.answerText.isBlank()) {
            warnings += "没有提取到明确答案。"
        }
        if (parsed.confidence.equals("low", ignoreCase = true)) {
            warnings += "模型自评把握较低，建议查阅教材或请老师确认。"
        }
        if (parsed.recognizedQuestion.isBlank() && draft.images.isNotEmpty() && draft.text.isBlank()) {
            warnings += "没有完整识别出题干，请确认图片是否清晰完整。"
        }
        if (draft.images.isNotEmpty() && parsed.imageObservations.isEmpty()) {
            warnings += "模型没有逐张说明图片观察结果，请重点核对题干和图中细节。"
        }
        if (normalizedType in setOf("single", "multiple") && parsed.optionAnalyses.isEmpty()) {
            warnings += "模型没有给出逐项分析，选项辨析请结合教材核对。"
        }

        val status = when {
            parsed.status == "out_of_scope" -> "out_of_scope"
            parsed.status == "needs_confirmation" -> "needs_confirmation"
            !statusFieldValid -> "needs_confirmation"
            !evaluation.typeValid -> "needs_confirmation"
            evaluation.warningsIssue != null -> "needs_confirmation"
            evaluation.typeConflict -> "needs_confirmation"
            answerInvalid -> "needs_confirmation"
            parsed.answer.isEmpty() && parsed.answerText.isBlank() -> "needs_confirmation"
            else -> "answered"
        }
        // 题型冲突时不能把模型给的另一题型答案继续展示成确定答案。
        val conflict = evaluation.typeConflict
        return parsed.copy(
            status = status,
            questionType = normalizedType,
            answer = if (conflict) emptyList() else evaluation.normalizedAnswer,
            answerText = if (conflict) "" else parsed.answerText,
            warnings = (warnings + if (conflict) listOf("模型答案因题型与用户选择冲突未采用，请核对题型后重试。") else emptyList()).distinct(),
            rawJson = raw
        )
    }

    /** 找到第一个完整的 JSON 对象；字符串内的花括号不会干扰配对。 */
    fun extractJsonObject(raw: String): String {
        val cleaned = stripMarkdownFence(raw)
        runCatching { JSONObject(cleaned) }.onSuccess { return cleaned }
        val start = cleaned.indexOf('{')
        if (start < 0) throw ApiException("模型返回的内容不是结构化答案，请重试。", raw = raw.take(1000))
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until cleaned.length) {
            val c = cleaned[index]
            if (inString) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return cleaned.substring(start, index + 1)
                    }
                }
            }
        }
        throw ApiException("模型返回的 JSON 不完整，请重试。", raw = raw.take(1000))
    }

    private fun stripMarkdownFence(value: String): String {
        var text = value.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```JSON").removePrefix("```").trim()
            if (text.endsWith("```")) text = text.removeSuffix("```").trim()
        }
        return text
    }
}