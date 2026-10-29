package com.xjb.medicalquestionai

import com.xjb.medicalquestionai.data.AnswerMode
import com.xjb.medicalquestionai.data.AnalysisPreview

enum class AnalysisTaskStatus(val label: String) {
    PREPARING("准备图片"),
    UPLOADING("上传题目"),
    WAITING("等待模型响应"),
    RECEIVING("接收答案"),
    VALIDATING("核对答案"),
    COMPLETED("解析完成"),
    FAILED("解析失败"),
    CANCELLED("已取消"),
    INTERRUPTED("已中断");

    val isActive: Boolean
        get() = this in setOf(PREPARING, UPLOADING, WAITING, RECEIVING, VALIDATING)
}

enum class AnalysisStageStatus { PENDING, RUNNING, DONE, FAILED }

data class AnalysisStage(
    val title: String,
    val status: AnalysisStageStatus = AnalysisStageStatus.PENDING
)

data class AnalysisTaskState(
    val requestId: String,
    val questionId: String,
    val status: AnalysisTaskStatus,
    val stages: List<AnalysisStage>,
    val preview: AnalysisPreview? = null,
    val reasoningPreview: String = "",
    val startedAtElapsedRealtime: Long = 0L,
    val mode: AnswerMode = AnswerMode.STANDARD,
    val errorMessage: String? = null
) {
    val isActive: Boolean get() = status.isActive
    val statusLabel: String get() = status.label
}

/** 追问的流式状态：正文与思考内容分轨，页面退出后仍可在返回时继续展示。 */
data class FollowUpStreamState(
    val questionId: String,
    val sending: Boolean = true,
    val content: String = "",
    val reasoningPreview: String = "",
    val startedAtElapsedRealtime: Long = 0L,
    val error: String? = null
)
