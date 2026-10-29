# 退出解析页与拍摄下一题：第七轮独立验收

日期：2026-10-29；审查提交：`db1a9b0476883974f13e7703d5137785e9782f19`。

**结论：本轮整改通过代码与自动验证，可进入真机验收。上一轮 NC-01 P1、NC-02 P2 关闭，NC-03 保护保留。本结论不等于全部功能或产品验收通过。**

本轮未修改业务源码、正式测试或交付 APK，未创建提交或推送。审查本地提交，不将用户陈述的 GitHub 推送状态计为独立验证结果。

## 缺陷关闭依据

### NC-01：相机 effect 清理误作废新令牌，关闭

`CameraScreen.kt:125–140` 已将生命周期 effect 的 key 限定为 lifecycleOwner/activity，不再包含 captureToken；观察者及真正销毁时的清理通过 rememberUpdatedState 读取当前令牌。按快门修改令牌不会因 key 改变而触发旧 effect 清理，因此上一轮“null→T→旧清理作废 T”的触发链已移除。

顶部返回/系统返回仍显式使令牌失效；后台和非配置变更销毁也清理当前令牌，配置变更例外保留。MainViewModel 仍在导入前后检查令牌与草稿会话，并清理失效拍摄文件；下一题来源的相机取消仍返回首页确认页。

证据为源码调用链核验及成功编译，未运行完整 Compose 重组或 CameraX。正常快门、退出竞争和旋转仍须真机验证。

### NC-02：分析/追问停止身份不完整，关闭

`StopModels.kt` 新增 WorkIdentity，两个可空 ID 分别代表事件拥有的分析、追问，null 表示不拥有该类任务。`MainViewModel.kt:169–216` 分别比较任务身份，只 cancel/join 拥有的实例；无匹配但有其他工作时返回 stopped=true/ignored=true，不再把 null 当作通配符。

`ResultScreen.kt:170–190,226,251` 的生命周期、顶部返回和拍下一题已传入两类身份。生命周期观察者不随任务 ID 重建，而是读取最新可见状态快照；传给异步停止函数的是事件发生时的不可变 WorkIdentity。追问登记独立 UUID，并将它放入 FollowUpStreamState；流式更新使用 copy 保留 ID。下一题遇到 ignored 时不重置草稿、不导航。

独立 Java 探针调用当前编译产物中的生产 stopQuestionWork，而非仅调用 ownsAnalysis/ownsFollowUp。10 个场景全部通过：

| 场景 | 验证结果 |
| --- | --- |
| 空快照遇到新分析 | 不取消，ignored=true |
| 空快照遇到新追问 | 不取消，ignored=true |
| 旧分析 ID 遇到新追问 | 不取消，ignored=true |
| 旧分析 ID 遇到新分析 | 不取消，ignored=true |
| 旧追问 ID 遇到新追问 | 不取消，ignored=true |
| 当前分析 ID | 当前分析 Job 被取消 |
| 当前追问 ID | 当前追问 Job 被取消 |
| 混合身份：旧分析、当前追问 | 只取消追问，分析保持活动 |
| 无任务重复停止 | 幂等成功 |
| 不同题停止事件 | 原题分析与追问保持活动 |

探针通过反射/Unsafe 跳过 Android 初始化，只注入隔离的真实协程 Job/StateFlow；未运行数据库、页面事件调度或真实网络，不能据此声称真机/底层 HTTP 取消已经验证。源码与本轮执行结果足以关闭上一轮已证实的停止函数身份缺陷。

### NC-03：暂存草稿覆盖保护，继续保留

prepareNextQuestion 在重置前调用 canParkDraft；当前和暂存草稿都有内容时拒绝，空当前草稿不会覆盖已有暂存内容。没有重新引入静默覆盖或删除图片的代码。恢复交互和文件归属继续纳入设备验收。

拍题页隐私勾选和结果页追加图片确认未重新加入。

## 独立检查

- Android：testDebugUnitTest、lintDebug、assembleDebug、assembleDebugAndroidTest 成功；另以 --rerun-tasks 强制重跑 JVM 测试，27 个任务实际执行。
- JVM XML 汇总 64 tests、0 failures、0 errors；Lint 0 errors、22 warnings。AndroidTest 仅编译，未连接运行。
- Web：19/19；smoke ok=true，含 followUpsClearedOnEdit=true。
- 生产停止函数探针：10/10 场景通过，不计入仓库的 64 项正式测试。
- git diff --check 退出 0；业务代码无本轮改动。
- 检查开始、任何构建前，交付 APK 与当时 app-debug.apk 哈希相同：`de815fc0e748ee7764a45f190827267396911ac78a70cf965f7b093bc64c7cfe`；交付 APK 大小 21,875,294 bytes，与交接一致。

证据：`docs/evidence/independent-next-capture-round7-2026-10-29/`，含构建、强制单测、Web、生产停止探针源码/输出及 APK 摘要。探针依赖本机已编译应用和测试运行时 classpath，在仓库外编译执行。

按项目要求刷新并查询本地 Graphify 图，重要结论逐项回查源码；没有使用远程语义后端或提交图文件。图中源码定位用于导航，不作为运行证据。

## 进入真机验收的范围

按原修改方案 N01–N16 执行。优先验证：

1. 正常快门后成功进入确认页；快门/图片导入期间返回或切后台，迟到回调不加图、不跳页。
2. 分析和追问分别测试顶部返回、系统手势、后台和锁屏；停止后不继续追加，重开不自动联网。
3. 旋转、重组、权限弹窗不误停；离开时保存边界不损坏已完成答案或旧答案。
4. 生成中拍下一题能停止旧请求，新题图片不串；相机取消/拒绝/失败有正确返回目标。
5. 连拍多题不堆叠旧结果页；已有草稿可恢复，第二份草稿不会覆盖第一份。
6. 升级旧库与进程终止后重开，未完成预览不作为正式答案、分享或追问上下文。

没有设备/真实 Key，本轮以上内容、旧库 instrumented 迁移、真实 API、40 题质量及 15×3 速度对照均未执行，继续保留待验收状态。

## 非阻断文档整理

实施报告 N15 仍写 2s，实际 STOP_TIMEOUT_MS 为 4s；N16 “每 500ms”应写为预览事件触发且至少间隔 500ms 的节流保存。13.3 旧记录仍描述 expectedRequestId，可注明已被 13.4 的 WorkIdentity 替代。这些文档误差不重新打开本轮已关闭的代码缺陷。
