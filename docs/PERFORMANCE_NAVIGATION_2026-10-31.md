# Android 性能与页面进入/返回验收

日期：2026-10-31。范围：拍题、相机、题库、结果、追问、设置，以及这些页面使用的导航、数据库转换与导出路径。保留本轮之前的 DeepSeek 首次绑定教程。

## 已修复的问题

| 问题与触发方式 | 修复后行为 | 主要位置 |
| --- | --- | --- |
| 首页快捷入口和题库空状态直接 navigate，返回栈策略与底部导航不一致 | 三个主页面共享保存/恢复状态、singleTop 和回到起始目的地的导航规则；重复点击当前页面不新增栈层 | `ui/NavigationActions.kt`、`ui/AppNavigation.kt` |
| 连点历史卡片或相机入口可能重复进入详情 | 仅在来源页面已 RESUMED 时进入，详情使用 singleTop | `NavigationActions.kt`、`HistoryScreen.kt`、`NewQuestionScreen.kt` |
| 结果页顶部返回立即弹栈，系统返回依赖生命周期清理，不能确认任务已停止 | 两种返回共用有界停止与任务身份检查；成功后离开，失败/身份变化时保留页面并提示；等待后的导航明确运行在主线程 | `ResultScreen.kt` |
| 相机初始化未完成时离开，onDispose 在主线程调用 future.get() | 退出只释放本页已绑定的 use case；异步完成回调检查 disposed，避免离开后重新绑定；退出不等待 future | `CameraScreen.kt` |
| Room 返回后的 JSON 解析/序列化以及全量历史搜索可能在主线程执行 | 转换和搜索在 Dispatchers.Default 执行；历史过滤只订阅相关的保存历史偏好，避免无关设置变化触发整表过滤 | `QuestionDatabase.kt`、`MainViewModel.kt` |
| 设置页为导出按钮持续订阅并解析全部历史，且导出内容意外受题库搜索词限制 | 设置页仅订阅 COUNT 查询；点击导出才后台读取和组装文本，导出全部可见记录并排除临时题目 | `SettingsScreen.kt`、`MainViewModel.kt` |
| 追问消息放在一个 LazyColumn item 中整体渲染 | 追问按消息拆分为独立惰性条目，保留顺序及答案版本隔离；正文格式转换按内容缓存 | `ResultScreen.kt` |
| 100ms 计时更新导致结果页根节点频繁参与重组，而界面只显示整秒 | 计时改为 1 秒更新，订阅移到进度条目内；请求指标仍由原有精确时钟记录 | `MainViewModel.kt`、`ResultScreen.kt` |
| 数据库 Flow 在页面停止后继续收集 | 题库、结果详情、追问及设置计数随页面生命周期停止收集；内存中的轻量状态保留原订阅方式 | 各 screen |
| 可选内容插入/移除时列表条目缺少稳定身份与复用类型 | 首页、结果、题库及图片/追问列表补充 key 和 contentType；历史图片允许同一路径出现两次 | 各 screen |

数据库表结构与版本没有变化。图片导入/裁剪与 API 协议不在本次改动范围。

## 页面进入与返回

| 路径 | 检查结果 |
| --- | --- |
| 拍题 → 设置/题库 → 拍题 | 反复切换后仍只有一层首页；设置/题库系统返回回到首页 |
| 题库搜索 → 结果 → 顶部返回 / 系统返回 | 回到同一个题库搜索状态；快速双击卡片不形成两层结果 |
| 结果有任务 → 返回 | 等待当前身份对应的分析/追问停止，再离开；超时或持久化失败时沿用现有 StopOutcome 提示，禁止假装停止成功 |
| 结果 → 拍摄下一题 → 相机返回 | 旧结果页移出栈，返回首页；先前有内容的首页草稿仍可恢复 |
| 首页 → 相机 → 返回 | 本页拍照令牌失效；晚到的初始化回调不会重绑相机；已有草稿保留 |
| 结果 → 长追问 | 100 条本地追问可按需加载、滚动到最后一条；旧答案版本的消息不会混入 |
| 设置首次绑定 → 官网 → 返回 | 首次绑定教程既有 4 项测试仍通过，教程与输入框定位保留 |

完整模拟器回归同时覆盖了图片查看、图片导入、更新恢复和原有页面输入流程。相机初始化未完成的任意硬件时序仍不能由模拟器穷举。

## 验证

- JVM 单元测试：75/75，通过，0 failures / 0 errors。
- Android 模拟器：25/25，通过。包含新增 6 项导航/性能用例，原有教程 4、图片查看 2、UI smoke 3、更新 4、图片导入 6。
- 新增用例：重复切换主页面、历史双击与两种返回、100 条追问按需渲染与滚动、下一题后返回及草稿保护、导出不受搜索影响、两种返回等待任务清理。
- 取消顺序用例使用注册到真实 ViewModel 的合成 Job，特意延迟清理，检查离开结果页前清理已完成；它不是真实模型或网络取消测试。
- 最后完整回归在雷电 Android 9、1080×1920 / 460dpi（约 375.7dp 宽）执行。测试后恢复原始 1920×1080 / 280dpi。
- `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest assembleBenchmark`：通过。
- Lint：0 errors，4 warnings、2 information。警告来自既有 SharedPreferences.commit 调用，未将其批量替换以改变持久化语义。
- `git diff --check`：通过。
- 推送前补跑 Web 发布门禁：19 项单元测试和浏览器修复 smoke 全部通过；[日志](evidence/performance-navigation-2026-10-31/web-tests.log)。

验收中新增的异步任务清理测试曾发现返回操作的主线程约束问题；修复为显式 Main.immediate 后，最终 25 项全部通过。以最终日志为准。

证据：[构建与检查](evidence/performance-navigation-2026-10-31/build.log)、[完整模拟器回归](evidence/performance-navigation-2026-10-31/emulator-tests.log)。

## 性能采样及限制

增加独立 `.benchmark` applicationId 的不可调试安装包，继承当前 release 编译设置、使用本地 debug 证书签名，避免使用 Debug 包数据作为性能比较。现有 release 的 R8 仍关闭，本轮没有生成应用 Baseline Profile，因此本采样不等于经过完整生产编译优化的 Macrobenchmark。

脚本：`scripts/performance/navigation-sample.ps1`。同一雷电实例、1920×1080 / 280dpi、独立空历史安装包，每组 5 次有效进程冷启动、预热三个页面后执行 30 次主页面切换。脚本核对 am start 实际返回的 Activity，启动器过渡不计入有效启动。更新提示在采样前已关闭。

| 指标 | 修复前 | 最终源码 |
| --- | --- | --- |
| 5 次有效进程冷启动（ms） | 579 / 654 / 570 / 499 / 498 | 548 / 498 / 543 / 525 / 524 |
| 启动中位数（ms） | 570 | 525 |
| 帧耗时 P50 / P90 / P95 / P99（ms） | 5 / 7 / 7 / 12 | 5 / 7 / 7 / 11 |
| gfxinfo 全局 Janky frames | 286 / 2485，11.51% | 296 / 2469，11.99% |
| Number Slow UI thread | 10 | 6 |
| 单次 PSS（KB） | 70,432 | 68,642 |

这些是小样本、模拟器和空历史场景的观测；不同轮次帧统计和内存读数有波动。帧耗时大体相近，卡顿帧占比未证明改善，不能据此承诺固定提速或内存下降百分比。确定的实现收益是移走主线程历史转换/搜索、减少计时触发范围、按需渲染追问和移除退出相机时的阻塞等待。100 条追问用例验证惰性渲染与可达性，没有将它当作长列表性能基准。

官方 Android 模拟器启动超时，改用已有雷电。已按 Android Profiler 技能尝试 Perfetto 官方 record_android_trace 脚本；雷电返回 producer/data source 错误、无法 root adbd，文件仅 1,294 bytes，未获得有效调度/帧跟踪，未用该文件得出结论。帧统计使用 gfxinfo，内存使用 meminfo。真实设备、真实接口速度、长时间内存稳定性和持续流式网络取消仍未验证。

本机完整样本在 `E:\souti\acceptance-artifacts\performance-navigation\before-verified\` 和 `after-final\`；关键样本同步到报告的 evidence 目录。

## 交付

- 预览包：`E:\souti\repo\dist\medical-question-ai-performance-preview.apk`。
- versionName：`1.3.1-performance-preview`；versionCode：`100004`。
- 文件大小：22,108,321 bytes。
- SHA-256：`82b4f051d2616b0d3a04961358b4cfa9942200d59f57e1f66f9323024bc6be3c`。
- Debug 签名，保留数据覆盖安装到测试模拟器；已包含上一轮 DeepSeek 首次绑定教程。
- 按用户授权将本轮教程、性能与导航改动和验收证据一并提交并推送 main，触发现有 Android CI 与签名 Release 工作流。线上版本由工作流递增，发布状态以本次提交对应的 Actions 与 Release 为准。本地预览 APK 不作为线上签名资产上传。
