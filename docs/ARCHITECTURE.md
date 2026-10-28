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

`QuestionEntity` 保存题目主记录；`FollowUpEntity` 通过 `questionId` 关联追问，按 `createdAt` 升序读取。删除题目时会先删除其追问与本地图片，再删除主记录。数据库当前为 version 3：`MIGRATION_1_2` 创建追问表，`MIGRATION_2_3` 增加 `isEphemeral` 列（`0` 表示进入历史，`1` 表示关闭历史保存后的临时结果）；不使用破坏性迁移，后续版本升级需继续提供显式迁移。

## 3. 一次解析的完整流程

1. `NewQuestionScreen` 收集题型、文字、图片、隐私确认。
2. `MainViewModel.startAnalysis()` 校验：至少一张图或一段文字、最多 9 张图、必须确认隐私。
3. 先插入状态为 `analyzing` 的 `Question`；默认进入历史，关闭「保存解析历史」时标记为临时记录，仍可在结果页查看但不进入历史列表。
4. `QuestionAnalyzer` 生成系统提示词与用户提示词。
5. `ImageStore.readBase64()` 读取压缩后的 JPEG，控制总大小不超过 18 MB。
6. `DeepSeekClient.chatConversation()` 调用 `POST {baseUrl}/chat/completions`。
7. `QuestionAnalyzer.parseResult()` 解析 JSON、规范化题型、补充警告。
8. 更新记录为 `completed`，清空草稿并导航到 `result/{id}`；失败则记录 `failed` 与错误信息，保留草稿供重试。

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
- 分析前必须勾选隐私确认；界面明确提示不要上传患者身份信息。
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
