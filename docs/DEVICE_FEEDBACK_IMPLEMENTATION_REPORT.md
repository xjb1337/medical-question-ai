# 真机反馈实施交接报告

> 本报告为实施者交接，独立验收结论由审查者另行给出。本机无 Android 设备/模拟器、无真实 DeepSeek API Key，因此真机、真实接口、40 题质量对照和 15 题×3 次速度对照均未执行；自动检查只证明代码编译与纯逻辑/本地 mock 行为，不能替代真机与真实服务验收。

## 1. 版本与范围

- 实施日期/时区：2026-10-29，Asia/Shanghai。
- 开始提交/结束提交：开始 `4b6f3ee`（2026-10-28 23:15:48 +08:00）；本轮 NC-01～NC-03 修复提交见 `git log -1`，审查者可按 `git show --stat HEAD` 核对。
- 开始工作区状态及保留改动：开始时 `docs/DEVICE_FEEDBACK_PLAN_2026-10-29.md` 与 `docs/DEVICE_FEEDBACK_REPORT_TEMPLATE.md` 为未跟踪文件，其余工作区干净；实施后保留全部业务代码、测试、文档、APK 和 `docs/evidence/` 证据，不清理、不覆盖旧验收报告。
- DeepSeek 官方文档复核日期、页面和相对方案变化：未复核（沿用方案第 1 节 2026-10-29 的历史结论，页面为 `api-docs.deepseek.com` 的模型列表/思考模式说明；本次未重新联网抓取，未确认相对变化）。正式模型名为 `deepseek-flash`（文字+图片）与 `deepseek-v4-pro`（仅文字）。
- Android 版本/build：`versionCode 2`、`versionName 1.1.0`，debug 构建；包名 `com.xjb.medicalquestionai`，minSdk 26、targetSdk 35。
- APK 绝对路径 / SHA-256：`E:\souti\repo\dist\medical-question-ai-debug.apk`；`c54e5eadc8ed535793ca9dacdcf5c3f9c885682a18d83bc7e603cad53666f747`（21,842,313 bytes）。旧版归档于 `dist/archive/medical-question-ai-debug-v1.0.0-20261028.apk`，SHA-256 `9da7a2aa7908811af2ced7a01979e4c4058610d6e10b8b445b3493b448a058f3`。
- 真机型号 / Android 版本 / 屏幕宽度 / 字体缩放：无设备，未执行。
- 真实接口 / mock / 演示分别使用在哪些检查：真实接口未使用（无 Key）；单元测试使用纯 JVM 逻辑与固定 JSON/SSE 字节；Web 测试使用本地 mock DeepSeek 和浏览器 headless 环境；演示模式未作为验收证据。

## 2. 工作包完成情况

| 工作包 | 状态（通过/失败/未执行） | 提交/文件 | 运行证据与限制 |
| --- | --- | --- | --- |
| B0 基线、埋点、版本 | 未执行（无设备基线） | `app/build.gradle.kts`、`MainViewModel.kt` | 版本 2/1.1.0；`AnalysisMetrics` 代码已记录点击、提交、首包、完成、取消等阶段；无真机基线数据，不能判定性能门槛 |
| M1 模型和能力 | 部分（自动） | `data/ModelCatalog.kt`、`SettingsScreen.kt`、`web/assets/store.js`、`web/assets/api.js` | Android JVM 与 Web 单测验证 Flash/Pro 能力、未知模型不假设支持图片；真实 `/models` 列表和真实调用未执行 |
| M2 旧配置迁移 | 通过（自动） | `data/SettingsMigrations.kt`、`SettingsRepository.kt`、`web/assets/store.js` | 单测覆盖 chat→flash+快速、reasoner→flash+详细、别名、幂等、自定义代理不迁移；未做真机升级 |
| M3 连接与实际模型验证 | 未执行（真实） | `DeepSeekClient.kt`、`MainViewModel.kt`、`SettingsScreen.kt` | 代码已实现 `/models` 校验、模型列表存在性检查和实际调用验证；无 Key、无网络实调 |
| S1 模式和 JSON | 通过（自动） | `data/AnswerMode.kt`、`QuestionAnalyzer.kt`、`web/assets/api.js` | 单测验证三档 `thinking`/`reasoning_effort`/`max_tokens` 与 `json` 模式参数；未发真实请求 |
| S2 端到端 SSE 与答案预览 | 部分（自动/mock） | `data/SseParser.kt`、`data/PreviewJsonParser.kt`、`DeepSeekClient.kt`、`web/server.mjs` | JVM 单测覆盖跨字节 UTF-8、CRLF、注释、多行 data、`[DONE]`、截断 JSON；Web smoke 仅验证非流式 mock/UI 与浏览器展示，SSE 逐块转发、真实上游未验证 |
| S3 取消和生命周期 | 未执行（设备/并发） | `DeepSeekClient.kt`、`MainViewModel.kt`、`ResultScreen.kt` | 已实现 `Call.cancel()`、请求 ID 隔离、取消态保留旧结果；无真机点击和迟到数据实测 |
| S4 图片与上下文 | 未执行（真实） | `QuestionAnalyzer.kt`、`ImageStore.kt`、`NewQuestionScreen.kt` | 已实现 Pro 图片前置拦截、18MB 预算、追问历史裁剪；图片导入设备回归未执行 |
| U1 首页 | 未执行（真机） | `ui/screens/NewQuestionScreen.kt` | 代码已改为拍照优先、紧凑编辑、底部固定解析栏；无截图/录屏 |
| U2 生成中 | 未执行（真机） | `MainViewModel.kt`、`ResultScreen.kt` | 已实现阶段进度、答案预览、停止按钮；无真机状态验证 |
| U3 结果页 | 未执行（真机） | `ui/screens/ResultScreen.kt`、`ui/components/AnalysisResultView.kt` | 答案卡片置顶、详情折叠、追问栏固定；无 360/412dp、字体缩放截图 |
| U4 追问/历史/设置 | 未执行（真机）；Web 部分（自动/mock） | `ResultScreen.kt`、`HistoryScreen.kt`、`SettingsScreen.kt`、`web/assets/app.js` | Android 已实现流式追问、停止、失败保留、历史状态和设置迁移提示；Web 已实现追问快捷问题、原图开关、历史日期分组和四组设置，浏览器 smoke 只覆盖基础流程；无设备验收 |
| U5 视觉与可用性 | 未执行（真机） | `ui/theme/*`、各 screen | 仅编译通过；无浅深色、1.3× 字体、TalkBack、触控区域人工检查 |
| A1 质量对照 | 未执行 | — | 无 40 题答案集、无真实 Key、无人工评分，不能报告准确率 |
| A2 速度对照 | 未执行 | — | 无 15 题×3 次、无基线/新版真实请求，不能报告 P50/P95 或 30% 改善 |
| A3 功能异常回归 | 未执行 | — | C01–C21 中除自动/静态证据外均未在设备执行，逐项见第 3 节 |

## 3. 功能检查逐项结果

> 来源列严格区分自动、mock、代码审查和未执行；没有真机截图或录屏时不伪称通过。

| ID | 操作 | 实际结果 | 来源 | 证据/未覆盖 |
| --- | --- | --- | --- | --- |
| C01 | 首装、官方旧 chat/reasoner 升级、二次启动、改回模式再启动 | 迁移逻辑单测通过；首装/二次启动/改回模式未执行 | 自动（迁移）+ 未执行（设备） | `DeviceFeedbackCoreTest` 迁移用例；无设备升级日志 |
| C02 | 自定义代理同名模型不迁移；官方 root 与 /v1 拼接 | 官方地址严格判定、自定义模型保留单测通过；真实请求拼接未执行 | 自动 | `officialBaseUrlCheckIsStrict`、`customGatewayKeepsUserModelName` |
| C03 | Pro 文字正常、Pro+图片拦截、Flash 图片正常 | 能力表和 UI/ViewModel 前置拦截代码编译通过；真实调用未执行 | 自动（逻辑）+ 未执行（真实） | `catalogUsesCurrentOfficialModelsAndImageCapability`；无 Key |
| C04 | Key 离线保存；模型列表可达但模型不存在；实际调用失败不显示验证成功 | 代码已实现；无真实 Key，未执行 | 未执行（真实） | `SettingsScreen`/`MainViewModel` 代码审查 |
| C05 | 三档模式与追问真实请求参数符合模式 | 请求构造单测通过；真实抓包未执行 | 自动 | `answerModeParametersMatchProductTiers`、`payloadSendsThinkingTiersAndJsonMode` |
| C06 | SSE 拆包/合包/中文跨字节/转义/数组/保活/usage 末尾 | 解析器单测通过；Web mock 流通过 | 自动 + mock | `DeviceFeedbackCoreTest` SSE 用例、`web/test/unit.test.mjs` |
| C07 | 仅 reasoning 无 content、空答案、字段不合规、finish_reason=length、异常 EOF | 空答案、截断 JSON、finish 事件单测通过；真实流式异常未执行 | 自动（部分） | `emptyAnswerGetsNeedsConfirmationAndWarnings`、`truncatedJsonFailsWithFriendlyMessage`、`sseParserSupportsCommentsMultilineDataAndFinish` |
| C08 | 取消前无内容/迟到内容/重发/重复点击 | 代码已实现请求隔离和取消，未执行设备并发测试 | 未执行（设备） | `DeepSeekClient.cancel()`、`MainViewModel` activeJob/requestId |
| C09 | Node 代理逐块转发、浏览器断开取消、超时覆盖响应体 | Web smoke 仅覆盖非流式 mock/UI；SSE 逐块转发、真实浏览器断开和真实上游均未验证 | mock（仅非流式） | `web/test/browser-repair-smoke.mjs`、`web/server.mjs` |
| C10 | 切页面、旋转、前后台、进程终止后重开 | 未执行 | 未执行（设备） | 无设备 |
| C11 | 重答改设置、失败保留旧结果、追问并发不串题 | 未执行 | 未执行（设备/并发） | 代码审查 only |
| C12 | 分析期间图像不可改/误删、取消失败删除文件生命周期 | 未执行 | 未执行（设备） | `ImageImportRegressionTest` 仅编译 |
| C13 | 401/402/404/422/429/5xx/断网/慢网/超时错误说明 | 错误分类代码已实现；真实状态码未执行 | 自动（代码）+ 未执行（真实） | `DeepSeekClient` 错误映射、`HumanErrorText` |
| C14 | 旧历史、旧 confidence/usage、缺新元数据展示 | 旧字段别名/旧 JSON 单测通过 | 自动 | `ModelsTest` 7 项 |
| C15 | 相册/相机、方向、多图排序、裁剪、9 图上限、读写失败 | 设备回归未执行（仅 assembleDebugAndroidTest 编译通过） | 未执行（设备） | `ImageImportRegressionTest` 未运行 |
| C16 | 首页拍题入口、底部固定操作、键盘、安全区、纯文字 | 仅代码/编译，未执行 | 未执行（真机） | 无截图 |
| C17 | 答案首屏、长简答、警示、低把握、多选、非医学题、未完成预览 | 仅代码/编译，未执行 | 未执行（真机） | 无截图 |
| C18 | 追问可达、流式、停止、失败重试、附图默认关闭、长对话预算 | 仅代码/编译，未执行 | 未执行（真机） | Web mock 只覆盖基础追问用量 |
| C19 | 历史搜索/收藏/重开/删除/清空、关闭保存历史与文件清理 | 仅代码/编译，未执行 | 未执行（真机） | 无设备 |
| C20 | 360/412dp、浅深主题、1.3× 字体、TalkBack、48dp、滚动 | 仅代码/编译，未执行 | 未执行（真机） | 无截图/录屏 |
| C21 | APK 覆盖升级保留数据；手机版本/build 与哈希对应 | APK 元数据/签名已验证；覆盖安装未执行 | 通过（静态）+ 未执行（设备） | `aapt2`/`apksigner` 输出见 `docs/evidence/` |

## 4. 自动检查

| 命令 | 实际执行时间 | 退出状态/统计 | 报告位置 | 未覆盖内容 |
| --- | --- | --- | --- | --- |
| `./gradlew :app:testDebugUnitTest` | 2026-10-29 21:45 +08:00（NC-01～NC-03 最终重跑） | BUILD SUCCESSFUL；64 tests / 0 failures / 0 errors / 0 skipped（47 `DeviceFeedbackCoreTest` + 4 `ImageEditPolicyTest` + 7 `ModelsTest` + 4 `SettingsValidationTest` + 2 `CaptureDraftPolicyTest`） | `app/build/reports/tests/testDebugUnitTest/index.html`；`app/build/test-results/testDebugUnitTest/*.xml`；`docs/evidence/2026-10-29-android-final.log`（仅构建日志） | 纯 JVM，无设备/网络 |
| `./gradlew :app:lintDebug` | 2026-10-29 21:45 +08:00 | BUILD SUCCESSFUL；0 errors / 22 warnings（18 个 `GradleDependency` + 4 个 `ApplySharedPref`，统计来自 `app/build/reports/lint-results-debug.xml`） | `app/build/reports/lint-results-debug.html`、`app/build/reports/lint-results-debug.xml`；`docs/evidence/2026-10-29-android-final.log`（仅构建日志） | 静态检查，不证明 UI |
| `./gradlew :app:assembleDebug` | 2026-10-29 21:45 +08:00 | BUILD SUCCESSFUL；APK 21,842,313 bytes | `dist/medical-question-ai-debug.apk` | 未安装运行 |
| `./gradlew :app:assembleDebugAndroidTest` | 2026-10-29 21:45 +08:00 | BUILD SUCCESSFUL（测试 APK 编译） | `app/build/outputs/apk/androidTest/` | 未执行 instrumented 测试 |
| `./gradlew :app:connectedDebugAndroidTest` | 未执行 | `adb devices` 无设备 | — | 无设备 |
| `npm --prefix web test` | 2026-10-29 21:09 +08:00 | 19/19 单元测试通过；browser smoke `ok: true` | `docs/evidence/2026-10-29-web-final.log` | mock 上游，非真实 DeepSeek |
| `cmp` / `sha256sum`（build→dist） | 2026-10-29 21:46 +08:00 | 两文件逐字节一致；21,842,313 bytes；SHA-256 `c54e5ead…` | `docs/evidence/2026-10-29-apk-correspondence.log` | 只证明产物对应，不证明真机安装 |
| `git diff --check` | 2026-10-29 21:45 +08:00 | 退出 0（仅 CRLF 提示） | `docs/evidence/2026-10-29-git-diff-check.log` | 不检查运行行为 |

新增针对性测试：`DeviceFeedbackCoreTest`（模型/迁移/模式请求参数/SSE/预览 JSON/解析边界）、`SettingsValidationTest`（URL）、`ModelsTest`（旧 JSON 兼容）、`ImageEditPolicyTest`（裁剪）、`CaptureDraftPolicyTest`（连续下一题不覆盖暂存草稿）。测试结果 XML：`app/build/test-results/testDebugUnitTest/*.xml`。取消竞争、失败不覆盖旧结果两项需要 MainViewModel/Android instrumentation 环境，本机无设备，报告按“未覆盖”处理，未虚列为通过。

## 5. 质量对照

- 固定样例与标准答案位置、人工核对方式：未建立；方案要求的 40 题集未提供，本机也无真实 Key，不能采集新版输出。
- 分组构成与评分规则：未执行；方案要求文字/单图/多图/信息不足分组和人工评分。
- 各模式原始结果位置：无。
- 默认模式选择及依据：新安装默认「标准」；是否改为快速需由 40 题质量门槛决定，本次没有数据，不能宣称默认快速达标。

| 类型 | 样本数 | 基线正确/总数 | 新版正确/总数 | 新增错误 | 信息不足处理 |
| --- | --- | --- | --- | --- | --- |
| 文字 | 0（未执行） | — | — | — | — |
| 单图 | 0（未执行） | — | — | — | — |
| 多图/图表 | 0（未执行） | — | — | — | — |
| 信息不足 | 0（未执行） | 不适用 | 不适用 | — | — |

未逐例列出，因为没有真实运行；不能以 mock 或演示模式结果替代质量验收。

## 6. 性能对照

- 手机/网络/构建类型/服务时段：无设备、无真实 Key，未执行。
- 基线可用性及比较局限：仓库仅有旧 APK，未在同一手机/网络/服务时段复测，不能构成有效基线。
- 原始 CSV 路径：无（未生成）。
- 每组运行次数、失败/超时数：未执行；方案要求 15 题（文字 5、单图 5、多图 5）× 3 次/版本。
- 缓存、冷热态与模式是否一致：未执行，无法声明一致。

| 类型/模式 | 版本 | 成功/总数 | 首次可用答案 P50/P95 | 完整结果 P50/P95 | 图片准备 P50 | 主要错误 |
| --- | --- | --- | --- | --- | --- | --- |
| 文字 | 基线 | 未执行 | — | — | — | — |
| 文字 | 新版 | 未执行 | — | — | — | — |
| 单图 | 基线 | 未执行 | — | — | — | — |
| 单图 | 新版 | 未执行 | — | — | — | — |
| 多图 | 基线 | 未执行 | — | — | — | — |
| 多图 | 新版 | 未执行 | — | — | — | — |

P50 改善百分比、P95 回归、失败请求处理及未达到目标：均无法计算；方案中「完整结果 P50 至少降低 30%、P95 不恶化超过 10%、质量门槛达标」尚未实测，不能写“提速完成”。

## 7. UI 与升级证据

- 浅深色、360/412dp、1.3× 字体截图位置：无（无设备/模拟器截图）。
- 真机录屏位置和操作步骤：无（无设备）。
- 键盘、安全区、答案首屏、长简答、风险提示、触控区域检查：未执行；仅编译通过。
- 旧 APK 覆盖安装到新 APK 后数据、密钥和模型设置结果：未执行；旧 APK 已归档，保留同包名和同一 debug 签名证书，供有设备时做覆盖安装对照。
- 手机显示版本和被安装 APK 哈希对应关系：未安装到手机；静态核验结果：
  - `aapt2 dump badging`：`package: name='com.xjb.medicalquestionai' versionCode='2' versionName='1.1.0'`，`minSdkVersion:'26'`，`targetSdkVersion:'35'`（`docs/evidence/2026-10-29-apk-badging.txt`）
  - `apksigner verify --print-certs`：APK Signature Scheme v2 通过；新旧 APK 证书 SHA-256 均为 `77c3ca8d1a7cf0aca4718b328705bb21833976bc701dad89c11881c83ca1c861`（`docs/evidence/2026-10-29-apk-signature.txt`）
  - 新 APK SHA-256：`c54e5eadc8ed535793ca9dacdcf5c3f9c885682a18d83bc7e603cad53666f747`

## 8. 已知问题及待验收

1. 无 Android 设备/模拟器：`connectedDebugAndroidTest`、C01/C03 真机路径、C08、C10–C12、C15–C21 均未执行；所有 UI 截图、录屏、覆盖安装证据缺失。
2. 无真实 DeepSeek API Key：M3、C03/C04/C05/C07/C13 的真实接口路径未执行；质量对照和速度对照均未执行，不能报告准确率、P50/P95 或 30% 改善。
3. 质量集与性能样例未提供：40 题质量集、15 题×3 次矩阵、原始 CSV 均未生成。
4. 旧模型迁移只做 JVM 单测和代码审查；真机升级（尤其自定义代理、旧数据库、旧 SharedPreferences 共存）未验证。
5. 后端 Node 代理的 Web smoke 仅覆盖非流式 mock/UI；SSE 逐块转发、真实上游的背压、断连取消、长响应超时未验证。
6. 无旧版同设备基线：旧 APK 虽归档，但不能证明同手机、同网络、同服务时段下的速度差异。
7. 本次未创建 git 提交（用户未要求）；交付为工作区改动，审查者需按 `git diff` 核对。

最终声明：本报告为实施者交接，独立验收结论由审查者另行给出。

## 9. 代码评审问题回归（2026-10-29 复审）

| 级别 | 问题 | 处理结果 | 自动证据 |
| --- | --- | --- | --- |
| P1-1 | Web 解析非事务化、失败覆盖旧结果、分享状态未同步 | 已修复：开始解析先保存记录，失败恢复旧结果；分享按钮按可分享状态禁用并给出原因 | `web/test/unit.test.mjs`、`web/test/browser-repair-smoke.mjs` |
| P1-2 | Android 编辑题目图片缺少隐私确认，编辑后可能沿用旧结果 | 已按 2026-10-29 用户要求取消逐题隐私勾选/弹窗；编辑后重新解析仍保留旧结果，成功后才替换 | `ResultScreen.kt` 编译通过；`DeviceFeedbackCoreTest` 覆盖解析边界 |
| P1-3 | 答案预览可能把半截 JSON 当成可展示答案 | 已修复：预览必须通过 `status`、`answer`、`warnings` 等字段和题型校验；不足时只显示“生成中预览”，不冒充最终答案 | `DeviceFeedbackCoreTest` 预览用例 |
| P1-4 | 流式回答未校验 `finish_reason`，截断/过滤回答被当成完成 | 已修复：`QuestionAnalyzer.validateFinishReason` 拒绝 `length`、`content_filter`、空值和非正常结束，并保留用户可读错误 | `DeviceFeedbackCoreTest` finishReason 用例 |
| P2/P3 | 未知模型图片能力、官方模型名迁移、设置启动升级、取消残留、追问输入/预算、错误文案、Web 状态与浏览器返回键等 | 已逐项修复或按平台能力复核；不能自动验证的真机项保持未执行 | `DeviceFeedbackCoreTest`、`SettingsValidationTest`、`ModelsTest`、`ImageEditPolicyTest`、`web/test/unit.test.mjs` |

## 10. 独立验收 F1–F8 回归（2026-10-29 第二轮）

> 本节对应独立验收报告提出的 F1–F8；均为代码与自动测试证据，真机/真实接口结论仍按第 8 节保持未执行。

| 编号 | 原问题 | 处理结果 | 自动证据 |
| --- | --- | --- | --- |
| F1 | 流末尾“预览”口径与最终结果不一致，半截 JSON 可能短暂显示为答案 | `AnswerSemantics` 统一预览/最终校验；`PreviewJsonParser` 解析可读的部分内容并统一做语义校验，不足时仅作为“生成中预览”并提示核对，不写入最终答案 | `DeviceFeedbackCoreTest`：`previewRequiresStatusAnswerAndWarningsFields`、`previewParsesWarningsArray`、`previewRequiresRootObjectToBeClosed`；`web/test/unit.test.mjs` 对应预览用例 |
| F2 | 修改题干/题型/图片后仍把旧答案当当前答案，缺少输入版本关联 | `Question` 增加 `inputRevision`/`answerInputRevision` 与 `isAnswerStale`；文本、题型、图片修改均递增输入版本，成功答案写入当前版本；过期答案保留查看但状态标记“答案待更新”，复制/分享显示过期提示，追问禁用并要求重新解析；DB v7 迁移补列（`questions` 与 `follow_ups` 均含答案版本） | `DeviceFeedbackCoreTest`：`staleAnswerTracksInputRevision`、`settingsMigrationDoesNotReapplyAfterCurrentVersion`；`QuestionDatabase.kt`、`MainViewModel.kt`、`ResultScreen.kt`、`HistoryScreen.kt` 编译通过 |
| F3 | 最终题型与用户选择冲突时静默按模型题型展示 | `AnswerSemantics.evaluate` 校验 `status`/`questionType`/`answer`/`warnings`；用户选定题型时最终 `question_type` 必须一致，自动识别冲突降级为 `needs_confirmation` 并清空答案、附带原因；预览与最终共用同一规则 | `DeviceFeedbackCoreTest`：`parserDowngradesInvalidChoiceAnswers`、`parserRequiresExplicitWarningsArray` |
| F4 | 答案完整性判定不严：`finish_reason=length`/`content_filter`/空值/异常 EOF 仍可能显示完成；Web 在 `[DONE]` 后仍等待连接关闭，缺 `[DONE]` 未失败 | Android `QuestionAnalyzer.validateFinishReason` 只接受 `stop`/`normal`，空值、`length`、`content_filter` 等一律失败；Web `requestChatStream` 区分 `missing_finish_reason`/`incomplete_finish`，收到 `[DONE]` 立即 abort 上游并完成，缺 `[DONE]` 报错；服务端首包/连接超时和断连处理补齐 | `DeviceFeedbackCoreTest`：`finishReasonMustBeExplicitStopOrNormal`；`web/test/unit.test.mjs` 19 项中 finish/`[DONE]` 用例全部通过 |
| F5 | 未知/自定义模型被强制发送 `thinking`/`stream`/JSON mode，且图片能力无高级配置入口 | Android `QuestionAnalyzer` 对非官方 base 或未知模型默认走非流式、不发送 `thinking`/JSON mode，仅使用基础 chat；设置页高级接口提供“自定义模型支持图片/允许流式”能力声明；Web 对未知模型同样不假设能力 | `DeviceFeedbackCoreTest`：`customModelDoesNotGainImageCapabilityByDefault`；`SettingsScreen.kt` 编译通过；`web/test/unit.test.mjs` 自定义能力用例 |
| F6 | 数据库写失败仍显示解析成功 | 最终保存 `repository.update(completed)` 失败时任务置失败并提示保存失败，不显示旧结果冒充成功；图片/题干修改失败不关闭编辑入口并提示重试；Web 原已事务化保存并恢复旧记录 | `DeviceFeedbackCoreTest`/`MainViewModel.kt` 分支审查；Android 构建通过；Web smoke `dbFailureRestored=true` |
| F7 | 指标只进日志，结果不保存 `response_model`/`streamed`/`finish_reason`/`save_status` | `AnalysisMetrics` 新增并持久化 `requestModel`、`responseModel`、`thinkingEnabled`、`streamed`、`finishReason`、`transportClosed`、`saveStatus`、`totalMs`、`answerMode`；结果页元信息展示模型/传输方式/结束原因/耗时，保存失败日志标记 `save_status=failed` | `DeviceFeedbackCoreTest.metricsRoundTripThroughAnalysisResultJson`；`Models.kt`、`QuestionAnalyzer.kt`、`MainViewModel.kt`、`ResultScreen.kt` |
| F8 | 追问内容在失败时被清空，用户需重输 | `ResultScreen` 仅接受回调后清空输入；`sendFollowUp` 在用户 FollowUp 成功插入后调用 `onAccepted`，插入/请求失败保留输入并显示错误 | `MainViewModel.kt`、`ResultScreen.kt` 编译通过；Android 59 项 JVM 测试通过 |

## 11. 第二轮独立验收 R2-01～R2-06 与追问 null 修复（2026-10-29 第三轮修订）

> 对应 `docs/DEVICE_FEEDBACK_INDEPENDENT_ACCEPTANCE_ROUND2_2026-10-29.md` 与 `docs/FOLLOWUP_NULL_ROOT_CAUSE_2026-10-29.md`。所有结论仅覆盖代码与自动测试；真机与真实 API 仍未执行。

| 编号 | 原问题 | 处理结果 | 自动证据 |
| --- | --- | --- | --- |
| R2-01 | 单选题答案 `A、B` 被当作一个选项通过 | `AnswerSemantics.normalizeAnswerOptions` 统一拆分 `A、B`、`A,B`、`A/B`、`AB`、`A和B`；单选/多选按规范化后的选项数量校验；预览、最终解析、选项逐项分析共用同一规则 | `DeviceFeedbackCoreTest` 新增单字符串多选项与预览/最终一致性用例 |
| R2-02 | 非流式 `ChatResponse` 丢弃 `finish_reason`，分析/追问/验证伪造 `stop` | `ChatResponse` 增加 `finishReason`；`chatOnce`、非流式分析/追问、`verifyModel` 读取真实 `finish_reason` 并拒绝空值/非 `stop`；不再伪造完成 | `DeviceFeedbackCoreTest` finishReason 用例；`DeepSeekClient.kt` 编译通过 |
| R2-03 | 重答/修改成功后仍显示旧追问，Android 清理与保存不具事务性 | `FollowUp` 增加 `answerRevision`，DB v7 增加列并按 `questionId+answerRevision` 查询/写入；重答成功生成新答案版本，旧追问不再进入当前会话；Web 编辑/重答成功同步清空 `followUps` | `DeviceFeedbackCoreTest` 追问版本用例；`web/test/unit.test.mjs` 覆盖编辑清空追问 |
| R2-04 | 手选题型掩盖模型缺失/非法题型；选项分析不一致只影响最终、不影响预览；运行中答案卡未明确标记为预览 | `AnswerSemantics` 区分模型题型合法性与手选展示类型，非法/缺失题型仍判失败；选项分析不一致同时降级预览与最终；`PreviewJsonParser` 保留可用部分预览，`ResultScreen` 始终标注“生成中预览” | `DeviceFeedbackCoreTest` 预览/最终题型一致性、选项分析用例 |
| R2-05 | 计时字段不是用户可感知边界，且日志、持久化、UI 含义不一致 | 增加点击、图片就绪、请求体就绪、响应头、首个有效事件、首个内容、首个可用答案、校验、保存等 `clickTo*Ms`/`validationMs`/`saveMs` 字段；未知值保留 null；`totalMs` 从点击到最终保存结束；UI 元信息与日志读取同一 `AnalysisMetrics` | `DeviceFeedbackCoreTest.metricsRoundTripThroughAnalysisResultJson`、计时序列化用例；`Models.kt`、`QuestionAnalyzer.kt`、`MainViewModel.kt`、`ResultScreen.kt` |
| R2-06 | 编辑题目弹窗在保存失败时关闭并丢失草稿 | 编辑弹窗增加 `saving`/`error` 状态，保存成功才回调关闭；保存失败保持输入和弹窗，另行报告错误；重新解析失败不影响已保存旧结果 | `ResultScreen.kt` 编译通过；`MainViewModel.updateQuestionTextAndRetry` 分支审查 |
| Null P1 | 流式 `content`/`reasoning_content` 等显式 null 被转成字符串 `"null"`，历史追问可能污染上下文 | `DeepSeekClient` 与 `Models` 全部改为 `opt(key) as? String` 类型严格读取；空/角色/usage-only chunk 不刷新进度；历史遗留 `"null"` 追问展示为“未返回内容”，发送历史时过滤，失败条目可重试/删除 | `DeviceFeedbackCoreTest` null/空内容用例；Android 59 项 JVM 测试通过 |
| 用户体验调整 | 每次拍照/相册选题都出现隐私勾选 | 按用户要求移除 `NewQuestionScreen` 的隐私勾选、`MainViewModel.startAnalysis` 的提交门槛以及 `ResultScreen` 添加图片的隐私弹窗；保留设置页隐私说明 | 编译通过；`NewQuestionScreen.kt`/`MainViewModel.kt`/`ResultScreen.kt` 代码审查 |

**第三轮自动验证**：Android 59/59 JVM 测试通过（44 `DeviceFeedbackCoreTest` + 4 `ImageEditPolicyTest` + 7 `ModelsTest` + 4 `SettingsValidationTest`），Lint 0 errors / 22 warnings；Web `npm test` 19/19 单元测试通过、浏览器 smoke 通过（含 `followUpsClearedOnEdit: true`）；APK `dist/medical-question-ai-debug.apk` 21,827,839 bytes，SHA-256 `a14450b30a71a93ffc45e93e03caac1eb05ad9ca6502b33fdf0f56e48555b7cf`。

## 12. 第三轮独立验收 R3-01～R3-03 修复（2026-10-29）

> 对应 `docs/DEVICE_FEEDBACK_INDEPENDENT_ACCEPTANCE_ROUND3_2026-10-29.md`。结论仅覆盖代码与自动测试；真机与真实 API 未执行。

| 编号 | 原问题 | 处理结果 | 自动证据 |
| --- | --- | --- | --- |
| R3-01 | 单选双答案、非法题型、选项分析冲突最终降级，但预览仍可能按 `answered` 进入答案卡 | `AnalysisPreview` 增加 `answerInvalid`；预览与最终共用 `AnswerSemantics` 规范化与校验；`toResult()` 在非法时强制 `needs_confirmation` 并清空答案；运行中答案卡只在 `hasAnswerPreview` 为真时展示 | `DeviceFeedbackCoreTest.illegalPreviewIsDowngradedBeforeAnswerCard` 覆盖三类非法预览 |
| R3-02 | 响应头被当作首事件；验证耗时未测实际解析；保存第二笔失败被吞 | 新增 `RequestBodySent` 事件并记录 `requestBodyEnd`；`firstEventAt` 明确排除 `RequestBodySent`/`ResponseStarted`；`validationMs` 只包住 `parseResult`；首笔只写 `saving`，最终指标写入成功后才标 `saved`，第二笔失败会把任务置为失败并尝试落 `failed`；`totalMs` 在最终保存后回填 | `AnalysisMetrics` 序列化用例；`QuestionAnalyzer`/`DeepSeekClient`/`MainViewModel` 编译与 59 项 JVM 测试 |
| R3-03 | 已有 `nullnull…正文` 污染追问仍进入上下文 | `hasRepeatedJsonNullPrefix` 识别开头连续两个以上 `null`（含纯重复 null）；追问上下文裁剪、UI 展示和发送历史都使用该判定；正常 `null hypothesis` 不会被误伤 | `DeviceFeedbackCoreTest.pollutedNullPrefixHistoryIsIsolatedButNormalNullWordIsKept` |

**第三轮最终自动验证**：Android 59/59 JVM 测试通过，Lint 0 errors / 22 warnings；Web 19/19 单元测试和浏览器 smoke 通过；APK 21,827,839 bytes，SHA-256 `a14450b30a71a93ffc45e93e03caac1eb05ad9ca6502b33fdf0f56e48555b7cf`。交付包由本仓库最终 `assembleDebug` 输出直接复制，`cmp` 逐字节一致（见 `docs/evidence/2026-10-29-apk-correspondence.log`）；独立重建若出现不同字节，应优先比较源码提交、构建命令、版本与签名，而不是仅凭哈希判断代码版本。

## 13. 退出解析页与拍摄下一题（2026-10-29 第四轮方案）

> 对应 `docs/RESULT_EXIT_AND_NEXT_CAPTURE_PLAN_2026-10-29.md`。本轮只完成代码、JVM 测试和构建；无设备，真机生命周期、相机和 16 项交互验收未执行。

### 13.1 行为实现

| 编号 | 行为 | 实现结果 | 证据/限制 |
| --- | --- | --- | --- |
| N01 | 纯文字/图片题开始后立即顶部返回 | `ResultScreen` 的 `DisposableEffect`/`LifecycleEventObserver` 调用题目级 `stopQuestionWork`；分析协程取消后在 `NonCancellable` 中落 `interrupted`，不删除旧 `result`；输入图片仍保存在 `question.imagePaths` | 代码审查、`StopPolicy` 单测；真机返回路径未执行 |
| N02 | 已有答案预览但详情仍生成，系统返回/返回手势 | 停止时把当前 `AnalysisPreview.toResult()` 写入 `checkpointJson`；重开显示“未完成预览”，不进入正式答案、复制分享或追问上下文；不自动重发 | `markInterrupted` 初始化路径；真机返回手势未执行 |
| N03 | 正式结果已保存后返回 | 无活动任务时 `stopQuestionWork` 直接返回成功，不写取消/失败状态；已完成 `result`、收藏、模型/用量/耗时不变，无“取消成功”提示 | 代码审查；真机未执行 |
| N04 | 重答中返回（输入未改/已改） | 重答期间数据库保留旧 `completed` 记录；停止只标记本次请求，旧结果不覆盖；已修改输入保持 `isAnswerStale` 并继续显示“答案待更新”，不混入半成品 | `staleAnswerTracksInputRevision` 单测；真机未执行 |
| N05 | 追问等待/已有部分正文时离开 | 追问协程按 `questionId` 取消；已插入的用户文字保留，部分回复标记失败/未完成；重开不自动重发 | `followUpJobs` 取消路径审查；真机未执行 |
| N06 | 切后台/锁屏；旋转/重组/权限弹窗 | `StopPolicy.shouldStopOnLifecycle` 仅在非配置变更的 `ON_STOP`/`ON_DESTROY` 停止；权限弹窗不产生 Activity stop；旋转不触发停止 | `DeviceFeedbackCoreTest.lifecycleStopPolicyDoesNotStopOnRotationOrPermissionDialogs`；真机未执行 |
| N07 | 网络迟到增量/finish/DB 提交边界离开 | `activeRequestId`/`expectedRequestId` 校验迟到事件；最终写入用 `NonCancellable`；首笔写 `saving`、最终写 `saved`，失败落 `failed`，不残留永久 `saving` | `requestIdentityRejectsLateStopFromOldPage`；并发真机未执行 |
| N08 | 生成中点拍摄下一题，双击/连续点 | `nextQuestionPreparing` 为 `AtomicBoolean`；UI `preparingNext` 禁用按钮；并发点击只有一个成功，旧任务停止后只导航一次相机 | 代码审查；无 UI 自动化 |
| N09 | 正式完成后点下一题并拍照 | `prepareNextQuestion` 生成新 `sessionId` 草稿并导航 `camera?source=next&sessionId=...`；拍完回首页新题确认页，旧题图片/答案/ID 不变 | 代码审查；相机未执行 |
| N10 | 相机取消、拒绝权限、导入失败 | 新题流程只重置草稿并导航，不插入 `Question`；相机取消/权限拒绝回首页确认页；导入失败显示错误，不产生空历史、不追加旧题 | 代码审查；相机未执行 |
| N11 | 相机/压缩回调延迟时返回或开启另一个草稿 | `addCameraImage(uri, file, sessionId, captureToken)` 同时校验会话和拍摄令牌；退出/返回/进入后台会 invalidate 令牌，迟到回调在导入前后都检查令牌，丢弃图片和临时文件且不执行导航 | `CaptureDraftPolicyTest` 覆盖草稿保留策略；相机真机路径未执行 |
| N12 | 首页已有草稿，再从历史题拍下一题 | `_parkedDraft` 只保留一份既有草稿；当前草稿也有内容时 `prepareNextQuestion` 明确拒绝并提示“先恢复或清空”，不静默覆盖；恢复草稿时与当前草稿互换保存 | `CaptureDraftPolicyTest.parkedDraftIsNeverSilentlyReplacedByAnotherNonEmptyDraft`；无设备 |
| N13 | 连续完成三道题、三次拍下一题，再系统返回 | `AppNavigation` 使用 `popUpTo("result/{questionId}") { inclusive = true }` 再进相机；结果页每次新 ID，不重复开相机或重发旧任务 | 代码审查；真机未执行 |
| N14 | 360/412dp、深浅色、1.3× 字体、键盘及长追问 | `NextQuestionButton` 使用 `navigationBarsPadding`、`heightIn(min=48.dp)`、Material 主题色；底部追问栏同样处理系统安全区；文案单行可读 | 编译通过；真机视觉未执行 |
| N15 | 停止保存失败、取消超时、新草稿准备失败 | `stopQuestionWork` 用 `withTimeoutOrNull(2s)`，超时返回 `timedOut`；`prepareNextQuestion` 返回错误并保留旧任务，不导航；错误在结果页 banner 展示 | 代码审查；无设备 |
| N16 | 原版数据升级、进程在生成中被终止后重开 | DB v8 `MIGRATION_7_8` 增加 nullable `checkpointJson`；解析中每 500ms 落一次检查点；初始化把遗留 `analyzing/receiving/validating` 标为 `interrupted` 并保留 checkpoint，不自动联网 | 编译通过；instrumented 迁移未执行 |

### 13.2 本轮自动验证

- Android：`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest` BUILD SUCCESSFUL（2026-10-29 21:45 +08:00）；64/64 JVM 测试通过（47 `DeviceFeedbackCoreTest` + 4 `ImageEditPolicyTest` + 7 `ModelsTest` + 4 `SettingsValidationTest` + 2 `CaptureDraftPolicyTest`），Lint 0 errors / 22 warnings。
- Web：`npm test` 19/19 单元测试通过，浏览器 smoke 通过（`followUpsClearedOnEdit: true`）。
- APK：`dist/medical-question-ai-debug.apk` 21,842,313 bytes，SHA-256 `c54e5eadc8ed535793ca9dacdcf5c3f9c885682a18d83bc7e603cad53666f747`；与 `app/build/outputs/apk/debug/app-debug.apk` 逐字节一致。
- 未执行：真机返回/后台/锁屏/旋转/权限弹窗、相机取消、连点、16 项交互验收、`connectedDebugAndroidTest`、旧库升级实测、真实 API 和性能对照。

### 13.3 第五轮复核修复（NC-01～NC-03）

| 编号 | 问题 | 修复 | 证据/限制 |
| --- | --- | --- | --- |
| NC-01 P1 | 相机迟到回调仍可能加图并跳首页 | 拍照开始生成 `captureToken`；顶部返回、系统返回、`ON_STOP` 和页面销毁（非配置变更）都会 invalidate；`addCameraImage` 在导入前、导入后和 `onComplete` 前检查令牌，失效时删除临时/已导入文件且不导航 | 代码审查 + 生命周期编译；真机相机未执行 |
| NC-02 P2 | 退出路径未传 `expectedRequestId` | `ResultScreen` 从当前活动 `AnalysisTaskState.requestId` 取值，顶部返回、生命周期退出和“拍摄下一题”均传入 `stopQuestionWorkAsync`/`prepareNextQuestion`；请求身份不匹配时不会停掉同题新请求 | `DeviceFeedbackCoreTest.requestIdentityRejectsLateStopFromOldPage`；真机竞争未执行 |
| NC-03 P2 | 连续两次“拍摄下一题”覆盖暂存草稿 | 新增 `canParkDraft`：当前草稿与已有暂存草稿都非空时拒绝并提示先恢复/清空，不覆盖、不删除图片；空草稿可继续进入下一题 | `CaptureDraftPolicyTest`；无设备 |

**结论**：代码与自动测试覆盖本轮方案的可测逻辑；真机生命周期和相机路径仍需独立验收，不能据本报告宣称 16 项全部通过。
