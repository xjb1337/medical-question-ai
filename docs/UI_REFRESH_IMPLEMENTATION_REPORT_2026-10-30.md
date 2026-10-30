# UI 美化与雷电验证记录

日期：2026-10-30。

## 已完成

- 全局改为冷白、蓝绿色与深蓝灰色体系，统一圆角、细描边、字体层级和留白；深色模式同步调整。
- 首页改为渐变拍题入口，简化相册、文字与模式说明。拍摄后入口自动收为“继续拍摄”，将首屏空间让给图片预览。
- 底部导航统一白色/深色表面与选中态，“历史”改为“题库”。消除外层 Scaffold 与页面重复应用状态栏 Insets 的空白。
- 结果页以清晰的参考答案卡片突出答案，简化详细解析说明，调整下一题入口、追问输入栏和快捷追问文案。
- 题库统一搜索、卡片和空状态；设置页增加学习设置导引与清晰分组。
- 相机增加构图角标与拍摄提示，构图框仅辅助对齐，不裁剪实际照片。
- 本轮只调整展示。未修改模型接口、数据库结构、停止请求身份、相机令牌、草稿保护或更新下载实现；没有恢复逐题隐私勾选。

## 自动检查

- JVM：69/69，通过，0 failures / 0 errors。
- Lint：0 errors / 22 warnings。
- assembleDebug、assembleDebugAndroidTest：通过。
- 雷电 Android 9，ADB emulator-5554：最终源码的 8/8 instrumentation 测试通过。
- 设备测试包含 2 项新增 UI 测试（导航/输入、已保存结果/下一题入口），以及 6 项图片导入回归。
- 发现原有 EXIF 测试对 Android 编码器输出过严：像素旋转后 ORIENTATION_UNDEFINED=0 与 NORMAL=1 均无剩余旋转指令。测试现接受两者，仍检查旋转后的尺寸、缩放与隐私元数据清理；未更改图片处理业务代码。
- git diff --check：通过（仅 CRLF 提示）。

## 实际界面验证

- 已在用户电脑的雷电模拟器安装并运行新版。
- 1080×1920 / 440dpi 竖屏：查看首页、题库、设置、样例结果和键盘输入截图。
- 点击拍题入口、实际调用模拟器虚拟相机快门、返回首页并显示 1 张图片：成功；继续拍摄入口变为紧凑布局。
- 查看深色首页；360dp 宽度（480dpi）、fontScale=1.3 的首页，未发现文字截断或按钮遮挡。
- 最后恢复默认字体比例、浅色模式与 440dpi，保留手机竖屏便于预览。
- 结果页使用测试插入的本地样例，并明确标记 UI 验证来源。测试结束清理样例，不作为真实 AI 返回或质量、速度证据。
- 无真实 DeepSeek API Key，本轮未执行真实联网解题与追问；未将模拟器绿色虚拟相机画面当作真实题目识别验证。

## 安装包

路径：`E:\souti\repo\dist\medical-question-ai-ui-preview.apk`。

- versionName：1.3.0-ui-preview。
- versionCode：100003（沿用已发布版本的数值，避免本地试用包抢占下一次正式发布的递增编号）。
- 大小：21,491,514 bytes。
- SHA-256：`acb69159793e12b6e10610833c991ed30f742e835dd7e760a0a74a91cf156cff`。
- 使用现有 debug 签名，与此前引导包一致。已覆盖安装到雷电；Android 真机覆盖安装未执行。
- 自动测试构建使用 100004 / 1.3.0-ui-preview；交付包仅将版本编号调整回 100003，UI 源码相同。

## 证据位置

仓库内可直接查看：[首页](evidence/ui-refresh-2026-10-30/home.png)、[结果页（本地样例）](evidence/ui-refresh-2026-10-30/result.png)、[设置](evidence/ui-refresh-2026-10-30/settings.png)、[深色首页](evidence/ui-refresh-2026-10-30/home-dark.png)、[设备测试日志](evidence/ui-refresh-2026-10-30/emulator-tests.log)。

本机 `E:\souti\acceptance-artifacts\ui-refresh\`：

- `screens/home.png`、`screens/history-empty.png`、`screens/history-sample.png`、`screens/settings.png`、`screens/result.png`、`screens/text-keyboard.png`：设备测试截屏。
- `home-dark.png`、`home-large-font.png`、`camera.png`、`captured-home.png`：手动界面与拍摄验证截屏。
- `before-home.png`：修改前首页。
- `emulator-tests.log`：8 项设备测试通过记录。
- `package-build.log`：最终交付包构建记录。

其他检查日志：`E:\souti\acceptance-artifacts\ui-checks-final.log`。

## 交付状态

按用户“推送到 GitHub”的要求，将源码、测试、报告和关键截图一起提交至 main。推送触发既有自动构建与签名发布工作流，实际发布结果以对应 Actions 运行记录为准。本地试用包不上传到 Git；线上 APK 由 CI 单独生成，版本编号与试用包不同。Graphify 已更新，生成图谱继续保持本地忽略。
