package com.xjb.medicalquestionai

/**
 * 模型偶尔会把 Markdown 强调符写进答案；客户端不渲染 Markdown，直接显示会出现 `**`。
 * 这里只做保守的纯文本清理，不尝试解释复杂语法，也不改动正文中的普通星号。
 */
fun String.isJsonNullLiteral(): Boolean = trim().equals("null", ignoreCase = true)

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
