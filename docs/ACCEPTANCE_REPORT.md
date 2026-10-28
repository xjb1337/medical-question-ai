# 修复验收报告

日期：2026-10-28（Asia/Shanghai）

验收版本：`4b6b327`，修复提交 `26f843d`，对照基线 `3024c24`。

验收开始时工作区干净。本次未修改业务代码或实施模型的测试，仅新增本报告；复核脚本和日志位于 `E:/souti/acceptance-artifacts/` 与 `E:/souti/acceptance-build.log`。

## 结论

**本轮暂不通过完整验收，需要返工后复验。**

原 Android 图片导入空值误判已从代码中修正；网页裁剪、密钥说明与追问用量链路的复核通过。Android 基础构建和 14 项 JVM 单测也通过。

但是，新增的 Android 图片回归测试有两个方法签名不符合 JUnit 要求。这是已确认的测试代码缺陷，不只是缺少设备；直接连接设备仍无法正常运行该测试类。Android 裁剪测试也缺少计划要求的文件独立性、原文件内容不变等关键断言。

设备端图片导入/CameraX/裁剪流程及真实 DeepSeek 医学题效果均仍待验收。本机 `adb devices` 没有设备，没有可用 AVD；本次未安装模拟器或调用真实接口。

## 必修返工项

### A1：修正设备测试返回类型（P2，阻断 R1/R6 验收）

文件：`app/src/androidTest/java/com/xjb/medicalquestionai/data/ImageImportRegressionTest.kt`，第 90、103 行。

两个异常场景采用 `fun test() = runBlocking { ... }`，其最后表达式为 `store.importImage(...)`。Kotlin 推导出返回类型 `DraftImage`，并非 `Unit`。`try/finally` 也不改变 try 表达式的返回类型。

本次在重新编译后，用 `javap` 和 JUnit 4.13.2 自身的 `FrameworkMethod.validatePublicVoidNoArg()` 检查实际 class 文件，得到：

```text
JUnit signature validation: 4 methods, 2 errors
Method corruptImageReportsImportErrorInsteadOfCrashingWholeImport() should be void
Method unopenableUriReportsImportError() should be void
```

JUnit 在初始化测试类时要求测试方法返回 void；这会阻止该类的正常执行，`assembleDebugAndroidTest` 无法发现此问题。

修复方式：明确使用 `runBlocking<Unit>`，或将测试改为块函数体并在其中调用 `runBlocking`。保留预期异常断言和失败不遗留文件的检查，不能删除异常测试来消除报错。

复验条件：四个测试方法编译后均为 `void`，JUnit 签名校验通过；有设备后执行 `connectedDebugAndroidTest`，交回实际报告。没有设备时只能标记“签名问题已修复，设备执行待验收”。

复核脚本：`E:/souti/acceptance-artifacts/ValidateDeviceTest.java`。

证据日志：`E:/souti/acceptance-artifacts/device-test-signatures.log`。此脚本只校验测试结构，不代替设备测试。

### A2：补足 Android 裁剪回归断言（P2，R2 证据不完整）

同一测试文件第 53–57 行仅检查源文件存在、裁剪文件存在、返回宽度变小。即使错误实现覆盖原图并返回同一个路径，这些断言仍可能通过。

`ImageEditPolicyTest` 使用人为构造的不同路径，只证明列表插入逻辑，不能证明实际图片文件互不影响。

需要补充：

- 裁剪前记录导入图的内容哈希及实际尺寸，裁剪后验证它们完全不变。
- 验证新图 ID、规范化文件路径与原图不同，并读取新图实际尺寸和内容。
- 用边框、四角标记等合成图验证裁剪范围，不仅使用纯色图片。
- 删除裁剪图后原图内容仍在，删除原图后裁剪图仍在；验证题目删除/清空时两张图都清理。
- 补充取消、失败不改变列表及 9 张上限场景的验证证据。网页 9 张时拒绝新增裁剪图，本次已独立复核通过；Android 仍缺运行证据。

当前 `cropCopy()` 代码确实创建了独立输出文件。本项是回归验证不足，不是在声称已发现原图被覆盖的新生产缺陷。

### A3：保留历史测试记录（P3，R5 收尾）

`docs/TESTING.md` 第 156 行起仍使用原来的“本机验证记录”章节，但把原有 7 项单测及旧 APK 哈希替换成 14 项和新哈希，没有按计划保留历史记录并新增本次记录。

请将旧记录保留为明确的历史小节，另设修复后验证小节，分别写清日期/版本、命令和对应 APK。不要把本次发现的设备测试签名问题仅归类为“无设备”。

## 本次独立复核结果

| 检查 | 本次结果 | 证据/限制 |
| --- | --- | --- |
| Android 全量重新执行检查 | 通过 | `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain --rerun-tasks`；30 秒，84 项任务全部执行 |
| JVM 单测 | 14/14 通过 | Models 7、ImageEditPolicy 3、SettingsValidation 4；0 失败、0 错误、0 跳过 |
| Android Lint | 通过 | 0 errors、18 个 GradleDependency 版本提示 |
| 设备测试方法签名 | **失败** | 4 个方法中 2 个返回 DraftImage，JUnit 明确拒绝 |
| 仓库 `npm test` | 通过 | 无头 Chrome、本地模拟上游；非真实 DeepSeek |
| 扩展网页复核 | 通过 | 9 张时拒绝新增裁剪且已有图片不变；追问用量落库和刷新后保留；无用量/旧记录兼容；失败恢复输入；密钥切换至 sessionStorage 后持久存储副本清空，刷新后会话密钥保留 |
| JavaScript 语法 | 通过 | app/api/images/store/server/browser-repair-smoke |
| 交付 APK 哈希 | 与实施报告一致 | `dist/medical-question-ai-debug.apk`：`ed8b90eb1f75e5efaf4a1bdd70010ec2da0d653025ad8034ff67bb430e8e09f1` |
| 交付 APK 签名/元信息 | 通过 | v2 签名验证；包名 `com.xjb.medicalquestionai`、版本 1.0.0/1、minSdk 26、targetSdk 35 |
| 设备安装、相机/相册和真实模型 | 未执行 | 缺少设备与真实接口测试条件 |

本次重新构建的 `app/build/outputs/apk/debug/app-debug.apk` 没有替换交付目录中的 APK；重新打包后字节哈希可以变化，上表哈希针对已交付的 dist 文件。

构建环境：Windows、JDK `C:/Program Files/Java/jdk-17`、SDK `E:/android-sdk/android-sdk`、Gradle 8.9。SDK 根目录包含双层 `android-sdk`，重跑时应使用此实际路径。

证据位置：

- 构建日志：`E:/souti/acceptance-build.log`。
- JVM XML：`app/build/test-results/testDebugUnitTest/TEST-*.xml`。
- Lint：`app/build/reports/lint-results-debug.xml`。
- 扩展网页脚本生成器：`E:/souti/acceptance-artifacts/extend-web-test.mjs`。
- 扩展网页运行脚本及结果：`E:/souti/acceptance-artifacts/web-extended.mjs`、`web-extended.log`。

## 按修复计划的结项状态

| 工作包 | 状态 |
| --- | --- |
| R1 图片导入 | 根因修正已确认；测试签名需修复，设备验证待完成 |
| R2 保留完整图 | 实现已检查，网页复核通过；Android 回归断言与设备证据需补充 |
| R3 密钥/网络说明 | 本次代码与网页存储复核通过，Android 实机交互未验证 |
| R4 网页追问用量 | 本地模拟接口到持久化/刷新展示链路通过；真实接口未验证 |
| R5 项目文档 | 主要状态和隐私表述已修正；历史测试记录需恢复分节 |
| R6 验收证据 | 构建与网页证据已复核；设备测试代码仍有缺陷，不能结项 |

下一次交回：优先修 A1，补 A2、A3，更新实施报告；在可用环境运行针对性检查，再由验收方复核。设备或真实接口条件仍不足时，明确保留相应待验收项。
