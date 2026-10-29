package com.xjb.medicalquestionai.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.xjb.medicalquestionai.MainViewModel
import com.xjb.medicalquestionai.data.DraftImage
import com.xjb.medicalquestionai.data.ImageStore
import com.xjb.medicalquestionai.data.QuestionType
import com.xjb.medicalquestionai.ui.components.AnswerModeSelector
import com.xjb.medicalquestionai.ui.components.AppTopBar
import com.xjb.medicalquestionai.ui.components.ErrorBanner
import com.xjb.medicalquestionai.ui.components.QuestionTypeChips
import com.xjb.medicalquestionai.ui.components.SectionCard
import com.xjb.medicalquestionai.ui.components.formatBytes
import com.xjb.medicalquestionai.ui.components.rememberFileImageRequest
import com.xjb.medicalquestionai.ui.theme.Amber700

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
                title = "医学拍题助手",
                subtitle = "拍下题目，先把答案讲清楚"
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    if (submitBlockedReason != null && !analyzing) {
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
                                navController.navigate("result/$questionId")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = submitBlockedReason == null,
                        shape = MaterialTheme.shapes.large
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
                            Text("开始解析", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "解析由 AI 生成，请结合教材与课程讲义核对",
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
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CameraHeroCard(
                    enabled = draft.images.size < ImageStore.MAX_IMAGES && !analyzing && !isImporting,
                    onClick = {
                        navController.navigate("camera?source=home&sessionId=${draft.sessionId}")
                    }
                )
            }

            if (parkedDraft != null) {
                item {
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
                item {
                    ErrorBanner(analysisError!!, onDismiss = viewModel::consumeAnalysisError)
                }
            }

            item {
                SectionCard(title = "从相册添加图片（可选）") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            enabled = draft.images.size < ImageStore.MAX_IMAGES && !analyzing && !isImporting
                        ) {
                            Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("从相册选择")
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
                            "还没有题目图片。拍照或从相册选择；只输入文字也可以解析。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(draft.images, key = { it.id }) { image ->
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
                item {
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
                            TextButton(onClick = { navController.navigate("settings") }) { Text("去切换模型") }
                        }
                    }
                }
            }

            item {
                SectionCard(
                    title = "补充文字（可选）",
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
                            draft.text.ifBlank { "题干太长、图片看不清，或需要补充符号时，可在这里输入。" },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (draft.text.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            item {
                SectionCard(title = "题型与做题模式") {
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
                item {
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
                                Text("先配置 DeepSeek API 密钥", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "密钥只保存在本机，使用 Android Keystore 加密。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { navController.navigate("settings") }) { Text("去设置") }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "模型输出不保证正确，请结合课程教材核对",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
private fun CameraHeroCard(enabled: Boolean, onClick: () -> Unit) {
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 34.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("拍照搜题", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                "对准题目拍摄，支持多张、旋转与裁剪",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
            )
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
                    model = rememberFileImageRequest(image.path),
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
                        tint = Amber700,
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

private enum class CropHandle { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

@Composable
private fun ImageEditDialog(
    image: DraftImage,
    index: Int,
    enableMoveLeft: Boolean,
    enableMoveRight: Boolean,
    onDismiss: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onRotate: (Int) -> Unit,
    onCrop: (android.graphics.RectF, Boolean) -> Unit,
    onDelete: () -> Unit
) {
    var cropMode by remember { mutableStateOf(false) }
    var cropRect by remember { mutableStateOf(Rect(0.04f, 0.04f, 0.96f, 0.96f)) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("调整题目图片 $index") },
        text = {
            Column {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val imageAspect = if (image.height > 0) image.width.toFloat() / image.height else 1f
                    val maxWidth = maxWidth
                    val maxHeight = maxHeight
                    val displayWidth = if (maxWidth / maxHeight > imageAspect) maxHeight * imageAspect else maxWidth
                    val displayHeight = displayWidth / imageAspect
                    Box(
                        modifier = Modifier
                            .size(displayWidth, displayHeight)
                            .background(Color.Black, RoundedCornerShape(10.dp))
                    ) {
                        AsyncImage(
                            model = rememberFileImageRequest(image.path),
                            contentDescription = "待调整图片",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                        if (cropMode) {
                            CropOverlay(
                                cropRect = cropRect,
                                onCropRectChange = { cropRect = it }
                            )
                        }
                    }
                }
                if (image.warnings.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    image.warnings.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Amber700)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "原图 ${image.width} × ${image.height} · ${formatBytes(image.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (cropMode) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "拖动四角或框内区域，选择要保留的画面。默认保留当前完整图，并在其后新增一张裁剪图。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            if (cropMode) {
                Row {
                    TextButton(onClick = { cropRect = Rect(0.04f, 0.04f, 0.96f, 0.96f) }) { Text("重置") }
                    Button(onClick = {
                        onCrop(android.graphics.RectF(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom), true)
                        cropMode = false
                    }) { Text("保留原图并添加") }
                }
            } else {
                Button(onClick = onDismiss) { Text("完成") }
            }
        },
        dismissButton = {
            if (cropMode) {
                Row {
                    TextButton(onClick = { cropMode = false }) { Text("取消") }
                    TextButton(onClick = {
                        onCrop(android.graphics.RectF(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom), false)
                        cropMode = false
                    }) { Text("替换当前图") }
                }
            } else {
                Column(horizontalAlignment = Alignment.End) {
                    Row {
                        TextButton(onClick = onMoveLeft, enabled = enableMoveLeft) { Text("前移") }
                        TextButton(onClick = onMoveRight, enabled = enableMoveRight) { Text("后移") }
                    }
                    Row {
                        TextButton(onClick = {
                            onRotate(-90)
                            cropMode = false
                        }) { Text("左转") }
                        TextButton(onClick = {
                            onRotate(90)
                            cropMode = false
                        }) { Text("右转") }
                        TextButton(onClick = {
                            cropRect = Rect(0.04f, 0.04f, 0.96f, 0.96f)
                            cropMode = true
                        }) { Text("裁剪") }
                        TextButton(onClick = { confirmDelete = true }) {
                            Text("删除", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这张图片？") },
            text = { Text("删除后需要重新选择或拍摄。") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun CropOverlay(
    cropRect: Rect,
    onCropRectChange: (Rect) -> Unit
) {
    var handle by remember { mutableStateOf(CropHandle.NONE) }
    val borderColor = Color.White
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { position ->
                        val width = size.width.toFloat()
                        val height = size.height.toFloat()
                        if (width <= 0f || height <= 0f) return@detectDragGestures
                        val x = position.x / width
                        val y = position.y / height
                        val threshold = 0.11f
                        handle = when {
                            near(x, y, cropRect.left, cropRect.top, threshold) -> CropHandle.TOP_LEFT
                            near(x, y, cropRect.right, cropRect.top, threshold) -> CropHandle.TOP_RIGHT
                            near(x, y, cropRect.left, cropRect.bottom, threshold) -> CropHandle.BOTTOM_LEFT
                            near(x, y, cropRect.right, cropRect.bottom, threshold) -> CropHandle.BOTTOM_RIGHT
                            x in cropRect.left..cropRect.right && y in cropRect.top..cropRect.bottom -> CropHandle.MOVE
                            else -> CropHandle.NONE
                        }
                    },
                    onDragEnd = { handle = CropHandle.NONE },
                    onDragCancel = { handle = CropHandle.NONE },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (handle == CropHandle.NONE) return@detectDragGestures
                        val dx = dragAmount.x / size.width
                        val dy = dragAmount.y / size.height
                        onCropRectChange(dragCropRect(cropRect, handle, dx, dy))
                    }
                )
            }
    ) {
        val left = cropRect.left * size.width
        val top = cropRect.top * size.height
        val right = cropRect.right * size.width
        val bottom = cropRect.bottom * size.height
        val shade = Color.Black.copy(alpha = 0.58f)
        drawRect(shade, Offset.Zero, Size(size.width, top))
        drawRect(shade, Offset(0f, bottom), Size(size.width, size.height - bottom))
        drawRect(shade, Offset(0f, top), Size(left, bottom - top))
        drawRect(shade, Offset(right, top), Size(size.width - right, bottom - top))
        drawRect(
            color = borderColor,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            style = Stroke(width = 2.dp.toPx())
        )
        val thirdX = (right - left) / 3f
        val thirdY = (bottom - top) / 3f
        drawLine(Color.White.copy(alpha = 0.55f), Offset(left + thirdX, top), Offset(left + thirdX, bottom), 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.55f), Offset(right - thirdX, top), Offset(right - thirdX, bottom), 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.55f), Offset(left, top + thirdY), Offset(right, top + thirdY), 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.55f), Offset(left, bottom - thirdY), Offset(right, bottom - thirdY), 1.dp.toPx())
        listOf(
            Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom)
        ).forEach { drawCircle(Color.White, radius = 6.dp.toPx(), center = it) }
    }
}

private fun near(x: Float, y: Float, targetX: Float, targetY: Float, threshold: Float): Boolean =
    kotlin.math.abs(x - targetX) <= threshold && kotlin.math.abs(y - targetY) <= threshold

private fun dragCropRect(rect: Rect, handle: CropHandle, dx: Float, dy: Float): Rect {
    val minSize = 0.08f
    var left = rect.left
    var top = rect.top
    var right = rect.right
    var bottom = rect.bottom
    when (handle) {
        CropHandle.TOP_LEFT -> {
            left = (left + dx).coerceIn(0f, right - minSize)
            top = (top + dy).coerceIn(0f, bottom - minSize)
        }
        CropHandle.TOP_RIGHT -> {
            right = (right + dx).coerceIn(left + minSize, 1f)
            top = (top + dy).coerceIn(0f, bottom - minSize)
        }
        CropHandle.BOTTOM_LEFT -> {
            left = (left + dx).coerceIn(0f, right - minSize)
            bottom = (bottom + dy).coerceIn(top + minSize, 1f)
        }
        CropHandle.BOTTOM_RIGHT -> {
            right = (right + dx).coerceIn(left + minSize, 1f)
            bottom = (bottom + dy).coerceIn(top + minSize, 1f)
        }
        CropHandle.MOVE -> {
            val width = right - left
            val height = bottom - top
            left = (left + dx).coerceIn(0f, 1f - width)
            top = (top + dy).coerceIn(0f, 1f - height)
            right = left + width
            bottom = top + height
        }
        CropHandle.NONE -> Unit
    }
    return Rect(left, top, right, bottom)
}
