# App 内检测更新：实施与独立验收方案

日期：2026-10-29。基于本地 `db1a9b0`，供其他模型实施、再交由独立复核。本文不表示功能已经实现，也未修改 CI、发布 Release 或上传签名材料。

## 1. 要实现的体验

用户不再到 GitHub 找 APK。首次手动安装带本功能的版本后：

`推送 main → CI 验证、固定签名打包 → 发布 GitHub Release → App 提示新版本 → 用户点击下载 → 校验 → 系统确认安装 → 覆盖更新并保留数据`。

设置页始终提供“检查更新”，显示当前版本；App 在安全的首页时机可自动检查并温和提示。普通安装流程保留系统要求的授权/安装确认，不承诺静默安装。首次开启“允许此来源安装应用”是 Android 安装授权，不是逐题隐私勾选；不得重新加入拍题隐私门槛。

本轮只做 Android；不改变 Web 发布方式，不接入 Google Play，不做热修复、强制更新、后台自动安装或公共下载镜像。

## 2. 已核实的项目现状

- applicationId `com.xjb.medicalquestionai`，minSdk 26、targetSdk/compileSdk 35；当前 versionCode=2、versionName=1.1.0。
- 现有 `.github/workflows/android.yml` 在 push/PR 时测试、Lint、assembleDebug，仅上传 14 天有效的 Actions artifact；权限只有 contents:read，没有发布 Release。
- GitHub 连接确认 `xjb1337/medical-question-ai` 为 public，默认 main；因此 App 无需携带 GitHub 账号 Token 访问公开更新源。
- Manifest 已有 INTERNET 和 FileProvider；现有 file_paths 仅 captures/ 与 question_images/，尚无更新 APK 目录。
- SettingsScreen 有版本信息基础，尚无更新控制器。更新网络、状态与拍题/追问必须分离。
- 当前交付 APK 的公开签名证书 SHA-256：`77c3ca8d1a7cf0aca4718b328705bb21833976bc701dad89c11881c83ca1c861`，证书名 Android Debug。这里只读取了 APK 公共证书，未读取/输出私钥。

已用本地 Graphify 查询关联并确认源码；图输出保持本地。

## 3. 第一优先级：稳定签名与版本号

### 3.1 固定签名，保证覆盖安装

本轮目标是个人真机测试通道，优先延续现有手机 APK 的签名，避免卸载导致历史和密钥丢失。

1. 实施者在本地确认可用 keystore 的证书与当前 APK 一致；不在聊天、日志、仓库或报告输出私钥/密码/base64。
2. CI 使用固定 keystore，通过 GitHub Actions Secrets 注入。推荐名称 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；本地使用未跟踪配置。
3. keystore 只在受信任的 main 发布 job 使用；PR 验证不接触签名 secrets，不使用 pull_request_target 执行 PR 代码。
4. secrets 缺失或证书摘要不符时：普通测试可执行，发布必须明确失败；不得回退到 runner 自动生成的 debug keystore。
5. CI 使用独立签名配置，不直接依赖 runner 的默认 debug 签名；为 APK 记录证书摘要。
6. 若旧签名不可取得：停止宣称能保留数据覆盖升级，交付明确迁移说明供用户决定；不得自动卸载、清数据或暗中更换包名。正式对外发布的独立 release 签名规划留待后续，不伪称当前 Debug 通道是正式发行签名。

Secret 配置由实施者在可用授权范围完成；无法配置时交付准备好的 workflow 和精确清单，并如实标注发布未跑通。不要假造发布证据。

### 3.2 versionCode 严格递增

- APK versionCode 为唯一更新排序依据，用 Long 读取；不以字符串 versionName、tag、日期或 commit 字母序判新旧。
- Gradle 支持 CI 注入 versionCode/versionName，本地有明确默认值；第一次功能版本必须大于 2。
- 发布 workflow 使用固定、可复现的递增号规则，例如 `100000 + github.run_number`，重跑同一 run 不额外升级。具体前缀记录在发布文档。
- 新 workflow/重建工作流可能导致 run_number 改变；发布前必须检查候选 versionCode 大于已发布通道版本，避免回退。维护者迁移编号时显式调整偏移，不静默覆盖。
- 版本名可用 `1.2.0-test.<run_number>`；设置页显示版本名与构建号，方便验收定位。

## 4. 更新发布源与元数据

### 4.1 使用 GitHub Releases

初期仓库仅服务当前个人测试通道：每次 main push 验证成功后发布一个**普通、非 draft、非 prerelease** Release，tag 唯一且包含 versionCode/短 commit。本方案使用 `/releases/latest`，因此不得把产物仅放到 prerelease 或 Actions artifact 中。

- 元数据入口：`https://api.github.com/repos/xjb1337/medical-question-ai/releases/latest`。
- 精确寻找同一 Release 的 `update.json` 与 `medical-question-ai.apk`；拒绝缺失/重名，不取任意第一个 APK，不跨 Release 混配。
- App 使用 Release API 返回的 browser_download_url 读取 update.json；元数据中的 apkAssetName 再匹配同一 Release 的 APK URL。避免任意远程 apkUrl。
- 其他用途 Release 不能抢占该入口；未来存在正式/测试多通道时另行规划独立通道指针，本轮不混用 prerelease/latest。

### 4.2 update.json 约定

下面是结构示意，不可把示例值直接当成有效发布：

```json
{
  "schemaVersion": 1,
  "applicationId": "com.xjb.medicalquestionai",
  "versionCode": 100123,
  "versionName": "1.2.0-test.123",
  "minSdk": 26,
  "apkAssetName": "medical-question-ai.apk",
  "apkSizeBytes": 21875294,
  "apkSha256": "<64位十六进制摘要>",
  "signingCertSha256": "<签名证书摘要>",
  "commitSha": "<完整提交SHA>",
  "publishedAt": "<UTC ISO8601>",
  "releaseNotes": "本次修改说明"
}
```

- APK、摘要、包名、版本、minSdk、证书必须从最终签名 APK 提取并交叉核对；不得先根据预期填 JSON 再另行重打包。
- App 严格检查字段类型、必填项、正数范围与 SHA 格式，null 不转为字符串。未知 schemaVersion 明确提示当前版本暂不支持该更新信息。
- 元数据大小限制 64 KiB，APK 首期大小上限 150 MiB；超出时明确拒绝，不无限读入内存。CI 也遵守上限。
- releaseNotes 作为有长度上限的普通文本展示，不渲染网页或执行内容。

### 4.3 原子发布与并发

- main 发布 job 的 contents:write 只在需要的 job 开启，PR job 保持只读。
- 发布独立 concurrency group；不要依赖队列一定 FIFO。发布前检查该提交仍为目标分支最新提交、候选版本仍高于当前已发布版本，过期构建不把 latest 指回旧包。
- 先创建 draft Release，上传最终 APK 与 update.json，确认资产可用、元数据对应，再发布为 latest；上传失败不能公开半成品。
- tag/versionCode 对应不可变产物；同一版本重跑若摘要不同，明确失败，不覆盖已公开包。
- Web 检查、Android JVM、Lint、APK 构建在发布前通过；AndroidTest 编译可以纳入现有门禁，不能写成设备测试通过。
- 只发布签名产物与公开摘要，不发布 keystore、密钥、构建环境变量或用户数据。

## 5. App 检测与提示交互

### 设置页

“应用更新”卡片显示当前版本/构建号、检查按钮、状态及最近成功检查时间；可提供“启动时检查更新”开关，默认开启。手动检查不受自动检查间隔限制，但并发点击合并为同一请求。

状态明确区分：检查中、已是最新、发现更新、暂时无法检查、下载中、校验中、可安装、等待授权/安装确认、失败。没有 Release 的 404 显示“暂无已发布更新”，不能显示网络故障或伪造“已经最新”。

### 自动检查

- 在首页处于前台、无相机/权限弹窗/解析/追问进行时检查，默认成功检查间隔至少 6 小时。
- 结果页、拍照、编辑和追问期间不弹更新对话框，不打断题目流程；可暂存检测结果，返回安全首页再提示。
- 不因 Compose 重组/旋转重复检查或重复弹窗；“稍后”本次前台会话内不再提示同一版本。手动检查始终可再次查看。
- 自动检查失败安静处理，不阻止拍题；手动检查失败明确显示“检查失败，可重试”。失败有退避，不能每次导航都重试。
- 不自动下载、不强制安装。更新弹窗包含版本、简短说明、大小、“下载更新/稍后”。

## 6. 下载、校验与安装

### 6.1 独立下载控制器

- 使用独立 UpdateRepository/UpdateViewModel 与独立 OkHttpClient，不复用带 DeepSeek Key、baseUrl 或题目数据的请求构造器。
- 更新网络不读取或上传题目、图片、API Key；App 不内置 GitHub Token。不要为下载困难添加随机公共代理。
- HTTPS；GitHub Release 下载会重定向到其资产域名，允许经核实的官方资产重定向；逐跳禁止降为 HTTP，不把可控 JSON 中的 URL 直接信任为任意下载源。初始 URL 必须属于本仓库对应 Release。
- 下载到 app 私有 cache/updates/ 下独立 `.part` 文件；流式写磁盘，支持取消、进度、无 Content-Length 的情况；完成校验后原子改名 `.apk`。
- 单次只允许一个更新下载；旋转不重复下载。离开设置可后台于当前进程继续，回到设置可看到进度；不承诺进程死亡后继续后台下载。
- 进程被杀后的 `.part` 不作为完成包；本轮允许重新下载，不必实现断点续传或前台服务。取消/失败清理自己拥有的临时文件，不碰 captures、question_images 或数据库。
- 明确超时、断网、存储不足、用户取消；下载失败不能落到“可安装”。未知长度显示已下载大小，不伪造百分比。校验在 IO 线程执行。

### 6.2 安装前检查

按顺序检查实际文件大小、SHA-256、APK 可解析性、applicationId、versionCode、minSdk、候选证书与当前已安装应用证书兼容性；与 update.json 一致且比当前安装版本新才可安装。API 26/27 与 API 28+ 的签名读取差异需要适配。

元数据给出的证书摘要本身不是信任锚；以已安装应用的真实签名为基准比较。SHA 防止下载损坏，不能代替签名信任；最终 APK 签名有效性和覆盖兼容性由系统安装器强制校验。PackageManager 解析成功也不能等价于安装成功。

再次点击“安装”时复查文件与已安装版本，防止使用旧缓存；同版本/低版本、错包、错签名、损坏或不支持设备均拒绝，并给可理解提示。

### 6.3 系统安装

- Manifest 增加 REQUEST_INSTALL_PACKAGES；用户点击安装时才检查 canRequestPackageInstalls()，未允许则解释并打开当前包的 ACTION_MANAGE_UNKNOWN_APP_SOURCES 设置页。
- 授权返回后重新检查；保留已校验包，给“继续安装”入口。拒绝/返回不循环弹设置、不重新下载、不标记成功。
- 复用既有 FileProvider，但只增加 cache/updates/ 窄路径，不放开整个私有目录。通过 content URI、APK MIME 与临时读授权调用系统安装器；不使用 file://。
- 无安装器或启动失败时可恢复地显示错误；用户取消后 App 保持可用，已下载包可重新安装。
- 不调用静默卸载/清数据，不自动关闭正在运行的题目任务；有解析/追问活动时提示稍后安装，避免安装重启造成任务丢失。
- 启动安装器不等于安装完成：显示“请在系统界面完成安装”，下次启动读取真实已安装版本确认更新；不得提前显示“已更新”。
- 成功升级后确认设置、密钥、历史、图片、收藏、DB v8 数据保留。当前工程 allowBackup=false，不依靠系统备份兜底。

## 7. 建议改动文件与责任

| 位置 | 责任 |
| --- | --- |
| app/build.gradle.kts | 版本注入、固定签名配置，保留本地开发可用 |
| .github/workflows/android.yml 或独立 release workflow | main 验证后打包发布，PR 无发布权限 |
| scripts/ 下元数据生成/发布校验脚本 | 从最终 APK 提取并比对信息，不泄露 secrets |
| data/update/ 下新增模型、仓库、校验器 | 严格元数据解析、版本比较、GitHub 访问、文件下载/校验 |
| 独立 UpdateViewModel / 更新 UI | 生命周期、状态与并发管理，不混用 MainViewModel 的题目 Job |
| SettingsScreen / AppNavigation 首页安全提示入口 | 检查入口、弹窗、下载状态和安装触发 |
| AndroidManifest.xml / file_paths.xml | 安装权限声明与最小 FileProvider 路径 |
| docs/APP_UPDATE_RELEASE_GUIDE.md | 首次安装、secrets 配置、版本规则、发布/回退与故障处理 |

具体类名可按项目风格调整；不为此重构题目分析、数据库、追问与相机核心链路。

## 8. 验收矩阵

| 编号 | 必须验证的行为 | 证据层级 |
| --- | --- | --- |
| U01 | 新/同/旧 versionCode，版本名误导时仍按数字判断 | JVM |
| U02 | null/缺字段/错误类型/未知 schema/超限元数据不误报更新 | JVM + 网络桩 |
| U03 | 404、超时、403/429、无网络、缺失/重名资产有正确状态 | 网络桩 |
| U04 | 两个 Release 不混配；拒绝 draft/prerelease；跳过不兼容 minSdk | JVM/网络桩 |
| U05 | 流式下载、未知长度、取消、失败、大小/哈希不符不允许安装 | 生产下载器测试 |
| U06 | 错包名/版本/签名/损坏包拒绝；同签名新版本允许 | APK 校验器 + 设备 |
| U07 | 连点/旋转/页面往返不重复请求或下载，状态可恢复 | 控制器 + 设备 |
| U08 | 首次安装授权、拒绝、返回、继续安装不循环且不重下载 | 设备 |
| U09 | 系统取消/无安装器/安装失败不伪称已更新 | 设备 |
| U10 | 老版→新版覆盖升级，历史/密钥/图片/收藏保留 | 真实两包 + 设备 |
| U11 | 分析/追问/相机不被检查和更新提示打断，安装时活动任务受保护 | 设备 |
| U12 | 进程死亡后半包不安装；校验包丢失可重下，清理不误删题图 | 控制器 + 设备 |
| U13 | main 发布最终包、元数据、证书、commit 一致；PR 不发布、不用签名 secrets | CI 实跑 |
| U14 | 旧 workflow/并发/重跑不回退 latest，不覆盖不同摘要的同版本包 | 发布脚本测试 + CI |
| U15 | 不携带 DeepSeek/GitHub 密钥，不改逐题隐私行为 | 静态 + 请求证据 |
| U16 | 无更新时照常拍题，自动失败安静、手动失败可见，限频/稍后有效 | 控制器 + 设备 |

回归：保留既有 Android 64 项与 Web 19 项测试（新增测试后总数据实更新），运行 JVM、Lint、assembleDebug/AndroidTest 及实际签名发布构建；不以测试数代替生产调用链证据。安全校验与状态分支值得测试，不要求镜像 UI 文案的无效测试。

## 9. 必须交付的东西与完成标准

1. 可复核 diff/提交与实现报告，按 U01–U16 标明通过/未执行及证据。
2. 可手动首次安装的引导 APK，版本高于 2、签名兼容当前包；给 APK SHA、大小、versionCode/name、证书摘要和 commit。
3. 第二个更高版本的真实签名包与对应公开 Release，用于 App 检测/下载/覆盖升级；不能只用 mock 证明端到端完成。
4. CI 发布 URL/run 链接、update.json 与 APK 的对应性记录；签名 secret 未配置或 CI 未运行要单列，不说“推送即更新已完成”。
5. 真机安装权限、取消/重试、覆盖升级数据保留的录屏或日志；没有设备则如实待验，不用代码审查代替。

验收分层：代码/自动检查通过 → 发布链实际通过 → 真机两版本升级通过，三层分别给结论。没有真实 DeepSeek Key 不阻碍本功能本身的检测/下载验证，勿因此伪造接口测试或重新跑无关质量矩阵。

## 10. 官方资料

- GitHub Releases API、latest 与 assets：https://docs.github.com/en/rest/releases/releases
- GitHub API 限流：https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api
- Android 安装授权与 canRequestPackageInstalls：https://developer.android.com/reference/android/content/pm/PackageManager
- Android 安装器与用户确认：https://developer.android.com/reference/android/content/pm/PackageInstaller
- Android 8 起按来源安装授权：https://android-developers.googleblog.com/2017/08/making-it-safer-to-get-apps-on-android-o.html

本方案采用 GitHub Releases 分发并在 App 中调用系统安装流程；不是 Google Play In-App Updates。第一次新增本功能仍需手动安装一次，此后才可使用 App 内更新。
