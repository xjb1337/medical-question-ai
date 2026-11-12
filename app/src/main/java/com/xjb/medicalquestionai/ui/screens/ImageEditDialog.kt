package com.xjb.medicalquestionai.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.xjb.medicalquestionai.data.DraftImage
import com.xjb.medicalquestionai.data.ImageCropResult
import com.xjb.medicalquestionai.ui.components.formatBytes
import com.xjb.medicalquestionai.ui.components.rememberFileImageRequest
import kotlinx.coroutines.launch

internal val CropRectKey = SemanticsPropertyKey<Rect>("CropRect")

@Composable
internal fun ImageEditDialog(
    image: DraftImage,
    index: Int,
    enableMoveLeft: Boolean,
    enableMoveRight: Boolean,
    onDismiss: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onRotate: (Int) -> Unit,
    onCrop: suspend (android.graphics.RectF, Boolean) -> ImageCropResult,
    startInCropMode: Boolean = false,
    onDelete: () -> Unit
) {
    var cropMode by remember(image.id, startInCropMode) { mutableStateOf(startInCropMode) }
    var cropRect by remember(image.id) { mutableStateOf(CropGeometry.defaultRect) }
    var confirmDelete by remember(image.id) { mutableStateOf(false) }
    var cropSaving by remember(image.id) { mutableStateOf(false) }
    var cropError by remember(image.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun saveCrop(keepOriginal: Boolean) {
        if (cropSaving) return
        val savedRect = cropRect
        cropSaving = true
        cropError = null
        scope.launch {
            when (val result = onCrop(savedRect.toAndroidRect(), keepOriginal)) {
                ImageCropResult.Success -> {
                    cropSaving = false
                    if (startInCropMode) onDismiss() else cropMode = false
                }
                is ImageCropResult.Failure -> {
                    cropSaving = false
                    cropError = result.message
                }
            }
        }
    }

    Dialog(
        onDismissRequest = {
            if (!cropSaving) {
                if (cropMode) cropMode = false else onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                val controlsMaxHeight = maxHeight * 0.6f
                Column(Modifier.fillMaxSize().testTag("image-editor")) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (cropMode) "裁剪题目图片 $index" else "调整题目图片 $index",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge
                        )
                        if (!cropMode) TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("完成") }
                    }
                    BoxWithConstraints(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val imageAspect = if (image.width > 0 && image.height > 0) {
                            image.width.toFloat() / image.height
                        } else 1f
                        val displayWidth = minOf(maxWidth, maxHeight * imageAspect)
                        Box(Modifier.size(displayWidth, displayWidth / imageAspect).background(Color.Black)) {
                            AsyncImage(
                                model = rememberFileImageRequest(image.path, image.revision),
                                contentDescription = "待调整图片",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                            if (cropMode) CropOverlay(cropRect = cropRect, onCropRectChange = { cropRect = it })
                        }
                    }
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = controlsMaxHeight)
                            .verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        image.warnings.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                        Text(
                            "原图 ${image.width} × ${image.height} · ${formatBytes(image.sizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (cropMode) {
                            Text(
                                "拖动四角调整范围，拖动框内移动。保存后将用裁剪后的图替换当前图；如仍需完整原图，可选择保留并新增。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TextButton(
                                    onClick = {
                                        cropError = null
                                        cropMode = false
                                    },
                                    enabled = !cropSaving,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) { Text("取消") }
                                TextButton(
                                    onClick = {
                                        cropError = null
                                        cropRect = CropGeometry.defaultRect
                                    },
                                    enabled = !cropSaving,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) { Text("重置") }
                            }
                            if (cropSaving) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp).testTag("crop-saving-indicator"),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(
                                        "正在保存裁剪结果…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (cropError != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer,
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth().testTag("crop-save-error")
                                ) {
                                    Text(
                                        cropError!!,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                            Button(
                                onClick = { saveCrop(keepOriginal = false) },
                                enabled = !cropSaving,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("crop-replace-primary")
                            ) {
                                Text(if (cropSaving) "正在保存…" else "替换当前图")
                            }
                            OutlinedButton(
                                onClick = { saveCrop(keepOriginal = true) },
                                enabled = !cropSaving,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("crop-keep-secondary")
                            ) { Text("保留原图并添加") }
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = onMoveLeft, enabled = enableMoveLeft, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("前移") }
                                OutlinedButton(onClick = onMoveRight, enabled = enableMoveRight, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("后移") }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OutlinedButton(onClick = { onRotate(-90) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("左转") }
                                OutlinedButton(onClick = { onRotate(90) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("右转") }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = {
                                        cropRect = CropGeometry.defaultRect
                                        cropMode = true
                                    },
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                ) { Text("裁剪") }
                                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text("删除", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

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

private fun Rect.toAndroidRect() = android.graphics.RectF(left, top, right, bottom)

@Composable
internal fun CropOverlay(cropRect: Rect, onCropRectChange: (Rect) -> Unit) {
    val latestRect by rememberUpdatedState(cropRect)
    val latestOnCropRectChange by rememberUpdatedState(onCropRectChange)
    Canvas(
        modifier = Modifier.fillMaxSize().testTag("crop-overlay")
            .semantics { this[CropRectKey] = cropRect }
            .pointerInput(Unit) {
                var handle = CropHandle.NONE
                var gestureRect = latestRect
                detectDragGestures(
                    onDragStart = { position ->
                        gestureRect = latestRect
                        handle = if (size.width > 0 && size.height > 0) {
                            CropGeometry.hitTest(gestureRect, Offset(position.x / size.width, position.y / size.height))
                        } else CropHandle.NONE
                    },
                    onDragEnd = { handle = CropHandle.NONE },
                    onDragCancel = { handle = CropHandle.NONE },
                    onDrag = { change, dragAmount ->
                        if (handle != CropHandle.NONE && size.width > 0 && size.height > 0) {
                            change.consume()
                            gestureRect = CropGeometry.drag(
                                gestureRect, handle, dragAmount.x / size.width, dragAmount.y / size.height
                            )
                            latestOnCropRectChange(gestureRect)
                        }
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
        drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(width = 2.dp.toPx()))
        val thirdX = (right - left) / 3f
        val thirdY = (bottom - top) / 3f
        drawLine(Color.White.copy(alpha = 0.55f), Offset(left + thirdX, top), Offset(left + thirdX, bottom), 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.55f), Offset(right - thirdX, top), Offset(right - thirdX, bottom), 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.55f), Offset(left, top + thirdY), Offset(right, top + thirdY), 1.dp.toPx())
        drawLine(Color.White.copy(alpha = 0.55f), Offset(left, bottom - thirdY), Offset(right, bottom - thirdY), 1.dp.toPx())
        listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom)).forEach {
            drawCircle(Color.White, radius = 6.dp.toPx(), center = it)
        }
    }
}
