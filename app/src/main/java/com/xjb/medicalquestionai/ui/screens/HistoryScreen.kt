package com.xjb.medicalquestionai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xjb.medicalquestionai.ui.openTopLevel
import com.xjb.medicalquestionai.ui.openDetail
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.xjb.medicalquestionai.MainViewModel
import com.xjb.medicalquestionai.data.Question
import com.xjb.medicalquestionai.ui.components.AppTopBar
import com.xjb.medicalquestionai.ui.components.EmptyState
import com.xjb.medicalquestionai.ui.components.HistoryStatusChip
import com.xjb.medicalquestionai.ui.components.rememberFileImageRequest
import com.xjb.medicalquestionai.ui.components.QuestionImageViewer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
    navController: NavController
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    val query by viewModel.historyQuery.collectAsState()
    val clearingHistory by viewModel.isClearingHistory.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val notice by viewModel.draftNotice.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var onlyFavorites by rememberSaveable { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var deleteQuestionId by rememberSaveable { mutableStateOf<String?>(null) }
    var viewingImages by remember { mutableStateOf<List<String>?>(null) }
    val matches = history?.matches.orEmpty()
    val favoriteMatches = remember(matches) { matches.filter { it.isFavorite } }
    val visibleHistory = if (onlyFavorites) favoriteMatches else matches
    val total = history?.total ?: 0
    val searching = query.isNotBlank()
    val emptyTitle = when {
        total == 0 -> "还没有历史记录"
        onlyFavorites && searching -> "没有匹配的收藏"
        searching -> "没有找到匹配的题目"
        else -> "还没有收藏的题目"
    }

    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeDraftNotice()
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "我的题库",
                subtitle = when {
                    clearingHistory -> "正在清空…"
                    total > 0 -> "共 $total 道题"
                    else -> null
                },
                actions = {
                    if (total > 0) {
                        IconButton(onClick = { showClearDialog = true }, enabled = !clearingHistory) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "清空历史")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setHistoryQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text("搜索题干或答案") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                shape = MaterialTheme.shapes.large,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setHistoryQuery("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "清除搜索词")
                        }
                    }
                }
            )
            if (total > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !onlyFavorites,
                        onClick = { onlyFavorites = false },
                        label = { Text("全部 (${matches.size})") }
                    )
                    FilterChip(
                        selected = onlyFavorites,
                        onClick = { onlyFavorites = true },
                        label = { Text("仅收藏 (${favoriteMatches.size})") }
                    )
                }
            }
            if (history == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (visibleHistory.isEmpty()) {
                EmptyState(
                    icon = when {
                        total == 0 -> Icons.Outlined.History
                        searching -> Icons.Outlined.Search
                        else -> Icons.Outlined.StarBorder
                    },
                    title = emptyTitle,
                    description = when {
                        total == 0 -> "拍照或粘贴题干开始解析。在解析页点按星标可以收藏题目。"
                        onlyFavorites && searching -> "可切换到全部题目，或尝试其他关键词。"
                        searching -> "换一个关键词试试，或者清除搜索条件。"
                        else -> "在题目卡片或解析页点按星标，即可加入收藏。"
                    },
                    actionLabel = when {
                        total == 0 -> "去拍题"
                        onlyFavorites -> "查看全部"
                        else -> "清除搜索"
                    },
                    onAction = {
                        focusManager.clearFocus()
                        when {
                            total == 0 -> navController.openTopLevel("home")
                            onlyFavorites -> onlyFavorites = false
                            else -> viewModel.setHistoryQuery("")
                        }
                    }
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 18.dp, end = 18.dp, top = 4.dp, bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visibleHistory, key = { it.id }, contentType = { "question" }) { question ->
                        HistoryCard(
                            question = question,
                            onViewImages = { viewingImages = question.imagePaths },
                            onClick = {
                                focusManager.clearFocus()
                                navController.openDetail("result/${question.id}")
                            },
                            onToggleFavorite = { viewModel.toggleFavorite(question.id) },
                            onDelete = { deleteQuestionId = question.id },
                            actionsEnabled = !clearingHistory
                        )
                    }
                }
            }
        }
    }

    viewingImages?.let { paths ->
        QuestionImageViewer(paths = paths, onDismiss = { viewingImages = null })
    }
    deleteQuestionId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteQuestionId = null },
            title = { Text("删除这道题？") },
            text = { Text("题目、解析、追问和应用内的图片都会删除。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteQuestionId = null
                    viewModel.deleteQuestion(id)
                }, enabled = !clearingHistory) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteQuestionId = null }) { Text("取消") } }
        )
    }
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空全部历史记录？") },
            text = { Text("这会删除所有题目和解析，并清理应用内的题目图片，操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    viewModel.clearHistory()
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun HistoryCard(
    question: Question,
    onViewImages: () -> Unit,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    actionsEnabled: Boolean
) {
    val date = remember(question.createdAt) {
        SimpleDateFormat("MM月dd日 HH:mm", Locale.CHINA).format(Date(question.createdAt))
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(Modifier.padding(14.dp)) {
            if (question.imagePaths.isNotEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(68.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onViewImages)) {
                        AsyncImage(
                            model = rememberFileImageRequest(question.imagePaths.first()),
                            contentDescription = "查看原题图片",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                        Icon(Icons.Outlined.ZoomIn, contentDescription = null, tint = Color.White,
                            modifier = Modifier.align(Alignment.BottomEnd)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(topStart = 6.dp))
                                .padding(3.dp).size(18.dp))
                    }
                    if (question.imagePaths.size > 1) {
                        Text("共 ${question.imagePaths.size} 张", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HistoryStatusChip(question.status)
                    if (question.isAnswerStale) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "答案待更新",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    question.shortQuestion,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(5.dp))
                val preview = when (question.status) {
                    "failed" -> question.errorMessage ?: "解析失败，可点击重试"
                    "cancelled" -> "已取消本次解析，可重新解析"
                    "interrupted" -> "上次解析被中断，可重新解析"
                    "analyzing", "receiving", "validating", "preparing", "uploading" -> "正在解析…"
                    else -> question.result?.answerDisplay ?: "等待解析结果"
                }
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (question.status) {
                        "failed" -> MaterialTheme.colorScheme.error
                        "analyzing", "receiving", "validating", "preparing", "uploading" -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "${question.typeLabel} · ${question.sourceLabel} · $date",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column {
                IconButton(onClick = onToggleFavorite, enabled = actionsEnabled) {
                    Icon(
                        if (question.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (question.isFavorite) "取消收藏" else "收藏",
                        tint = if (question.isFavorite && actionsEnabled) MaterialTheme.colorScheme.primary
                        else androidx.compose.material3.LocalContentColor.current
                    )
                }
                IconButton(onClick = onDelete, enabled = actionsEnabled) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "删除题目")
                }
            }
        }
    }
}
