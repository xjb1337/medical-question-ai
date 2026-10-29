# 测试说明

## 1. 自动化测试

### 单元测试

```bash
./gradlew testDebugUnitTest
```

当前共 69 个 JVM 单元测试（6 个测试类，全部通过）：

- `UpdateCoreTest`（5）：更新清单严格字段/类型/schema/null 校验、数字 versionCode 比较、Release 资产唯一性与 draft/prerelease 拒绝。
- `CaptureDraftPolicyTest`（2）：连续“拍摄下一题”时已有暂存草稿不被非空当前草稿覆盖；空白草稿不视为有内容。
- `DeviceFeedbackCoreTest`（47）：设置迁移幂等、自定义代理不误迁移、模型能力保护、三档 `thinking`/`max_tokens` 请求参数、SSE UTF-8/CRLF/注释/多行/`[DONE]` 边界、无效 `[DONE]` 前置事件、预览 JSON 完整字段/截断/转义、选择题答案校验、`finish_reason` 严格结束校验、错误分类、追问历史整轮裁剪预算、答案输入版本过期判定、F1–F8 严格预览/最终答案语义与题型冲突、R2 单字符串多选项/预览一致性/追问版本/null 污染用例、R3 非法预览降级和连续 null 前缀隔离用例。
- `ModelsTest`（7）：`AnalysisResult` 的 JSON 序列化/反序列化往返、旧版字段别名兼容（`type`、`question`、`input_tokens` 等）、空答案的待确认状态、填空题 `answer_text` 不误判为缺失答案、题型存储值与中文标签、纯图片题目的历史列表兜底文案。
- `SettingsValidationTest`（4）：HTTPS Base URL 校验、首尾空白/斜杠规范化、拒绝 HTTP、拒绝伪造主机名。
- `ImageEditPolicyTest`（4）：裁剪「保留原图」时插在原图之后，原图对象与路径不变。

图片导入的真机回归测试位于 `app/src/androidTest/java/com/xjb/medicalquestionai/data/ImageImportRegressionTest.kt`，覆盖合成 PNG 导入、裁剪保留原图和损坏图片抛出 `ImageImportException`。该测试需要连接设备/模拟器执行 `./gradlew connectedDebugAndroidTest`；本次没有可用设备，只能完成 `assembleDebugAndroidTest` 编译验证，不能记为功能测试通过。

报告位置：

```text
app/build/reports/tests/testDebugUnitTest/index.html
```

### 静态检查

```bash
./gradlew lintDebug
```

Lint 报告：

```text
app/build/reports/lint-results-debug.html
```

### 构建验证

```bash
./gradlew assembleDebug assembleDebugAndroidTest
```

### 浏览器回归（网页版）

```bash
npm --prefix web test
# 等价于 node --test web/test/unit.test.mjs && node web/test/browser-repair-smoke.mjs
```

单元测试覆盖 Web 设置 v2 迁移、模型能力、三档请求参数、SSE 分片/UTF-8/CRLF/`[DONE]`、预览 JSON 和取消状态；浏览器 smoke 自行启动本地 mock DeepSeek、网页服务器和无头 Chrome，验证合成 PNG 导入、裁剪保留原图、解析 Token 展示、追问 Token 用量、历史落库。它不调用真实 DeepSeek API，也不能替代真实浏览器手工验收。

## 2. 手工验收清单

### 首次启动

- [ ] 首页显示「尚未配置 API Key」提示
- [ ] 点击提示可进入设置页
- [ ] 设置页可以保存 Key，并显示脱敏后的 Key 提示
- [ ] 「测试连接」成功/失败都有明确提示
- [ ] 重启应用后 Key 仍然存在且不明文展示

### 图片与相机

- [ ] 相机权限拒绝时显示授权引导，不崩溃
- [ ] 拍照后回到首页并出现缩略图
- [ ] 相册可多选，最多 9 张
- [ ] 第 10 张被拒绝并提示上限
- [ ] 缩略图可以左移/右移、旋转、裁剪、删除
- [ ] 删除后列表与数据一致

### 解析

- [ ] 只有文字可以解析
- [ ] 只有图片可以解析
- [ ] 文字+图片可以解析
- [ ] 首页不再出现逐题隐私确认，填好题目后可直接解析
- [ ] 解析中显示进度，重复点击不会发起第二次请求
- [ ] 成功后进入结果页，题干、答案、思路、关键点完整
- [ ] 多选题答案显示多个选项
- [ ] 低置信度/空答案显示风险提示
- [ ] 断网时显示错误并保留草稿

### 结果页

- [ ] 复制答案到剪贴板
- [ ] 系统分享只含文字
- [ ] 收藏/取消收藏状态可切换
- [ ] 编辑题干或图片后可以重新分析
- [ ] 追问可以发送文字
- [ ] 追问可以附加图片
- [ ] 追问失败后输入内容保留

### 历史

- [ ] 新解析按时间倒序出现
- [ ] 搜索题干命中
- [ ] 搜索答案命中
- [ ] 收藏筛选正确
- [ ] 删除单条后列表刷新
- [ ] 清空全部有二次确认
- [ ] 关闭「保存解析历史」后未收藏的新解析不出现在历史列表，收藏题仍显示

### 设置与隐私

- [ ] 模型名修改后用于下一次请求
- [ ] Base URL 修改后用于下一次请求
- [ ] 删除 Key 后首页恢复未配置状态
- [ ] 数据库与 SharedPreferences 中不存在明文 Key（可用 Android Studio Device File Explorer 检查）
- [ ] 应用未申请 READ/WRITE_EXTERNAL_STORAGE

## 3. API 联调

### 验证 Key

```bash
curl -sS https://api.deepseek.com/models \
  -H "Authorization: Bearer $DEEPSEEK_API_KEY"
```

### 验证 Chat Completions

```bash
curl -sS https://api.deepseek.com/chat/completions \
  -H "Authorization: Bearer $DEEPSEEK_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-flash",
    "messages": [
      {"role": "user", "content": "用一句话解释什么是心肌梗死"}
    ],
    "thinking": {"type": "disabled"},
    "max_tokens": 3072,
    "stream": true
  }'
```

### 图片请求格式

应用发送 OpenAI 兼容的多模态消息：

```json
{
  "role": "user",
  "content": [
    {"type": "text", "text": "请解析这道医学题……"},
    {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}}
  ]
}
```

若使用不支持视觉输入的模型，带图请求会返回 400/422，应用会提示切换模型或仅使用文字。

## 4. 验证记录

### 4.1 历史记录：初版交付（修复前，保留）

日期：2026-10-28（Asia/Shanghai）

对应版本：初版交付基线 `3024c24`（修复前）

- 环境：Windows、JDK 17.0.10、Gradle 8.9、AGP 8.7.3、Android SDK 35 / Build-Tools 35.0.0。
- `./gradlew testDebugUnitTest`：通过，7/7 单元测试，0 失败。
- `./gradlew lintDebug`：通过，0 errors、18 warnings（均为依赖版本更新提示，无阻断项）。
- `./gradlew assembleDebug`：通过，生成可安装的 debug APK，约 21 MB。
- `aapt2 dump badging`：包名 `com.xjb.medicalquestionai`，versionCode 1、versionName 1.0.0，minSdk 26、targetSdk 35。
- `apksigner verify`：APK Signature Scheme v2 验证通过（debug 签名）。
- 产物副本：`dist/medical-question-ai-debug.apk`，SHA-256 `9da7a2aa7908811af2ced7a01979e4c4058610d6e10b8b445b3493b448a058f3`。
- 尚未连接真实安卓手机，因此第 2 节的手工验收项需要在设备上继续执行。

> 以上是修复前的历史记录，按要求保留；不要用后续结果覆盖。

### 4.2 修复后验证（2026-10-28，代码提交 `26f843d` + A1–A3 返工 `5b5945d`）

环境：Windows、JDK 17.0.10、Gradle 8.9、AGP 8.7.3、Android SDK 35 / Build-Tools 35.0.0。

- `./gradlew testDebugUnitTest`：通过，15/15 单元测试，0 失败。
- `./gradlew lintDebug`：通过，0 errors、18 warnings（均为依赖版本更新提示，无阻断项）。
- `./gradlew assembleDebug`：通过，生成可安装的 debug APK，约 21 MB。
- `./gradlew assembleDebugAndroidTest`：通过（测试 APK 编译成功）。
- 设备测试签名复核：验收发现 `ImageImportRegressionTest` 的 `corruptImage...` 与 `unopenableUri...` 两个方法因 `runBlocking` 尾表达式被 Kotlin 推导为 `DraftImage`，JUnit 4 要求 `void`；已改为显式 `runBlocking<Unit>`/块函数体。JDK 17 `javap` 静态校验显示全部 6 个 `@Test` 方法均返回 `void`；本机没有设备/模拟器，`connectedDebugAndroidTest` **未执行**。编译成功不能记为真机功能通过。
- `node web/test/browser-repair-smoke.mjs`：通过；合成 PNG 导入、裁剪保留原图、解析 Token、追问 Token 与历史落库均验证通过（mock 上游，非真实 DeepSeek）。
- `aapt2 dump badging`：包名 `com.xjb.medicalquestionai`，versionCode 1、versionName 1.0.0，minSdk 26、targetSdk 35。
- `apksigner verify`：APK Signature Scheme v2 验证通过（debug 签名）。
- 产物副本：`dist/medical-question-ai-debug.apk`，SHA-256 `9da7a2aa7908811af2ced7a01979e4c4058610d6e10b8b445b3493b448a058f3`。
- 尚未连接真实安卓手机，因此第 2 节的手工验收项需要在设备上继续执行；真实 DeepSeek 也未调用。

### 4.3 真机反馈改造验证（2026-10-29）

环境：Windows、JDK 17.0.10、Gradle 8.9、AGP 8.7.3、Android SDK 35 / Build-Tools 35.0.0、Node.js 25.8.1。

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`：BUILD SUCCESSFUL；69/69 单元测试通过（单元测试重跑日志 `docs/evidence/2026-10-29-android-final.log`）。
- Lint：0 errors、22 warnings（18 个 `GradleDependency` 依赖版本提示 + 4 个 `ApplySharedPref`）。
- 设备：`adb devices` 无设备；`connectedDebugAndroidTest` 未执行，不能记为真机功能通过。
- `npm --prefix web test`：19/19 单元测试通过；浏览器 smoke 通过（`ok: true`）。
- `aapt2 dump badging`：包名 `com.xjb.medicalquestionai`，versionCode 3、versionName 1.2.0，minSdk 26、targetSdk 35（guide APK；候选更新包为 versionCode 4 / 1.2.1-test.1）。
- `apksigner verify`：APK Signature Scheme v2 验证通过；新旧 APK 使用同一 debug 证书 SHA-256 `77c3ca8d1a7cf0aca4718b328705bb21833976bc701dad89c11881c83ca1c861`。
- 产物：`dist/medical-question-ai-debug.apk` / `dist/medical-question-ai-update-guide.apk`，21,940,535 bytes，SHA-256 `1818eea30285ae5648325c9120078b77eaf8cb80cd71d47093fb0f18a102aa87`；候选包 `dist/medical-question-ai-update-candidate.apk`，21,458,738 bytes，SHA-256 `c7baddfebcfc4bcc337d78622f772ed4f29dda4c8184955d3c2297b41b836f33`。
- 未执行：真实 DeepSeek Key 联调、40 题质量对照、15 题×3 次速度对照、真机 UI/相机/覆盖安装、GitHub Release 发布/真机应用内更新。详见 `docs/DEVICE_FEEDBACK_IMPLEMENTATION_REPORT.md`。

### 4.4 退出解析页与拍摄下一题（2026-10-29 第四轮）

- JVM：`StopPolicy` 生命周期判定、旧请求身份校验、草稿 sessionId/暂存草稿不覆盖策略包含在 69 项测试中；checkpoint 落库路径为代码审查与构建验证。
- 自动验证命令与结果同 4.3；Lint 0 errors / 22 warnings。
- 未执行：真机返回/后台/锁屏/旋转/权限弹窗、相机取消、连点、16 项交互验收、`connectedDebugAndroidTest`、旧库升级实测。详见 `docs/DEVICE_FEEDBACK_IMPLEMENTATION_REPORT.md` 第 13 节。
- 行为约定：离开结果页停止当前题解析和追问，保留已完成答案；未完成预览标注为“未完成预览”，不自动重试；结果页底部固定“拍摄下一题”，新题使用独立 session，不继承旧文字/图片。

### 4.5 应用内更新（2026-10-29 第五轮）

- JVM：`UpdateCoreTest`（5 项）覆盖 update.json 的 schema/必填字段/严格类型/null、数字 versionCode 比较、Release 的 draft/prerelease 拒绝、同一 Release 必须唯一包含 `update.json` 与 `medical-question-ai.apk`。
- 构建：`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过；guide APK versionCode 3 / 1.2.0，候选包 versionCode 4 / 1.2.1-test.1，两者同一 debug 证书。
- CI：`.github/workflows/release.yml` 已编写但未配置 GitHub Secrets、未真实运行；GitHub Release、真机下载/签名校验/安装/重启/覆盖升级未执行，不能记为通过。
- 人工发布和首次安装步骤见 `docs/APP_UPDATE_RELEASE_GUIDE.md`。

## 5. 回归重点

1. 图片导入：先 `inJustDecodeBounds` 读取尺寸，再按采样率解码；空流、损坏图片、超大图片都应转成 `ImageImportException`，不能拖垮整个导入流程。
2. 裁剪保留原图：新增裁剪图应插在原图之后，原图 id/path 不变；替换裁剪才更新原路径的宽高与文件大小。相关纯逻辑在 `ImageEditPolicy`，必须保留单元测试。
3. Room 版本升级：当前数据库版本为 8，包含 `MIGRATION_1_2`（追问表）、`MIGRATION_2_3`（`questions.isEphemeral`）、`MIGRATION_3_4`（`mode`/`durationMs`/`promptVersion`）、`MIGRATION_4_5`（`baseUrl` 快照）、`MIGRATION_5_6`（答案输入版本 `inputRevision`/`answerInputRevision`，默认 0）、`MIGRATION_6_7`（追问答案版本 `answerRevision`，默认 0）和 `MIGRATION_7_8`（未完成预览 `checkpointJson`，nullable）；升级时必须提供 Migration，禁止直接 `fallbackToDestructiveMigration()` 上线。
4. 图片文件生命周期：删除题目、重答替换图片、清空历史时都要删除对应文件，避免私有目录膨胀。
5. JSON 契约变化：新增字段必须保持可选；旧记录反序列化不能崩溃。
6. 大图内存：低端机连续导入 9 张 4000px 图片时不应 OOM。
7. API Key 泄漏：日志、崩溃报告、分享内容中不得出现 Key。
