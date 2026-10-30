package com.xjb.medicalquestionai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.size.Size
import java.io.File
import kotlin.math.roundToInt

/** Reads the saved question file, independently of the small card thumbnail request. */
@Composable
fun QuestionImageViewer(paths: List<String>, initialIndex: Int = 0, onDismiss: () -> Unit) {
    if (paths.isEmpty()) return
    var index by remember(paths, initialIndex) { mutableStateOf(initialIndex.coerceIn(paths.indices)) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color(0xFF10151B))) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("原题图片 ${index + 1}/${paths.size}", color = Color.White,
                    modifier = Modifier.weight(1f).padding(start = 8.dp))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭图片查看", tint = Color.White)
                }
            }
            Text("双指缩放 · 拖动查看 · 双击放大", color = Color.LightGray,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp))
            key(paths[index], index) {
                ZoomableQuestionImage(paths[index], Modifier.weight(1f))
            }
            if (paths.size > 1) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    IconButton(onClick = { index-- }, enabled = index > 0) { Icon(Icons.Outlined.ChevronLeft, "上一张", tint = if (index > 0) Color.White else Color.Gray) }
                    IconButton(onClick = { index++ }, enabled = index < paths.lastIndex) { Icon(Icons.Outlined.ChevronRight, "下一张", tint = if (index < paths.lastIndex) Color.White else Color.Gray) }
                }
            }
        }
    }
}

@Composable
private fun ZoomableQuestionImage(path: String, modifier: Modifier) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    fun bound(value: Offset, zoom: Float): Offset {
        val x = viewport.width * (zoom - 1f) / 2f
        val y = viewport.height * (zoom - 1f) / 2f
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
    fun setZoom(value: Float) {
        scale = value.coerceIn(1f, 6f)
        offset = bound(offset, scale)
    }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = bound(offset + pan, scale)
    }
    val context = LocalContext.current
    val request = remember(path, context) {
        ImageRequest.Builder(context).data(File(path)).size(Size.ORIGINAL).build()
    }
    Column(modifier) {
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged { viewport = it }
                .transformable(transform)
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        offset = Offset.Zero
                        scale = if (scale > 1f) 1f else 3f
                    })
                },
            contentAlignment = Alignment.Center
        ) {
            SubcomposeAsyncImage(
                model = request,
                contentDescription = "放大查看的原题图片",
                contentScale = ContentScale.Fit,
                loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) } },
                error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("图片无法读取，可能已被删除", color = Color.White) } },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { setZoom(scale / 1.5f) }, enabled = scale > 1f) { Icon(Icons.Outlined.ZoomOut, "缩小", tint = if (scale > 1f) Color.White else Color.Gray) }
            Text("${(scale * 100).roundToInt()}%", color = Color.White)
            IconButton(onClick = { setZoom(scale * 1.5f) }, enabled = scale < 6f) { Icon(Icons.Outlined.ZoomIn, "放大", tint = if (scale < 6f) Color.White else Color.Gray) }
            IconButton(onClick = { scale = 1f; offset = Offset.Zero }) { Icon(Icons.Outlined.RestartAlt, "还原", tint = Color.White) }
        }
    }
}
