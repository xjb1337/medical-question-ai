package com.xjb.medicalquestionai.data

/**
 * 三档做题模式。参数按“先答案、再解释”的产品目标设定：
 * - 快速：关闭思考，限制输出长度，适合单选题和快速核对；
 * - 标准：开启 low 思考，兼顾速度与准确率，是当前未经质量门槛验证时的默认档；
 * - 详细：开启 high 思考，保留完整推理和逐项辨析。
 */
enum class AnswerMode(
    val storageValue: String,
    val label: String,
    val description: String,
    val thinkingEnabled: Boolean,
    val reasoningEffort: String?,
    val maxTokens: Int,
    val timeoutMs: Long
) {
    FAST(
        storageValue = "fast",
        label = "快速",
        description = "关闭思考，先给答案，适合单选题和快速核对",
        thinkingEnabled = false,
        reasoningEffort = null,
        maxTokens = 3072,
        timeoutMs = 120_000
    ),
    STANDARD(
        storageValue = "standard",
        label = "标准",
        description = "开启轻度思考，兼顾速度与准确率（推荐）",
        thinkingEnabled = true,
        reasoningEffort = "low",
        maxTokens = 8192,
        timeoutMs = 180_000
    ),
    DETAILED(
        storageValue = "detailed",
        label = "详细",
        description = "开启深度思考，保留完整推理和逐项辨析",
        thinkingEnabled = true,
        reasoningEffort = "high",
        maxTokens = 16384,
        timeoutMs = 240_000
    );

    val usesThinking: Boolean get() = thinkingEnabled
    val budgetLabel: String get() = "最多 ${maxTokens} tokens"

    companion object {
        fun fromStorageOrNull(value: String?): AnswerMode? =
            entries.firstOrNull { it.storageValue == value?.trim()?.lowercase() }

        fun fromStorage(value: String?): AnswerMode = fromStorageOrNull(value) ?: STANDARD
    }
}
