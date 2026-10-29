package com.xjb.medicalquestionai.ui.screens

import android.content.Intent
import android.net.Uri
import com.xjb.medicalquestionai.isJsonNullLiteral
import com.xjb.medicalquestionai.toReadablePlainText
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.xjb.medicalquestionai.AnalysisTaskState
import com.xjb.medicalquestionai.AnalysisTaskStatus
import com.xjb.medicalquestionai.FollowUpStreamState
import com.xjb.medicalquestionai.MainViewModel
import com.xjb.medicalquestionai.data.AnalysisResult
import com.xjb.medicalquestionai.data.FollowUp
import com.xjb.medicalquestionai.data.ModelCatalog
import com.xjb.medicalquestionai.data.Question
import com.xjb.medicalquestionai.data.QuestionType
import com.xjb.medicalquestionai.ui.components.AnalysisAnswerCard
import com.xjb.medicalquestionai.ui.components.AnalysisDetails
import com.xjb.medicalquestionai.ui.components.AnalysisWarningCard
import com.xjb.medicalquestionai.ui.components.AppTopBar
import com.xjb.medicalquestionai.ui.components.CollapsibleSection
import com.xjb.medicalquestionai.ui.components.ErrorBanner
import com.xjb.medicalquestionai.ui.components.InfoBanner
import com.xjb.medicalquestionai.ui.components.QuestionTypeChips
import com.xjb.medicalquestionai.ui.components.SectionCard
import com.xjb.medicalquestionai.ui.components.rememberFileImageRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class QuestionUiState(
    val loading: Boolean,
    val question: Question?
)

@Composable
fun ResultScreen(
    questionId: String,
    viewModel: MainViewModel,
    navController: NavController
) {
    val flow = remember(questionId) { viewModel.questionFlow(questionId) }
    val questionState by produceState<QuestionUiState>(
        initialValue = QuestionUiState(loading = true, question = null),
        questionId
    ) {
        flow.collect { value = QuestionUiState(loading = false, question = it) }
    }
    val question = questionState.question
    val questionLoading = questionState.loading
    val task by viewModel.analysisTask.collectAsState()
    val isCancelling by viewModel.isCancelling.collectAsState()
    val elapsedMs by viewModel.analysisElapsedMs.collectAsState()
    val sendingIds by viewModel.followUpSendingIds.collectAsState()
    val followUpError by viewModel.followUpError.collectAsState()
    val followUpStreams by viewModel.followUpStreams.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val showJumpToBottom by remember { derivedStateOf { listState.canScrollForward } }

    var showEditDialog by remember { mutableStateOf(false) }
    var editSaving by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }
    var addingImages by remember { mutableStateOf(false) }
    var followUpInput by rememberSaveable(questionId) { mutableStateOf("") }
    var attachImages by rememberSaveable(questionId) { mutableStateOf(false) }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(9)
    ) { uris ->
        if (uris.isNotEmpty()) {
            addingImages = true
            viewModel.addImagesToQuestion(questionId, uris) { message ->
                addingImages = false
                scope.launch { snackbarHostState.showSnackbar(message) }
            }
        }
    }

    val running = task?.takeIf { it.questionId == questionId && it.isActive }
    val taskFailed = task?.takeIf { it.questionId == questionId && it.status == AnalysisTaskStatus.FAILED }
    val taskCancelled = task?.takeIf { it.questionId == questionId && it.status == AnalysisTaskStatus.CANCELLED }
    val taskInterrupted = task?.takeIf { it.questionId == questionId && it.status == AnalysisTaskStatus.INTERRUPTED }
    val runningPreview = running?.preview
    val runningPreviewResult = runningPreview?.toResult()
    val previewHasAnswer = runningPreviewResult?.hasAnswerPreview() == true
    val canFollowUp = question != null && !question.isAnswerStale && (
        question.result != null ||
            question.status == "completed" ||
            question.status == "failed" ||
            question.status == "cancelled" ||
            question.status == "interrupted"
        )
    val sendingFollowUp = followUpStreams[questionId]?.sending == true || questionId in sendingIds
    val followUpStream = followUpStreams[questionId]

    Scaffold(
        topBar = {
            AppTopBar(
                title = "题目解析",
                subtitle = question?.let {
                    SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(Date(it.createdAt))
                },
                onBack = { navController.popBackStack() },
                actions = {
                    if (question != null) {
                        IconButton(onClick = { viewModel.toggleFavorite(questionId) }) {
                            Icon(
                                if (question.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                contentDescription = if (question.isFavorite) "取消收藏" else "收藏"
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (question != null) {
                FollowUpInputBar(
                    value = followUpInput,
                    onValueChange = { followUpInput = it },
                    sending = sendingFollowUp,
                    enabled = canFollowUp,
                    attachAvailable = question.imagePaths.isNotEmpty(),
                    attachImages = attachImages,
                    onAttachChange = { attachImages = it },
                    onSend = {
                        val text = followUpInput.trim()
                        if (text.isNotEmpty() && !sendingFollowUp) {
                            viewModel.sendFollowUp(questionId, text, attachImages) {
                                // 只有用户没有在等待期间改写输入时才清空，失败时保留原文供重试。
                                if (followUpInput.trim() == text) followUpInput = ""
                            }
                        }
                    },
                    onStop = { viewModel.cancelFollowUp(questionId) }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                questionLoading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                question == null -> MissingQuestionCard(
                    onBack = { navController.popBackStack() },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
                else -> {
                    val q = question
                    val result = q.result
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (running != null && previewHasAnswer && runningPreviewResult != null) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    AnalysisAnswerCard(result = runningPreviewResult, compact = true)
                                    if (runningPreviewResult.warnings.isNotEmpty()) {
                                        AnalysisWarningCard(runningPreviewResult.warnings)
                                    }
                                    Text(
                                        if (!runningPreview.isComplete) {
                                            "以上为预览答案，模型仍在核对完整性与格式，最终结果会自动替换。"
                                        } else {
                                            "以上为预览答案，已通过格式校验，正在等待最终结果。"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (running != null) {
                            item {
                                AnalysisProgressCard(
                                    task = running,
                                    elapsedMs = elapsedMs,
                                    cancelling = isCancelling,
                                    showPreviewAnswer = !previewHasAnswer,
                                    previewComplete = runningPreview?.isComplete == true,
                                    onCancel = { viewModel.cancelAnalysis() }
                                )
                            }
                        }
                        val banner = resolveStatusBanner(q, taskFailed, taskCancelled, taskInterrupted, running == null)
                        if (banner != null && running == null && result == null) {
                            item {
                                ResultStatusBanner(
                                    tone = banner.tone,
                                    title = banner.title,
                                    message = banner.message,
                                    onRetry = { viewModel.retryAnalysis(questionId) {} }
                                )
                            }
                        }
                        if (result != null) {
                            if (q.isAnswerStale) {
                                item {
                                    InfoBanner("题目已修改，当前显示的是修改前的旧答案；请点击“重新解析”生成对应新题目的答案。")
                                }
                            }
                            item { AnalysisAnswerCard(result = result) }
                            item { ResultMetaLine(q) }
                            if (result.warnings.isNotEmpty()) {
                                item { AnalysisWarningCard(result.warnings) }
                            }
                            if (q.imagePaths.isNotEmpty()) {
                                item { QuestionImagesRow(q.imagePaths) }
                            }
                            item {
                                CollapsibleSection(
                                    title = "详细解析",
                                    subtitle = "识别题干、选项分析、解题思路、知识点、易错点与使用建议"
                                ) {
                                    AnalysisDetails(result)
                                }
                            }
                            if (banner != null) {
                                item {
                                    ResultStatusBanner(
                                        tone = banner.tone,
                                        title = banner.title,
                                        message = banner.message,
                                        onRetry = { viewModel.retryAnalysis(questionId) {} }
                                    )
                                }
                            }
                            item {
                                ResultActions(
                                    addingImages = addingImages,
                                    onCopy = {
                                        val copyText = if (q.isAnswerStale) {
                                            "注意：题目已修改，本答案对应修改前的题目，尚未重新解析。\n${result.answerDisplay}"
                                        } else {
                                            result.answerDisplay
                                        }
                                        clipboard.setText(AnnotatedString(copyText))
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (q.isAnswerStale) "已复制旧答案，请留意题目已修改" else "答案已复制"
                                            )
                                        }
                                    },
                                    onShare = {
                                        val shareText = buildString {
                                            appendLine("医学拍题助手 · 解析结果")
                                            appendLine()
                                            appendLine("模式：${q.modeLabel} · 模型：${q.modelLabel}")
                                            result.metrics?.let { m ->
                                                appendLine(
                                                    "思考：${if (m.thinkingEnabled) "开启" else "关闭"} · " +
                                                        "传输：${if (m.streamed) "流式" else "非流式"} · " +
                                                        "结束原因：${m.finishReason.ifBlank { "未知" }} · " +
                                                        "保存：${m.saveStatus}"
                                                )
                                            }
                                            if (q.isAnswerStale) appendLine("注意：题目已修改，本答案对应修改前的题目，尚未重新解析。")
                                            if (result.recognizedQuestion.isNotBlank()) appendLine(result.recognizedQuestion)
                                            appendLine("答案：${result.answerDisplay}")
                                            result.reasoningSteps.forEachIndexed { index, step ->
                                                appendLine("${index + 1}. ${step.title}：${step.detail}")
                                            }
                                        }
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, shareText)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "分享解析"))
                                    },
                                    onEditQuestion = { showEditDialog = true },
                                    onAddImages = {
                                        imagePicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    onRetry = { viewModel.retryAnalysis(questionId) {} }
                                )
                            }
                            item {
                                FollowUpThread(
                                    questionId = questionId,
                                    answerRevision = q.answerRevision,
                                    viewModel = viewModel,
                                    stream = followUpStream,
                                    sending = sendingFollowUp,
                                    error = followUpError,
                                    onStop = { viewModel.cancelFollowUp(questionId) },
                                    onDismissError = { viewModel.consumeFollowUpError() }
                                )
                            }
                        } else if (running == null && q.status == "completed") {
                            item {
                                EmptyResultCard(
                                    retrying = taskFailed != null,
                                    onRetry = { viewModel.retryAnalysis(questionId) {} }
                                )
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                    if (showJumpToBottom) {
                        FilledTonalMiniButton(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                            onClick = {
                                scope.launch {
                                    listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showEditDialog && question != null) {
        EditQuestionDialog(
            initialText = question.questionText,
            initialType = QuestionType.fromStorage(question.questionType),
            retrying = running != null || editSaving,
            error = editError,
            onDismiss = {
                if (!editSaving) {
                    showEditDialog = false
                    editError = null
                }
            },
            onConfirm = { text, type ->
                editSaving = true
                editError = null
                viewModel.updateQuestionTextAndRetry(
                    id = questionId,
                    newText = text,
                    newType = type,
                    onSaved = {
                        editSaving = false
                        showEditDialog = false
                    },
                    onError = { message ->
                        editSaving = false
                        editError = message
                    }
                )
            }
        )
    }

}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AnalysisProgressCard(
    task: AnalysisTaskState,
    elapsedMs: Long,
    cancelling: Boolean,
    showPreviewAnswer: Boolean,
    previewComplete: Boolean,
    onCancel: () -> Unit
) {
    val elapsedSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val currentIndex = when (task.status) {
        AnalysisTaskStatus.PREPARING -> 0
        AnalysisTaskStatus.UPLOADING -> 1
        AnalysisTaskStatus.WAITING -> 2
        AnalysisTaskStatus.RECEIVING -> 3
        AnalysisTaskStatus.VALIDATING -> 4
        else -> 0
    }
    val previewResult = task.preview?.toResult()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "正在解析 · ${task.mode.label}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text("已耗时 ${elapsedSeconds}s", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    previewComplete -> "预览答案已生成，正在核对完整性与格式…"
                    elapsedSeconds >= 20 -> "仍在等待模型响应，请保持网络连接；可以取消后重试。"
                    else -> "已进入结果页，完成后会自动显示，可随时取消。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            ProgressStageLabels.forEachIndexed { index, label ->
                StageRow(
                    index = index,
                    label = label,
                    current = index == currentIndex,
                    done = index < currentIndex
                )
            }
            if (showPreviewAnswer && previewResult != null && previewResult.hasAnswerPreview()) {
                Spacer(Modifier.height(12.dp))
                AnalysisAnswerCard(result = previewResult, compact = true)
            } else if (showPreviewAnswer && previewResult != null && previewResult.recognizedQuestion.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "已识别题干，正在生成答案…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (task.reasoningPreview.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                CollapsibleSection(
                    title = "思考过程（预览）",
                    subtitle = "思考内容可能不完整，仅用于观察进度"
                ) {
                    Text(
                        task.reasoningPreview.take(MaxReasoningPreviewChars),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (task.reasoningPreview.length > MaxReasoningPreviewChars) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "…已截断，完整思考不写入结果",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onCancel,
                enabled = !cancelling,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (cancelling) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在取消…")
                } else {
                    Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("取消解析")
                }
            }
        }
    }
}

private val ProgressStageLabels = listOf(
    "准备图片",
    "上传题目",
    "等待模型响应",
    "接收答案",
    "核对答案"
)

private const val MaxReasoningPreviewChars = 1600
private const val MaxFollowUpReasoningChars = 1200

@Composable
private fun StageRow(index: Int, label: String, current: Boolean, done: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val circleColor = when {
            done -> MaterialTheme.colorScheme.primary
            current -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        val contentColor = when {
            done || current -> MaterialTheme.colorScheme.onPrimary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(circleColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                Icon(Icons.Outlined.Check, contentDescription = null, tint = contentColor, modifier = Modifier.size(13.dp))
            } else {
                Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = contentColor)
            }
        }
        Spacer(Modifier.width(9.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
            color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        if (current) {
            Text("进行中", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private data class StatusBannerModel(
    val tone: BannerTone,
    val title: String,
    val message: String
)

private enum class BannerTone { ERROR, WARNING, INFO }

private fun resolveStatusBanner(
    question: Question,
    taskFailed: AnalysisTaskState?,
    taskCancelled: AnalysisTaskState?,
    taskInterrupted: AnalysisTaskState?,
    noRunningTask: Boolean
): StatusBannerModel? = when (question.status) {
    "failed" -> StatusBannerModel(
        BannerTone.ERROR,
        "解析失败",
        question.errorMessage ?: taskFailed?.errorMessage ?: "请检查网络与 API 设置后重新解析。"
    )
    "cancelled" -> StatusBannerModel(
        BannerTone.INFO,
        "已取消本次解析",
        if (taskCancelled != null) "这次取消不会影响已保存的旧结果。" else "可以重新解析这道题。"
    )
    "interrupted" -> StatusBannerModel(
        BannerTone.WARNING,
        "上次解析被中断",
        "应用退出或任务结束后解析未完成，可以重新解析；旧结果会保留。"
    )
    "analyzing" -> if (noRunningTask) StatusBannerModel(
        BannerTone.WARNING,
        "解析任务未在运行",
        "上次解析没有完成。点击“重新解析”重新发起请求。"
    ) else null
    else -> taskFailed?.let {
        StatusBannerModel(BannerTone.ERROR, "重新解析失败", it.errorMessage?.ifBlank { "旧结果已保留，可稍后重试。" } ?: "旧结果已保留，可稍后重试。")
    } ?: taskInterrupted?.let {
        StatusBannerModel(BannerTone.WARNING, "重新解析被中断", "旧结果已保留，可以再次重新解析。")
    }
}

@Composable
private fun ResultStatusBanner(
    tone: BannerTone,
    title: String,
    message: String,
    onRetry: () -> Unit
) {
    val container = when (tone) {
        BannerTone.ERROR -> MaterialTheme.colorScheme.errorContainer
        BannerTone.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        BannerTone.INFO -> MaterialTheme.colorScheme.surfaceVariant
    }
    val onContainer = when (tone) {
        BannerTone.ERROR -> MaterialTheme.colorScheme.onErrorContainer
        BannerTone.WARNING -> MaterialTheme.colorScheme.onTertiaryContainer
        BannerTone.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = container,
        shape = MaterialTheme.shapes.large
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = onContainer)
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = onContainer)
            }
            Spacer(Modifier.height(6.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = onContainer)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("重新解析")
            }
        }
    }
}

@Composable
private fun ResultMetaLine(question: Question) {
    val duration = if (question.durationMs > 0) {
        String.format(Locale.CHINA, "耗时 %.1f s", question.durationMs / 1000f)
    } else {
        "耗时未知"
    }
    val metrics = question.result?.metrics
    val requestModel = metrics?.requestModel?.takeIf { it.isNotBlank() } ?: question.model
    val responseModel = metrics?.responseModel.orEmpty()
    val modelLabel = if (responseModel.isNotBlank() && responseModel != requestModel) {
        "${ModelCatalog.displayName(requestModel)}（响应 $responseModel）"
    } else {
        ModelCatalog.displayName(requestModel)
    }
    val transport = when {
        metrics == null -> ""
        metrics.streamed -> "流式"
        else -> "非流式"
    }
    val thinking = metrics?.let { if (it.thinkingEnabled) "思考开" else "思考关" }.orEmpty()
    val saveStatus = when (metrics?.saveStatus) {
        "saved" -> "已保存"
        "failed" -> "保存失败"
        else -> ""
    }
    val finish = metrics?.finishReason?.takeIf { it.isNotBlank() }?.let { "· $it" } ?: ""
    Text(
        listOf(question.modeLabel, modelLabel, transport, thinking, saveStatus, finish, duration)
            .filter { it.isNotBlank() }.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun QuestionImagesRow(paths: List<String>) {
    SectionCard(title = "原题图片") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(paths) { path ->
                AsyncImage(
                    model = rememberFileImageRequest(path),
                    contentDescription = "题目图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(96.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultActions(
    addingImages: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onEditQuestion: () -> Unit,
    onAddImages: () -> Unit,
    onRetry: () -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(onClick = onCopy) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("复制答案")
        }
        OutlinedButton(onClick = onShare) {
            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("分享")
        }
        OutlinedButton(onClick = onEditQuestion) {
            Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("修改题干")
        }
        OutlinedButton(onClick = onAddImages, enabled = !addingImages) {
            if (addingImages) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(if (addingImages) "正在添加…" else "补充图片")
        }
        OutlinedButton(onClick = onRetry) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text("重新解析")
        }
    }
}

@Composable
private fun MissingQuestionCard(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text("题目不存在或已删除", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "可能已在历史记录中删除。返回首页后可以重新拍题解析。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) { Text("返回首页") }
        }
    }
}

@Composable
private fun EmptyResultCard(retrying: Boolean, onRetry: () -> Unit) {
    Column {
        InfoBanner("这道题暂时没有解析内容。可以检查 API 设置和网络后重新解析。")
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onRetry,
            enabled = !retrying,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (retrying) "正在重新解析…" else "重新解析")
        }
    }
}

@Composable
private fun FollowUpInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    enabled: Boolean,
    attachAvailable: Boolean,
    attachImages: Boolean,
    onAttachChange: (Boolean) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (enabled && attachAvailable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("附带原图", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "默认关闭，开启会额外消耗 token",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = attachImages,
                        onCheckedChange = onAttachChange,
                        enabled = !sending
                    )
                }
            }
            if (enabled && value.isBlank() && !sending) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 2.dp)
                ) {
                    items(listOf("为什么不是其他选项？", "解释关键概念", "这道题的鉴别点是什么？")) { quick ->
                        SuggestionChip(onClick = { onValueChange(quick) }, label = { Text(quick) })
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    placeholder = {
                        Text(
                            when {
                                !enabled -> "解析完成后可继续追问…"
                                sending -> "老师正在回答…可先输入下一问"
                                else -> "追问这道题…"
                            }
                        )
                    },
                    minLines = 1,
                    maxLines = 4,
                    shape = MaterialTheme.shapes.large
                )
                Spacer(Modifier.width(8.dp))
                if (sending) {
                    Button(
                        onClick = onStop,
                        modifier = Modifier.size(48.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(Icons.Outlined.Stop, contentDescription = "停止生成", modifier = Modifier.size(20.dp))
                    }
                } else {
                    Button(
                        onClick = onSend,
                        enabled = enabled && value.isNotBlank(),
                        modifier = Modifier.size(48.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送追问", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowUpThread(
    questionId: String,
    answerRevision: Long,
    viewModel: MainViewModel,
    stream: FollowUpStreamState?,
    sending: Boolean,
    error: String?,
    onStop: () -> Unit,
    onDismissError: () -> Unit
) {
    val messages by remember(questionId, answerRevision) {
        viewModel.followUps(questionId, answerRevision)
    }
        .collectAsState(initial = emptyList())
    SectionCard(title = "继续追问") {
        if (messages.isEmpty() && !sending) {
            Text(
                "有没看懂的地方？可以围绕这道题继续问，系统会带着原题和上次解析一起回答。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                messages.forEach { item -> FollowUpBubble(item) }
            }
        }
        if (stream != null && stream.sending) {
            Spacer(Modifier.height(8.dp))
            FollowUpStreamBubble(stream = stream, onStop = onStop)
        } else if (sending) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "老师正在回答…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            ErrorBanner(error)
            TextButton(onClick = onDismissError) { Text("知道了") }
        }
    }
}

@Composable
private fun FollowUpStreamBubble(stream: FollowUpStreamState, onStop: () -> Unit) {
    var reasoningExpanded by rememberSaveable(stream.questionId) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 4.dp, bottomEnd = 14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (stream.content.isBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            if (stream.reasoningPreview.isNotBlank()) "正在思考…" else "正在生成回答…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        stream.content.toReadablePlainText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (stream.reasoningPreview.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    TextButton(
                        onClick = { reasoningExpanded = !reasoningExpanded },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Text(
                            if (reasoningExpanded) "收起思考过程" else "查看思考过程（预览）",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    if (reasoningExpanded) {
                        Text(
                            stream.reasoningPreview.take(MaxFollowUpReasoningChars),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
                        )
                        if (stream.reasoningPreview.length > MaxFollowUpReasoningChars) {
                            Text(
                                "…已截断",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onStop, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("停止生成", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun FollowUpBubble(item: FollowUp) {
    val isUser = item.role == "user"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 14.dp
            ),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(
                    if (item.content.isJsonNullLiteral()) "（本次回答为空，请重试）"
                    else item.content.toReadablePlainText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!isUser && item.usage.isAvailable) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "用量 ${item.usage.totalTokens} tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    )
                }
            }
        }
    }
}

@Composable
private fun FilledTonalMiniButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("回到底部", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun EditQuestionDialog(
    initialText: String,
    initialType: QuestionType,
    retrying: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, QuestionType) -> Unit
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    var type by remember(initialType) { mutableStateOf(initialType) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改题目后重新解析") },
        text = {
            Column {
                Text(
                    "修正识别错误的题干，并确认题型。保存后会重新请求模型解析，旧结果会保留到新结果返回。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!error.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(10.dp))
                QuestionTypeChips(
                    selected = type,
                    onSelected = { type = it },
                    enabled = !retrying
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text, type) },
                enabled = text.isNotBlank() && !retrying
            ) { Text("保存并重新解析") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun AnalysisResult.hasAnswerPreview(): Boolean =
    status == "answered" && (answer.isNotEmpty() || answerText.isNotBlank())