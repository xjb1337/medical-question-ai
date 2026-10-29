# 应用内更新：第二轮独立验收

审查提交 `2b7a6b7`，实现提交 `8cecfae`。本轮未修改业务代码、正式测试、工作流或交付 APK；不创建提交、不发布 Release、不配置或读取签名私钥。

**结论：暂不通过。2 项 P1、1 项 P2。** 第一轮若干缺陷已修复，但新增下载取消/重试竞态与 SDK 工具查找失败，发布身份校验也仍未完整。

## U2-01 · P1：取消后立即重试，旧任务清理覆盖新下载

位置：`data/update/UpdateViewModel.kt:115–208`；`data/update/UpdateRepository.kt` 固定 finalFile/partFile 与 finally 清理。

cancelDownload 取消旧 Call/Job 后立即清空 downloadJob 并设 downloading=false，没有等旧任务退出。用户可立即重试，新任务与旧任务同时存在。旧任务的 onCall(null)、catch 和 finally 没有请求身份判断，会清空新任务的 activeDownloadCall/downloadJob、重置 downloading 并覆盖 UI 状态。两个下载还使用同一 `.part`/最终文件路径，旧任务 finally 可能删除新任务的文件。

**生产控制器独立探针：** 直接构造生产 UpdateViewModel，注入隔离的 UpdateRepository/OkHttp interceptor 与假的 Application/SharedPreferences。让下载 A 的退出延迟，取消 A 后开始 B，再释放 A。记录：

```text
newJobStillActive=true
downloadJobReferenceClearedByOldFinally=true
activeCallReferenceClearedByOldFinally=true
downloadingFlagWhileNewJobActive=false
uiStatusWhileNewJobActive=FAILED
```

探针使用协程测试 Main dispatcher 与内存网络控制，不运行真实网络、数据库、Compose 或设备。这确认实际控制器的任务竞争，不冒充真机复现。文件相互删除风险由同一路径及无身份清理源码确认，未声称探针已复现具体文件损坏。

**整改：** 取消期间维持 cancelling/忙状态，直到旧任务退出并完成资源清理后才允许新下载；或建立下载 requestId，将全部进度/完成/失败/清理回调限定到拥有者，并使用各任务自己的临时文件。onCall(null)、finally 不能清除后来任务引用。真正取消与普通网络失败需区分。取消新任务时必须仍可触达新 Call。

**复验：** A 开始→取消→立即重试 B→释放 A，B 的状态/引用/文件保持正确；再取消 B，确保其 HTTP 请求停止。验证连点、校验期间取消、重试成功、旧失败迟到和文件清理边界；不能只测 cancel 按钮可见。

原 UP-01 的弹窗关闭、稍后、FAILED 操作分支及 INSTALLING 返回恢复入口已接入，安装哈希校验移至 IO，这是有效修复。仍应补追问活动保护：首页/设置传入的 canInstall 只考虑 isAnalyzing，没有接入 followUpSendingIds。真实系统取消/授权恢复未执行。

## U2-02 · P1：SDK 工具查找返回多行，清单生成直接失败

位置：`scripts/release/generate-update-json.sh:18–39`。

find_tool 遍历 ANDROID_HOME、ANDROID_SDK_ROOT、local.properties，对每个 root 都输出找到的文件且不 return/break。环境中有两项指向有效 SDK，即使是同一目录，也会使 AAPT/APKSIGNER 为多行文本；随后 `"$AAPT" dump badging ...` 把多行作为一个可执行文件名。

**真实脚本局部执行：** 给两个 SDK 环境变量设置同一已安装 SDK，并保留本项目 local.properties，执行实际脚本与实际 guide APK。AAPT 收到三行路径，报 No such file or directory，退出 127。公开证书/路径日志留证，无私钥或密码。该结果不依赖 python3 的前轮本机问题，失败在 aapt 执行之前；尚未远程运行 CI，不声称已在 GitHub runner 复现。

**整改：** 第一次找到有效可执行文件后只输出一个路径并立即返回；空 root、重复 root、无该工具都需可靠处理。不同 SDK 环境变量或 Windows 路径不能拼入同一结果。

**复验：** 单个 root、两个相同 root、两个不同 root、仅 local.properties、有空/缺工具 root、有空格的路径；对最终 APK 运行完整脚本成功，再故意给错期望版本/证书时应失败。仅测试 Python JSON 写入段不够。

UP-03 的包名/版本名/版本号/minSdk/证书读取和期望值比对源码已加入；本轮仍不能把完整生成链标为通过，因为当前上述查找问题直接阻断脚本。

## U2-03 · P2：发布前提交检查过早，既有 tag 指向未验证

位置：`.github/workflows/release.yml:42–46,111–144`；`scripts/release/check-release.py:30–38`。

新增 `--target "$GITHUB_SHA"` 修复了**不存在 tag**时默认指向 main 最新提交的问题；latest 请求失败也不再一律当不存在。但仍有两个缺口：

- main head 检查在准备环境、构建、生成清单之前，可能间隔数分钟。构建期间 main 前进，Publish Release 不再复查，过期构建仍会被提升为 latest。
- check-release.py 只比较候选 tag 与 latest tag。它没有查询所有既有 tag，也没有核实候选 tag 的远端 commit；candidate-sha 仅检查非空。候选 tag 已存在、指向其他提交但没有 Release 时，gh release create 可复用该 tag，`--target` 不会把既有 tag 移到候选提交。

本项是实际脚本调用链静态确认，未创建远端 tag/Release 制造复现。GitHub CLI 对 `--target` 自动创建 tag 的含义见 [官方说明](https://cli.github.com/manual/gh_release_create)。

**整改/复验：** 在实际公开/提升 latest 前重新核验 main head；查询候选 tag 是否存在，按明确不可复用策略拒绝，或核实同 SHA 同摘要才允许幂等继续，错 SHA 一律拒绝。覆盖旧 head、候选 tag 已存在但非 latest、tag 指向不同 SHA、首次发布与同版本重跑；记录脚本测试和后续 CI 实跑。补既定 Web 发布前门禁。

## UP-04、UP-05 复验结果

- 跨 tag：旧探针补齐本轮要求的真实资产 content_type 后，调用当前生产 checkForUpdate；old manifest tag/new APK tag 组合现已拒绝，输出 `crossReleaseAssetsAccepted=false`。原跨 tag 缺陷关闭。
- 无长度超长清单：生产读取最多 73,728 字节即拒绝，限制 65,536，超过量为一块 8 KiB；不再读完整 2 MiB。这是有界读取，原全量读入缺陷关闭，不要求必须只多读1字节。
- 已存在旧 final APK 时，part 清理已不再依赖 final 是否存在，源码修复保留。
- 仍有编码边界待扩测：tag 含 `/` 时 expectedSegments 使用 split，而 URL 可能把斜杠编码为同一段；本轮 CI 生成的 tag 不含斜杠，暂不单列发布阻断。
- 检查请求的 invokeOnCompletion 是完成时回调，不能仅据此宣称阻塞读取消已验证；取消/慢速读取继续需要实际控制器测试。本轮下载按钮明确调用 Call.cancel 的改动有效，但受 U2-01 引用丢失影响。

## 自动检查与 APK

- Android testDebugUnitTest、lintDebug、assembleDebug、assembleDebugAndroidTest 成功；另强制 --rerun-tasks 重跑 JVM，69 tests、0 failures、0 errors；Lint 0 errors、22 warnings。AndroidTest 仅编译。
- Web 19/19，smoke ok=true，含 followUpsClearedOnEdit=true；git diff --check 退出0。
- guide：21,475,118 bytes，SHA-256 `84b9e8db3b7c9e9e56c31a463a73170943f67517b466140f0a55cc73aa0e5f06`，aapt 3/1.2.0。
- candidate：21,475,122 bytes，SHA-256 `0b4ff605ce8ab94ce952157ab4108669b954306100f94ac0f1c0bfbc63c9878f`，aapt 4/1.2.1-test.1。
- 两包 apksigner verify 成功，同一公开证书 `77c3ca8d1a7cf0aca4718b328705bb21833976bc701dad89c11881c83ca1c861`，与交接一致；未设备覆盖安装。
- 证据：`docs/evidence/independent-in-app-update-round2-2026-10-29/`，含控制器竞态探针、绑定/有界读取探针源码与输出、脚本执行日志、测试和 APK 摘要。探针在仓库外编译执行，不计入69项正式测试。
- 本轮按要求刷新/查询本地 Graphify 并回查源码；无远程语义处理或图文件提交。

## 下一步与未执行边界

修复 U2-01～03，保留已修复的严格解析、校验、隐私勾选移除、弹窗关闭和恢复入口。下一次应增加生产下载控制器及完整发布脚本回归证据，而不只维持解析测试数量。

真实 GitHub Actions/Release、下载源可达性、真机授权/安装取消恢复、两版本升级和数据保留仍未执行。**GitHub 公开更新源检测不需要 DeepSeek API Key**；无真实 Key 仅限制拍题真实模型测试，不能作为无法验证公开更新链路的原因。无设备和发布环境时可继续准确标注待验，不使用 mock 代替。
