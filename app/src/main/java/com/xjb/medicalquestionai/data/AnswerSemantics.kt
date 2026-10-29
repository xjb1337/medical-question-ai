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
     * @param warningsFieldPresent 字段是否在 JSON 中出现
     * @param warningsFieldValid 字段出现时是否为 JSON 数组（错类型不能静默当成空数组）
     */
    fun evaluate(
        status: String,
        questionType: String,
        answer: List<String>,
        answerText: String,
        warningsFieldPresent: Boolean,
        warningsFieldValid: Boolean,
        selectedType: QuestionType
    ): Evaluation {
        val modelType = normalizeQuestionType(questionType)
        val statusValid = status.isNotBlank() && status in allowedStatuses
        val modelTypeValid = modelType in allowedTypes
        // 用户明确选了题型时，最终展示必须服从用户选择；模型识别结果只用于提示冲突。
        // AUTO 才采用模型识别出的题型。
        val normalizedType = if (selectedType != QuestionType.AUTO) selectedType.storageValue else modelType
        val typeValid = if (selectedType != QuestionType.AUTO) true else modelTypeValid
        val typeConflict = selectedType != QuestionType.AUTO &&
            modelTypeValid &&
            modelType != selectedType.storageValue
        val answerIssue = if (status == "answered") {
            when {
                !typeValid -> "未能确定题型，请手动选择题型或补充题干。"
                typeConflict -> "模型识别题型与用户选择不一致，请核对题型后重试。"
                normalizedType == "single" -> {
                    val letters = answer.mapNotNull(::optionLetter)
                    when {
                        answer.size != 1 -> "单选题应只有一个答案，当前答案数量异常，请核对。"
                        letters.size != answer.size -> "答案中包含无法识别的选项，请核对选项字母。"
                        else -> null
                    }
                }
                normalizedType == "multiple" -> {
                    val letters = answer.mapNotNull(::optionLetter)
                    when {
                        answer.size < 2 -> "多选题的答案少于两个，请核对是否漏选。"
                        letters.size != answer.size -> "答案中包含无法识别的选项，请核对选项字母。"
                        letters.toSet().size != letters.size -> "答案中存在重复选项，请核对。"
                        else -> null
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
