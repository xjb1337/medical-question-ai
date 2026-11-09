package com.xjb.medicalquestionai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.xjb.medicalquestionai.MainViewModel
import com.xjb.medicalquestionai.data.DraftImage
import com.xjb.medicalquestionai.data.ImageStore
import com.xjb.medicalquestionai.ui.openDetail
import com.xjb.medicalquestionai.ui.openTopLevel
import com.xjb.medicalquestionai.ui.components.AnswerModeSelector
import com.xjb.medicalquestionai.ui.components.AppTopBar
import com.xjb.medicalquestionai.ui.components.ErrorBanner
import com.xjb.medicalquestionai.ui.components.QuestionTypeChips
import com.xjb.medicalquestionai.ui.components.SectionCard
import com.xjb.medicalquestionai.ui.components.formatBytes
import com.xjb.medicalquestionai.ui.components.rememberFileImageRequest
import com.xjb.medicalquestionai.ui.theme.BrandCyan
import com.xjb.medicalquestionai.ui.theme.BrandGradient
import com.xjb.medicalquestionai.ui.theme.BrandGradientEnd
import com.xjb.medicalquestionai.ui.theme.BrandMint

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewQuestionScreen(
    viewModel: MainViewModel,
    navController: NavController
) {
    val draft by viewModel.draft.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val analyzing by viewModel.isAnalyzing.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val analysisError by viewModel.analysisError.collectAsState()
    val notice by viewModel.draftNotice.collectAsState()
    val parkedDraft by viewModel.parkedDraft.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var editingImageId by remember { mutableStateOf<String?>(null) }
    var textExpanded by rememberSaveable { mutableStateOf(false) }
    val editingImage = editingImageId?.let { id -> draft.images.firstOrNull { it.id == id } }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(ImageStore.MAX_IMAGES)
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addImages(uris)
    }

    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeDraftNotice()
        }
    }

    val imageUnsupported = draft.images.isNotEmpty() && !settings.modelSupportsImages
    val submitBlockedReason = when {
        isImporting -> "正在导入图片…"
        draft.text.isBlank() && draft.images.isEmpty() -> "未添加图片或文字"
        !settings.apiKeyConfigured -> "未配置密钥"
        imageUnsupported -> "当前模型不支持图片，请切换到 DeepSeek Flash"
        analyzing -> "正在解析…"
        else -> null
    }

    Scaffold(
        topBar = {
            AppTopBar(
                title = "医学拍题",
                subtitle = "把每一道题，学明白"
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 0.dp,
                shadowElevation = 2.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    if (submitBlockedReason != null && !analyzing &&
                        (draft.text.isNotBlank() || draft.images.isNotEmpty())) {
                        Text(
                            submitBlockedReason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Button(
                        onClick = {
                            viewModel.startAnalysis { questionId ->
                                // 清理本地预览状态，确保返回首页时不会短暂看到上一题的图片或文字。
                                editingImageId = null
                                textExpanded = false
                                navController.navigate("result/$questionId") { launchSingleTop = true }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = submitBlockedReason == null,
                        shape = MaterialTheme.shapes.large,
                        colors = ButtonDefaults.buttonColors(
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f)
                        )
                    ) {
                        if (analyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("正在解析…")
                        } else {
                            Text(if (draft.text.isBlank() && draft.images.isEmpty()) "添加题目后开始解析" else "开始解析",
                                style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "AI 辅助学习 · 请结合教材核对",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item(key = "camera-entry", contentType = "camera-entry") {
                CameraHeroCard(
                    enabled = draft.images.size < ImageStore.MAX_IMAGES && !analyzing && !isImporting,
                    compact = draft.images.isNotEmpty(),
                    onClick = {
                        navController.openDetail("camera?source=home&sessionId=${draft.sessionId}")
                    }
                )
            }

            if (parkedDraft != null) {
                item(key = "parked-draft", contentType = "notice") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "已暂存上一份草稿，图片和文字仍在。",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { viewModel.restoreParkedDraft() }) {
                                Text("恢复草稿")
                            }
                        }
                    }
                }
            }

            if (analysisError != null) {
                item(key = "analysis-error", contentType = "error") {
                    ErrorBanner(analysisError!!, onDismiss = viewModel::consumeAnalysisError)
                }
            }

            item(key = "draft-images", contentType = "images") {
                SectionCard(title = "题目图片") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            enabled = draft.images.size < ImageStore.MAX_IMAGES && !analyzing && !isImporting
                        ) {
                            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("相册导入")
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${draft.images.size}/${ImageStore.MAX_IMAGES} 张",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (draft.images.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "支持多图，点按图片可裁剪、旋转和排序。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(draft.images, key = { it.id }, contentType = { "draft-image" }) { image ->
                                DraftImagePreview(
                                    image = image,
                                    index = draft.images.indexOfFirst { it.id == image.id } + 1,
                                    enabled = !analyzing,
                                    onOpen = { editingImageId = image.id }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "共 ${draft.images.size} 张 · 约 ${formatBytes(draft.images.sumOf { it.sizeBytes })}"
                        )
                        Text(
                            "点按图片可旋转、裁剪、排序或删除；建议保留完整画面，再另加局部图。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isImporting) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在处理图片……", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (imageUnsupported) {
                item(key = "unsupported-images", contentType = "notice") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "当前模型 ${settings.modelLabel} 不支持图片输入，已选择 ${draft.images.size} 张图片。请切换到 DeepSeek Flash 后再解析，图片不会被悄悄丢弃。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            TextButton(onClick = { navController.openTopLevel("settings") }) { Text("去切换模型") }
                        }
                    }
                }
            }

            item(key = "draft-text", contentType = "text-input") {
                SectionCard(
                    title = "文字题目",
                    trailing = {
                        TextButton(onClick = { textExpanded = !textExpanded }) {
                            Text(if (textExpanded) "收起" else "展开")
                        }
                    }
                ) {
                    if (textExpanded) {
                        OutlinedTextField(
                            value = draft.text,
                            onValueChange = viewModel::updateDraftText,
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 10,
                            enabled = !analyzing,
                            placeholder = { Text("例如：题干、选项、患者表现、化验值，或希望重点关注的知识点……") },
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            shape = MaterialTheme.shapes.medium
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "只输入文字也可以解析；题干中的否定词、数值、单位和选项请尽量完整保留。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            draft.text.ifBlank { "也可以粘贴题干与选项，直接开始解析。" },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (draft.text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            item(key = "answer-preferences", contentType = "preferences") {
                SectionCard(title = "解题偏好") {
                    QuestionTypeChips(
                        selected = draft.questionType,
                        onSelected = viewModel::updateQuestionType,
                        enabled = !analyzing
                    )
                    Spacer(Modifier.height(12.dp))
                    AnswerModeSelector(
                        selected = settings.answerMode,
                        onSelected = viewModel::saveAnswerMode,
                        enabled = !analyzing
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "当前模型：${settings.modelLabel}" + if (settings.modelSupportsImages) " · 支持图片" else " · 仅文字",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!settings.apiKeyConfigured) {
                item(key = "key-binding", contentType = "notice") {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("连接你的 AI 助手", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "首次绑定可在设置中查看详细教程并跳转官网。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { navController.openTopLevel("settings") }) { Text("去绑定") }
                        }
                    }
                }
            }

        }
    }

    if (editingImage != null) {
        ImageEditDialog(
            image = editingImage,
            index = draft.images.indexOfFirst { it.id == editingImage.id } + 1,
            enableMoveLeft = draft.images.indexOfFirst { it.id == editingImage.id } > 0,
            enableMoveRight = draft.images.indexOfFirst { it.id == editingImage.id } < draft.images.lastIndex,
            onDismiss = { editingImageId = null },
            onMoveLeft = { viewModel.moveImage(editingImage.id, -1) },
            onMoveRight = { viewModel.moveImage(editingImage.id, 1) },
            onRotate = { degrees -> viewModel.rotateImage(editingImage.id, degrees) },
            onCrop = { rect, keepOriginal -> viewModel.cropImage(editingImage.id, rect, keepOriginal) },
            onDelete = {
                viewModel.removeImage(editingImage.id)
                editingImageId = null
            }
        )
    }
}

@Composable
private fun CameraHeroCard(enabled: Boolean, compact: Boolean, onClick: () -> Unit) {
    if (compact) {
        Card(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CameraAlt, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("继续拍摄", style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("补充局部图片，让题目信息更完整", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                }
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        }
        return
    }
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        Box(Modifier.fillMaxWidth().background(Brush.linearGradient(BrandGradient))) {
            Canvas(Modifier.matchParentSize()) {
                drawCircle(BrandMint.copy(alpha = 0.16f), size.width * 0.42f,
                    Offset(size.width, size.height * 0.1f))
                drawCircle(BrandCyan.copy(alpha = 0.18f), size.width * 0.25f,
                    Offset(size.width * 0.95f, size.height * 0.95f))
            }
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("AI 医学学习助手", style = MaterialTheme.typography.labelMedium,
                    color = Color.White)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("拍下题目\n读懂每一步", fontSize = 27.sp, lineHeight = 34.sp,
                            fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.height(6.dp))
                        Text("从参考答案到解题思路", style = MaterialTheme.typography.bodySmall,
                            color = Color.White)
                    }
                    Surface(shape = RoundedCornerShape(24.dp), color = Color.White.copy(alpha = 0.12f)) {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null,
                            modifier = Modifier.padding(16.dp).size(34.dp), tint = Color.White)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Surface(shape = RoundedCornerShape(50), color = Color.White, contentColor = BrandGradientEnd) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("拍照解题", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(14.dp))
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftImagePreview(
    image: DraftImage,
    index: Int,
    enabled: Boolean,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier.width(142.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.medium
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(98.dp)
                    .clickable(enabled = enabled, onClick = onOpen)
            ) {
                AsyncImage(
                    model = rememberFileImageRequest(image.path, image.revision),
                    contentDescription = "题目图片 $index",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    color = Color.Black.copy(alpha = 0.62f),
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier
                        .padding(6.dp)
                        .size(24.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("$index", style = MaterialTheme.typography.labelLarge)
                    }
                }
                if (image.warnings.isNotEmpty()) {
                    Icon(
                        Icons.Outlined.WarningAmber,
                        contentDescription = "图片有提示",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(20.dp)
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "第 $index 张",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatBytes(image.sizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
