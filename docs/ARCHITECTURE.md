# 架构说明

## 1. 总体结构

应用采用单 Activity + Compose Navigation 的分层结构：

```text
UI (Compose Screens)
   │  StateFlow / 事件回调
MainViewModel
   │
   ├── QuestionAnalyzer ── DeepSeekClient ── OkHttp ── API
   ├── QuestionRepository ── Room
   ├── FollowUpRepository ── Room
   ├── ImageStore ── 应用私有文件目录
   └── SettingsRepository / SecureKeyStore
```

- **UI 层**只负责渲染 `UiState` 与转发用户操作，不直接访问数据库或网络。
- **MainViewModel** 是唯一业务编排点：校验输入、写入数据库、调用分析器、更新状态、保存结果。
- **data 层**按职责拆分：领域模型、Room、图片文件、DeepSeek 客户端、提示词解析、设置存储。

## 2. 关键数据模型

### AnalysisResult

`AnalysisResult` 是解析结果的核心结构，持久化为 `answer_json`：

| 字段 | 含义 |
| --- | --- |
| `status` | `answered` / `needs_confirmation` / `out_of_scope` / `error` |
| `questionType` | `auto` / `single` / `multiple` / `fill` / `short` |
| `recognizedQuestion` | OCR/理解后的题干 |
| `imageObservations` | 逐图观察：图序号、观察内容、不确定点 |
| `optionAnalyses` | 逐项分析：选项、判定、理由 |
| `answer` / `answerText` | 答案列表与展示文本 |
| `reasoningSteps` | 分步解题思路 |
| `keyPoints` / `pitfalls` | 关键知识点与易错点 |
| `confidence` / `warnings` | 置信度与需要人工核对的风险提示 |
| `usage` | Prompt/Completion/Total token 用量 |

`AnalysisResult.fromJson()` 兼容常见变体键名（如 `question`、`type`、`input_tokens`、对象形式的选项分析），并对缺失字段做降级处理，避免旧数据导致页面崩溃。

### Question / FollowUp

`QuestionEntity` 保存题目主记录；`FollowUpEntity` 通过 `questionId` 关联追问，按 `createdAt` 升序读取。删除题目时会先删除其追问与本地图片，再删除主记录。数据库当前为 version 8：`MIGRATION_1_2` 创建追问表，`MIGRATION_2_3` 增加 `isEphemeral` 列，`MIGRATION_3_4` 增加 `mode`、`durationMs`、`promptVersion`，`MIGRATION_4_5` 增加实际请求使用的 `baseUrl` 快照，`MIGRATION_5_6` 增加答案输入版本 `inputRevision`/`answerInputRevision`，`MIGRATION_6_7` 增加追问答案版本 `answerRevision`，`MIGRATION_7_8` 增加未完成预览 `checkpointJson`（nullable）；不使用破坏性迁移，后续版本升级需继续提供显式迁移。

## 3. 一次解析的完整流程

1. `NewQuestionScreen` 收集题型、文字、图片和做题模式；按 2026-10-29 用户要求不再逐题勾选隐私。
2. `MainViewModel.startAnalysis()` 校验：至少一张图或一段文字、最多 9 张图、带图时必须使用支持图片的模型；隐私说明保留在设置页，不再阻断提交。
3. 先插入状态为 `analyzing` 的 `Question`，同时固定 `model`、`baseUrl`、`mode`、`promptVersion` 请求快照；默认进入历史，关闭「保存解析历史」时标记为临时记录。
4. `QuestionAnalyzer.analyzeStream()` 生成按模式裁剪的系统提示词与用户提示词，并把 `thinking` / `reasoning_effort` / `max_tokens` 显式写入请求。
5. `ImageStore.readBase64()` 读取压缩后的 JPEG，控制单次请求总大小不超过 18 MB。
6. `DeepSeekClient.stream()` 通过 OkHttp 异步调用 `POST {baseUrl}/chat/completions`（`stream: true`），`SseParser` 增量处理 UTF-8 分片、CRLF、注释、`[DONE]`；`PreviewJsonParser` 从增量 JSON 中提取答案预览。
7. 取消通过 `call.cancel()` 中断连接；`MainViewModel` 用请求 ID 隔离事件，取消/失败只更新当前任务，不覆盖已有完成结果。
8. `QuestionAnalyzer.parseResult()` 解析 JSON、规范化题型、补充警告；完成后在同一事务快照上写回 `completed`、实际模型、模式、耗时、prompt 版本并更新结果页状态。

### 退出解析页与拍摄下一题

- 解析任务按 `questionId` 绑定；结果页 `DisposableEffect` + `LifecycleEventObserver` 在返回、`ON_STOP`/`ON_DESTROY`（非配置变更）时调用 `stopQuestionWork()`，取消该题分析协程和追问协程。
- 取消后通过 `NonCancellable` 落库：已有完整 `result` 保留；本次未完成内容写入 nullable `checkpointJson`，状态 `interrupted`。结果页只把 checkpoint 标为“未完成预览”，不自动重试，也不允许复制/分享/追问。
- 旋转和权限弹窗不触发停止：`StopPolicy.shouldStopOnLifecycle` 排除 `isChangingConfigurations` 的 `ON_STOP`/`ON_DESTROY`。
- 结果页底部固定“拍摄下一题”；`prepareNextQuestion()` 先有界等待旧任务停止，再创建独立 `sessionId` 草稿并进入新题相机，避免旧答案页和串图。
- 进程被杀后的 `analyzing/receiving/validating` 在 ViewModel 初始化时统一标记为 `interrupted`，checkpoint 保留供预览。

### 模型、模式与设置迁移

- 官方模型表：`deepseek-flash`（文字+图片）、`deepseek-v4-pro`（仅文字）。官方地址判定按主机 `api.deepseek.com` 与根路径/`/v1` 精确匹配，自定义代理不触发生效。
- 三档模式：快速 `thinking.type=disabled`、3072 tokens、120s；标准 `thinking.type=enabled` + `reasoning_effort=low`、8192 tokens、180s；详细 `reasoning_effort=high`、16384 tokens、240s。默认标准，快速是否设为默认取决于 40 题质量门槛。
- `SettingsMigrations` 对旧 `deepseek-chat` → flash/快速、`deepseek-reasoner` → flash/详细、旧 v4 别名 → flash 做幂等迁移；仅官方 Base URL 自动迁移，自定义网关保留用户模型名。
- `AnalysisTaskState` 保存本次请求的阶段、预览、思考摘要和状态；`MainViewModel` 只在请求 ID 与当前任务匹配时更新，避免旧请求回写。

### 模型输出契约

系统提示词要求只返回 JSON：

```json
{
  "status": "answered",
  "question_type": "single",
  "recognized_question": "……",
  "image_observations": [
    {"image_index": 1, "observation": "……", "uncertainty": "……"}
  ],
  "option_analysis": [
    {"option": "A", "verdict": "正确", "reason": "……"}
  ],
  "answer": ["A"],
  "answer_text": "A",
  "reasoning_steps": [{"title": "……", "detail": "……"}],
  "key_points": ["……"],
  "pitfalls": ["……"],
  "confidence": "high",
  "warnings": []
}
```

解析器容忍 Markdown 代码围栏、字段别名、字符串形式的步骤和对象形式的选项表；对明显不属于医学学习范围的输入，模型返回 `out_of_scope`，界面以“非医学题目”卡片礼貌说明，不展示把握程度。

### 应用内更新

- `UpdateViewModel` 在首页启动和设置页触发检查；仅在无分析任务时展示更新提示，静默提示每 24 小时最多一次。
- `UpdateRepository` 读取 `releases/latest`，只接受非 draft/prerelease，并要求同一 Release 唯一包含 `update.json` 与 `medical-question-ai.apk`。
- `UpdateManifestParser` 严格校验 schemaVersion/applicationId/versionCode/minSdk/apkAssetName/apkSizeBytes/apkSha256/signingCertSha256/commitSha 等字段，JSON null 不转为字符串。
- 下载到 `cache/updates`，校验文件大小与 SHA-256；`ApkVerifier` 再核对包名、versionCode、minSdk 和签名证书是否与已安装应用一致；`UpdateInstaller` 通过 FileProvider 调系统安装器。CI 发布流程见 `docs/APP_UPDATE_RELEASE_GUIDE.md`。

## 4. 图片流水线

- 系统 Photo Picker 返回 `content://` URI，不申请存储权限。
- 解码时按最长边采样，避免 OOM。
- 读取 EXIF 方向并旋转，统一转正。
- 最长边压缩到 1800 px，JPEG 质量 86，保存至 `filesDir/question_images`。
- 旋转默认通过临时文件原子替换原文件，路径保持不变；裁剪可选择替换原图，或保留原图并新增一张局部图（受 9 张上限约束）。
- 图片仅在分析时读取为 Base64；历史记录保存本地路径，不保存 Base64。

## 5. 安全与隐私

- API Key：`EncryptedSharedPreferences` + Android Keystore 派生密钥，AES-256-GCM。
- 不记录请求头、API Key 或完整图片到日志。
- 按 2026-10-29 用户要求不再逐题勾选隐私确认；设置页与首页提示不要上传患者身份信息。
- Android 网络仅允许 HTTPS：自定义 Base URL 在设置页和 `DeepSeekClient` 两处校验，HTTP 地址会被拒绝；不配置明文流量例外。
- 网页版为独立实现，API Key 默认明文存于浏览器 localStorage，请求经本地 Node 代理转发；其边界不在 Android APK 的安全承诺内，详见 `docs/PRIVACY.md`。
- 导出/分享只包含文字答案，不包含本地图片路径。

## 6. 错误处理

| 场景 | 处理 |
| --- | --- |
| 未配置 Key | 首页横幅 + 设置页引导 |
| 401/402/429 | 映射为中文错误，提示检查 Key、余额、频率 |
| 网络超时 | 保留草稿，显示重试按钮 |
| JSON 解析失败 | 抛出中文错误提示重试，并将原始响应前 1000 字保存在 `ApiException.raw` |
| 图片过大/模糊 | 导入时提示，分析前限制总大小 |
| 题目超出医学学习范围 | 返回 `out_of_scope` 并说明原因 |

## 7. 可扩展点

- `DeepSeekClient` 可替换为任意 OpenAI 兼容服务。
- `QuestionAnalyzer` 的提示词与 JSON 契约集中管理，便于版本化。
- Room 迁移可新增 `AnalysisVersion` 字段，支持结果重放。
- 可增加导出 Markdown/Anki、题目集、错题本等模块，不影响现有分析链。
