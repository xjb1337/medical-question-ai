# 修复交接报告

日期：2026-10-28

分支：`main`

工作区：`E:\souti\repo`

提交：基线 `3024c24`；修复代码 `26f843d`；A1–A3 返工代码 `5b5945d`；本报告随其后的文档提交保留（见 `git log --oneline`）。

## 版本与范围

- 开始前基线：`b41719b`（远端初始文档提交）。工作区已有 54 个未提交的 Android/Web/文档成果；先原样提交为基线 `3024c24`（`chore: capture existing Android and web implementation`），修复期间未使用 `reset`、`clean` 或删除现有成果。
- 本次修复范围：`docs/REPAIR_PLAN.md` 的 R1–R6；不包含账号、云同步、独立 OCR、临床诊疗、部署或 GitHub 推送。
- 修改文件及用途：
  - `app/src/main/java/com/xjb/medicalquestionai/data/ImageStore.kt`：拆分图片导入的打开流、尺寸探测、像素解码和 EXIF 读取；新增 `ImageImportException`、裁剪副本与替换裁剪、失败清理、`deleteAll` 批量清理。
  - `app/src/main/java/com/xjb/medicalquestionai/data/ImageEditPolicy.kt`：纯逻辑的裁剪图插入规则。
  - `app/src/main/java/com/xjb/medicalquestionai/MainViewModel.kt`：裁剪保留原图/替换原图分支、9 张统一上限、保存 Base URL 返回值、删除题目/清空历史时清理图片集合。
  - `app/src/main/java/com/xjb/medicalquestionai/ui/screens/NewQuestionScreen.kt`：裁剪对话框提供“保留原图并添加”和“替换当前图”。
  - `app/src/main/java/com/xjb/medicalquestionai/data/SettingsRepository.kt`：Base URL 规范化与 HTTPS 校验。
  - `app/src/main/java/com/xjb/medicalquestionai/data/DeepSeekClient.kt`：请求前再次校验 HTTPS Base URL。
  - `app/src/main/java/com/xjb/medicalquestionai/ui/screens/SettingsScreen.kt`：HTTP 地址拒绝提示与保存行为。
  - `app/src/test/java/com/xjb/medicalquestionai/data/ImageEditPolicyTest.kt`：裁剪插入、原图不变、目标不存在三类测试。
  - `app/src/test/java/com/xjb/medicalquestionai/data/SettingsValidationTest.kt`：HTTPS、HTTP、空值、畸形地址和规范化测试。
  - `app/src/androidTest/java/com/xjb/medicalquestionai/data/ImageImportRegressionTest.kt`：真实 Android Bitmap 导入、EXIF、缩放、元数据剥离和损坏图片回归测试（仅编译，见“未执行”）。
  - `web/assets/api.js`：追问返回 `{ text, usage, demo }`，解析真实 `usage`，无用量返回 `null`。
  - `web/assets/app.js`：保存并渲染追问用量、旧记录兼容、追问失败恢复输入、裁剪保留原图/替换两种模式、设置页安全文案。
  - `web/assets/styles.css`：追问用量样式。
  - `web/test/browser-repair-smoke.mjs`：无头 Chrome + mock DeepSeek 的浏览器回归测试。
  - `web/package.json`、`web/README.md`：测试入口与网页存储/代理说明。
  - `README.md`、`PROJECT_PLAN.md`、`docs/ARCHITECTURE.md`、`docs/PRIVACY.md`、`docs/TESTING.md`：状态、技术决策、密钥/网络边界、验证记录和 APK 信息。
  - `docs/REPAIR_REPORT.md`：本报告。

## 任务结果

| 编号 | 状态 | 修改说明 | 验证证据 | 未解决项 |
| --- | --- | --- | --- | --- |
| R1 | 已修复，设备回归未运行 | 根因是尺寸探测使用 `inJustDecodeBounds = true` 后，`decodeStream` 正常返回空 Bitmap，却被 `?: error(...)` 当作流读取失败。现分开判断流是否打开、尺寸是否有效、第二次像素解码是否成功；失败会清理目标文件并给出可读错误。保留 EXIF 校正、1800px 上限、JPEG 86 和元数据剥离。 | `ImageImportRegressionTest` 已加入并通过 `assembleDebugAndroidTest` 编译；15/15 JVM 单测通过；见下方“未执行”。 | 缺少 Android 设备，`connectedDebugAndroidTest` 未运行；合法 PNG/JPEG、损坏数据、无法打开 URI 的真实运行结果未取得。 |
| R2 | 已实现（自动逻辑通过，真机 UI 未运行） | Android 默认“保留原图并添加”，新图独立 ID/路径并插在原图后；可显式“替换当前图”；9 张上限会阻止并提示。Web 同样提供两种操作。 | `ImageEditPolicyTest` 4/4 通过；浏览器测试 `afterCrop: 2`；Android 编译通过。 | 真机裁剪预览、删除其中一张后的生命周期、9 张边界的手工验收未运行。 |
| R3 | 已修复 | Android Base URL 只接受 HTTPS，设置页即时提示且保存失败不写入；请求层再次校验。文档区分 Android 加密存储、浏览器 localStorage/sessionStorage 明文、本地 Node 代理和自定义 HTTP 行为。 | `SettingsValidationTest` 4/4；Lint 0 errors；文档与代码对照检查。 | 未在真实设备上验证设置页每一处提示的视觉效果。 |
| R4 | 已修复（mock 验证） | 追问返回值由纯文本改为 `{ text, usage, demo }`；解析并持久化 `prompt_tokens`、`completion_tokens`、`total_tokens`；旧记录无用量时隐藏；演示模式标注“模拟用量”。 | `npm test` 通过：`followUsage: "Token 用量：输入 234 · 输出 56 · 合计 290"`，`noUsageLabelHidden: true`、`oldRecordUsageHidden: true`、`failureRestored: true`。 | 未调用真实 DeepSeek，真实接口用量字段未实测。 |
| R5 | 已完成 | `PROJECT_PLAN.md` 改为“首版代码已实现，正在修复与验收”；开发阶段改为进度记录；README/docs 校准密钥、网络、APK 和状态表述；新 APK 使用新 SHA-256。 | 文档与最终构建/APK 信息核对；`git diff --check` 通过。 | 真实设备/真实模型验收仍未完成，文档已明确保留待验收状态。 |
| R6 | 已完成（报告） | 本报告记录范围、修改、证据、未执行项和交接步骤。 | 下方检查表和报告本身。 | 设备/真实模型验收需用户提供条件。 |

## 验收返工 A1–A3

依据 `docs/ACCEPTANCE_REPORT.md` 的返工要求，本轮补齐以下项目；验收报告原文保持不修改，作为历史证据。

### A1：设备测试返回类型已修复

- 文件：`app/src/androidTest/java/com/xjb/medicalquestionai/data/ImageImportRegressionTest.kt`。
- 两个异常测试改为块函数体，并在内部使用 `runBlocking<Unit>`；删除、裁剪、导入测试也统一为 `runBlocking<Unit>`。
- 实际编译后用 JDK 17 `javap` 检查 `ImageImportRegressionTest.class`：全部 6 个 `@Test` 方法均返回 `void`，包括：
  - `importsGeneratedPngAndCropCopyPreservesOriginal()`
  - `deletingOneCopyDoesNotAffectTheOther()`
  - `deletingQuestionImageSetRemovesBothFiles()`
  - `jpegWithExifIsRotatedScaledAndMetadataIsStripped()`
  - `corruptImageReportsImportErrorInsteadOfCrashingWholeImport()`
  - `unopenableUriReportsImportError()`
- 复验边界：签名问题已修复；本机仍无设备/AVD，`connectedDebugAndroidTest` **未运行**，设备执行待验收。

### A2：Android 裁剪回归断言已补足

- `importsGeneratedPngAndCropCopyPreservesOriginal`：先记录导入图的 SHA-256、实际宽高；裁剪后再次校验原图哈希和宽高完全不变；校验新图 ID、规范化路径不同；读取新图实际尺寸并验证 600×450；用红/绿/蓝/黄四象限合成图验证左上裁剪区域包含红色标记。
- `deletingOneCopyDoesNotAffectTheOther`：删除裁剪图后原图和原图哈希仍在；重新裁剪后删除原图，裁剪图和裁剪哈希仍在。
- `deletingQuestionImageSetRemovesBothFiles`：通过 `ImageStore.deleteAll()` 验证题目删除/清空使用的图片集合清理路径；`MainViewModel.deleteQuestion()` 和 `clearHistory()` 已统一调用该方法。
- `ImageEditPolicy.canAddImage()` + `ImageEditPolicyTest.cropLimit_rejectsTheTenthImage`：覆盖 9 张上限；`missingTarget_returnsNullWithoutChangingTheInputList` 覆盖取消/失败时列表不变。
- 本项仍缺设备运行证据；以上断言目前只完成编译验证，不能替代设备功能验收。

### A3：历史验证记录已恢复

- `docs/TESTING.md` 现有两个明确小节：
  - `4.1 历史记录：初版交付（修复前，保留）`：7/7 单测、旧 APK 哈希 `98964...`，作为历史记录保留。
  - `4.2 修复后验证（2026-10-28，代码提交 26f843d + A1–A3 返工 5b5945d）`：15/15 单测、新 APK 哈希、设备测试签名修复说明。
- 不再用新结果覆盖旧记录；验收报告和设备测试签名问题单独说明，未归入“无设备”。

## 本次检查

| 命令或人工用例 | 环境 | 实际/模拟 | 结果 | 日志或报告路径 |
| --- | --- | --- | --- | --- |
| `./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain` | Windows、JDK 17.0.10、Gradle 8.9、Android SDK 35、Build-Tools 35.0.0 | 实际 | 通过（exit 0），`BUILD SUCCESSFUL in 11s`，84 tasks（11 executed，73 up-to-date） | `E:/souti/rework-final-build.log`；`app/build/reports/lint-results-debug.html` |
| `testDebugUnitTest` | 同上 | 实际 | 15/15 通过，0 failures、0 errors、0 skipped：`ImageEditPolicyTest` 4、`ModelsTest` 7、`SettingsValidationTest` 4 | `app/build/test-results/testDebugUnitTest/TEST-*.xml` |
| `lintDebug` | 同上 | 实际 | 0 errors、18 warnings（均为非阻断的 GradleDependency 版本提示） | `app/build/reports/lint-results-debug.xml` |
| `assembleDebug` | 同上 | 实际 | 成功，生成 debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| `assembleDebugAndroidTest` | 同上 | 实际 | 测试 APK 编译成功，但未执行设备测试 | `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` |
| JDK 17 `javap -p` 检查 `ImageImportRegressionTest.class` | 本机 | 实际 | 6 个 `@Test` 方法全部返回 `void` | `app/build/tmp/kotlin-classes/debugAndroidTest/com/xjb/medicalquestionai/data/ImageImportRegressionTest.class` |
| `adb devices`、`emulator -list-avds` | 本机 | 实际 | 无设备、无 AVD | 终端输出 |
| `npm test`（`web/test/browser-repair-smoke.mjs`） | Node 25、本地 Chrome headless、mock DeepSeek | 模拟上游 | 通过：`imported:1`、`afterCrop:2`、`resultRendered:true`、`followUsage` 正确、无用量/旧记录/失败恢复断言通过、`historyCount:1` | `web/test/browser-repair-smoke.mjs` 输出；mock 不是真实 DeepSeek |
| `node --check`（app/api/images/store/server/test） | Node 25 | 实际 | 全部语法检查通过 | 终端输出 |
| `aapt2 dump badging dist/medical-question-ai-debug.apk` | Android Build-Tools 35.0.0 | 实际 | `com.xjb.medicalquestionai`、versionCode 1、versionName 1.0.0、minSdk 26、targetSdk 35 | 终端输出 |
| `apksigner verify --verbose dist/medical-question-ai-debug.apk` | Android Build-Tools 35.0.0 | 实际 | `Verifies`；APK Signature Scheme v2: true；debug 签名 | 终端输出 |
| `git diff --check` | Git | 实际 | 通过（仅有工作区 LF/CRLF 提示，无空白错误） | 终端输出 |

## APK（本次新构建）

- 路径：`dist/medical-question-ai-debug.apk`
- 版本：`1.0.0`（versionCode 1），包名 `com.xjb.medicalquestionai`
- 大小：21,474,173 bytes（约 21 MB）
- SHA-256：`9da7a2aa7908811af2ced7a01979e4c4058610d6e10b8b445b3493b448a058f3`
- 签名：debug 签名，APK Signature Scheme v2 验证通过
- 安装结果：**未安装**（本机无连接设备/模拟器，不能记为安装通过）

## 需验收方或用户继续执行

缺少的设备/接口/样本条件：

- 一台 Android 真机或已配置的模拟器；当前 `adb devices` 为空，`emulator -list-avds` 为空。
- 真实 DeepSeek API Key（由用户自行在设置页输入，不要写入仓库或报告）和真实学习题样本。
- 相机权限拒绝、相册选择、9 张图片、完整图加局部图、解析、追问、重开历史和删除的真机手工流程。

已准备好的命令/步骤：

1. 安装：`adb install -r dist/medical-question-ai-debug.apk`。
2. Android 设备回归：`cd E:\souti\repo && .\gradlew.bat connectedDebugAndroidTest`；若失败，先检查设备连接和 SDK 版本。
3. 网页手工验收：`cd E:\souti\repo\web && npm start`，打开 `http://127.0.0.1:4173`；先开启演示模式，再用自己的 API Key 关闭演示模式测试真实解析。
4. 真实模型验收按 `docs/TESTING.md` 的题型与图片组合逐题记录；不要把 mock/演示结果当成真实模型结果。

已知问题与限制：

- R1 的设备端测试尚未执行；构建和测试 APK 编译成功不等于图片导入功能已在设备上通过。
- R2 的真机裁剪 UI 与文件生命周期未手工验收。
- R4 的真实接口 token 字段未实测，只验证了本地 mock 响应、持久化与页面展示链路。
- Lint 的 18 个 warning 是依赖版本更新提示，本次未升级依赖，避免扩大范围。
- 本报告和修复代码尚未推送 GitHub；是否推送由用户决定。
