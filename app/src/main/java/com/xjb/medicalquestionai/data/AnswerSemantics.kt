package com.xjb.medicalquestionai.data

/**
 * 解析结果与流式预览共用的答案语义校验。
 *
 * 只负责“这个字段组合能不能当成一条可用答案”，不负责 JSON 扫描或 UI 展示，
 * 这样预览和最终解析不会出现两套互相矛盾的规则。
 */
object AnswerSemantics {
    val allowedStatuses = setOf("answered", "needs_confirmation", "out_of_scope")
    val allowedTypes = setOf("single", "multiple", "fill", "short")

    data class Evaluation(
        val normalizedType: String,
        val normalizedAnswer: List<String>,
        val statusValid: Boolean,
        val typeValid: Boolean,
        val typeConflict: Boolean,
        val answerIssue: String?,
        val warningsIssue: String?
    ) {
        val isAnswerInvalid: Boolean
            get() = !statusValid || !typeValid || typeConflict || answerIssue != null

        val previewIssue: String?
            get() = when {
                !statusValid -> "状态字段无效"
                !typeValid -> "题型字段缺失或无效"
                typeConflict -> "题型与用户选择不一致"
                answerIssue != null -> answerIssue
                warningsIssue != null -> warningsIssue
                else -> null
            }
    }

    fun normalizeQuestionType(value: String): String = when (value.lowercase().trim()) {
        "single", "single_choice", "单选", "单选题" -> "single"
        "multiple", "multiple_choice", "multi", "多选", "多选题" -> "multiple"
        "fill", "fill_blank", "填空", "填空题" -> "fill"
        "short", "short_answer", "简答", "简答题", "case" -> "short"
        else -> if (value.isBlank()) "unknown" else value.lowercase().trim()
    }

    /**
     * 把模型常见的答案写法规范化成选项字母列表。
     *
     * 关键点：不能把 `A、B` 只识别成第一个字母 A，否则单选会绕过“只能有一个答案”的约束。
     * `A. 选项正文` 仍是一个选项；`A、B`、`A,B`、`A/B`、`AB` 则拆成两个选项。
     */
    fun normalizeAnswerOptions(values: List<String>): List<String> = values.flatMap { raw ->
        val value = raw.trim()
        if (value.isEmpty()) return@flatMap emptyList()
        val separated = value.split(Regex("[,，、/;；\\s]+|和")).filter { it.isNotBlank() }
        when {
            separated.size > 1 && separated.all { optionLetter(it) != null } ->
                separated.mapNotNull(::optionLetter)
            value.length in 2..8 &&
                value.all { it in 'A'..'Z' || it in 'a'..'z' } ->
                value.map { it.uppercaseChar().toString() }
            else -> listOf(value)
        }
    }

    /**
     * @param warningsFieldPresent 字段是否在 JSON 中出现
     * @param warningsFieldValid 字段出现时是否为 JSON 数组（错类型不能静默当成空数组）
     * @param optionAnalysisOptions 逐项分析里出现的选项；为空表示字段缺失，不做额外推断
     */
    fun evaluate(
        status: String,
        questionType: String,
        answer: List<String>,
        answerText: String,
        warningsFieldPresent: Boolean,
        warningsFieldValid: Boolean,
        selectedType: QuestionType,
        optionAnalysisOptions: List<String> = emptyList()
    ): Evaluation {
        val modelType = normalizeQuestionType(questionType)
        val statusValid = status.isNotBlank() && status in allowedStatuses
        // 用户选择题型只决定最终展示采用哪一种，不能把模型缺失/非法的题型字段当成合格响应。
        val modelTypeValid = modelType in allowedTypes
        val normalizedType = if (selectedType != QuestionType.AUTO) selectedType.storageValue else modelType
        val typeValid = modelTypeValid
        val typeConflict = selectedType != QuestionType.AUTO &&
            modelTypeValid &&
            modelType != selectedType.storageValue
        val normalizedAnswer = if (normalizedType in setOf("single", "multiple")) {
            normalizeAnswerOptions(answer)
        } else {
            answer
        }
        val letters = normalizedAnswer.mapNotNull(::optionLetter)
        val analyzedLetters = optionAnalysisOptions.mapNotNull(::optionLetter).toSet()
        val optionAnalysisIssue = if (
            normalizedType in setOf("single", "multiple") &&
            analyzedLetters.isNotEmpty() &&
            letters.isNotEmpty() &&
            !analyzedLetters.containsAll(letters)
        ) {
            "答案选项与逐项分析不一致，请核对选项。"
        } else null
        val answerIssue = if (status == "answered") {
            when {
                !typeValid -> "模型返回的题型字段缺失或无效，请手动选择题型或补充题干。"
                typeConflict -> "模型识别题型与用户选择不一致，请核对题型后重试。"
                normalizedType == "single" -> {
                    when {
                        normalizedAnswer.size != 1 -> "单选题应只有一个答案，当前答案数量异常，请核对。"
                        letters.size != normalizedAnswer.size -> "答案中包含无法识别的选项，请核对选项字母。"
                        else -> optionAnalysisIssue
                    }
                }
                normalizedType == "multiple" -> {
                    when {
                        normalizedAnswer.size < 2 -> "多选题的答案少于两个，请核对是否漏选。"
                        letters.size != normalizedAnswer.size -> "答案中包含无法识别的选项，请核对选项字母。"
                        letters.toSet().size != letters.size -> "答案中存在重复选项，请核对。"
                        else -> optionAnalysisIssue
                    }
                }
                normalizedType == "fill" -> if (answerText.isBlank()) {
                    "填空题需要在 answer_text 中按空顺序作答。"
                } else null
                normalizedType == "short" -> if (answerText.isBlank()) {
                    "简答题需要在 answer_text 中给出分点结论。"
                } else null
                else -> null
            }
        } else null
        val warningsIssue = when {
            !warningsFieldPresent -> "warnings 字段缺失"
            !warningsFieldValid -> "warnings 字段不是数组"
            else -> null
        }
        return Evaluation(
            normalizedType = normalizedType,
            normalizedAnswer = normalizedAnswer,
            statusValid = statusValid,
            typeValid = typeValid,
            typeConflict = typeConflict,
            answerIssue = answerIssue,
            warningsIssue = warningsIssue
        )
    }

    private fun optionLetter(value: String): String? {
        val match = Regex("^([A-Za-z])(?:[.、:：)）\\s].*)?$").matchEntire(value.trim()) ?: return null
        return match.groupValues[1].uppercase()
    }
}
