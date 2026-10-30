# 历史图片放大与每日自动更新检查

## 交付行为

1. 点击题库（历史）卡片缩略图打开图片查看器；点击卡片正文仍进入题目解析。解析页图片也支持查看。
2. 查看器支持双指缩放、拖动、双击切换 1 倍/3 倍、按钮缩放（1～6 倍）、复位、多图切换和关闭。换图后恢复 1 倍。图片丢失时显示错误提示。
3. 查看器直接读取本地保存的题目图片文件，使用原尺寸加载请求，不放大历史缩略图。
4. 每天第一次进入或恢复 App 前台时自动检查更新，按设备本地自然日记录。同一天切页、重开进程不会重复自动检查。跨午夜后再次进入无需等满 24 小时。
5. 自动请求前保存当天日期。离线进入或请求因断网失败时，恢复网络后自动补查一次；补查次数持久化，反复切网、切页或重开进程不增加次数。HTTP 限流、清单格式异常、仍在线时的超时不触发自动补查。设置页可手动重试。正在解析/追问时延后到任务结束，下载及待安装期间不发起自动检查。
6. 历史缩略图增加放大图标，多图题显示数量；查看器显示双指缩放、拖动和双击提示。
7. 更新弹窗新增“本版本暂不提醒”，按 versionCode 持久化，只关闭该版本的自动弹窗。更高版本仍会提醒，设置页仍能查看、下载已忽略的版本。

## 代码入口

- `ui/components/QuestionImageViewer.kt`：公共图片查看器。
- `ui/screens/HistoryScreen.kt`、`ui/screens/ResultScreen.kt`：查看入口。
- `data/update/DailyUpdateCheckPolicy.kt`：本地日期与到期判定。
- `data/update/UpdateViewModel.kt`：持久化当天尝试记录。
- `ui/AppNavigation.kt`：前台生命周期触发与提示展示。

## 验证

- JVM：75 tests，0 failures；每日策略覆盖首次进入、同日去重、本地午夜、跨年及日期回拨、补查次数和按版本忽略。
- Lint：0 errors / 22 warnings。Debug 与 AndroidTest APK 编译成功。
- 雷电模拟器 Android 9：15 tests，0 failures / 0 errors / 0 skipped。覆盖历史缩略图与解析页入口、多图数量、手势提示、按钮缩放、双击、复位、多图切换、关闭和缺失图片，以及已有导航、输入和图片导入回归。
- 新增 4 项更新集成测试，使用真实 ViewModel 和 Android SharedPreferences、可控网络状态及 Release 响应，验证离线等待、联网补查次数、重建后保留、次日额度恢复、在线超时不自动补查、忽略版本持久化及更高版本提醒。这些测试不代表真实断网/联网切换或真实 Release 下载证据。
- 最终包已重新安装至模拟器，启动成功；本地日期及 `last_attempt_local_day` 均为 `2026-10-30`。当天停止进程再启动后，日期与成功检查时间保持不变。
- 模拟器显示设置：1920×1080，280 DPI，无 override；本轮未修改分辨率、DPI、字体或主题。
- 本地 Graphify 代码图已刷新，生成目录保持忽略。

日志位于仓库外 `E:\souti\acceptance-artifacts\`：

- `history-images-daily-update-build.log`
- `history-images-daily-update-device.log`
- `history-images-daily-update-device.xml`
- `history-images-daily-update-launch.log`
- `image-hints-network-recovery-final.log`（最终 75 项 JVM + 15 项模拟器测试）

## APK

- 文件：`dist/medical-question-ai-history-update-preview.apk`
- Debug 预览版：versionCode 100003 / versionName 1.3.1-preview。
- 大小：22,035,516 bytes。
- SHA-256：`66d921cebe8829b96aad4209dc8d08a23e22c9695861ac434ca7812b93ab0627`。
- 与最终 `app-debug.apk` 哈希一致；本地预览包使用 Debug 签名。正式发布以 GitHub Actions 生成的签名 Release APK 为准。

## 边界

- “原题图片”指 App 本地保存的图片。现有导入逻辑会压缩并限制最长边为 1800 像素，无法恢复历史图片在导入前的相机源分辨率。
- 本轮未做实体手机测试、系统安装覆盖升级或真实新版本提示/下载验证；未改变模拟器系统日期验证跨日，跨日边界由单元测试覆盖。
- 双指缩放与拖动已接入 Compose 手势；自动 UI 测试验证按钮与双击，未模拟双指手势。
