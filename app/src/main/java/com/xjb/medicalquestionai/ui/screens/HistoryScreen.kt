package com.xjb.medicalquestionai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: MainViewModel,
    navController: NavController
) {
    val history by viewModel.history.collectAsState()
    val query by viewModel.historyQuery.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "我的题库",
                subtitle = if (history.isEmpty()) "每一次解题，都值得回顾" else "已记录 ${history.size} 道题 · 随时温习",
                actions = {
                    if (history.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Outlined.DeleteOutline, contentDescription = "清空历史")
                        }
                    }
                }
            )
        },
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
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text("搜索题干或答案") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                shape = MaterialTheme.shapes.large,
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        TextButton(onClick = { viewModel.setHistoryQuery("") }) { Text("清除") }
                    }
                }
            )
            if (history.isEmpty()) {
                EmptyState(
                    icon = if (query.isBlank()) Icons.Outlined.History else Icons.Outlined.Search,
                    title = if (query.isBlank()) "还没有历史记录" else "没有找到匹配的题目",
                    description = if (query.isBlank()) "拍一张题目，或者直接粘贴题干，解析完成后会自动保存在这里。"
                    else "换一个关键词试试，或者清除搜索条件。",
                    actionLabel = if (query.isBlank()) "去拍题" else "清除搜索",
                    onAction = {
                        if (query.isBlank()) navController.navigate("home") else viewModel.setHistoryQuery("")
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
                    items(history, key = { it.id }) { question ->
                        HistoryCard(
                            question = question,
                            onClick = { navController.navigate("result/${question.id}") },
                            onToggleFavorite = { viewModel.toggleFavorite(question.id) },
                            onDelete = { viewModel.deleteQuestion(question.id) }
                        )
                    }
                }
            }
        }
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
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
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
        Row(Modifier.padding(12.dp)) {
            if (question.imagePaths.isNotEmpty()) {
                AsyncImage(
                    model = rememberFileImageRequest(question.imagePaths.first()),
                    contentDescription = "题目缩略图",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(68.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(10.dp)
                        )
                        .clip(RoundedCornerShape(10.dp))
                )
                Spacer(Modifier.width(12.dp))
            } else {
                Surface(
                    modifier = Modifier.size(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.BookmarkBorder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
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
                    Text(
                        "${question.typeLabel} · ${question.sourceLabel} · $date",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多", modifier = Modifier.size(18.dp))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(if (question.isFavorite) "取消收藏" else "收藏") },
                                leadingIcon = {
                                    Icon(
                                        if (question.isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onToggleFavorite()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                }
                            )
                        }
                    }
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
            }
            if (question.isFavorite) {
                Icon(
                    Icons.Outlined.Star,
                    contentDescription = "已收藏",
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}
