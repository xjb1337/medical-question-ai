# DeepSeek 首次绑定教程交付与验收

日期：2026-10-31。范围：Android App。

## 用户流程

未配置密钥的用户从首页「去绑定」或底部「设置」进入设置页，默认展开「DeepSeek 首次绑定教程」。已有密钥的用户默认看到收起的教程入口，可随时重新展开。

1. 登录开放平台：按钮打开 `https://platform.deepseek.com`，说明在官方网页完成登录、注册与验证码操作。
2. 查看 API 余额：按钮打开 `https://platform.deepseek.com/top_up`，说明按用量计费，余额不足时由用户自行决定充值。
3. 创建并复制密钥：按钮打开 `https://platform.deepseek.com/api_keys`，说明寻找创建入口、填写名称、复制完整 Key、妥善保存，以及登录后重新打开入口的做法。
4. 返回 App 保存：「已有 API Key，直接填写」和「我已复制，去粘贴密钥」均收起教程、滚动到密钥框并聚焦。用户自行粘贴并点击保存，保留现有加密存储流程；保存成功收起教程。教程也提醒官方 Key 使用默认官方接口地址。
5. 检查与验证：分别解释「检查连接」查询模型列表、「验证模型」发送短文字请求及其费用，说明 401、402、超时的处理，以及完成后从底部「拍题」开始使用。

教程展开状态及滚动位置可在 Activity 重建后恢复；密钥草稿不写入 saved instance state。应用不自动读取剪贴板，不把 Key 拼入官网 URL。浏览器启动失败时显示可选择、可复制的官方地址。

## 官方信息核对

- [DeepSeek 首次调用 API](https://api-docs.deepseek.com/zh-cn/)：官方 Base URL、API Key 申请入口。
- [DeepSeek 错误码](https://api-docs.deepseek.com/zh-cn/quick_start/error_codes/)：401、402 与官方充值入口。
- 登录后的平台页面在网页抓取中返回 403，因此教程使用“如创建 API key”等描述，未声称按钮文字永久不变，也未提供未经核实的充值金额或支付方式。

## 验证结果

- `testDebugUnitTest`：75 项通过，0 failures / 0 errors。
- `lintDebug`、`assembleDebug`、`assembleDebugAndroidTest`：通过；Lint 0 errors，4 warnings，2 information。
- 现有雷电 Android 9 模拟器，默认横屏：新增教程 4 项测试通过。
- 1080×1920、460dpi（约 375.7dp 宽）手机竖屏：完整 instrumentation 19 项通过，包括教程 4、图片查看 2、导航与结果页 3、更新 4、图片导入 6。
- 同一手机竖屏、fontScale=2.0、深色模式：教程 4 项再次通过；教程内容和按钮可换行、纵向滚动。
- 教程测试检查输入框定位与聚焦、教程展开/收起与滚动位置在 Activity 重建后恢复、未保存明文 Key 不恢复、4 个官网 URL 与 BROWSABLE Intent、无浏览器时复制官方地址。测试不移除或覆盖设备主人已有的 Key；若检测到真实已保存密钥会跳过这些无密钥场景。
- 真实系统 `ACTION_VIEW` 打开官方 API keys URL，返回 `Status: ok`，由 `com.android.browser/.BrowserActivity` 接收。该项只证明系统浏览器启动成功，不证明登录、创建密钥或充值成功。
- `git diff --check`：通过。

证据：[手机竖屏教程](evidence/key-guide-2026-10-31/guide-overview.png)、[定位密钥框](evidence/key-guide-2026-10-31/key-entry.png)、[19 项模拟器回归](evidence/key-guide-2026-10-31/emulator-tests.log)、[2 倍字体深色模式](evidence/key-guide-2026-10-31/large-dark-tests.log)。其他构建日志与截图在 `E:\souti\acceptance-artifacts\key-guide*`。

## 预览 APK

- 文件：`E:\souti\repo\dist\medical-question-ai-key-guide-preview.apk`。
- versionName：`1.3.0-key-guide-preview`；versionCode：`100004`。
- 大小：21,540,710 bytes。
- SHA-256：`5b928cf3824bb4f633aff88b5bf611d59e67520e604262803c7db6253f2665f8`。
- Debug 签名，已保留数据覆盖安装到测试模拟器。此为本地预览包；源码随后与性能和导航修复一并推送，线上签名 APK 由既有 Release 工作流生成，版本与本地预览包分开。

## 验证边界

未使用真实 DeepSeek API Key；没有执行真实账号登录、创建 Key、充值、模型调用或实体手机验收。不能将教程 UI、模拟器与跳转 Intent 测试视为真实 API/图片识别能力验证。超大字体时，原有页面顶栏的副标题仍受既有单行高度约束；教程正文与操作按钮可正常滚动使用。
