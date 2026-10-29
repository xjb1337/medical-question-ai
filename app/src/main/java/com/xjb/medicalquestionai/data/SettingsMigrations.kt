package com.xjb.medicalquestionai.data

/**
 * 设置迁移必须是纯函数，便于单元测试：
 * - 仅当接口地址是 DeepSeek 官方主机且为根路径或 /v1 时，才把旧模型名迁移为新官方型号；
 * - 自定义网关保留用户填写的模型名，避免把代理端自定义名称改坏；
 * - 旧版本没有 answerMode 时按官方迁移表补模式；默认档为“标准”，只有质量门槛通过后才改为快速。
 */
data class SettingsMigrationResult(
    val model: String,
    val answerMode: AnswerMode,
    val version: Int,
    val changed: Boolean,
    val note: String
)

object SettingsMigrations {
    const val CURRENT_VERSION = 2

    private data class LegacyRule(
        val model: String,
        val mode: AnswerMode? = null,
        val note: String
    )

    private val LEGACY_RULES = mapOf(
        "deepseek-chat" to LegacyRule(
            model = ModelCatalog.FLASH.id,
            mode = AnswerMode.FAST,
            note = "已迁移旧模型 deepseek-chat 为 deepseek-flash，并采用快速模式；可在设置中切换思考模式。"
        ),
        "deepseek-reasoner" to LegacyRule(
            model = ModelCatalog.FLASH.id,
            mode = AnswerMode.DETAILED,
            note = "已迁移旧模型 deepseek-reasoner 为 deepseek-flash，并保留偏重推理的详细模式。"
        ),
        "deepseek-v4-flash" to LegacyRule(
            model = ModelCatalog.FLASH.id,
            note = "已将旧型号 deepseek-v4-flash 规范为 deepseek-flash，原有模式保持不变。"
        ),
        "deepseek-v4-flash-vision-exp" to LegacyRule(
            model = ModelCatalog.FLASH.id,
            note = "已将实验型号 deepseek-v4-flash-vision-exp 规范为 deepseek-flash，原有模式保持不变。"
        )
    )

    fun migrate(
        model: String?,
        baseUrl: String?,
        answerMode: String?,
        storedVersion: Int
    ): SettingsMigrationResult {
        val official = ModelCatalog.isOfficialBaseUrl(baseUrl)
        val originalModel = model?.trim().orEmpty()
        val currentModel = originalModel.ifBlank { ModelCatalog.FLASH.id }
        val storedMode = AnswerMode.fromStorageOrNull(answerMode)
        // 已经完成过迁移的版本不再执行任何旧名规范：即使用户后来重新保存了旧模型名，
        // 也必须尊重用户当前选择，避免每次启动都被改回去。
        if (storedVersion >= CURRENT_VERSION) {
            return SettingsMigrationResult(
                model = currentModel,
                answerMode = storedMode ?: AnswerMode.STANDARD,
                version = storedVersion,
                changed = false,
                note = ""
            )
        }
        val rule = if (official) LEGACY_RULES[currentModel] else null
        val migratedModel = rule?.model ?: currentModel
        // 旧模型规则只在用户尚无有效模式时补默认；已有模式一律保留（v4 旧名尤其如此）。
        val mode = storedMode ?: rule?.mode ?: AnswerMode.STANDARD
        val version = CURRENT_VERSION
        val modelChanged = migratedModel != originalModel
        val modeChanged = storedMode == null
        val note = when {
            !modelChanged -> ""
            storedMode != null && rule != null ->
                "已将旧型号 $currentModel 规范为 $migratedModel，原有${storedMode.label}模式保持不变。"
            rule?.mode != null -> rule.note
            originalModel.isBlank() ->
                "已将模型设置为 $migratedModel，并采用${mode.label}模式。"
            else ->
                "已将旧型号 $currentModel 规范为 $migratedModel，并采用${mode.label}模式。"
        }
        return SettingsMigrationResult(
            model = migratedModel,
            answerMode = mode,
            version = version,
            changed = modelChanged || modeChanged || version != storedVersion,
            note = note
        )
    }
}
