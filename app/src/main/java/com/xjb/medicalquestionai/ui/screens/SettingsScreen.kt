package com.xjb.medicalquestionai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.xjb.medicalquestionai.BuildConfig
import com.xjb.medicalquestionai.MainViewModel
import com.xjb.medicalquestionai.data.SettingsRepository
import com.xjb.medicalquestionai.ui.components.AppTopBar
import com.xjb.medicalquestionai.ui.components.InfoBanner
import com.xjb.medicalquestionai.ui.components.SectionCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    navController: NavController
) {
    val settings by viewModel.settings.collectAsState()
    val isTesting by viewModel.isTestingKey.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var apiKeyInput by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var showRemoveDialog by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var baseUrlInput by remember(settings.baseUrl) { mutableStateOf(settings.baseUrl) }
    var modelInput by remember(settings.model) { mutableStateOf(settings.model) }
    var localError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings.apiKeyHint) {
        if (settings.apiKeyHint.isNotBlank()) localError = null
    }

    Scaffold(
        topBar = { AppTopBar(title = "设置", subtitle = "配置模型与数据", onBack = { navController.popBackStack() }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionCard(title = "DeepSeek API 密钥") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Key,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                    scope.launch { snackbarHostState.showSnackbar("密钥已保存，建议点击“测试连接”检查是否可用") }
                                } else {
                                    localError = error
                                }
                            }
                        },
                        enabled = !isTesting,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(17.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(7.dp))
                        }
                        Text(if (settings.apiKeyConfigured) "保存新密钥" else "保存密钥")
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.testSavedConnection { error ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(error ?: "连接正常，密钥可用")
                                }
                            }
                        },
                        enabled = settings.apiKeyConfigured && !isTesting
                    ) {
                        Text("测试连接")
                    }
                }
                if (settings.apiKeyConfigured) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { showRemoveDialog = true }, enabled = !isTesting) {
                        Text("移除已保存的密钥", color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(Modifier.height(4.dp))
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
                        "密钥使用 Android Keystore 加密后保存在本机，不会写入日志或历史记录。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            SectionCard(title = "模型") {
                Text(
                    "deepseek-flash 支持图片输入，适合拍题解析；deepseek-chat 适合纯文字题；deepseek-reasoner 更擅长复杂推理，但速度较慢且不支持图片。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = settings.model == SettingsRepository.DEFAULT_MODEL,
                        onClick = { viewModel.saveModel(SettingsRepository.DEFAULT_MODEL) },
                        label = { Text("deepseek-flash") }
                    )
                    FilterChip(
                        selected = settings.model == "deepseek-chat",
                        onClick = { viewModel.saveModel("deepseek-chat") },
                        label = { Text("deepseek-chat") }
                    )
                    FilterChip(
                        selected = settings.model == "deepseek-reasoner",
                        onClick = { viewModel.saveModel("deepseek-reasoner") },
                        label = { Text("deepseek-reasoner") }
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = modelInput,
                        onValueChange = { modelInput = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("自定义模型名") },
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
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前：${settings.model}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            SectionCard(title = "数据与历史") {
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
                Spacer(Modifier.height(8.dp))
                Text(
                    "题目图片保存在应用私有目录，卸载应用会一并删除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SectionCard(
                title = "接口地址（高级）",
                trailing = {
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) "收起" else "展开")
                    }
                }
            ) {
                if (showAdvanced) {
                    OutlinedTextField(
                        value = baseUrlInput,
                        onValueChange = { baseUrlInput = it; localError = null },
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
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "仅在使用兼容 OpenAI 协议的中转服务时修改。Android 版只接受 HTTPS 地址，HTTP 地址会被拒绝保存。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        settings.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            InfoBanner(
                "医学拍题助手 · v${BuildConfig.VERSION_NAME}\n解析由 AI 生成，仅用于学习辅助，不构成诊断或治疗建议。"
            )
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
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showRemoveDialog = false }) { Text("取消") } }
        )
    }
}
