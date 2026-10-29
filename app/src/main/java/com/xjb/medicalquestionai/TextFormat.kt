package com.xjb.medicalquestionai

/**
 * 模型偶尔会把 Markdown 强调符写进答案；客户端不渲染 Markdown，直接显示会出现 `**`。
 * 这里只做保守的纯文本清理，不尝试解释复杂语法，也不改动正文中的普通星号。
 */
fun String.isJsonNullLiteral(): Boolean = trim().equals("null", ignoreCase = true)

/**
 * 旧版本曾把显式 JSON null 当正文，形成 `nullnullnull…正常回答`。
 * 只识别开头连续两个以上的 null 前缀，避免误伤正文里正常出现的 “null hypothesis” 等文本。
 */
private val repeatedJsonNullPrefix = Regex("^(?:\\s*null\\s*){2,}", RegexOption.IGNORE_CASE)

fun String.hasRepeatedJsonNullPrefix(): Boolean =
    repeatedJsonNullPrefix.containsMatchIn(this)

fun String.stripRepeatedJsonNullPrefix(): String {
    val match = repeatedJsonNullPrefix.find(this) ?: return this
    return substring(match.range.last + 1).trimStart()
}

fun String.toReadablePlainText(): String {
    if (isBlank()) return this
    return this
        .replace(Regex("```[a-zA-Z0-9_-]*\\s*"), "")
        .replace("```", "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        .replace(Regex("`([^`]+)`"), "$1")
}
