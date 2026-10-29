# 应用内更新：第一轮独立验收

2026-10-29；审查目标 `cef2b30`，并包含 `95a8963`、`2c17bca`。依据《IN_APP_UPDATE_IMPLEMENTATION_PLAN_2026-10-29.md》。

**结论：暂不通过，2 项 P1、3 项 P2。** 以下结论来自源码与局部生产代码探针；未进行设备安装、真实 Release 发布或 GitHub Actions 执行。未修改业务代码、正式测试、工作流或交付 APK，未上传签名材料或创建提交。

## UP-01 · P1：下载/安装状态缺少用户可用的退出与恢复路径

位置：`ui/components/UpdateUi.kt:49–85,117–143`；`ui/AppNavigation.kt` 首页 UpdateProgressDialog 调用；`data/update/UpdateViewModel.kt:110–184`。

已实现进度、安装授权状态与系统安装跳转，但实际 UI 有以下断路：

- 首页下载弹窗在 DOWNLOADING/VERIFYING 时不允许返回关闭，按钮是禁用的“请稍候”；控制器没有取消下载函数，也没保存下载 Job/Call，用户不能取消或关闭弹窗后继续拍题。
- READY_TO_INSTALL 时系统返回调用的 onDismiss 在 AppNavigation 中是空回调，弹窗依然无法关闭，没有“稍后安装”。用户下载完只能继续安装，或强行离开应用。
- UpdateActionButtons 虽有 FAILED→重试下载分支，但外层 UpdateSection 只在五种非失败状态调用它，失败分支不可达。失败后只能重新检查再下载，不能按交付声称直接重试。
- 安装器启动后状态保持 INSTALLING。若用户在系统界面取消，返回设置没有继续安装入口（该状态不在外层显示集合），首页也没有恢复入口；已下载且校验的包不能直接再安装。
- 首页安装遇到未授权会切到 WAITING_INSTALL_PERMISSION，首页进度弹窗随即消失，授权按钮只在设置页，当前流程没有就地引导。

这些是源码分支可达性问题，不声称已真机复现。它们直接违背可取消、稍后、取消安装后可恢复的目标。

**整改：** 把“是否展示弹窗”与下载/就绪状态分开；提供取消下载、关闭进度后继续使用及稍后安装，真正取消底层 HTTP 请求并清理自己的临时文件。FAILED 与 INSTALLING/系统返回必须有明确恢复状态和入口，已校验文件可复用。首页与设置共用授权/继续安装路径。安装前检查拍题/追问活动，安装校验移到 IO（当前 install 的 viewModelScope.launch 在主线程同步计算 APK 哈希），不能卡住 UI 或中断活动题目。

**复验：** 控制器/实际 UI 入口覆盖下载中取消、就绪后关闭、下载失败直接重试、授权拒绝/返回、系统取消安装后重试，以及活动题目时延后安装。不能仅测状态枚举。没有设备的部分继续标注未执行。

## UP-02 · P1：Release tag 未固定到构建提交，过期构建可发布

位置：`.github/workflows/release.yml:96–117`。

checkout/COMMIT_SHA 使用触发事件提交 A，但 gh release create 没有 `--target`，也未预先建立并验证 tag。GitHub CLI 对不存在的 tag 默认从默认分支最新状态创建。A 构建期间 main 前进到 B，发布 tag 可以指向 B，资产却由 A 构建、清单 commitSha 也是 A。参见 [gh release create 官方说明](https://cli.github.com/manual/gh_release_create)。

发布前也没有“候选提交仍为 main 当前头”的检查，concurrency 只序列化工作流，不能替代提交归属检查。官方亦不保证按工作流派发先后顺序执行，参见 [并发控制说明](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)。

latest 查询用 `2>/dev/null || true` 将任意获取失败当成没有版本；网络/权限异常会绕过单调性门禁。workflow_dispatch 的 release job 还会因 if 只允许 push 被跳过，指南不能把它当成可用发布重试入口。

**整改：** 发布 tag 明确绑定 `github.sha`，校验现有 tag 也指向它；发布前重新读取 main 头，过期构建不提升 latest。可靠读取同通道最新 manifest；仅确认不存在的情况允许首次发布，其他网络/授权/解析错误必须失败。候选版本和提交/产物不可变，重跑按同版本同摘要幂等处理，异摘要拒绝。实际数字规则目前是字符串拼接 `100000` 与 run_number，应改为有 Android 整数范围校验的清晰规则；不要把它误写成数值相加。发布门禁补 Web 既有检查。

**复验：** 用可注入 gh/远端查询的脚本测试 A 构建中 main→B、latest 读取失败、同版本重跑、已存在错指向 tag、并发旧构建；之后再补 CI 实跑证据。本轮没有远程创建 Release 来制造复现。

## UP-03 · P2：生成清单的版本、包名、minSdk 和证书未全部取自 APK

位置：`scripts/release/generate-update-json.sh:16–40`。

脚本仅从文件读取大小和 SHA；versionCode/name 来自环境变量，applicationId/minSdk 为常量，signingCertSha256 直接来自 EXPECTED_CERT。workflow 另有 apksigner 与预期证书对比，这是有效保护，但没有核对 APK 实际包名、版本、minSdk 与清单相等。

**局部验证：** 对脚本中原样提取的 Python 生成段传入实际 guide APK 的大小、SHA 与证书，但指定 versionCode=9999/name=9.9.9-test，成功写出清单。aapt 独立读取该 APK 实际为 versionCode=3/name=1.2.0。该探针只执行真实脚本的 Python 段；完整 Bash 在本机 Git Bash 调用 python3 时退出 49，未据此推断 Linux CI 也会失败。

App 最后的 APK 校验会拒绝这种包，未发现绕过安装校验；问题是发布端会把不可安装的更新展示并让用户白下载。

**整改/复验：** 从最终签名 APK 提取包名、versionCode/name、minSdk、真实证书，环境变量仅作期望值逐项比对；不符则发布失败。加已知 APK 与故意错版本/错包/错证书的生成脚本测试，不仅测试 JSON 解析。

## UP-04 · P2：资产 URL 仅匹配仓库前缀，允许跨 tag 混配

位置：`data/update/UpdateRepository.kt:18–40,82–99`。

ReleaseAssetSelector 虽要求唯一 update.json/APK，却只验证共同 `/releases/download/` 前缀，不验证两 URL 属于 root.tag_name，也不验证 URL 文件名/路径与所选资产一致。根 tag=new、manifest URL 指向 old、APK URL 指向 new，仍被接受。

**独立执行：** 用 OkHttp interceptor 返回上述组合，调用真实编译后的 UpdateRepository.checkForUpdate，得到 Available：`crossReleaseAssetsAccepted=true`。没有发送远程请求。真实 GitHub 正常响应通常自洽；本发现是明确同 Release 绑定校验要求未落实，不声称 GitHub 实际返回了混配资产或能够绕过签名。

**整改/复验：** 使用 HttpUrl 与安全编码后的 tag/asset name 精确绑定本仓库同一 Release；处理 tag 含 `/` 等编码，不做简单 contains；资产 type/name/tag/path 缺失、跨 tag、重复资产均拒绝。为生产 selector/检查函数增加上述探针场景。

## UP-05 · P2：元数据限额在全量读入后检查，不能限制内存/读取

位置：`data/update/UpdateRepository.kt:79–80,119–120`。

Release JSON 与 update.json 都调用 body.bytes() 读取整个响应，然后才检查 1 MiB/64 KiB 上限。没有 Content-Length 或服务端忽略长度时，响应可持续扩大，达到限制也不会停止读取。

**独立执行：** 给生产检查函数返回无 Content-Length 的 2 MiB manifest，计数 ResponseBody 实际读入 `2,097,595` 字节才拒绝；配置限制 `65,536`。探针验证超量读取，未制造真实 OOM。

**整改/复验：** Content-Length 超限时尽早拒绝，同时逐块最多读上限+1 字节；两个 JSON 入口都用有界读取。确保取消会取消正在 execute/read 的 Call（当前 ensureActive 仅在 APK read 之前检查，不能立即打断阻塞读取），不将 CancellationException 当普通检查失败。对超长/无长度/慢速响应验证及时退出与文件清理。下载失败时若已存在旧 final APK，现有 finally 不删 part 文件，也需按本次下载归属修正。

## 独立自动检查与已完成部分

- Android testDebugUnitTest、lintDebug、assembleDebug、assembleDebugAndroidTest 成功；另以 --rerun-tasks 重跑 JVM，69 tests、0 failures、0 errors；Lint 0 errors、22 warnings。AndroidTest 仅编译。
- Web 19/19、smoke ok=true，含 followUpsClearedOnEdit=true。
- git diff --check 退出 0。
- guide：21,940,535 bytes，SHA-256 `1818eea30285ae5648325c9120078b77eaf8cb80cd71d47093fb0f18a102aa87`；aapt 读取 3/1.2.0。
- candidate：21,458,738 bytes，SHA-256 `c7baddfebcfc4bcc337d78622f772ed4f29dda4c8184955d3c2297b41b836f33`；aapt 读取 4/1.2.1-test.1。
- 两包 apksigner verify 成功，证书均为 `77c3ca8d1a7cf0aca4718b328705bb21833976bc701dad89c11881c83ca1c861`，与现有 Debug 包相同。这证明两份本地包的签名相容基础，不代表已完成设备覆盖升级。
- 独立网络客户端、HTTPS 域名限制、流式 APK 写入、下载摘要/大小、包名/数字版本/minSdk/已安装证书检查、窄路径 FileProvider、未知来源授权状态、安装前复核与 draft 资产上传已有实现。
- 仅新增 5 项正式更新单测，重点覆盖解析、数字比较和 selector；没有下载器、安装状态恢复与发布并发生产链测试。本轮 69/69 不等于 U01–U16 全覆盖。

证据：`docs/evidence/independent-in-app-update-2026-10-29/`。独立 Java 探针在仓库外编译，以测试运行时 classpath 调用当前产物，网络由内存 interceptor 替代，未调用真实 API。未修改实现测试。

## 未执行与文档修正

发布链与真机升级均尚未执行，且存在本轮代码阻断，不建议先引导用户安装后宣称以后推送即能更新。修复后分开验收代码、真实 Release/CI、真机两版本升级与数据保留。

发布指南当前写“正式 keystore”，却没有说明必须与已安装 guide 的 Debug 证书兼容；应明确固定的现有签名延续路径，避免换正式证书后所有 guide 用户都被 App 拒绝。不能要求自动卸载/清数据。不得在报告放私钥或密码。

报告“每24小时提示”与实际6小时成功检查不一致；指南要求改 Gradle 默认版本但 CI 用注入值，需统一。docs/update.json 是本地候选样本，实际客户端读取 Release 资产；报告不能把它当成已发布源。按 U01–U16 逐项补结果，不用笼统的69项测试代替。

本轮按要求更新/查询本地 Graphify，并用源码确认所有重要结论；无远程语义索引、无图文件提交。
