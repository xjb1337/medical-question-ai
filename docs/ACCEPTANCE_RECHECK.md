# A1–A3 返工复验

日期：2026-10-28（Asia/Shanghai）

复验提交：`c8f4892`，返工代码 `5b5945d`。复验开始时工作区干净，原 `docs/ACCEPTANCE_REPORT.md` 保持不变。本次只新增本复验记录，未修改业务代码。

## 结论

**A1、A3 通过复验；A2 的主要测试补强通过代码复核，但设备执行和取消/裁剪失败场景的证据仍不完整。**

本次没有发现新的业务代码阻断问题。原先的 JUnit 签名阻断已解决；完整 Android 功能和真实模型验收仍不能标为通过。

## 分项结果

| 项目 | 结果 | 实际证据与边界 |
| --- | --- | --- |
| A1 测试方法签名 | 通过 | 重新编译后，JUnit 4.13.2 的 `FrameworkMethod.validatePublicVoidNoArg()` 校验全部 6 个测试方法：返回 void，0 错误。不是仅检查 Kotlin 源码 |
| A2 原图与裁剪图独立性 | 测试代码复核通过，设备运行待验收 | 已检查原图 SHA-256 不变、原始解码尺寸、新图独立 ID/规范化路径、600×450 实际尺寸和四象限图片内容断言。原图哈希不变也保证其尺寸未被修改 |
| A2 删除与清理 | 测试代码复核通过，设备运行待验收 | 已补删除任一文件不影响另一文件及其哈希的断言；`deleteAll()` 被题目删除/清空历史调用，设备测试验证该辅助方法清理两张图。尚未在设备运行完整 ViewModel/数据库/UI 删除流程 |
| A2 九张上限 | JVM 规则测试通过 | 验证八张可加、九张拒绝；ViewModel 在裁剪前后均调用规则。Android UI/异步流程未实测 |
| A2 取消/失败不改列表 | **证据仍不足** | `missingTarget_returnsNullWithoutChangingTheInputList()` 只覆盖目标不存在的列表编辑分支，不等于用户取消裁剪或实际图片读写失败。取消按钮源码只退出裁剪模式，逻辑可读，但没有对应交互运行证据 |
| A3 历史验证记录 | 通过 | 修复前 7/7 和旧 APK 哈希已恢复至 4.1，新 15/15 和新哈希单列 4.2；历史验收报告未被覆盖 |

## 本次独立执行

1. `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain --rerun-tasks`：通过，23 秒，84 项任务全部执行。使用 JDK `C:/Program Files/Java/jdk-17`、SDK `E:/android-sdk/android-sdk`。
2. JVM 测试：15/15，0 失败、0 错误、0 跳过（Models 7、ImageEditPolicy 4、SettingsValidation 4）。
3. Lint：0 errors，18 个 GradleDependency 依赖版本提示。
4. 编译后测试类结构校验：6 个 `@Test` 方法全部返回 void，JUnit 校验 0 错误。
5. `npm test`：通过，使用无头 Chrome 和本地 mock 上游；图片导入、保留原图裁剪、追问用量、旧记录/无用量、失败恢复和历史流程均通过。网页源码本轮未变更。
6. `dist/medical-question-ai-debug.apk` 的 SHA-256 与交接一致：`9da7a2aa7908811af2ced7a01979e4c4058610d6e10b8b445b3493b448a058f3`；APK v2 签名通过，包名 `com.xjb.medicalquestionai`，版本 1.0.0/1，minSdk 26、targetSdk 35。
7. `git diff --check 4b6b327..HEAD`：通过。
8. `adb devices`：无连接设备。未运行 `connectedDebugAndroidTest`、未安装 APK、未调用真实 DeepSeek。

日志与报告：

- 构建：`E:/souti/acceptance-recheck-build.log`。
- JUnit 结构校验：`E:/souti/acceptance-artifacts/device-test-signatures-recheck.log`；校验脚本仍为同目录的 `ValidateDeviceTest.java`。
- JVM：`app/build/test-results/testDebugUnitTest/TEST-*.xml`。
- Lint：`app/build/reports/lint-results-debug.xml`。

本次重新构建的 app/build APK 没有替换 dist 交付副本；上述哈希对应 dist 文件。

## 尚需完成

- 将交接报告中“missingTarget 覆盖取消/失败”的表述收窄为“目标不存在时不改列表”，取消和实际裁剪失败继续列为待验证。若要声称已覆盖，需补充对应 UI/业务流程或故障注入测试及运行证据。
- 连接设备或模拟器运行 6 项 Android 图片测试；在真机执行相机/相册、裁剪取消、读写失败恢复、保存/重开/删除/清空和数量上限流程。
- 具备真实接口条件后执行 DeepSeek 题型与医学图题验收。模拟上游测试不能替代真实模型效果验证。

上述设备与真实接口条件不足已被如实披露，不将未运行记为失败，也不将编译/结构检查通过记为设备功能通过。
