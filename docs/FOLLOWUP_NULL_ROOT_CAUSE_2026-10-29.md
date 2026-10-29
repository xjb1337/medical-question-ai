# 真机追问大量 null：原因与整改要求

日期：2026-10-29。来源：用户真机截图，追问回复气泡前部连续显示 null，末尾开始出现正常中文。此文件是第二轮验收之后新增的缺陷记录，不覆盖已有报告。

## 判定

**P1：Android 流式字段读取把 JSON null 转为文字，污染追问正文、思考预览与历史上下文。** 当前代码存在可以直接解释截图的路径；本次没有该次真实 API 原始响应，截图与代码结合得出的归因应与真实抓包证据区分。

截图里的长文本位于回复气泡中。按现有 UI 代码，回复正文与可展开的思考预览是两条显示路径；不能把截图中的 null 当成正常的模型思考内容。

## 根因

位置：`app/src/main/java/com/xjb/medicalquestionai/data/DeepSeekClient.kt:431–436`。

```kotlin
val reasoning = delta?.optString("reasoning_content", "").orEmpty()
    .ifBlank { delta?.optString("reasoning", "").orEmpty() }
val content = delta?.optString("content", "").orEmpty()
if (reasoning.isNotBlank()) events += ChatStreamEvent.ReasoningDelta(reasoning)
if (content.isNotBlank()) events += ChatStreamEvent.ContentDelta(content)
```

Android 的 JSONObject 将显式 JSON null 保存为 JSONObject.NULL。它通过 optString 转换时得到字符串 `"null"`，不是 Kotlin null，也不是空串；因此 orEmpty() 和 isNotBlank() 均拦不住它。

例如收到 `{"delta":{"content":null}}`，当前实现会发出 ContentDelta("null")。若思考阶段多次出现这种空正文分片，`MainViewModel.kt:885–891` 每次执行 content.append(event.text)，最终变成 nullnullnull…。后续真正正文继续接在末尾，符合截图。

同样的问题也会污染 reasoning_content 和 finish_reason 等使用 optString 的可空字段。虚假的正文增量还会刷新“有效进展”计时。追问完成后，content 被保存为 assistant 历史消息，后续追问可能继续把污染文本带入上下文。

Android 官方源码明确说明此转换行为：[JSONObject.java](https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSONObject.java)；转换实现见 [JSON.java](https://android.googlesource.com/platform/libcore/+/refs/heads/main/json/src/main/java/org/json/JSON.java)。

## 为什么 JVM 测试没有发现

项目 testImplementation 使用 `org.json:json:20240303`。本轮对该实际 JAR 运行合成探针：

```text
JVM optString(content=null): length=0 value=[]
JSON null sentinel toString: null
```

即桌面测试库会把该情况变为空串，Android 系统实现会把哨兵对象转成文字 null。现有测试未对实际 Android JSON 行为建立回归约束；测试通过不能证明此处真机无问题。

## 实施要求

1. 所有流式可空文本字段使用类型明确的读取：先 opt，再仅接受实际 String，例如 `obj?.opt(key) as? String ?: ""`。不要依赖 optString 的强制转换行为；显式空值、缺字段和错误类型不得发出正文/思考增量。
2. 保留真实文本原样，包括必要空白；不要通过全局替换 `"null"` 清洗用户/模型文本。真实字符串内容与 JSON null 是不同情况。
3. 同时检查 content、reasoning_content、reasoning、finish_reason，以及非流式 content/model 等可空字段；正常结束、usage-only、role-only 事件不得产生伪正文。仅空字段的分片不得刷新有效正文进展计时。
4. 思考阶段只有推理增量时，回复正文显示“正在思考…”状态；正文增量到达后才追加回复文字。按原方案，普通用户界面优先展示处理状态和最终解题依据，原始思考预览不作为确定答案。
5. 已保存的污染追问不能仅修显示：提供重试/删除入口，并避免已识别的污染历史继续进入新请求。不要批量替换所有历史中的 null 文本。
6. 截图下方 Markdown 星号原样显示，是 Text 直接显示正文的另一项 UI 表现；可按产品约定增加安全的基础 Markdown 渲染。它与 JSON 空值污染应分别修复。

## 回归要求

- 连续 100 个显式 `content:null`、仅 reasoning 增量的分片：正文仍为空，界面只有等待/思考状态。
- 随后真实正文到达：最终回复严格等于正文，无 null 前缀，保存历史与再次发送上下文也无污染。
- reasoning_content:null、字段缺失、role-only、usage-only、finish-only 分片均不追加文字。
- 字段错误类型不被强转为正文；真正的字符串 `"null"` 不能被当作 JSON 空值全局删去。
- 同时验证 Android 环境行为；如果使用 JVM 测试，补足 Android 兼容实现或抽出类型严格的解析层测试，不能只依赖目前 JSON JAR 的 optString 行为。
- 真实接口复测仍需要设备/API Key；合成测试与真机证据分别记录。

本轮仅诊断和记录问题，未修改业务代码或 APK。
