# 真机反馈改造：第二轮独立验收

日期：2026-10-29（Asia/Shanghai）。审查对象：当前工作区、实施交接报告第 10 节和 `dist/medical-question-ai-debug.apk`。上轮报告保留，不覆盖。审查期间未修改业务代码、实现测试或交付 APK；本轮仅新增验收文档和独立探针/证据。

## 1. 验收结论

**暂不通过，继续整改。** 第二轮确实修好了多处旧问题，自动检查也复跑通过；但仍有 **2 项 P1、4 项 P2**，见 R2-01～R2-06。另有交付包与独立重建包哈希不一致的待核验事项。

本结论区分三件事：编译/自动检查通过；代码缺陷是否闭环；真机、真实接口、质量与速度目标是否达标。后两者不能用测试数量替代。

## 2. 独立复跑结果

| 检查 | 本轮结果 | 证据与边界 |
| --- | --- | --- |
| Android `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` | BUILD SUCCESSFUL，84 个任务执行 | `docs/evidence/independent-round2-2026-10-29/android-build.log` |
| JVM 测试 XML | 52/52，0 failures/errors/skipped | 37 DeviceFeedbackCore、4 ImageEditPolicy、7 Models、4 SettingsValidation |
| Lint XML | 0 errors / 22 warnings | 静态检查，不证明界面实际可用 |
| `npm --prefix web test` | 19/19 单测通过，浏览器 smoke `ok:true` | `web-tests.log`；本地 mock，不是真实 DeepSeek |
| 原独立 Web SSE 反例复跑 | 缺 `[DONE]` 的解题/追问均失败；`[DONE]` 后不等待 HTTP EOF | `web-stream-probes.log`；保持连接场景约 1.2ms 返回 |
| 本地 Node 代理逐块转发/取消 | 通过 | `proxy-probes.log`；首块约 18ms，结束约 623ms；客户端取消后上游约 4.9ms 关闭。这些是本地合成网络数据，不能作为产品提速数据 |
| 新增 Android 编译产物探针 | 复现 R2-01、R2-02、R2-04 | `android-probes.log`；使用本轮编译出的实际类，OkHttp 拦截器返回合成响应，不访问外网，不使用真实 Key |
| 新增浏览器成功编辑/重答探针 | 复现 R2-03 | `web-browser-probes.log`；真实 headless 浏览器、本地数据库与 mock 上游 |
| `git diff --check` | 退出 0 | 有换行格式提示，无 diff 空白错误 |
| Android 设备 | `adb devices` 空 | 未运行 connectedDebugAndroidTest、未安装 APK |

Android 探针源文件已保存于证据目录。它仅在合成测试中为无图片场景创建未初始化的 ImageStore 占位对象，该对象未被访问；网络调用由 OkHttp interceptor 在进程内返回固定响应。探针不能证明相机、存储或真机生命周期行为。

## 3. F1–F8 回归判定

| 原项 | 本轮判定 | 已确认的改进与剩余问题 |
| --- | --- | --- |
| F1 预览完整性 | 部分修复 | 原双数组答案、非法 status、错类型 warnings 已被拦截；预览与最终仍有不同校验，见 R2-01/R2-04 |
| F2 输入/答案/追问关联 | 部分修复 | Android 增加输入版本、过期标签、复制/分享提示及 UI 追问禁用；Web 成功编辑仍继承旧追问，Android 清理追问与答案保存非原子，见 R2-03 |
| F3 题型校验 | 核心反例已修，边界未闭环 | 显式 SINGLE 对 multiple+A/B 已降级且清空答案；简答缺正文已降级；单字符串双选项仍按单选成功，见 R2-01；缺失/非法类型见 R2-04 |
| F4 正常结束判定 | Web 原问题通过；Android 非流式遗漏 | Web 三个独立反例全部通过；Android 自定义默认非流式分支伪造 stop，见 R2-02 |
| F5 自定义能力 | 原问题通过（代码/合成请求） | 实际捕获未知接口请求 `stream=false`，没有 thinking 和 response_format；已有图片/流式声明入口。真实兼容网关未测试 |
| F6 保存失败 | 最终提交分支静态通过；编辑体验仍有问题 | 最终保存异常不会走 COMPLETED 分支；尚无 Android Room 故障注入测试。报告所说“修改失败不关闭编辑入口”不成立，见 R2-06 |
| F7 性能埋点 | 未闭环 | 元信息持久化已补，但上轮指出的时间边界仍未改正，见 R2-05 |
| F8 追问准入丢稿 | 原问题静态通过 | onAccepted 在用户消息成功入库后调用，无 Key/其他解析拒绝不会清输入；网络失败后输入框仍为空，但用户消息已入库。报告必须区分“准入失败保留输入”和“请求失败保留历史” |

“静态通过”表示代码分支已修正，不等于 Android 设备操作或故障注入已执行。

## 4. 需要整改的问题

### R2-01 · P1：单字符串中的多个选项仍被当成合法单选

位置：`app/src/main/java/com/xjb/medicalquestionai/data/AnswerSemantics.kt:69–84,111–113`；`data/AnalysisResultParser.kt:101–103`。

独立探针输入：

```json
{"status":"answered","question_type":"single","answer":["A、B"],"warnings":[]}
```

实际输出：

```text
choice_delimiter_multiple_letters: preview=answered [A、B] final=answered [A、B]
```

数组长度虽然是 1，但内容是两个选项。`optionLetter()` 允许字母后的 `、` 加任意正文，因而把整段 `A、B` 当成 A；校验通过后又保留原字符串，最终仍显示 A、B，并保存 answered。这个格式属于模型常见的分隔符输出，不应绕过单选约束。

**修复要求：** 在共享层统一解析/规范化选项，不用“识别首字母”代替答案数量校验。明确兼容 `A. 选项正文` 的规则；对 `A、B`、`A,B`、`A/B` 等多字母形式，要么规范化后发现单选多答案并降级，要么直接拒绝该格式。预览、最终解析、选项分析校验使用同一规则。

**必须复验：** 单选 `['A','B']`、`['A、B']`、`['A,B']`、`['A/B']` 均不能成为 answered 或确定答案预览；有效单字母保持正常；多选分隔符与重复项按明确规范处理。断言最终 status/展示答案，不能只检查警告文字。

### R2-02 · P2：非流式接口的真实 finish_reason 被丢弃并改成 stop

位置：`data/DeepSeekClient.kt:36–40,269–280`；`data/QuestionAnalyzer.kt:91–102,204–209`。

`ChatResponse` 没有结束原因，`chatOnce()` 只读 message/content、usage、model。解题非流式分支直接赋值 `finishReason="stop"`，追问非流式分支直接发出 `Finished("stop")`。因此 `validateFinishReason()` 即使正确，也检查不到接口实际返回的异常值。

本轮对实际 `QuestionAnalyzer.analyzeStream()` 注入四种非流式响应，正文均为合法 JSON：

```text
nonstream_finish_stop:           COMPLETED status=answered recordedFinish=stop
nonstream_finish_length:         COMPLETED status=answered recordedFinish=stop
nonstream_finish_content_filter: COMPLETED status=answered recordedFinish=stop
nonstream_finish_null:           COMPLETED status=answered recordedFinish=stop
```

未知接口默认非流式已经生效，捕获请求为 `stream=false thinking=false jsonMode=false`。所以这是新增默认路径上的完整性漏洞，还会污染 F7 的持久化指标；不能在报告中写“Android 空值、length、content_filter 一律失败”。

**修复要求：** ChatResponse 传递真实 finish_reason，解题、追问、实际模型验证都按统一正常结束规则判定；缺字段保持未知并报错，不伪造 stop。区分“非流式响应体完整读完”和“SSE 收到 DONE”的记录语义。

**必须复验：** 经实际客户端/Analyzer 的非流式 stop 正常；length、content_filter、缺失/空结束原因均失败；解题与追问都覆盖。仅直接测试 validateFinishReason 函数不足以证明调用链正确。

### R2-03 · P1：Web 修改题目并重答成功后，旧追问仍混入新题上下文

位置：`web/assets/app.js:1379,1439–1452,1554`；Android 相关风险：`MainViewModel.kt:588`。

网页创建编辑后的临时记录时，使用 `followUps: editingQuestion?.followUps || []`，成功结果再展开该记录，因而继承旧追问。后续发送追问又把全部 followUps 作为 prior，旧输入/旧答案的对话进入新题请求。

独立浏览器探针先建立一对有明确 ID 的旧题追问（user/assistant），再通过 UI 修改题干并正常解析成功。实际落库：

```text
successful_edit_retains_old_followups:
oldCount=2, inheritedCount=2,
questionText=第二轮独立合成测试：新题目，与旧追问不同,
status=completed
```

失败回滚保留旧题及其追问是正确的；成功提交新输入/新答案后继续把旧对话当新题上下文则不正确。当前 smoke 只覆盖失败重答，未覆盖成功后的关联切换。

Android 正常成功路径已经删除旧追问，但 `runCatching { deleteForQuestion(...) }` 吞掉失败，且删除在新答案保存之后、不是一个事务。若答案写入成功、追问清理失败，仍会报告完成，旧追问没有版本字段，后续会再次被读取。此 Android 异常路径为静态确认，本轮没有 Room 故障注入证据。

**修复要求：** 对话明确绑定输入/答案版本。成功新答后，新版追问上下文为空或只读取本版本；旧对话可归档查看。失败/取消保留旧版。Android 使用事务或版本过滤保证答案已切换而旧对话清理失败时也不会串用；在 ViewModel/请求构建层执行过期答案禁追问，不能只依赖按钮状态。

**必须复验：** 两端“Q1/R1/H1 → 修改 Q2 → 重答成功 R2 → 追问”请求不含 H1；失败/取消仍保留原版；注入 Android 追问清理失败后仍不得把 H1 当新版历史。保留现有失败重答测试。

### R2-04 · P2：预览与最终的合法性判定仍有分叉

位置：`data/AnswerSemantics.kt:58–65`；`data/PreviewJsonParser.kt:67–98`；`data/AnalysisResultParser.kt:57–63`。

独立探针：

```text
invalid_type_manual_single: preview=answered [A] final=answered [A]
missing_type_manual_single: preview=BLOCKED final=answered [A]
option_analysis_mismatch:  preview=answered [A] final=needs_confirmation [A]
```

第一例用户选单选，模型 `question_type="banana"`；`typeValid` 因用户选定类型直接变成 true，所以非法字段未被阻止。第二例完全缺失 question_type，预览拒绝、最终却接受。第三例答案 A，但 option_analysis 只包含 B：最终解析额外降级，预览没有对应校验，先展示为 answered。

这说明抽出 AnswerSemantics 并未让所有影响“可确定答案”的判定真正共享。用户明确选择题型可以决定输出应采用什么题型，但不能把模型字段非法/缺失自动当成响应格式合格。

**修复要求：** 分开记录模型字段是否有效、与用户类型是否匹配、最终采用什么题型；影响最终降级的语义检查也用于预览（或预览明确降级为待核对，不能标记为有效答案）。保留旧历史宽松读取能力，但新模型输出应走严格路径。

**必须复验：** 手动题型下缺失/非法字段、类型冲突、答案与选项分析不一致、AUTO 的合法与非法类型；比较预览与最终状态，确保不会先确定后否定。答案卡在请求终止/落库前始终标明“预览，仍在生成/核对”。当前 ResultScreen.kt:250 附近的预览提示受 `!isComplete` 控制，而 feed 只返回 isComplete=true，该提示分支仍不可达；进度卡虽另有预览文案，答案卡自身仍应明确标记。

### R2-05 · P2：F7 增加了元信息，但原来错误的计时边界未修复

位置：`MainViewModel.kt:391,429–465,504–512`；`data/QuestionAnalyzer.kt:40,105,148`；`data/DeepSeekClient.kt:205`。

上轮 F7 原问题是“响应头误记首事件、模型等待误记上传、起点晚于点击”。交接报告第 10 节将它改述为“指标只进日志”，因此新增 metrics 的工作没有覆盖原问题：

- `ResponseStarted` 仍在 HTTP 响应头到达后发出，WaitingForModel 随即记录 first_event_ms；此时可能一个有效 SSE 事件都没有。
- 非流式分支甚至在调用 chatOnce 之前就发出 WaitingForModel；该路径的 first_event_ms 也不是接口首事件。
- uploadDoneMs 仍等 Stage(2)，即首个正文、或详细模式首段 reasoning 才记录，包含上传后的模型等待。
- startedAt 仍在初始数据库写入/导航之后，不是按钮点击时刻。
- metrics.totalMs 用 System.currentTimeMillis，起点是 Analyzer 调用，终点早于最终保存；Question.durationMs 和日志另有一套时长，也不包含最终保存完成时间。字段没有说明这些差异。
- request_bytes、独立首个 content 等必要字段仍未补齐。

**修复要求：** 用单调时钟分别记录点击、图片准备完成、实际请求体发送完成（例如 OkHttp requestBodyEnd）、响应头、首个有效 SSE 事件、首个 content、首个可用答案、结果验证及落库完成。测不到的值标未知，不能借用模型首输出充当上传结束。日志、持久化与 UI 对字段含义一致；总耗时终点明确。新增响应模型/保存状态字段应保留。

**必须复验：** 可控合成上游分别延迟响应头和正文，断言 headers/event/content 不混淆；覆盖真正 requestBodyEnd，及数据库保存延迟。此项没有修好前，日志不能作为原方案 P50/P95 或上传瓶颈的可靠证据。

### R2-06 · P2：题干保存失败前已关闭编辑弹窗，修改草稿丢失

位置：`ui/screens/ResultScreen.kt:403–412,1125–1126`；`MainViewModel.kt:761–773`。

点击“保存并重新解析”立即执行 `showEditDialog=false`，然后才调用异步数据库保存。编辑文字只存在弹窗内的 remember 状态中；保存失败虽然会显示错误，但弹窗已移除，重新打开读取的还是数据库旧文字，用户的修改稿丢失。

这与实施报告第 10 节 F6 所说“图片/题干修改失败不关闭编辑入口并提示重试”不符。本问题由 Compose 状态及异步调用顺序静态确认；本轮没有真机 UI 故障注入。

**修复要求：** 保存期间保留弹窗和编辑草稿，收到明确保存成功回调后再关闭；保存失败留在原编辑状态并显示错误。也可以将草稿提升到 ViewModel/rememberSaveable，但不能失败后恢复成旧题干。区分“本地修改已保存、随后重答失败”和“本地修改根本未保存”。

**必须复验：** 注入题干 update 异常，长文本和新题型仍可见、可重试；成功保存后才关闭；随后网络失败时保留已保存的新输入并标记旧答案过期。

## 5. APK 与交付记录

交付 APK 核验：

- 路径：`E:\souti\repo\dist\medical-question-ai-debug.apk`
- 大小：21,810,362 bytes。
- SHA-256：`bd029d5f2fa51064b86a1aef425cadbcff1582043efc26564d5260b15b3a632c`，与报告一致。
- 包名 `com.xjb.medicalquestionai`；versionCode 2 / versionName 1.1.0；minSdk 26 / targetSdk 35。
- APK v2 签名验证通过；证书 SHA-256 `77c3ca8d1a7cf0aca4718b328705bb21833976bc701dad89c11881c83ca1c861`。

**交付对应性待核验：** 本轮全量任务独立重建 APK 为 21,376,680 bytes，SHA-256 为 `f6adb67b83a23c086201abc1bef0515ecf73fecddacca71fc261a144540d6c90`；再次单独 `assembleDebug --rerun-tasks` 仍得到同一重建哈希。它不同于 dist 交付包；ZIP 解压后的对比定位到 classes5.dex 字节不同。仅凭这点不能断言 dist 是旧代码，也不能认定两包代码语义一致；本轮没有反编译比较，保留为未证实事项。

下轮修复后从最终构建输出复制交付 APK，当场验证输出包和 dist 的 SHA-256 一致，并更新报告。不要直接覆盖本轮交付包而不留下归档/变更记录。版本仍为 2/1.1.0，手机上单看版本无法区分整改轮次，应记录安装包哈希或明确构建标识。

## 6. 报告需要更正的表述与未执行项

1. F1/F3 的“统一规则”“字段非法一律阻止”应改为实际覆盖情况，补 R2-01/R2-04 反例。
2. F2 应补网页成功重答的关联切换，Android 追问清理异常不能静默吞掉后仍宣称上下文一致。
3. F4 应明确流式与非流式；当前非流式缺结束原因仍成功，不能写 Android 一律失败。
4. F6 “修改失败不关闭编辑入口”与当前实现相反。最终结果保存异常分支改正应如实保留为代码证据，不能用 Web 数据库 smoke 证明 Android 故障恢复。
5. F7 必须对应上一轮原始问题，不能将“时间含义错误”改述为“只进日志”。
6. F8 当前是“消息入库后清输入”。若后续请求失败，输入框不会恢复，但原消息已在历史里；这满足上轮的安全准入要求，却不是报告宣称的“所有插入/请求失败保留输入”。按实际产品约定修改文案或补恢复逻辑。
7. 设备/模拟器、真实 Key、真实接口、覆盖升级、40 题质量集、15 题×3 次/版本速度比较、360/412dp/字体缩放/浅深主题等仍未执行。不能据本报告宣布真机 UI、正确率或“提速 30%”验收通过。
8. 数据库 v6 的迁移代码已检查且编译通过；真机旧数据迁移和异常状态恢复仍需执行。新增 5 个 JVM 测试并未覆盖完整 ViewModel、Room 故障与 UI 交互。

## 7. 可直接交给实施模型的整改指令

请以本报告 R2-01～R2-06 为本轮范围继续修复，保留原计划及两轮验收报告，不把验收文档改成“已通过”：

1. 共享答案规范化，阻止单字符串 A、B 等多答案绕过单选约束；预览和最终同规则。
2. ChatResponse 传递真实结束原因，非流式解题/追问/模型验证均验证；禁止手填 stop。
3. 两端追问绑定输入/答案版本：成功新答不混入旧对话，失败保留旧版，清理失败也不串用；补成功重答浏览器测试及 Android 仓库故障测试。
4. 补齐严格模型字段、选项一致性校验；旧历史兼容与新响应验证分开；答案卡持续标记预览状态。
5. 按真实事件修正性能时间边界，统一日志/落库/UI 含义；缺失值记未知。补可控延迟测试，尚无实测时不宣称速度达标。
6. 保存题干失败不关闭弹窗、不丢草稿，明确保存成功回调和网络重答失败的区别。
7. 补针对性回归，重跑现有 Android/Web 检查；报告逐项标明实际执行与未执行。保存最终构建输出，交付包与输出包哈希一致，并更新交付记录与整改轮次标识。

提交下一轮时提供逐项变更位置、反例测试结果、APK 哈希；仍缺设备/API Key的项目继续标未执行，不能用 mock 替代。
