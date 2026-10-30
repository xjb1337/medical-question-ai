package com.xjb.medicalquestionai.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xjb.medicalquestionai.BuildConfig
import com.xjb.medicalquestionai.MainViewModel
import com.xjb.medicalquestionai.data.AnswerMode
import com.xjb.medicalquestionai.data.ModelCatalog
import com.xjb.medicalquestionai.data.ModelSpec
import com.xjb.medicalquestionai.data.SettingsRepository
import com.xjb.medicalquestionai.data.update.UpdateViewModel
import com.xjb.medicalquestionai.ui.components.AppTopBar
import com.xjb.medicalquestionai.ui.components.CollapsibleSection
import com.xjb.medicalquestionai.ui.components.InfoBanner
import com.xjb.medicalquestionai.ui.components.SectionCard
import com.xjb.medicalquestionai.ui.components.UpdateSection
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    navController: NavController,
    updateViewModel: UpdateViewModel
) {
    val settings by viewModel.settings.collectAsState()
    val history by viewModel.history.collectAsState()
    val isTesting by viewModel.isTestingKey.collectAsState()
    val isCheckingConnection by viewModel.isCheckingConnection.collectAsState()
    val isVerifyingModel by viewModel.isVerifyingModel.collectAsState()
    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val followUpSendingIds by viewModel.followUpSendingIds.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var apiKeyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var baseUrlInput by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var modelInput by remember(settings.model) { mutableStateOf(settings.model) }
    var localError by remember { mutableStateOf<String?>(null) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    var connectionOk by remember { mutableStateOf<Boolean?>(null) }
    var modelVerifyStatus by remember { mutableStateOf<String?>(null) }
    var modelVerifyOk by remember { mutableStateOf<Boolean?>(null) }

    // 连接/模型测试结果只对当前密钥、地址和模型有效，设置变化后立即作废旧状态。
    LaunchedEffect(settings.apiKeyHint, settings.baseUrl, settings.model) {
        connectionStatus = null
        connectionOk = null
        modelVerifyStatus = null
        modelVerifyOk = null
    }

    Scaffold(
        topBar = { AppTopBar(title = "学习设置", subtitle = "按你的习惯，调整解题体验") },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("让学习更顺手", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("连接模型，选择你的解题节奏", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                    }
                }
            }
            SectionCard(title = "密钥与连接") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (settings.apiKeyConfigured) Icons.Outlined.CheckCircle else Icons.Outlined.Key,
                        contentDescription = null,
                        tint = if (settings.apiKeyConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (settings.apiKeyConfigured) "已配置 · ${settings.apiKeyHint}" else "尚未配置",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (settings.apiKeyConfigured) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = {
                        apiKeyInput = it
                        localError = null
                        connectionStatus = null
                        connectionOk = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (settings.apiKeyConfigured) "输入新密钥以替换" else "粘贴 API 密钥") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                    enabled = !isTesting,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showKey = !showKey }) {
                            Icon(
                                if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showKey) "隐藏密钥" else "显示密钥"
                            )
                        }
                    },
                    shape = MaterialTheme.shapes.medium
                )
                if (localError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(localError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val key = apiKeyInput.trim()
                            if (key.isBlank()) {
                                localError = "请先粘贴 API 密钥"
                                return@Button
                            }
                            viewModel.saveApiKey(key) { error ->
                                if (error == null) {
                                    apiKeyInput = ""
                                    connectionStatus = null
                                    connectionOk = null
                                    scope.launch { snackbarHostState.showSnackbar("密钥已保存，建议测试连接") }
                                } else {
                                    localError = error
                                }
                            }
                        },
                        enabled = !isTesting,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text(if (settings.apiKeyConfigured) "保存新密钥" else "保存密钥")
                    }
                    OutlinedButton(
                        onClick = {
                            connectionStatus = null
                            connectionOk = null
                            modelVerifyStatus = null
                            modelVerifyOk = null
                            viewModel.checkSavedConnection { message, ok ->
                                connectionOk = ok
                                connectionStatus = message
                                scope.launch { snackbarHostState.showSnackbar(message) }
                            }
                        },
                        enabled = settings.apiKeyConfigured && !isCheckingConnection && !isTesting,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        if (isCheckingConnection) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (isCheckingConnection) "检查中…" else "检查连接")
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            modelVerifyStatus = null
                            modelVerifyOk = null
                            viewModel.verifySavedModel { message, ok ->
                                modelVerifyOk = ok
                                modelVerifyStatus = message
                                scope.launch { snackbarHostState.showSnackbar(message) }
                            }
                        },
                        enabled = settings.apiKeyConfigured && !isVerifyingModel && !isTesting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        if (isVerifyingModel) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (isVerifyingModel) "验证中…" else "验证模型（发一次极短文字请求）")
                    }
                }
                if (connectionStatus != null) {
                    Spacer(Modifier.height(10.dp))
                    val ok = connectionOk == true
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                                tint = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                connectionStatus!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                if (modelVerifyStatus != null) {
                    Spacer(Modifier.height(8.dp))
                    val ok = modelVerifyOk == true
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                                tint = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                modelVerifyStatus!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
                if (settings.apiKeyConfigured) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { showRemoveDialog = true }, enabled = !isTesting) {
                        Text("移除已保存的密钥", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "密钥使用 Android Keystore 加密后保存在本机，不会写入日志或历史记录。测试连接会实际请求当前模型。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard(title = "做题模式") {
                Text(
                    "做题模式决定思考强度与输出长度；可在首页随时切换。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnswerMode.entries.forEach { mode ->
                        ModeCard(
                            mode = mode,
                            selected = settings.answerMode == mode,
                            enabled = true,
                            onClick = { viewModel.saveAnswerMode(mode) }
                        )
                    }
                }
            }

            SectionCard(title = "模型") {
                if (settings.modelMigrationNote.isNotBlank()) {
                    InfoBanner("设置已自动迁移：${settings.modelMigrationNote}")
                    TextButton(onClick = viewModel::dismissModelMigrationNote) { Text("知道了") }
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    "拍题优先使用支持图片的 Flash；Pro 仅用于纯文字题。旧模型名已移出预设。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModelCatalog.models.forEach { spec ->
                        ModelCard(
                            spec = spec,
                            selected = settings.model == spec.id,
                            onClick = { viewModel.saveModel(spec.id) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "当前：${settings.modelLabel}" + if (settings.modelSupportsImages) " · 支持图片" else " · 仅文字",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            SectionCard(title = "数据与隐私") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("保存解析历史", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "关闭后仍会生成当前解析，但不会显示在历史记录中；收藏的题目仍会保留。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.saveHistory,
                        onCheckedChange = viewModel::saveHistoryPreference
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            if (history.isEmpty()) {
                                scope.launch { snackbarHostState.showSnackbar("没有可导出的历史记录") }
                            } else {
                                val text = buildString {
                                    appendLine("医学拍题助手 · 历史记录导出")
                                    appendLine("共 ${history.size} 条")
                                    history.forEach { question ->
                                        appendLine()
                                        appendLine("【${question.typeLabel}】${question.shortQuestion}")
                                        appendLine("状态：${question.status} · ${question.modeLabel} · ${question.modelLabel}")
                                        question.result?.let { appendLine("答案：${it.answerDisplay}") }
                                    }
                                }
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, text)
                                }
                                context.startActivity(Intent.createChooser(intent, "导出历史记录"))
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) { Text("导出历史") }
                    OutlinedButton(
                        onClick = { showClearDialog = true },
                        enabled = history.isNotEmpty(),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Text("清空历史", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "题目图片保存在应用私有目录，卸载应用会一并删除。导出内容只包含文字，不包含图片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            CollapsibleSection(
                title = "高级接口",
                subtitle = "仅在使用兼容 OpenAI 协议的中转服务时修改"
            ) {
                OutlinedTextField(
                    value = baseUrlInput,
                    onValueChange = {
                        baseUrlInput = it
                        localError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Base URL") },
                    singleLine = true,
                    enabled = !isTesting,
                    placeholder = { Text("https://api.deepseek.com") },
                    shape = MaterialTheme.shapes.medium
                )
                if (baseUrlInput.trim().startsWith("http://", ignoreCase = true)) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "警告：Android 版仅允许 HTTPS，HTTP 地址不会保存，也无法用于解析。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val validation = SettingsRepository.validateBaseUrl(baseUrlInput)
                            if (validation != null) {
                                localError = validation
                            } else if (viewModel.saveBaseUrl(SettingsRepository.normalizeBaseUrl(baseUrlInput))) {
                                localError = null
                                scope.launch { snackbarHostState.showSnackbar("接口地址已保存") }
                            } else {
                                localError = "接口地址保存失败，请检查格式"
                            }
                        },
                        enabled = !isTesting
                    ) { Text("保存地址") }
                    TextButton(onClick = {
                        baseUrlInput = SettingsRepository.DEFAULT_BASE_URL
                        viewModel.saveBaseUrl(SettingsRepository.DEFAULT_BASE_URL)
                    }) { Text("恢复默认") }
                }
                Text(
                    if (ModelCatalog.isOfficialBaseUrl(baseUrlInput)) "官方地址" else "自定义兼容地址：能力与模型名需自行确认",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Text("自定义模型（能力未验证）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("模型名") },
                        singleLine = true,
                        enabled = !isTesting,
                        shape = MaterialTheme.shapes.medium
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            val name = modelInput.trim()
                            if (name.isBlank()) {
                                scope.launch { snackbarHostState.showSnackbar("模型名称不能为空") }
                            } else {
                                viewModel.saveModel(name)
                                scope.launch { snackbarHostState.showSnackbar("模型名称已保存") }
                            }
                        },
                        enabled = !isTesting
                    ) { Text("保存") }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "自定义模型默认按不支持图片、非流式处理；如服务端确认支持，可在此声明能力，官方模型始终按官方能力表处理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自定义模型支持图片", style = MaterialTheme.typography.bodyMedium)
                        Text("仅在图片题需要发送原图时生效", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.customModelSupportsImages,
                        onCheckedChange = viewModel::saveCustomModelSupportsImages,
                        enabled = !isTesting
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自定义模型支持流式输出", style = MaterialTheme.typography.bodyMedium)
                        Text("关闭时使用一次性返回，兼容不支持 SSE 的接口", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.customModelStreaming,
                        onCheckedChange = viewModel::saveCustomModelStreaming,
                        enabled = !isTesting
                    )
                }
                if (localError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(localError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            UpdateSection(
                updateViewModel = updateViewModel,
                canInstall = !isAnalyzing && followUpSendingIds.isEmpty()
            )

            SectionCard(title = "关于") {
                Text(
                    "医学拍题助手",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "版本 ${BuildConfig.VERSION_NAME}（build ${BuildConfig.VERSION_CODE}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "解析由 AI 生成，仅用于学习辅助，不构成诊断或治疗建议。请结合教材、课程讲义和老师意见核对。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text("移除 API 密钥？") },
            text = { Text("移除后需要重新填写密钥才能解析题目。") },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveDialog = false
                    viewModel.removeApiKey()
                    connectionStatus = null
                    connectionOk = null
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showRemoveDialog = false }) { Text("取消") } }
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
                    scope.launch { snackbarHostState.showSnackbar("历史记录已清空") }
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun ModeCard(
    mode: AnswerMode,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        mode.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                    if (mode == AnswerMode.STANDARD) {
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                "推荐",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    if (mode.usesThinking) {
                        val effort = when (mode.reasoningEffort) {
                            "low" -> "低"
                            "high" -> "高"
                            else -> mode.reasoningEffort ?: "默认"
                        }
                        "${mode.budgetLabel} · 思考强度$effort"
                    } else {
                        "${mode.budgetLabel} · 不开启思考"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = "已选择",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    spec: ModelSpec,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (spec.supportsImages) Icons.Outlined.Image else Icons.Outlined.TextFields,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    spec.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    spec.id,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    spec.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = if (spec.supportsImages) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        if (spec.supportsImages) "支持图片" else "仅文字 · 不支持图片",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            if (selected) {
                Icon(
                    Icons.Outlined.Memory,
                    contentDescription = "当前模型",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
