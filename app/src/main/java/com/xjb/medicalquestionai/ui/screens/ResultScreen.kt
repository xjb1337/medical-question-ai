package com.xjb.medicalquestionai.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import com.xjb.medicalquestionai.MainViewModel
import com.xjb.medicalquestionai.data.FollowUp
import com.xjb.medicalquestionai.data.Question
import com.xjb.medicalquestionai.data.QuestionType
import com.xjb.medicalquestionai.ui.components.AnalysisResultView
import com.xjb.medicalquestionai.ui.components.AppTopBar
import com.xjb.medicalquestionai.ui.components.ErrorBanner
import com.xjb.medicalquestionai.ui.components.InfoBanner
import com.xjb.medicalquestionai.ui.components.QuestionTypeChips
import com.xjb.medicalquestionai.ui.components.SectionCard
import com.xjb.medicalquestionai.ui.components.rememberFileImageRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ResultScreen(
    questionId: String,
    viewModel: MainViewModel,
    navController: NavController
) {
    val flow = remember(questionId) { viewModel.questionFlow(questionId) }
    val question by flow.collectAsState(initial = null)
    val retryingIds by viewModel.retryingIds.collectAsState()
    val retrying = questionId in retryingIds
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showEditDialog by remember { mutableStateOf(false) }
    var addingImages by remember { mutableStateOf(false) }
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

    Scaffold(
        topBar = {
            AppTopBar(
                title = "题目解析",
                subtitle = question?.let { SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA).format(Date(it.createdAt)) },
                onBack = { navController.popBackStack() },
                actions = {
                    if (question != null) {
                        IconButton(onClick = { viewModel.toggleFavorite(questionId) }) {
                            Icon(
                                if (question!!.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                contentDescription = if (question!!.isFavorite) "取消收藏" else "收藏"
                            )
                        }
                    }
                }
            )
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
                question == null -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                question!!.status == "analyzing" || retrying -> {
                    AnalyzingState(question!!)
                }
                question!!.status == "failed" -> {
                    FailedState(
                        question = question!!,
                        error = question!!.errorMessage ?: "解析失败，请重试",
                        onRetry = { viewModel.retryAnalysis(questionId) }
                    )
                }
                else -> {
                    val result = question!!.result
                    if (result == null) {
                        FailedState(
                            question = question!!,
                            error = "没有找到解析内容，请重新解析。",
                            onRetry = { viewModel.retryAnalysis(questionId) }
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 18.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            QuestionMeta(question!!)
                            if (question!!.imagePaths.isNotEmpty()) {
                                QuestionImages(question!!.imagePaths)
                            }
                            AnalysisResultView(result)
                            if (result.usage.isAvailable) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        result.usage.shortLabel,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(result.answerDisplay))
                                        scope.launch { snackbarHostState.showSnackbar("答案已复制") }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("复制答案")
                                }
                                OutlinedButton(
                                    onClick = {
                                        val shareText = buildString {
                                            appendLine("医学拍题助手 · 解析结果")
                                            appendLine()
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
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("分享")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = { showEditDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("修改题干")
                                }
                                OutlinedButton(
                                    onClick = { viewModel.retryAnalysis(questionId) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("重新解析")
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    imagePicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                enabled = !addingImages && question!!.imagePaths.size < 9,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (addingImages) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(17.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(if (addingImages) "正在添加图片…" else "补充题目图片")
                            }
                            FollowUpSection(question = question!!, viewModel = viewModel)
                            Spacer(Modifier.height(20.dp))
                        }
                    }
                }
            }
        }
    }

    if (showEditDialog && question != null) {
        EditQuestionDialog(
            initialText = question!!.questionText,
            initialType = QuestionType.fromStorage(question!!.questionType),
            retrying = retrying,
            onDismiss = { showEditDialog = false },
            onConfirm = { text, type ->
                showEditDialog = false
                viewModel.updateQuestionTextAndRetry(questionId, text, type)
            }
        )
    }
}

@Composable
private fun QuestionMeta(question: Question) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MetaPill(question.typeLabel)
        MetaPill(question.sourceLabel)
        MetaPill("由 ${question.model} 解析")
    }
}

@Composable
private fun MetaPill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun QuestionImages(paths: List<String>) {
    SectionCard(title = "题目图片") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(paths) { path ->
                AsyncImage(
                    model = rememberFileImageRequest(path),
                    contentDescription = "题目图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(112.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                )
            }
        }
    }
}

@Composable
private fun FollowUpSection(
    question: Question,
    viewModel: MainViewModel
) {
    val messages by remember(question.id) { viewModel.followUps(question.id) }
        .collectAsState(initial = emptyList())
    val sendingIds by viewModel.followUpSendingIds.collectAsState()
    val sending = question.id in sendingIds
    val error by viewModel.followUpError.collectAsState()
    var input by remember(question.id) { mutableStateOf("") }
    var attachImages by remember(question.id) { mutableStateOf(false) }

    SectionCard(title = "继续追问") {
        Text(
            "有没看懂的地方？可以围绕这道题继续问，系统会带着原题和上次解析一起回答。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (messages.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            messages.forEach { item -> FollowUpBubble(item) }
        }
        if (error != null) {
            Spacer(Modifier.height(8.dp))
            ErrorBanner(error!!)
            TextButton(onClick = { viewModel.consumeFollowUpError() }) {
                Text("知道了")
            }
        }
        if (question.imagePaths.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("附带原图追问", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "适合需要重新看图片细节的问题；会额外消耗 token。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = attachImages, onCheckedChange = { attachImages = it }, enabled = !sending)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text("例如：为什么 B 不对？请再讲一遍这道题的鉴别点") },
            minLines = 2,
            maxLines = 5,
            enabled = !sending,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                val text = input.trim()
                if (text.isNotEmpty()) {
                    input = ""
                    viewModel.sendFollowUp(question.id, text, attachImages)
                }
            },
            enabled = input.isNotBlank() && !sending,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(Modifier.width(8.dp))
                Text("老师正在回答…")
            } else {
                Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("发送追问")
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
            .padding(vertical = 4.dp),
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
                    item.content,
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
private fun EditQuestionDialog(
    initialText: String,
    initialType: QuestionType,
    retrying: Boolean,
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
                    "修正识别错误的题干，并确认题型。保存后会重新请求模型解析。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

@Composable
private fun AnalyzingState(question: Question) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(44.dp), strokeWidth = 3.dp)
        Spacer(Modifier.height(18.dp))
        Text("正在识别题目并组织讲解…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "通常需要十几秒到一分钟。请保持网络连接，不要离开当前页面。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
        Text(
            question.shortQuestion,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun FailedState(
    question: Question,
    error: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        InfoBanner("这道题暂时没有解析成功。你可以检查 API 设置和网络后重新解析。")
        ErrorBanner(error)
        if (question.imagePaths.isNotEmpty()) {
            QuestionImages(question.imagePaths)
        }
        if (question.questionText.isNotBlank()) {
            SectionCard(title = "题目文字") {
                Text(question.questionText, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
            Text("重新解析")
        }
    }
}
