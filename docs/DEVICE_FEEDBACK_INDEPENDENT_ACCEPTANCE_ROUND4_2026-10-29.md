# 第四轮独立验收（2026-10-29）

**结论：本轮 R3-01～R3-03 代码整改通过，可进入真机验收；整体产品验收尚未完成。**

检查对象为当前工作区。HEAD 仍为 5e75082，本轮实现尚有未提交改动，不应将该提交号单独作为本轮全部实现的标识。审查过程中未修改业务代码、实现测试或交付 APK，仅新增本报告及独立证据。

## 逐项复核

| 项目 | 结论 | 独立证据与限制 |
| --- | --- | --- |
| R3-01 非法答案预览 | 通过 | 对实际编译类运行单选 A、B、非法 question_type、答案 A 与选项分析 B 冲突三个反例，feed→toResult 均为 needs_confirmation、空答案，不再满足正常答案卡准入条件 |
| R3-02 计时边界与保存异常 | 代码整改通过 | requestBodyEnd 发出 RequestBodySent；Analyzer 排除请求体/响应头事件后记录首 SSE 事件；parseResult 前后记录 validationMs；第二阶段及最终指标写入失败会提示并进入任务失败状态。尚未对 Room 分阶段写入故障或网络可控延迟做独立动态注入 |
| R3-03 既有 null 污染 | 通过（合成逻辑与静态检查） | 实际 trimFollowUpHistory 对 nullnullnull+正文的旧助手消息不再保留该轮，探针 polluted_history_kept=0；仓库读取亦过滤重复前缀，UI 有对应保护；原始库记录未批量覆盖 |
| 新 null 分片 | 通过（解析函数） | 对实际 DeepSeekClient 私有解析函数连续注入 100 个 content/reasoning_content/finish_reason 显式空值分片，伪正文/思考增量为 0。尚需真实接口真机确认 |
| 追问版本与编辑保存回调 | 保留 | 代码仍使用 answerRevision 过滤；编辑保存成功后关闭，失败保留草稿的回调逻辑保留 |
| 隐私交互 | 符合本轮要求 | 每次拍题勾选及追加图片确认已移除，不作为验收缺陷 |

独立探针核心输出：

```text
单选多答案：preview=needs_confirmation answer=[] final=needs_confirmation
非法题型：preview=needs_confirmation answer=[] final=needs_confirmation
选项冲突：preview=needs_confirmation answer=[] final=needs_confirmation
100_null_frames_fake_deltas=0
polluted_history_kept=0
```

## 自动检查与 APK

- Android testDebugUnitTest、lintDebug、assembleDebug、assembleDebugAndroidTest 构建成功。另以 --rerun-tasks 强制重跑 JVM 测试；59/59，0 failures/errors。Lint 为 0 errors、22 warnings。AndroidTest 仅编译，不代表设备执行。
- Web npm --prefix web test：19/19；浏览器 smoke ok:true，包含 followUpsClearedOnEdit:true。
- git diff --check：退出 0。
- 独立证据在 `docs/evidence/independent-round4-2026-10-29/`，含构建、强制单测重跑、Web、独立探针日志与 Java 源文件。
- 交付包 `dist/medical-question-ai-debug.apk` 与检查时的 `app/build/outputs/apk/debug/app-debug.apk` 哈希一致，均为 `a14450b30a71a93ffc45e93e03caac1eb05ad9ca6502b33fdf0f56e48555b7cf`；大小均为 21,827,839 bytes。此前交付对应性疑问在本轮检查范围内关闭。

## 非阻断说明与后续验收

保存耗时目前先采样再以额外一笔写入持久化，因此落库 totalMs 的时间点早于最后一笔“指标补写”完成。它已覆盖前两阶段结果保存，不能无限追加写入以追逐最后一次完成时间。建议文档明确区分“结果提交耗时”和“指标补写”，不要将该小段差异作为产品速度结论，也不为这一点继续阻断本轮代码整改。

下一阶段需要在实际手机与真实 Key 环境执行：拍照/相册/追加图片、连续追问与 null 回归、取消/重答/切页、DB v7 覆盖升级、编辑保存异常、360/412dp 与字体缩放等 UI 检查，再完成 40 题质量及 15 题×3 次/版本速度比较。

本轮没有这些环境和运行数据，不能宣布准确率、真机 UI 或提速目标达标。建议测试安装时同时记录本报告 APK 哈希，避免版本号相同但安装包不同造成混淆。
