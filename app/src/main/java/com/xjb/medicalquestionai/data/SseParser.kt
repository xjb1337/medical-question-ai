package com.xjb.medicalquestionai.data

import java.io.ByteArrayOutputStream

data class SseEvent(
    val event: String?,
    val data: String,
    val id: String?
)

/**
 * 增量 SSE 解析器。按字节缓冲到换行再整体 UTF-8 解码：
 * UTF-8 多字节字符的任一字节都不会等于 0x0A，因此换行切分不会截断中文；
 * 即使一个汉字的三个字节被拆到两个网络包里，也会先拼齐再解码。
 */
class SseParser {
    private val lineBuffer = ByteArrayOutputStream()
    private val dataLines = mutableListOf<String>()
    private var eventName: String? = null
    private var lastId: String? = null

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): List<SseEvent> {
        val output = mutableListOf<SseEvent>()
        var index = offset
        val end = offset + length
        while (index < end) {
            val byte = bytes[index]
            if (byte == '\n'.code.toByte()) {
                val line = lineBuffer.toString(Charsets.UTF_8.name())
                lineBuffer.reset()
                processLine(line, output)
            } else {
                lineBuffer.write(byte.toInt())
            }
            index++
        }
        return output
    }

    fun finish(): List<SseEvent> {
        val output = mutableListOf<SseEvent>()
        if (lineBuffer.size() > 0) {
            val line = lineBuffer.toString(Charsets.UTF_8.name())
            lineBuffer.reset()
            processLine(line, output)
        }
        dispatch(output)
        return output
    }

    private fun processLine(rawLine: String, output: MutableList<SseEvent>) {
        val line = rawLine.removeSuffix("\r")
        if (line.isEmpty()) {
            dispatch(output)
            return
        }
        if (line.startsWith(":")) return // keep-alive 注释
        val colon = line.indexOf(':')
        val field = if (colon >= 0) line.substring(0, colon) else line
        var value = if (colon >= 0) line.substring(colon + 1) else ""
        if (value.startsWith(" ")) value = value.substring(1)
        when (field) {
            "event" -> eventName = value
            "data" -> dataLines += value
            "id" -> lastId = value
            "retry" -> Unit
            else -> Unit
        }
    }

    private fun dispatch(output: MutableList<SseEvent>) {
        if (dataLines.isNotEmpty()) {
            output += SseEvent(
                event = eventName,
                data = dataLines.joinToString("\n"),
                id = lastId
            )
        }
        dataLines.clear()
        eventName = null
    }
}
