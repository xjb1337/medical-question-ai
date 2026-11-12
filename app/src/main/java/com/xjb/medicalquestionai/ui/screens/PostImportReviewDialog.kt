package com.xjb.medicalquestionai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.xjb.medicalquestionai.data.DraftImage
import com.xjb.medicalquestionai.data.ImageReviewSource

/**
 * Optional post-import step. Dismissing it keeps every image exactly as imported.
 */
@Composable
internal fun PostImportReviewDialog(
    images: List<DraftImage>,
    source: ImageReviewSource,
    onCropImage: (String) -> Unit,
    onUseOriginal: () -> Unit,
    onRetakeOrReselect: () -> Unit,
    onDismiss: () -> Unit
) {
    if (images.isEmpty()) return
    val fromCamera = source == ImageReviewSource.CAMERA
    val retakeLabel = if (fromCamera) "重拍" else "重选"
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .testTag("post-import-review"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            if (fromCamera) Icons.Outlined.CameraAlt else Icons.Outlined.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(10.dp).size(26.dp)
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(
                            if (fromCamera) "照片已导入" else "图片已导入",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (images.size == 1) {
                                "可裁剪本题，也可直接使用原图。"
                            } else {
                                "本次导入 ${images.size} 张，可逐张裁剪。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (images.size > 1) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        images.forEachIndexed { index, image ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "第 ${index + 1} 张",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = { onCropImage(image.id) },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("review-crop-${image.id}")
                                ) { Text("裁剪这张") }
                            }
                        }
                    }
                }

                Button(
                    onClick = { onCropImage(images.first().id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("review-crop-primary")
                ) { Text("裁剪本题") }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = onUseOriginal,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("review-use-original")
                    ) { Text("使用原图") }
                    TextButton(
                        onClick = onRetakeOrReselect,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("review-retake-or-reselect")
                    ) {
                        Text(retakeLabel, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
