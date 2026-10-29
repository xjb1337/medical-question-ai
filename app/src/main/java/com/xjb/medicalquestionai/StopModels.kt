package com.xjb.medicalquestionai

import com.xjb.medicalquestionai.data.QuestionDraft

/** 离开解析页、进入后台、拍下一题和用户主动取消必须使用不同原因。 */
enum class StopReason {
    PAGE_EXIT,
    BACKGROUND,
    NEXT_QUESTION,
    USER
}

data class StopOutcome(
    val stopped: Boolean,
    val timedOut: Boolean = false,
    val message: String? = null
)

data class NextQuestionResult(
    val success: Boolean,
    val sessionId: String? = null,
    val message: String? = null
)

internal fun QuestionDraft?.hasContent(): Boolean =
    this != null && (text.isNotBlank() || images.isNotEmpty())

/** 只允许保留一份既有草稿；已有暂存且当前草稿也有内容时必须显式提示。 */
internal fun canParkDraft(current: QuestionDraft?, parked: QuestionDraft?): Boolean =
    !current.hasContent() || !parked.hasContent()

/** 生命周期退出判定，抽成纯函数便于 JVM 验证旋转/重组不误停。 */
enum class LifecycleStopEvent { DESTROY, STOP, OTHER }

object StopPolicy {
    fun shouldStopOnLifecycle(event: LifecycleStopEvent, changingConfigurations: Boolean): Boolean =
        !changingConfigurations && (event == LifecycleStopEvent.DESTROY || event == LifecycleStopEvent.STOP)

    fun matchesRequest(activeRequestId: String?, expectedRequestId: String?): Boolean =
        expectedRequestId == null || activeRequestId == expectedRequestId
}
