package com.xjb.medicalquestionai.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 结果页等待态。答案和详细解析先以稳定尺寸占位，避免转圈结束后页面大幅跳动。
 */
@Composable
internal fun ResultLoadingSkeletonPage(
    modifier: Modifier = Modifier,
    elapsedMs: Long? = null,
    statusText: String = "正在生成解析"
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("result-loading-skeleton"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "loading-status", contentType = "loading-status") {
            ResultLoadingStatusCard(statusText = statusText, elapsedMs = elapsedMs)
        }
        item(key = "answer-skeleton", contentType = "answer-skeleton") {
            AnswerSkeletonCard()
        }
        item(key = "analysis-skeleton", contentType = "analysis-skeleton") {
            AnalysisSkeletonCard()
        }
    }
}

@Composable
private fun ResultLoadingStatusCard(statusText: String, elapsedMs: Long?) {
    val elapsed = elapsedMs?.let { " · 已等待 ${(it / 1000L).coerceAtLeast(0L)}s" }.orEmpty()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    statusText,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "通常需要 10–30 秒$elapsed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun AnswerSkeletonCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        )
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "参考答案",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                SkeletonPill(width = 72.dp, height = 26.dp, container = true)
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SkeletonBar(modifier = Modifier.size(width = 58.dp, height = 48.dp), radius = 12.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    SkeletonBar(fraction = 0.52f, height = 24.dp)
                    SkeletonBar(fraction = 0.34f, height = 14.dp)
                }
            }
            Spacer(Modifier.height(20.dp))
            SkeletonBar(fraction = 0.48f, height = 13.dp)
        }
    }
}

@Composable
internal fun AnalysisSkeletonCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "详细解析",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "生成中",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.height(6.dp))
            SkeletonBar(fraction = 0.66f, height = 12.dp)
            Spacer(Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SkeletonBar(fraction = 0.94f, height = 14.dp)
                SkeletonBar(fraction = 0.86f, height = 14.dp)
                SkeletonBar(fraction = 0.58f, height = 14.dp)
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonPill(width = 76.dp, height = 30.dp, modifier = Modifier.weight(1f))
                SkeletonPill(width = 76.dp, height = 30.dp, modifier = Modifier.weight(1f))
                SkeletonPill(width = 76.dp, height = 30.dp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SkeletonBar(
    modifier: Modifier = Modifier,
    fraction: Float = 1f,
    height: Dp = 14.dp,
    radius: Dp = 999.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth(fraction)
            .height(height)
            .clip(RoundedCornerShape(radius))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.11f))
    )
}

@Composable
private fun SkeletonPill(
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    container: Boolean = false
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(999.dp))
            .background(
                if (container) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.primary.copy(alpha = 0.11f)
            )
    )
}
