package com.xjb.medicalquestionai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xjb.medicalquestionai.data.update.UpdateStatus
import java.util.Locale
import com.xjb.medicalquestionai.data.update.UpdateUiState
import com.xjb.medicalquestionai.data.update.UpdateViewModel

@Composable
fun UpdateSection(updateViewModel: UpdateViewModel, canInstall: Boolean = true) {
    val state by updateViewModel.state.collectAsState()
    SectionCard(title = "应用更新") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("当前版本 ${state.currentVersionName}（build ${state.currentVersionCode}）", style = MaterialTheme.typography.bodyMedium)
                state.lastCheckedAt?.let {
                    Text("最近检查：${updateViewModel.formattedLastCheckedAt()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedButton(
                onClick = updateViewModel::checkManually,
                enabled = state.status != UpdateStatus.CHECKING && state.status != UpdateStatus.DOWNLOADING && state.status != UpdateStatus.VERIFYING
            ) { Text(if (state.status == UpdateStatus.CHECKING) "检查中…" else "检查更新") }
        }
        state.message?.let { message ->
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = statusColor(state.status))
        }
        val release = state.update
        if (release != null && state.status in setOf(
                UpdateStatus.AVAILABLE, UpdateStatus.DOWNLOADING, UpdateStatus.VERIFYING,
                UpdateStatus.READY_TO_INSTALL, UpdateStatus.WAITING_INSTALL_PERMISSION,
                UpdateStatus.INSTALLING, UpdateStatus.FAILED
            )
        ) {
            Spacer(Modifier.height(10.dp))
            Text("新版本 ${release.manifest.versionName}（build ${release.manifest.versionCode}）", style = MaterialTheme.typography.titleSmall)
            Text("大小 ${formatUpdateBytes(release.manifest.apkSizeBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(release.manifest.releaseNotes, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            UpdateActionButtons(state, updateViewModel, canInstall)
        }
    }
}

@Composable
private fun UpdateActionButtons(state: UpdateUiState, updateViewModel: UpdateViewModel, canInstall: Boolean) {
    when (state.status) {
        UpdateStatus.DOWNLOADING, UpdateStatus.VERIFYING -> {
            Column(Modifier.fillMaxWidth()) {
                if (state.progressPercent != null) {
                    LinearProgressIndicator(progress = { state.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${state.progressPercent}% · ${formatUpdateBytes(state.downloadedBytes)} / ${formatUpdateBytes(state.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("已下载 ${formatUpdateBytes(state.downloadedBytes)}", style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = updateViewModel::dismissProgress) { Text("后台下载") }
                    TextButton(onClick = updateViewModel::cancelDownload) { Text("取消下载") }
                }
            }
        }
        UpdateStatus.READY_TO_INSTALL -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { updateViewModel.install(!canInstall) }, enabled = canInstall) { Text("安装更新") }
                OutlinedButton(onClick = updateViewModel::dismissProgress) { Text("稍后安装") }
            }
        }
        UpdateStatus.WAITING_INSTALL_PERMISSION -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = updateViewModel::openInstallPermissionSettings) { Text("去授权") }
                OutlinedButton(onClick = { updateViewModel.install(!canInstall) }, enabled = canInstall) { Text("继续安装") }
                TextButton(onClick = updateViewModel::dismissProgress) { Text("关闭") }
            }
        }
        UpdateStatus.INSTALLING -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { updateViewModel.install(!canInstall) }, enabled = canInstall) { Text("继续安装") }
                TextButton(onClick = updateViewModel::dismissProgress) { Text("关闭") }
            }
        }
        UpdateStatus.FAILED -> {
            if (state.installReady && state.downloadedFile != null) {
                Button(onClick = { updateViewModel.install(!canInstall) }, enabled = canInstall) { Text("重试安装") }
            } else {
                Button(onClick = updateViewModel::download) { Text("重试下载") }
            }
        }
        else -> {
            Button(onClick = updateViewModel::download) { Text("下载更新") }
        }
    }
}

@Composable
fun UpdateAvailablePrompt(
    state: UpdateUiState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val release = state.update ?: return
    if (!state.showPrompt) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${release.manifest.versionName}") },
        text = {
            Column {
                Text("大小 ${formatUpdateBytes(release.manifest.apkSizeBytes)}")
                Spacer(Modifier.height(8.dp))
                Text(release.manifest.releaseNotes, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(onClick = onDownload) { Text("下载更新") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } }
    )
}

@Composable
fun UpdateProgressDialog(
    state: UpdateUiState,
    updateViewModel: UpdateViewModel,
    onDismiss: () -> Unit,
    canInstall: Boolean = true
) {
    if (!state.showProgress) return
    if (state.status !in setOf(
            UpdateStatus.DOWNLOADING, UpdateStatus.VERIFYING, UpdateStatus.READY_TO_INSTALL,
            UpdateStatus.WAITING_INSTALL_PERMISSION, UpdateStatus.INSTALLING, UpdateStatus.FAILED
        )
    ) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (state.status) {
                    UpdateStatus.READY_TO_INSTALL -> "更新包已就绪"
                    UpdateStatus.WAITING_INSTALL_PERMISSION -> "需要安装授权"
                    UpdateStatus.INSTALLING -> "正在安装"
                    UpdateStatus.FAILED -> "更新未完成"
                    else -> "正在下载更新"
                }
            )
        },
        text = {
            Column {
                if (state.status == UpdateStatus.DOWNLOADING || state.status == UpdateStatus.VERIFYING) {
                    if (state.progressPercent != null) {
                        LinearProgressIndicator(progress = { state.progressPercent / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("${state.progressPercent}% · ${formatUpdateBytes(state.downloadedBytes)} / ${formatUpdateBytes(state.totalBytes)}")
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text("已下载 ${formatUpdateBytes(state.downloadedBytes)}")
                    }
                }
                state.message?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
                if (!canInstall) {
                    Spacer(Modifier.height(8.dp))
                    Text("当前有拍题或追问任务，完成后再安装更新。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (state.status) {
                UpdateStatus.READY_TO_INSTALL -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { updateViewModel.install(!canInstall) }, enabled = canInstall) { Text("安装更新") }
                        TextButton(onClick = onDismiss) { Text("稍后安装") }
                    }
                }
                UpdateStatus.WAITING_INSTALL_PERMISSION -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = updateViewModel::openInstallPermissionSettings) { Text("去授权") }
                        OutlinedButton(onClick = { updateViewModel.install(!canInstall) }, enabled = canInstall) { Text("继续安装") }
                    }
                }
                UpdateStatus.INSTALLING -> {
                    Button(onClick = { updateViewModel.install(!canInstall) }, enabled = canInstall) { Text("继续安装") }
                }
                UpdateStatus.FAILED -> {
                    if (state.installReady && state.downloadedFile != null) {
                        Button(onClick = { updateViewModel.install(!canInstall) }, enabled = canInstall) { Text("重试安装") }
                    } else {
                        Button(onClick = updateViewModel::download) { Text("重试下载") }
                    }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { updateViewModel.dismissProgress(); onDismiss() }) { Text("后台下载") }
                        TextButton(onClick = { updateViewModel.cancelDownload(); onDismiss() }) { Text("取消下载") }
                    }
                }
            }
        },
        dismissButton = {
            when (state.status) {
                UpdateStatus.DOWNLOADING, UpdateStatus.VERIFYING -> TextButton(onClick = onDismiss) { Text("关闭") }
                UpdateStatus.READY_TO_INSTALL, UpdateStatus.WAITING_INSTALL_PERMISSION, UpdateStatus.INSTALLING -> TextButton(onClick = onDismiss) { Text("稍后") }
                UpdateStatus.FAILED -> TextButton(onClick = onDismiss) { Text("关闭") }
                else -> Unit
            }
        }
    )
}

@Composable
private fun statusColor(status: UpdateStatus) = when (status) {
    UpdateStatus.FAILED -> MaterialTheme.colorScheme.error
    UpdateStatus.UP_TO_DATE -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun formatUpdateBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.CHINA, "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
