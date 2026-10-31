package com.xjb.medicalquestionai.ui.components

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Public documentation links only. Never include a key in an external URL. */
enum class DeepSeekGuideLink(val url: String) {
    PLATFORM("https://platform.deepseek.com"),
    BALANCE("https://platform.deepseek.com/top_up"),
    API_KEYS("https://platform.deepseek.com/api_keys"),
    DOCUMENTATION("https://api-docs.deepseek.com/zh-cn/")
}

@Composable
fun DeepSeekBindingGuide(
    expanded: Boolean,
    keyConfigured: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onEnterKey: () -> Unit
) {
    SectionCard(title = "DeepSeek 首次绑定教程") {
        Text(
            if (keyConfigured) "已保存密钥。需要更换账号或排查连接时，可以重新查看步骤。"
            else "第一次使用？按下面 5 步获取并绑定 API Key（API 密钥）。",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        if (expanded) {
            Button(onClick = onEnterKey, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (keyConfigured) "去填写或更换密钥" else "已有 API Key，直接填写")
            }
            Spacer(Modifier.height(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                GuideStep("1 · 登录 DeepSeek 开放平台",
                    "点击下方按钮，在浏览器中登录或注册。请确认地址为 platform.deepseek.com；登录与验证码操作在官方网页完成。") {
                    OfficialDeepSeekLinkButton("打开官方平台 / 登录", DeepSeekGuideLink.PLATFORM)
                }
                GuideStep("2 · 查看 API 余额",
                    "登录后查看 API 账户余额。API 调用按用量计费；如余额不足，再按官方页面提示自行充值，金额与支付方式以官网为准。") {
                    OfficialDeepSeekLinkButton("查看余额 / 充值", DeepSeekGuideLink.BALANCE)
                }
                GuideStep("3 · 创建并复制 API Key",
                    "进入「API keys」页面，找到创建密钥入口（如「创建 API key」），填写便于识别的名称，例如「医学拍题」，确认后复制完整密钥。若网页要求先登录，请登录后重新打开此入口。\n\n请及时妥善保存密钥；如果之后无法查看完整内容，可重新创建。API Key 是调用凭证，请勿分享或发送密钥截图。") {
                    OfficialDeepSeekLinkButton("打开 API keys 页面", DeepSeekGuideLink.API_KEYS)
                }
                GuideStep("4 · 返回 App，粘贴并保存",
                    "复制后，使用系统返回键或最近任务切回本 App，教程会保留当前展开状态。点击下方按钮定位输入框，长按并选择「粘贴」，再点击「保存密钥」。\n\n使用官方 Key 时，接口地址保留 https://api.deepseek.com 即可；如果此前改过地址，请在「高级接口」中检查。") {
                    OutlinedButton(onClick = onEnterKey, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("我已复制，去粘贴密钥")
                    }
                }
                GuideStep("5 · 检查连接，再验证模型",
                    "保存后先点击「检查连接」，检查接口和密钥；再点击「验证模型」，发送一次极短文字请求，可能产生少量 API 费用。只有验证通过，才说明当前模型可以生成文字。之后点击底部「拍题」开始使用。\n\n若提示 401，重新核对或创建密钥；402 表示余额不足，请查看官方账户余额；网络超时请检查网络后重试。连接或文字验证通过，仍不代表图片识别已验证。")
                OfficialDeepSeekLinkButton("查看官方使用文档", DeepSeekGuideLink.DOCUMENTATION)
            }
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = { onExpandedChange(!expanded) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (expanded) "收起教程" else "查看详细绑定步骤")
        }
    }
}

@Composable
private fun GuideStep(title: String, description: String, action: @Composable () -> Unit = {}) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

@Composable
private fun OfficialDeepSeekLinkButton(label: String, link: DeepSeekGuideLink) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var showFallback by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                })
            } catch (_: ActivityNotFoundException) {
                showFallback = true
            } catch (_: SecurityException) {
                showFallback = true
            }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    ) { Text(label) }
    if (showFallback) {
        AlertDialog(
            onDismissRequest = { showFallback = false },
            title = { Text("暂时无法打开浏览器") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请复制官方地址，在浏览器中打开；完成后返回本 App 继续。")
                    SelectionContainer { Text(link.url) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(link.url))
                    showFallback = false
                }) { Text("复制官方地址") }
            },
            dismissButton = { TextButton(onClick = { showFallback = false }) { Text("关闭") } }
        )
    }
}
