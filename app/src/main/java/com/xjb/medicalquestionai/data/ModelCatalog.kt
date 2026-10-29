package com.xjb.medicalquestionai.data

import java.net.URI

data class ModelSpec(
    val id: String,
    val label: String,
    val description: String,
    val supportsImages: Boolean
)

/**
 * 模型能力三态。未知模型既不能假定支持图片，也不能假定支持 JSON 模式或思考参数，
 * 必须由调用方按“未知”降级处理，避免向不兼容的接口发送额外字段导致 400/422。
 */
enum class ModelCapability {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN
}

/**
 * 官方模型能力表。名称来自 2026-10-29 的官方型号：
 * - deepseek-flash：速度快，支持文字与图片；
 * - deepseek-v4-pro：推理更强，但不接受图片输入。
 * 自定义接口可能使用任意模型名，能力记为 UNKNOWN，不能假定支持图片。
 */
object ModelCatalog {
    val FLASH = ModelSpec(
        id = "deepseek-flash",
        label = "DeepSeek Flash",
        description = "响应快，支持文字和图片，适合日常拍题",
        supportsImages = true
    )
    val PRO = ModelSpec(
        id = "deepseek-v4-pro",
        label = "DeepSeek V4 Pro",
        description = "推理更强，仅支持文字，不支持图片输入",
        supportsImages = false
    )

    val models: List<ModelSpec> = listOf(FLASH, PRO)

    val defaultModel: String get() = FLASH.id

    fun find(id: String?): ModelSpec? = models.firstOrNull { it.id == id?.trim() }

    fun displayName(id: String?): String = find(id)?.label ?: (id?.takeIf { it.isNotBlank() } ?: FLASH.label)

    fun capability(id: String?): ModelCapability = when (val spec = find(id)) {
        null -> ModelCapability.UNKNOWN
        else -> if (spec.supportsImages) ModelCapability.SUPPORTED else ModelCapability.UNSUPPORTED
    }

    fun supportsImages(id: String?, customModelSupportsImages: Boolean = false): Boolean =
        when (capability(id)) {
            ModelCapability.SUPPORTED -> true
            ModelCapability.UNSUPPORTED -> false
            // 未知模型默认不支持图片；用户可在高级设置显式声明支持。
            ModelCapability.UNKNOWN -> customModelSupportsImages
        }

    /**
     * 官方地址判定必须按主机 + 路径精确匹配，不能用 contains/endsWith 猜测：
     * 只接受 https://api.deepseek.com、https://api.deepseek.com/ 和 https://api.deepseek.com/v1。
     * 自定义代理即使包含官方域名片段也不会被误迁移。
     */
    fun isOfficialBaseUrl(baseUrl: String?): Boolean {
        val value = baseUrl?.trim().orEmpty()
        if (value.isEmpty()) return true
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() != "https") return false
        if (uri.host?.lowercase() != "api.deepseek.com") return false
        if (!uri.userInfo.isNullOrBlank() || !uri.query.isNullOrBlank() || !uri.fragment.isNullOrBlank()) return false
        val path = uri.path.orEmpty().trimEnd('/')
        return path.isEmpty() || path == "/v1"
    }
}
