# 退出解析页与拍摄下一题：第六轮独立复核

审查日期：2026-10-29；目标提交：`242c48b1c8c464190d69f5bd1c34af4811420411`。

**结论：暂不通过。NC-01 的修复引入 1 项 P1 回归；NC-02 尚有 1 项 P2 未关闭；NC-03 通过本轮代码复核。** 未修改业务源码、实现测试或交付 APK。没有设备、真实 API 或网络竞争运行证据。

## NC-01：P1，新令牌会被旧 effect 的清理回调作废

位置：`app/src/main/java/com/xjb/medicalquestionai/ui/screens/CameraScreen.kt:107,125–138,209–212`；`MainViewModel.kt:338–346,370–382`。

新增的退出令牌、导入后校验、失效文件清理以及 next_question 来源返回首页，确实修复了上一轮发现的若干遗漏。但 `DisposableEffect(lifecycleOwner, activity, captureToken)` 将可变令牌作为 key，而 `onDispose` 直接读取委托状态变量 `captureToken`。

可达时序：

1. 相机初始令牌为空，旧 effect 已注册。
2. 正常按快门，`beginCameraCapture()` 创建并登记有效令牌 T，`captureToken = T` 触发重组。
3. key 改变使旧 effect 执行清理；该闭包读取的是此时的状态 T，并非注册时的 null。
4. `invalidateCameraCapture(T)` 将正在拍照的令牌删除。
5. 照片保存或导入回调检查失败，正常照片被丢弃，无法完成本次拍题流程。

**独立验证：** 仓库外探针调用当前 Kotlin 编译生成的实际 `onDispose` 实现，传入同一 MutableState。模拟旧 effect 注册后将状态从 null 更新为新令牌，再调用清理：

```text
cameraTokenActiveBeforeOldEffectDispose=true
cameraTokenActiveAfterOldEffectDispose=false
```

探针没有运行完整 Compose 重组、CameraX 或 Android 生命周期；它确认生产清理回调读取并作废最新令牌，重组触发条件由源码中的 effect key 确认。这是代码与局部执行证据，不冒充真机结果。

**整改要求：** 不要因令牌赋值而执行页面退出清理。可将生命周期 effect 限定为 owner/activity 等页面身份，使用最新状态读取当前令牌，仅在真正退出/后台/销毁时作废；同时保留旋转例外。若使用不可变捕获，须确保旧 effect 清理仅处理自己拥有的令牌，且生命周期观察者仍能处理当前操作。不要简单删除所有退出清理。

**复验要求：** 正常快门→重组→延迟照片回调必须成功加图并导航一次；快门→重组→返回/后台→延迟回调必须不加图、不导航并清理文件。另覆盖连续拍照、旋转和导入期间退出。仅测试令牌集合 add/remove 无法覆盖本回归。

## NC-02：P2，追问身份缺失，空 ID 仍作为通配停止

位置：`ui/screens/ResultScreen.kt:169–186,221,246`；`MainViewModel.kt:168–190,1158–1175,1301–1302`。

本轮已将活动分析 requestId 传入三个退出入口，非空旧分析 ID 遇到新分析 ID 时确实会拒绝停止。但这并未完成原要求中的追问及页面可见会话隔离：

- `activeRequestId` 仅由活动 AnalysisTaskState 得到；追问没有独立请求 ID，结果页在答案完成后通常传 null。
- 停止函数仅在 `expectedRequestId != null && activeAnalysis != null` 时比较身份。没有分析、只有新追问时，即使带的是旧分析 ID，也会取消当前追问。
- null 继续表示无条件匹配。旧事件捕获“当时无分析”的 null 后遇到同题新分析，仍会取消新任务。

**独立验证：** 探针跳过 Android 构造与数据库初始化，仅注入隔离的真实协程 Job/StateFlow，调用当前编译后的生产 `stopQuestionWork`：

```text
staleNonNullId_cancelledNewFollowUp=true  outcome=stopped=true
staleNullId_cancelledNewAnalysis=true    outcome=stopped=true
staleNonNullId_cancelledNewAnalysis=false outcome=stopped=false
```

最后一项是对照，证明已接入的非空分析身份校验有效；前两项证明剩余身份缺口。探针没有制造真实 UI 事件排队或调用底层网络 cancel，不能声称真机竞态已复现。

**整改要求：** 为页面可见会话和分析/追问各自建立可比较的身份快照；区分“当时没有任务”和“无条件停止”，不要用 null 同时表达两种含义。只取消该事件实际拥有的任务。当前页面发起新请求时更新归属，过期事件应幂等忽略，不作为用户错误弹出。三个生产退出入口都要传递完整身份。

**复验要求：** 排队旧页面停止事件→启动同题新分析/追问→释放旧事件，断言新任务及网络请求未被取消；同时确认当前页面的分析和追问能正常停止。覆盖旧身份为 null、旧分析已结束后新追问开始、非空不匹配、重复退出。生产控制器/入口级验证应替代仅验证 StopPolicy.matchesRequest 的测试。

## NC-03：草稿覆盖保护通过代码复核

`prepareNextQuestion` 已在停止和重置之前调用 `canParkDraft`；当当前与暂存草稿均有内容时明确拒绝，不覆盖草稿或删除图片。当前草稿为空则允许继续，已有暂存草稿保留。新增测试覆盖该策略。真实导航、恢复交互与文件引用仍待设备验证。

## 独立自动检查与交付物

- Android 使用 JDK 17、现有 Android SDK，执行 `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks`，84 个任务实际执行，BUILD SUCCESSFUL。
- JVM XML 汇总：64 tests、0 failures、0 errors；Lint：0 errors、22 warnings。AndroidTest 仅编译，未连接运行。
- Web：19/19，smoke `ok: true`，含 `followUpsClearedOnEdit: true`。
- `git diff --check` 退出 0。拍题页面未重新引入逐题隐私勾选。
- 交付 `dist/medical-question-ai-debug.apk` 大小 21,842,313 bytes，SHA-256 与用户报告一致：`c54e5eadc8ed535793ca9dacdcf5c3f9c885682a18d83bc7e603cad53666f747`。
- 本次强制全量构建后 `app-debug.apk` SHA-256 为 `999c1e77d5799e14ecd4cde478a5f83a6c44006c08bb87fe48ef45c03cbc179e`，与 dist 不同；ZIP 解包内容仅 `classes5.dex` 的摘要不同。本轮没有据此认定交付包来源错误，也不能重复宣称它与本次独立重建产物逐字节一致。修复后需重新生成交付 APK 并记录对应性。

证据：`docs/evidence/independent-next-capture-round6-2026-10-29/`。其中 Java 探针依赖当前编译产物与本机测试运行时 classpath，在仓库外编译执行；使用反射/Unsafe 避开 Android 初始化，只验证上述局部生产逻辑，不属于新增正式测试。

## 报告与未执行边界

实施报告第 13 节仍写 N15 超时 2s，实际常量为 4s；N16 的“每 500ms”应改为“预览事件触发且间隔至少 500ms 的节流保存”，不是固定周期定时器。NC-02 行应准确说明目前只覆盖非空分析请求身份。

本轮已在项目根以 `graphify update E:\souti\repo` 构建本地代码图，并查询相机、停止和图片导入关系；重要结论均复核源码。图含 1,405 节点、3,225 边；输出仅保存在本地 `graphify-out/`，没有使用远程语义后端、安装 hook 或提交图文件。

N01–N16 真机交互、旧库 instrumented 迁移、真实接口质量与速度仍保持未执行。下一轮先修复以上 P1/P2 并补针对实际调用链的回归证据；已通过的草稿策略、严格空值、预览/正式答案隔离与隐私交互移除应保留。
