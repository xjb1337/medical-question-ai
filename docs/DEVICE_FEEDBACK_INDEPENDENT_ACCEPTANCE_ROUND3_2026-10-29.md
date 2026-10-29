# 第三轮独立验收（2026-10-29）

审查提交：`5e75082`。开始时工作区干净；本次只新增验收报告、探针与证据，未修改业务代码或交付 APK。

结论：**暂不通过，剩余 1 项 P1、2 项 P2，以及 APK 对应性待核验。**

## 独立检查

- Android testDebugUnitTest、lintDebug、assembleDebug、assembleDebugAndroidTest 均成功；另对 JVM 测试使用 --rerun-tasks 强制重跑，57/57，0 failures/errors。Lint 报告 0 errors、22 warnings。AndroidTest 仅编译，未上设备。
- Web 19/19，浏览器 smoke 成功，包含 followUpsClearedOnEdit:true。
- 实际 DeepSeekClient.parseStreamEvent 连续解析 100 个 content/reasoning_content/finish_reason 显式空值分片，正文/思考伪增量为 0。类型严格读取消除了上次截图问题的新响应来源；仍需真机真实接口复测。
- 非流式结束原因已保留和传递，模型验证检查真实原因；答案版本已加入追问查询/写入，DB v7 有迁移；保存题干采用成功回调关闭弹窗。这些路径的代码整改已确认，但 Room 故障、升级和真机 UI 尚未实测。
- 每次拍题隐私勾选及追加图片确认按本轮要求移除，不将其列为缺陷。
- git diff --check 通过。
- 证据目录：`docs/evidence/independent-round3-2026-10-29/`。含构建、强制测试重跑、Web、独立探针日志及探针源文件。

## R3-01 · P1：非法预览仍作为 answered 进入答案卡

位置：`data/PreviewJsonParser.kt` 的 feed/toResult；`ui/screens/ResultScreen.kt:169,250`。

最终答案校验已经修好，但是 feed 改成 `return preview.takeIf { it.hasUsefulContent }`，不再阻止语义不合格的预览。toResult 原样保留 status 与 answer，没有使用 semanticIssue 降级。UI 的 hasAnswerPreview 只检查 status==answered 和答案非空，没有检查 semanticIssue/isComplete。因此预览文本虽注明“还在核对”，已知不合法的答案依然进入正常答案卡。

对本轮实际编译产物执行的独立反例：

```text
单选 answer=["A、B"]：preview=answered [A,B], complete=false，final=needs_confirmation
question_type=banana：preview=answered [A], complete=false，final=needs_confirmation
答案 A、option_analysis 仅 B：preview=answered [A], complete=false，final=needs_confirmation
```

这三个预览的 semanticIssue 已经正确记录，但未影响显示资格。仅给答案卡加“预览”文案，不能代替拒绝已知非法答案。

整改：区分“尚未收齐但语义合格”和“已经判定非法”。后者不展示确定答案卡，可展示题干与核对状态；或 toResult 明确降级并隐藏无效答案。预览最终共享校验并让结果真正控制 UI。补测试覆盖 feed→toResult→答案卡准入，而不是只断言 semanticIssue/isComplete。

## R3-02 · P2：计时仍把响应头当首事件，未记录实际上传结束

位置：`data/QuestionAnalyzer.kt:118–123,170–189`，`MainViewModel.kt:557–574`。

- firstEventAt 在 collect 每个事件进入 when 之前无条件设置；第一个 ResponseStarted 就是响应头，所以 clickToFirstEventMs 仍等于头到达时间。应仅在真正的非保活 SSE 数据事件到达时设置，响应头单独计。
- requestReadyAt 是构造完 ChatRequest 的时间，尚未序列化和发送请求体。没有 requestBodyEnd 事件或实际上传结束字段。报告的“请求体完成”必须区分请求构建与发送完成；未知上传时间应明确记未知。
- validationAt 在 parseResult 之前采集，validationMs 却用它减首答案/首正文时间，实际包含等后续正文与结束标记，且不包含 parseResult 本身。应在验证前后分别采样。
- 保存阶段先写入 saveStatus=saving，再以 runCatching 写 saved 元信息。第二次失败被吞掉，UI/日志使用内存 savedQuestion，重开从数据库却仍读到 saving；totalMs 截止第一笔写入，没有包含第二笔写入。应统一真实持久化状态与计时定义，不能报告内存已保存而落库指标未完成。

点击起点、分字段与元信息持久化的改进应保留，但不能据此宣称计时边界已经完成。补响应头/正文分离延迟、真实上传事件、解析延迟、第二次写入失败的针对性测试；测不到的指标保持未知。

## R3-03 · P2：截图中已保存的连续 null 历史仍会传给后续追问

位置：`TextFormat.kt:7`、`data/QuestionAnalyzer.kt` 的 isUsableForHistory/trimFollowUpHistory，`ResultScreen.kt` 的 FollowUpBubble。

目前只拒绝全文恰好等于单个 null 的消息。截图对应的是 `nullnullnull…正常中文回答`，不满足该条件，Markdown 清理也不处理这种污染；升级后旧记录仍显示且会进入后续模型上下文。

独立探针建立一轮 user/assistant，assistant 为 `nullnullnullfixture answer`，调用实际 trimFollowUpHistory 后两条均被保留，日志为 `polluted_history_kept=2`。所以“新空值不再产生”已经修复，“既有污染历史不再传递”尚未修复。

整改：对已识别的、重复 null 前缀污染的助手历史做隔离/标记并排除请求上下文，保留原记录供查看与重试；或提供明确迁移/删除流程。不要全局删除所有 null 字符串，避免破坏合法正文。补截图格式的混合污染反例及正常包含 null 单词的保留测试。

## APK 对应性

交付 dist APK 大小 21,826,875 bytes，SHA-256 `44a4448da332a40812611b9bf1c4a518882bb033d9871f3c5cc77ed8d207cc1b`，与报告一致。

独立 assembleDebug 输出包 SHA-256 为 `c702a86b5f7c87fb9389b0b427fb1c3a5efcc114f7ea26f0ec3cc1fdf9da52b3`，仍与交付包不同。本次未反编译比较，不能单凭哈希断言交付包是旧代码，也不能确认它就是本轮受检源码的等价产物。下一轮请从最终受检输出复制 dist 并当场验证两者哈希相同，记录提交与构建标识。

## 下轮交付要求

修复 R3-01～R3-03，提供上述反例测试和 APK 输出/交付一致性证据。保留已通过的版本过滤、空值类型安全解析、编辑保存回调及隐私交互移除。真实拍照、真实接口、DB 升级、40 题质量和速度比较继续如实标注未执行；本轮不能判定速度或真机 UI 验收通过。
