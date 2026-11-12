package com.xjb.medicalquestionai.data

import org.json.JSONArray

/**
 * 解析结果与流式预览共用的答案语义校验。
 *
 * 只负责“这个字段组合能不能当成一条可用答案”，不负责 JSON 扫描或 UI 展示，
 * 这样预览和最终解析不会出现两套互相矛盾的规则。
 */
object AnswerSemantics {
    val allowedStatuses = setOf("answered", "needs_confirmation", "out_of_scope")
    val allowedTypes = setOf("single", "multiple", "fill", "short")

    data class SeenOptions(
        val fieldPresent: Boolean,
        val fieldValid: Boolean,
        val itemsValid: Boolean,
        val values: List<SeenOption>
    ) {
        val usable: Boolean
            get() = fieldPresent && fieldValid && itemsValid && values.isNotEmpty()

        companion object {
            val absent = SeenOptions(false, false, false, emptyList())

            fun from(raw: Any?): SeenOptions {
                if (raw == null) return absent
                val array = when (raw) {
                    is JSONArray -> raw
                    is String -> runCatching { JSONArray(raw) }.getOrNull()
                    else -> null
                } ?: return SeenOptions(
                    fieldPresent = true,
                    fieldValid = false,
                    itemsValid = false,
                    values = emptyList()
                )
                var itemsValid = array.length() > 0
                val values = mutableListOf<SeenOption>()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index)
                    if (item == null) {
                        itemsValid = false
                        continue
                    }
                    val letter = seenOptionLetter(item.opt("letter"))
                    val text = (item.opt("text") as? String)?.trim().orEmpty()
                    if (letter == null || text.isBlank()) {
                        itemsValid = false
                        continue
                    }
                    values += SeenOption(letter, text)
                }
                return SeenOptions(
                    fieldPresent = true,
                    fieldValid = true,
                    itemsValid = itemsValid,
                    values = values
                )
            }
        }
    }

    data class PageCompleteness(
        val questionCountPresent: Boolean,
        val questionCountValid: Boolean,
        val questionCount: Int?,
        val pageCompletePresent: Boolean,
        val pageCompleteValid: Boolean,
        val pageComplete: Boolean?
    ) {
        companion object {
            val absent = PageCompleteness(
                questionCountPresent = false,
                questionCountValid = false,
                questionCount = null,
                pageCompletePresent = false,
                pageCompleteValid = false,
                pageComplete = null
            )

            fun from(
                questionCountPresent: Boolean,
                questionCountRaw: Any?,
                pageCompletePresent: Boolean,
                pageCompleteRaw: Any?
            ): PageCompleteness {
                val questionCount = positiveIntOrNull(questionCountRaw)
                return PageCompleteness(
                    questionCountPresent = questionCountPresent,
                    questionCountValid = questionCountPresent && questionCount != null,
                    questionCount = questionCount,
                    pageCompletePresent = pageCompletePresent,
                    pageCompleteValid = pageCompletePresent && pageCompleteRaw is Boolean,
                    pageComplete = (pageCompleteRaw as? Boolean)
                )
            }
        }
    }

    data class ImageObservationEvidence(
        val fieldPresent: Boolean,
        val fieldValid: Boolean,
        val values: List<ImageObservation>,
        val hasMalformedItem: Boolean,
        val hasInvalidIndex: Boolean,
        val hasBlankObservation: Boolean
    ) {
        fun issue(expectedCount: Int, requireFieldPresent: Boolean = true): String? {
            if (expectedCount <= 0) return null
            return when {
                !fieldPresent -> if (requireFieldPresent) {
                    "有 $expectedCount 张图片，但缺少 image_observations；每张图片都必须有一条观察，请重新解析。"
                } else {
                    null
                }
                !fieldValid ->
                    "image_observations 格式异常（应为数组），无法确认每张图片都被阅读，请重新解析。"
                hasMalformedItem ->
                    "image_observations 中存在格式异常的条目，请重新解析。"
                hasInvalidIndex ->
                    "image_observations 中存在缺少合法 image_index（应为 1-$expectedCount）的观察，请重新解析。"
                hasBlankObservation ->
                    "image_observations 中存在文本为空的观察，无法确认对应图片内容，请重新解析。"
                values.isEmpty() ->
                    "有 $expectedCount 张图片，但 image_observations 为空；请为每张图片提供一条观察。"
                !coversEveryImage(expectedCount) ->
                    "图片观察未覆盖每一张图（需要 $expectedCount 条，image_index 必须为 1-$expectedCount 且不重复），请重新解析。"
                else -> null
            }
        }

        private fun coversEveryImage(expectedCount: Int): Boolean {
            val actual = values.map { it.imageIndex }
            return actual.size == expectedCount &&
                actual.toSet().size == actual.size &&
                actual.toSet() == (1..expectedCount).toSet()
        }

        companion object {
            val absent = ImageObservationEvidence(
                fieldPresent = false,
                fieldValid = false,
                values = emptyList(),
                hasMalformedItem = false,
                hasInvalidIndex = false,
                hasBlankObservation = false
            )

            fun from(raw: Any?, fieldPresent: Boolean): ImageObservationEvidence {
                if (!fieldPresent) return absent
                val array = when (raw) {
                    is JSONArray -> raw
                    is String -> runCatching { JSONArray(raw) }.getOrNull()
                    else -> null
                } ?: return ImageObservationEvidence(
                    fieldPresent = true,
                    fieldValid = false,
                    values = emptyList(),
                    hasMalformedItem = false,
                    hasInvalidIndex = false,
                    hasBlankObservation = false
                )
                var hasMalformedItem = false
                var hasInvalidIndex = false
                var hasBlankObservation = false
                val values = mutableListOf<ImageObservation>()
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index)
                    if (item == null) {
                        hasMalformedItem = true
                        continue
                    }
                    val imageIndex = positiveIntOrNull(item.opt("image_index"))
                    if (imageIndex == null) hasInvalidIndex = true
                    val observation = (item.opt("observation") as? String)?.trim().orEmpty()
                    if (observation.isBlank()) hasBlankObservation = true
                    if (imageIndex != null) {
                        values += ImageObservation(
                            imageIndex = imageIndex,
                            observation = observation,
                            uncertainty = (item.opt("uncertainty") as? String)?.trim().orEmpty()
                        )
                    }
                }
                return ImageObservationEvidence(
                    fieldPresent = true,
                    fieldValid = true,
                    values = values,
                    hasMalformedItem = hasMalformedItem,
                    hasInvalidIndex = hasInvalidIndex,
                    hasBlankObservation = hasBlankObservation
                )
            }
        }
    }

    data class Evaluation(
        val normalizedType: String,
        val normalizedAnswer: List<String>,
        val statusValid: Boolean,
        val typeValid: Boolean,
        val typeConflict: Boolean,
        val answerIssue: String?,
        val optionsSeenIssue: String?,
        val optionAnalysisIssue: String?,
        val questionCount: Int?,
        val pageComplete: Boolean?,
        val pageCompletenessIssues: List<String>,
        val imageObservationIssue: String?,
        val suppressesAnswer: Boolean,
        val warningsIssue: String?
    ) {
        val isAnswerInvalid: Boolean
            get() = !statusValid ||
                !typeValid ||
                typeConflict ||
                answerIssue != null ||
                optionsSeenIssue != null ||
                optionAnalysisIssue != null ||
                pageCompletenessIssues.isNotEmpty() ||
                imageObservationIssue != null

        val previewIssue: String?
            get() = when {
                !statusValid -> "状态字段无效"
                !typeValid -> "题型字段缺失或无效"
                typeConflict -> "题型与用户选择不一致"
                answerIssue != null -> answerIssue
                optionsSeenIssue != null -> optionsSeenIssue
                optionAnalysisIssue != null -> optionAnalysisIssue
                pageCompletenessIssues.isNotEmpty() -> pageCompletenessIssues.first()
                imageObservationIssue != null -> imageObservationIssue
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
     * @param optionsSeen 选择题识别到的选项原文；非选择题不参与校验
     * @param requireChoiceEvidence 根对象已经完整时，选择题必须具备选项原文与逐项分析
     * @param pageCompleteness 画面中的题目数量与页面完整性证据
     * @param imageObservationEvidence 图片观察是否覆盖每一张上传图片
     * @param expectedImageCount 本次请求实际附带的图片数量
     * @param requireCompletenessEvidence 根对象已经完整时，必须存在 question_count、
     * page_complete 与有图时的 image_observations；字段已出现时始终立即校验
     */
    fun evaluate(
        status: String,
        questionType: String,
        answer: List<String>,
        answerText: String,
        warningsFieldPresent: Boolean,
        warningsFieldValid: Boolean,
        selectedType: QuestionType,
        optionAnalysisOptions: List<String> = emptyList(),
        optionsSeen: SeenOptions = SeenOptions.absent,
        requireChoiceEvidence: Boolean = false,
        pageCompleteness: PageCompleteness = PageCompleteness.absent,
        imageObservationEvidence: ImageObservationEvidence = ImageObservationEvidence.absent,
        expectedImageCount: Int = 0,
        requireCompletenessEvidence: Boolean = false
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
        val optionAnalysisMismatch = if (
            status != "out_of_scope" &&
            normalizedType in setOf("single", "multiple") &&
            analyzedLetters.isNotEmpty() &&
            letters.isNotEmpty() &&
            !analyzedLetters.containsAll(letters)
        ) {
            "答案选项与逐项分析不一致，请核对选项。"
        } else null
        val optionsSeenIssue = if (
            status != "out_of_scope" &&
            normalizedType in setOf("single", "multiple")
        ) {
            when {
                !optionsSeen.fieldPresent -> if (requireChoiceEvidence) {
                    "选择题缺少 options_seen（识别到的选项原文），请核对选项后重试。"
                } else null
                !optionsSeen.fieldValid -> "options_seen 格式异常（应为数组），请核对选项。"
                !optionsSeen.itemsValid || optionsSeen.values.isEmpty() ->
                    "options_seen 中存在缺少字母或原文为空的选项，请核对。"
                letters.isNotEmpty() && letters.any { it !in optionsSeen.values.map { option -> option.letter } } ->
                    "答案字母不在识别到的选项列表中，请核对。"
                else -> null
            }
        } else null
        val optionAnalysisIssue = when {
            status == "out_of_scope" -> null
            normalizedType !in setOf("single", "multiple") -> null
            optionAnalysisMismatch != null -> optionAnalysisMismatch
            requireChoiceEvidence && optionAnalysisOptions.isEmpty() ->
                "选择题缺少 option_analysis 逐项分析，请核对选项。"
            else -> null
        }
        val pageCompletenessIssues = buildPageCompletenessIssues(
            status = status,
            evidence = pageCompleteness,
            requireFieldPresent = requireCompletenessEvidence
        )
        val imageObservationIssue = if (status == "out_of_scope") {
            null
        } else {
            imageObservationEvidence.issue(
                expectedCount = expectedImageCount,
                requireFieldPresent = requireCompletenessEvidence
            )
        }
        val answerIssue = if (status == "answered") {
            when {
                !typeValid -> "模型返回的题型字段缺失或无效，请手动选择题型或补充题干。"
                typeConflict -> "模型识别题型与用户选择不一致，请核对题型后重试。"
                normalizedType == "single" -> {
                    when {
                        normalizedAnswer.size != 1 -> "单选题应只有一个答案，当前答案数量异常，请核对。"
                        letters.size != normalizedAnswer.size -> "答案中包含无法识别的选项，请核对选项字母。"
                        else -> null
                    }
                }
                normalizedType == "multiple" -> {
                    when {
                        normalizedAnswer.size < 2 -> "多选题的答案少于两个，请核对是否漏选。"
                        letters.size != normalizedAnswer.size -> "答案中包含无法识别的选项，请核对选项字母。"
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
            normalizedAnswer = normalizedAnswer,
            statusValid = statusValid,
            typeValid = typeValid,
            typeConflict = typeConflict,
            answerIssue = answerIssue,
            optionsSeenIssue = optionsSeenIssue,
            optionAnalysisIssue = optionAnalysisIssue,
            questionCount = pageCompleteness.questionCount,
            pageComplete = pageCompleteness.pageComplete,
            pageCompletenessIssues = pageCompletenessIssues,
            imageObservationIssue = imageObservationIssue,
            suppressesAnswer = !statusValid ||
                !typeValid ||
                typeConflict ||
                answerIssue != null ||
                optionAnalysisMismatch != null ||
                warningsIssue != null,
            warningsIssue = warningsIssue
        )
    }

    private fun buildPageCompletenessIssues(
        status: String,
        evidence: PageCompleteness,
        requireFieldPresent: Boolean
    ): List<String> {
        if (status == "out_of_scope") return emptyList()
        return buildList {
            when {
                !evidence.questionCountPresent && requireFieldPresent ->
                    add("模型没有返回 question_count，无法确认画面中是否为单道题。请裁剪或补拍至只包含一道完整题目后重新解析。")
                !evidence.questionCountPresent -> Unit
                !evidence.questionCountValid ->
                    add("question_count 字段非法（应为正整数），无法确认画面中是否为单道题。请裁剪或补拍后重新解析。")
                evidence.questionCount != 1 ->
                    add("检测到画面中可能有 ${evidence.questionCount} 道题，当前只支持单题解析。请裁剪或补拍至只保留一道题后重新解析。")
            }
            when {
                !evidence.pageCompletePresent && requireFieldPresent ->
                    add("模型没有返回 page_complete，无法确认题干与选项是否完整。请补拍或裁剪完整页面后重新解析。")
                !evidence.pageCompletePresent -> Unit
                !evidence.pageCompleteValid ->
                    add("page_complete 字段格式异常（应为布尔值），无法确认题干与选项是否完整。请补拍或裁剪完整页面后重新解析。")
                evidence.pageComplete == false ->
                    add("模型判断题干或选项可能被截断、拍漏，已禁止脑补缺失内容。请补拍或裁剪完整页面后重新解析。")
            }
        }
    }

    private fun positiveIntOrNull(value: Any?): Int? {
        val number = value as? Number ?: return null
        val doubleValue = number.toDouble()
        if (!doubleValue.isFinite() || doubleValue != doubleValue.toLong().toDouble()) return null
        return number.toLong().takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()
    }

    private fun seenOptionLetter(value: Any?): String? {
        val text = value as? String ?: return null
        val normalized = text.trim()
        if (normalized.length != 1) return null
        val letter = normalized[0]
        if (letter !in 'A'..'Z' && letter !in 'a'..'z') return null
        return letter.uppercase()
    }

    private fun optionLetter(value: String): String? {
        val match = Regex("^([A-Za-z])(?:[.、:：)）\\s].*)?$").matchEntire(value.trim()) ?: return null
        return match.groupValues[1].uppercase()
    }
}
