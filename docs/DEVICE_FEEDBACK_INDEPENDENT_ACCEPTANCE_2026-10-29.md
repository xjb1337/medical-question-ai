# 真机反馈修改：独立验收结果

日期：2026-10-29（Asia/Shanghai）  
审查目录：`E:\souti\repo`  
对照：`DEVICE_FEEDBACK_PLAN_2026-10-29.md`、`DEVICE_FEEDBACK_IMPLEMENTATION_REPORT.md`。  
代码基线：Git HEAD 仍为 `4b6f3ee`，本次实现位于工作区，未形成新提交。以下行号对应本次复核时的工作区。

## 1. 结论

**本轮暂不通过，需要返工。**

Android 构建、既有单测、Lint 与网页自动检查均已独立重跑通过，交付 APK 也与本次源码重新构建的 APK 一致。模型预设/设置迁移、三档模式、答案前置和流式调用等主要改动确已存在。

但发现 **2 项 P1、6 项 P2**：Android 预览及正式答案校验不完整、修改原题后旧答案/追问的版本关联缺失、网页 SSE 结束处理错误、自定义接口仍被发送未声明参数、落库失败被吞掉、性能埋点含义错误、追问前置拒绝会清空输入。其中数项直接与交付报告中“已修复”的表述不一致。

此外，真机 UI、覆盖安装升级、真实 DeepSeek 调用、40 题质量集和 15 题×3 次性能对照仍未完成。报告对此披露充分，没有虚报实测提速；这些必须继续保留为“未验证”，不能算本轮已验收完成。

本次没有修改业务实现、原方案或实施者报告；只新增独立验收记录和复验日志。后续应先修复确定缺陷，再做真实效果与设备验收。

## 2. 已独立执行并通过

| 项目 | 独立复核结果 | 边界 |
| --- | --- | --- |
| Android `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --rerun-tasks` | BUILD SUCCESSFUL，27s，84 项任务全部执行 | 没有用 up-to-date 代替重新执行 |
| JVM 测试 | 47/47：DeviceFeedbackCore 32、Models 7、ImageEditPolicy 4、SettingsValidation 4；0 失败/错误/跳过 | 不覆盖 MainViewModel/Room 的完整运行与 UI |
| Lint | 0 errors，21 warnings | 静态检查，不证明页面体验 |
| `npm --prefix web test` | 16/16 单测；浏览器 smoke `ok:true` | 原有 smoke 使用本地非流式 mock |
| APK 对应关系 | `dist` 与本次重新构建的 app APK SHA-256 相同 | 未覆盖安装到设备 |
| APK 版本/签名 | 1.1.0 / 2；minSdk 26，targetSdk 35；v2 签名通过 | 静态元数据检查 |
| 本地代理逐块转发（补测） | 首块约 19ms 可见，完整流约 629ms 才结束 | 本地合成上游，不代表 DeepSeek 网络延迟 |
| 浏览器取消→上游关闭（补测） | 约 5ms，断连取消链路通过 | 本地合成上游，不承诺服务端实际计费停止时间 |
| `git diff --check` | tracked 文件差异检查通过 | 不把未跟踪文件检查或运行行为混算进去 |

交付 APK SHA-256：

```text
0fef8416815987f804563df501c1ee84657e024bc50c10ec161d1139b65c887e
```

版本、字节数 21,360,296、旧 APK 归档哈希与实施报告一致。未替换 dist 副本。

独立复核也重新打开了 [官方模型能力表](https://api-docs.deepseek.com/quick_start/pricing/)、[思考模式](https://api-docs.deepseek.com/guides/thinking_mode/) 和 [Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)；本次查到的模型能力与模式控制结论未推翻原方案。没有使用真实 Key 发解题请求。

## 3. 必须返工的问题

### F1 · P1：Android 答案预览仍不校验状态、题型及答案合法性

位置：`data/PreviewJsonParser.kt:25–29,48–68`；展示入口 `ui/screens/ResultScreen.kt:233–249`。

`isComplete` 只要求 status 非空、有答案以及 warnings 字段存在。它没有校验 status 枚举、题型是否存在/与用户选择一致、单选答案数量、warnings 是否为数组。`feed()` 也没调用正式答案的校验逻辑。

对**本次编译出的实际类**执行独立 JVM 探针，结果：

```text
single_with_two_answers: ACCEPTED status=answered type=single answer=[A, B] warnings=[]
invalid_status: ACCEPTED status=banana type=single answer=[A] warnings=[]
missing_type: ACCEPTED status=answered type=unknown answer=[A] warnings=[]
warnings_wrong_type: ACCEPTED status=answered type=single answer=[A] warnings=[]
```

单选双答案会进入正常参考答案卡片；字符串 warnings 被静默变为空列表。正式结果稍后降级不能撤销此前错误预览的影响。结果页还以非 compact 答案卡渲染此预览，而“以上为预览答案”提示受 `!runningPreview.isComplete` 控制，当前解析器只返回 isComplete=true，因此该提示分支不会出现。下面虽有生成进度，答案卡自身没有明确“预览/生成中”标签。

**返工：** 预览与正式结果复用明确的语义校验；传入用户所选题型；不合法字段继续等待/拒绝预览，warnings 错类型不能视为空数组；预览卡始终显示未完成标签。needs_confirmation / out_of_scope 不以确定选择答案预览呈现。

**复验：** 上述 4 例不展示正常答案；补充题型冲突、字段跨块、警示晚到、后续正式答案改变、取消/断流后的标签与持久化断言。

实施报告第 9 节 P1-3 所称“答案通过题型校验后才展示”在 Android 当前实现中不成立；网页预览校验比 Android 完整，不能混作两端都通过。

### F2 · P1：修改原题后，旧成功答案和旧追问仍关联到新题目

位置：`MainViewModel.kt:686–705,708–739,514–527`。

确定的代码路径：

1. 原记录为题目 Q1、成功答案 R1 和追问 H1。
2. `updateQuestionTextAndRetry()` 先把数据库题干改成 Q2，但保持 `status=completed`、`result=R1`；补图片也只更新 imagePaths。
3. 重答前置拒绝、取消或网络失败时，`previous` 已是 Q2+R1，因此“保留旧结果”实际保留了题目与答案不一致的记录。
4. 返回历史重新打开后，仍有 completed 状态与普通答案卡；没有输入版本/dirty 状态标记 R1 属于修改前的 Q1。补图后尚未点击重答时也会发生。
5. 即使重答成功为 R2，完成分支没有清除/归档 H1；后续追问会把旧版题目的问答拼进新上下文。当前 `deleteForQuestion` 仅出现在删除题目的路径。

这是静态确认的调用/存储路径，未在设备上执行。不能把“新请求失败不覆盖 result”当作完整数据一致性修复。

**返工：** 为题干/图片与解析/追问建立输入版本关联，或将编辑内容暂存、成功后原子替换整套记录。允许查看旧结果时必须标为“修改前结果，当前题目待重答”，停用以当前答案为依据的分享/追问。新输入版本成功后清理、归档或过滤旧版追问。失败恢复必须保持同版本题干、图片、结果和元数据。

**复验：** 编辑文字/补图分别覆盖前置拒绝、网络失败、取消、成功、重开；逐项检查数据库中的输入版本、答案、状态、分享与追问上下文。实施报告第 9 节 P1-2 目前只做到了保留旧 result，没有解决版本关联。

### F3 · P2：Android 正式答案对题型冲突仍可判为 answered

位置：`data/AnalysisResultParser.kt:23,36–38,59–66,80–101`。

独立 JVM 探针确认：

```text
selected_single_model_multiple: status=answered answer=[A, B] answerText= warningsCount=2
short_without_answer_text: status=answered answer=[A] answerText= warningsCount=0
```

第一例用户选 SINGLE，模型返回 multiple+A/B：代码只追加警示，`answerInvalid` 没变，仍使用模型题型校验并保存 answered。第二例简答只有 answer 字母，answer_text 为空：选择题校验直接跳过，同样成功。已有测试仅验证“模型自报 single 却返回双答案”会降级，未覆盖手动题型冲突。

**返工：** 用户显式选择题型与模型题型冲突必须降级/要求核对；fill/short 的内容契约与对应 prompt 一致，不能只因 answer 数组非空就成功；处理重复选项等异常。两端遵循一致的语义规范。

**复验：** 补上述反例和所有显式题型组合的必要边界；检查返回状态及最终 UI，而非只断言 warnings 含某句话。

### F4 · P2：网页 SSE 把 HTTP EOF 当作结束条件，没有正确消费 [DONE]

位置：`web/assets/api.js:592–654`，尤其读取循环 `633–639`。

解析器认识 `[DONE]`，但外层循环仍一直读 HTTP body，返回前也不检查是否确实收到结束标记。用合成 Response 对实际导出函数做独立探针：

```text
analysis_stop_without_DONE: accepted=true, status=answered, finishReason=stop
followup_stop_without_DONE: accepted=true, finishReason=stop
DONE_without_HTTP_EOF: accepted=false, code=no_progress, ms≈45009
```

即：流在 stop 之后提前断开、缺少 `[DONE]` 时，解题和追问都被接受成功；相反，完整标记已到但连接还保持着，会继续等待约 45s 并错误报超时。后者直接损害这轮要改善的等待体验。Android 当前已检查结束标记，网页与其不一致。

**返工：** 传播解析器终止信号；收到 `[DONE]` 后结束应用层读取，妥善取消/释放 reader 并清理计时器。普通 EOF 缺少合法终止时报告不完整，不提交结果；保留 finish_reason 的独立校验。

**复验：** 将三个反例加入网页回归；另测最终 usage 与 stop/DONE 跨块、保活注释、意外断流，以及兼容非 SSE JSON 响应。

本地 Node 代理的逐块转发和断连取消本次已补测通过。这里的问题在网页消费端，不能误写成代理完全不可用。

### F5 · P2：Android 自定义接口“未知能力”仍强制发送 thinking 与 stream

位置：`data/DeepSeekClient.kt:78–119,136–137`、`data/QuestionAnalyzer.kt:59–74,79`。

上层把未知模型设成 thinkingEnabled=false，但请求构造器并不省略 thinking，而是始终发送 `thinking:{type:"disabled"}`；流式路径也始终 stream=true，且响应始终按 SSE 解析，没有内容类型分流。

独立构造未知网关请求，实际 payload 包含：

```json
{"model":"my-model","stream":true,"thinking":{"type":"disabled"}}
```

这不能兑现代码注释“未知模型不假设支持思考参数”的承诺。不接受 DeepSeek 扩展字段或只提供 JSON 返回的兼容服务会被额外参数/解析方式破坏；网页已有未知服务非流式分支，Android 未同步。

**返工：** 用“未声明/关闭/开启”区分参数省略与明确关闭；模型能力需要结合服务配置，保留高级配置能力。未知接口不发未声明扩展参数；按内容类型兼容普通 JSON，或使用已明确配置的非流式路径。不能把所有自定义模型的图片支持永久封死且不给能力配置入口。

**复验：** 本地严格模拟服务拒绝 thinking 扩展、只返回非流式 JSON；确认纯文字解题/追问可用，再测用户明确声明能力后的流式路径。

### F6 · P2：Android 落库异常被吞掉，仍进入“解析完成”

位置：`MainViewModel.kt:514–534`，关键为第 526 行。

最终结果的 `repository.update(current)` 包在 `runCatching` 中，返回值被忽略。更新失败后仍记录 completed 指标、把任务设为 COMPLETED、清空 activeRequestId。首次解析可能留在 receiving/validating 的数据库记录且无答案；重答可能仍展示旧结果，但用户被告知新解析完成。

这是静态确认的异常路径，本次没有注入真实 Room 故障。网页 smoke 的 DB failure 回滚通过不能证明 Android 路径通过。

**返工：** 持久化成功是 completed 的必要条件；在事务或明确成功分支之后再提交完成状态。失败应给可恢复的保存错误，并保持旧记录版本一致。不要用静默 runCatching 吞掉关键最终提交。

**复验：** 对仓库最终更新注入异常，断言无 completed 成功事件/指标，旧记录不变、重试入口可用。

### F7 · P2：性能埋点把响应头记为首事件，把模型等待记进上传耗时

位置：`DeepSeekClient.kt:193`、`MainViewModel.kt:418–446,478–487`。

`ResponseStarted` 在收到 HTTP 响应头后即发送；ViewModel 将其记为 first_event_ms，但此时可能一个有效 SSE 数据都没有。uploadDoneMs 则直到收到首个 content、或详细模式首个 reasoning 导致 Stage(2) 才记录，包含了上传结束后的模型等待。startedAt 在任务入库/导航之后才采集，也不是点击时刻。

因此日志尚不能用于原方案要求的分段瓶颈分析，未来拿它声称“上传耗时/首个模型事件”的比较会误导结论。当前无性能 CSV，尚未产生已报告的数值结论。

**返工：** 分别记录 clickedAt、headersAt、首个非保活事件、首个 content、答案预览、最终内容/落库；真实上传完成用 OkHttp requestBodyEnd 等实际事件，没有证据就记未知。日志区分请求目标模型与保留的旧结果模型，补齐必要的 request_bytes/finish_reason 等字段。

**复验：** 用可控制头、上传和正文延迟的本地上游验证各阶段边界，再采真实数据。不得用延长超时或动画当作速度指标。

### F8 · P2：追问前置拒绝之前，UI 已清空输入

位置：`ui/screens/ResultScreen.kt:194–206`、`MainViewModel.kt:750–766`。

点击发送先 `followUpInput=""`，然后才调用有前置检查的 sendFollowUp。结果页 enabled 主要基于“此题有结果/已结束”，没有同步所有全局分析状态/Key 条件。因此另一道题正在解析、或 Key 已被移除等情况下，ViewModel 返回错误但用户文字已经消失，且还未插入追问历史。

**返工：** 仅在请求被接受/消息安全保存后清输入；前置拒绝保留原文，UI 与实际发送准入条件一致。通过返回准入结果/事件处理，而不是假定调用 void 方法就已发送。

**复验：** 在已有结果页输入文字，分别制造“其他解析进行中”“无 Key”的前置拒绝，断言原输入和历史保持正确。

## 4. 报告与范围核对

- 真实 Key、设备、质量/速度集缺失均已清楚披露，这点符合交接要求；默认选择标准而非未经质量验证的快速模式也合理。
- 第 9 节“P1-3 已修复”需更正：Android 预览仅检查部分字段完整，不是语义有效；补测试后再报告修复。
- 第 9 节“编辑后沿用旧结果已修复”需更正为“重答不会覆盖旧 result，输入版本关联仍待修复”。
- C06 的“Web mock 流通过”与同报告 S2/C09/已知问题所写“仅非流式 mock”存在范围不一致。应分别列解析器单测、合成 Response 流、真实 Node 代理、浏览器、真实 API，不能统称端到端通过。
- 本次增加的代理补测可作为 C09 的本地证据，但仍不代表真实 DeepSeek、慢网背压和长时超时已验收。
- Android 结果页有原始 reasoning_content 的“思考过程”预览，偏离原方案“思考事件只驱动状态”的约定；建议移除原始思考展示，保持简短状态。此项作为范围偏差记录，不单独计入上述 8 项。
- 多项 U 包仍缺真机证据。代码有固定输入栏和答案前置，不代表安全区、字号、触控和长内容已通过。

## 5. 尚未验证的发布门槛

本次 `adb devices` 仍无连接设备；未安装 APK、未执行 connectedDebugAndroidTest；没有使用真实模型 Key。

因此继续待验：

1. 真机相机/相册、裁剪保留原图/替换、上限、取消失败和文件清理。
2. 首装/旧配置升级、Room 迁移、覆盖安装数据保留；前后台/旋转/进程终止恢复。
3. 360/412dp、浅深色、1.3×字体、IME/手势栏、TalkBack、触控和长结果实际渲染。
4. 真实 Flash/Pro 的文字/图片能力、连接与模型验证、错误处理、三档参数效果。
5. 固定 40 题质量对照、15 题×3 次/版本的性能对照、原始 CSV、P50/P95 和正确率门槛。

缺条件属于未验证；本次“不通过”同时基于已确认代码缺陷，并非只因为缺真机或 Key。

## 6. 证据与复现入口

本次保存的独立日志位于 [evidence/independent-2026-10-29](E:/souti/repo/docs/evidence/independent-2026-10-29)：

- `android-build.log`：84 项 Android 任务重跑。
- `web-tests.log`：网页单测与原浏览器 smoke。
- `android-probes.log`：F1/F3/F5 的已编译类直接探针。
- `web-stream-probes.log`：F4 的缺 DONE、DONE 后不 EOF 反例。
- `proxy-probes.log`：真实本地 Node 代理逐块转发和断连取消。

独立探针源码位于工作区外的验收目录，避免修改业务与已有测试：

- [JVM 探针](E:/souti/acceptance-artifacts/DeviceFeedbackIndependentProbe.java)。
- [类路径导出 Gradle 脚本](E:/souti/acceptance-artifacts/device-feedback-review-classpath.gradle)。
- [网页流式反例](E:/souti/acceptance-artifacts/device-feedback-stream-probe.mjs)。
- [本地代理探针](E:/souti/acceptance-artifacts/device-feedback-proxy-probe.mjs)。

所有网络探针使用合成 Key/本地上游或替换 fetch，没有调用实际收费 API。正文/答案仅为合成测试数据。

JVM 探针通过 Gradle Test 任务的实际 runtime classpath 调用本次编译类；F2/F6/F7/F8 为静态确定路径，未把它们伪装为设备运行失败。修改后应补对应的故障/状态回归证据。

## 7. 可直接交给实施模型的返工说明

> 请修复本独立验收报告 F1–F8，优先处理 F1/F2。不要只改交付报告的措辞或弱化测试。Android 预览和最终结果要复用语义校验；编辑输入与解析/追问必须保持版本关联；网页正确处理 DONE 与 EOF；未知接口不得强制发送未声明扩展参数；最终落库失败不能报完成；修正性能事件含义；前置拒绝保留追问输入。
>
> 保留现有工作区、原验收历史和已归档 APK。提交修复与针对性测试，重新运行既有 Android/Web 检查，更新实施交接报告并交付新构建对应的 APK/哈希。补上本地可执行的流式、故障与状态验证；真机和真实效果如仍缺条件继续标未验证，不能用 mock 代替。修完交由原审查模型复验，不在未获质量/速度证据时宣称整轮提速或 UI 验收完成。
