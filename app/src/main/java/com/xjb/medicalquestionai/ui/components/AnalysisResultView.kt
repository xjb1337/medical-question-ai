package com.xjb.medicalquestionai.ui.components

import com.xjb.medicalquestionai.toReadablePlainText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xjb.medicalquestionai.data.AnalysisResult
import com.xjb.medicalquestionai.ui.theme.Amber700
import com.xjb.medicalquestionai.ui.theme.Green700

/**
 * 结果内容。非 compact 时按“答案 → 关键警示 → 详细解析”的顺序展示；
 * compact 用于解析过程中的答案预览，只渲染答案卡片。
 */
@Composable
fun AnalysisResultView(
    result: AnalysisResult,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    if (compact) {
        AnalysisAnswerCard(result = result, modifier = modifier, compact = true)
        return
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AnalysisAnswerCard(result)
        if (result.warnings.isNotEmpty()) {
            AnalysisWarningCard(result.warnings)
        }
        AnalysisDetails(result)
    }
}

/**
 * 参考答案卡片。单选题/多选题的答案字母放大；填空与简答使用正常正文层级，便于长文本阅读。
 */
@Composable
fun AnalysisAnswerCard(
    result: AnalysisResult,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val isOutOfScope = result.status == "out_of_scope"
    val needsConfirmation = result.needsConfirmation
    val container = when {
        isOutOfScope -> MaterialTheme.colorScheme.surfaceVariant
        needsConfirmation -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val onContainer = when {
        isOutOfScope -> MaterialTheme.colorScheme.onSurfaceVariant
        needsConfirmation -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }
    val isChoice = result.questionType == "single" || result.questionType == "multiple"
    val answerStyle = when {
        compact -> MaterialTheme.typography.titleLarge
        isChoice -> MaterialTheme.typography.displaySmall
        else -> MaterialTheme.typography.bodyLarge
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container)
    ) {
        Column(Modifier.padding(if (compact) 16.dp else 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        isOutOfScope -> Icons.Outlined.Info
                        needsConfirmation -> Icons.Outlined.ErrorOutline
                        else -> Icons.Outlined.CheckCircle
                    },
                    contentDescription = null,
                    tint = onContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        isOutOfScope -> "非医学题目"
                        needsConfirmation -> "需要核对"
                        else -> "参考答案"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = onContainer,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.weight(1f))
                if (compact) {
                    Tag("生成中", onContainer)
                } else if (!isOutOfScope) {
                    StatusChip(result.confidenceLabel)
                }
            }
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            SelectionContainer {
                Text(
                    when {
                        isOutOfScope -> result.answerText.toReadablePlainText().ifBlank { "本题不属于医学学习范围。" }
                        needsConfirmation && result.answerDisplay == "未能确定答案" -> "信息不足，暂无法确定答案"
                        else -> result.answerDisplay
                    },
                    style = answerStyle,
                    fontWeight = FontWeight.Bold,
                    color = onContainer
                )
            }
            if (result.answerText.isNotBlank() && result.answerText != result.answerDisplay) {
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(
                        result.answerText,
                        style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                        color = onContainer
                    )
                }
            }
            if (!compact) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(result.typeLabel(), onContainer)
                    if (result.status == "needs_confirmation") Tag("待确认", onContainer)
                    if (isOutOfScope) Tag("范围外", onContainer)
                }
            }
        }
    }
}

/** 详细解析：识别题干、图片观察、选项分析、解题思路、知识点、易错点与调用信息。 */
@Composable
fun AnalysisDetails(result: AnalysisResult, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (result.warnings.isNotEmpty()) {
            SectionCard(title = "警示与提醒") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    result.warnings.forEach { warning ->
                        Text(
                            "· $warning",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (result.recognizedQuestion.isNotBlank()) {
            SectionCard(title = "识别到的题目") {
                SelectionContainer {
                    Text(
                        result.recognizedQuestion,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        if (result.imageObservations.isNotEmpty()) {
            SectionCard(title = "图片观察") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    result.imageObservations.forEach { observation ->
                        Column {
                            Text(
                                observation.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            SelectionContainer {
                                Text(
                                    observation.observation,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (observation.uncertainty.isNotBlank()) {
                                Text(
                                    "不确定：${observation.uncertainty}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        if (result.optionAnalyses.isNotEmpty()) {
            SectionCard(title = "选项分析") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    result.optionAnalyses.forEach { option ->
                        Row(verticalAlignment = Alignment.Top) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    option.option,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                if (option.verdict.isNotBlank()) {
                                    Text(
                                        option.verdict,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                SelectionContainer {
                                    Text(
                                        option.reason,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (result.reasoningSteps.isNotEmpty()) {
            SectionCard(title = "解题思路") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    result.reasoningSteps.forEachIndexed { index, step ->
                        Row(verticalAlignment = Alignment.Top) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        "${index + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    step.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (step.detail.isNotBlank()) {
                                    Spacer(Modifier.height(3.dp))
                                    SelectionContainer {
                                        Text(
                                            step.detail,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (result.keyPoints.isNotEmpty()) {
            BulletCard(
                title = "本题知识点",
                icon = { Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = Green700) },
                items = result.keyPoints
            )
        }
        if (result.pitfalls.isNotEmpty()) {
            BulletCard(
                title = "易错与鉴别",
                icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Amber700) },
                items = result.pitfalls
            )
        }
        SectionCard(title = "使用建议") {
            Text(
                "建议先遮住答案独立作答，再对照“详细解析”核对思路；如出现警示或“需要核对”，请优先复核题干识别、选项与关键数值。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (result.usage.isAvailable) {
            SectionCard(title = "调用信息") {
                Text(
                    result.usage.shortLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            "以上为学习辅助解析，请结合教材、课程讲义和老师意见核对。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
fun AnalysisWarningCard(warnings: List<String>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Amber700)
                Spacer(Modifier.width(8.dp))
                Text("请留意", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            warnings.forEach {
                Text("· $it", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(3.dp))
            }
        }
    }
}

private fun AnalysisResult.typeLabel(): String = when (questionType.lowercase()) {
    "single" -> "单选题"
    "multiple" -> "多选题"
    "fill" -> "填空题"
    "short" -> "简答题"
    else -> "题型未定"
}

@Composable
private fun StatusChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.45f)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun Tag(text: String, contentColor: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = contentColor.copy(alpha = 0.10f)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun BulletCard(
    title: String,
    icon: @Composable () -> Unit,
    items: List<String>
) {
    SectionCard(title = title) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.forEach { item ->
                Row(verticalAlignment = Alignment.Top) {
                    icon()
                    Spacer(Modifier.width(8.dp))
                    SelectionContainer {
                        Text(
                            item,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
