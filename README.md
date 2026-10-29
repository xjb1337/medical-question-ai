# 医学拍题助手

面向医学课程复习的 Android 拍题与 AI 解析应用。支持拍照/相册导入、多图题目、自动识别题型、结构化解析、追问、历史记录和收藏。应用使用 DeepSeek 兼容的 Chat Completions API。Android 版 API Key 由 AndroidX Security 加密存储；网页版为便于本地测试，默认将 Key 明文保存在浏览器 localStorage，具体边界见 [web/README.md](web/README.md)。

> 项目状态：已完成真机反馈相关改造并通过 Android 自动检查（2026-10-29）。Android 真机流程、真实接口质量与速度验收未执行（本机无可用设备、无真实 API Key）。
>
> 本项目仅用于个人学习与题目理解，不替代教材、临床指南或专业医疗建议。

## 网页版（快速测试）

仓库内附一个零依赖网页版，适合在电脑浏览器中快速验证交互和解析流程：

```bash
cd web
npm start
# 打开 http://127.0.0.1:4173
```

设置页开启「演示模式」即可不配置 API Key 跑通完整流程；真实解析需填写 DeepSeek API Key。详见 [web/README.md](web/README.md)。

## 功能

- **多图拍题**：CameraX 应用内拍照，或从相册选择最多 9 张图片。
- **图片整理**：排序、旋转、裁剪（可保留原图并新增裁剪结果，或替换原图）、删除；导入时自动校正 EXIF 方向并压缩。
- **题型选择**：自动识别 / 单选题 / 多选题 / 填空题 / 简答题，可手动覆盖。
- **官方模型**：`deepseek-flash`（支持文字与图片）和 `deepseek-v4-pro`（仅文字）；旧版 `deepseek-chat` / `deepseek-reasoner` 等设置会幂等迁移，旧追问和本地数据保留。
- **三档思考模式**：快速（关闭思考）/ 标准（low）/ 详细（high），请求显式携带 `thinking` 与 `reasoning_effort`，不依赖服务端默认值。
- **流式解析与取消**：SSE 增量接收，生成中显示答案预览与阶段进度，可随时取消；取消/失败不会覆盖已有完整结果。
- **退出即停与拍摄下一题**：离开解析页、切后台或锁屏会停止当前题解析/追问并保留已完成答案；未完成内容标记为预览。结果页底部固定“拍摄下一题”，生成中也可用，新题使用独立草稿，不继承旧文字/图片。
- **不再逐题勾选隐私**：新题直接解析，隐私说明保留在设置页；图片仍只发送到用户配置的接口。
- **结构化解析**：
  - 识别后的题干
  - 逐图观察与不确定点
  - 选项逐项判定与理由
  - 答案与解题思路
  - 关键知识点、易错点
  - 置信度与风险提示
- **追问**：结果页底部固定追问栏，围绕当前题目继续提问，可流式显示并停止；附加原图默认关闭，多轮上下文保存在本地。
- **编辑重答**：修改题干或图片后重新分析；失败保留旧答案与追问。
- **结果阅读**：答案卡片置顶，解析详情默认折叠，题目图片和追问按阅读顺序排列。
- **历史与收藏**：搜索、收藏、删除、清空；可配置是否保存历史。
- **隐私控制**：请勿上传可识别患者身份的信息；应用不要求逐题勾选隐私确认，Android 版 API Key 使用 AndroidX Security + Android Keystore 加密，网页版存储边界见网页版说明。
- **深浅色主题**：Material 3 界面，适配手机竖屏。

## 技术栈

| 层次 | 技术 |
| --- | --- |
| UI | Kotlin、Jetpack Compose、Material 3、Navigation Compose |
| 相机 | CameraX 1.4 |
| 数据 | Room 2.6、Kotlin Flow、Coroutines |
| 网络 | OkHttp 4.12、DeepSeek OpenAI 兼容 API |
| 图片 | Coil、ExifInterface、Android Bitmap |
| 安全 | EncryptedSharedPreferences（AES-256-GCM） |
| 构建 | Gradle 8.9、AGP 8.7.3、Kotlin 1.9.24、JDK 17 |

- `minSdk 26`，`targetSdk 35`，`compileSdk 35`
- 包名：`com.xjb.medicalquestionai`

## 快速开始

### 1. 环境要求

- JDK 17
- Android SDK Platform 35、Build-Tools 35.0.0
- Android Studio Ladybug 或更新版本（可选）

### 2. 构建

```bash
# 按本机安装位置填写
export JAVA_HOME="/path/to/jdk-17"
export ANDROID_HOME="/path/to/android-sdk"
./gradlew assembleDebug
```

Windows PowerShell：

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-17"
$env:ANDROID_HOME="C:\Users\你的用户名\AppData\Local\Android\Sdk"
.\gradlew.bat assembleDebug
```

也可在项目根目录创建不提交的 `local.properties`，写入 `sdk.dir=...`。

APK 输出：

```text
app/build/outputs/apk/debug/app-debug.apk
```

本次交付的预构建副本位于 `dist/medical-question-ai-debug.apk`（versionCode 2 / versionName 1.1.0），SHA-256：

```text
c54e5eadc8ed535793ca9dacdcf5c3f9c885682a18d83bc7e603cad53666f747
```

旧版 APK 保留在 `dist/archive/medical-question-ai-debug-v1.0.0-20261028.apk`，用于升级对照。

安装到已连接设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. 配置 API Key

1. 打开应用「设置」。
2. 填写 DeepSeek API Key（默认接口地址 `https://api.deepseek.com`）。
3. 选择官方模型：`deepseek-flash` 支持文字和图片；`deepseek-v4-pro` 仅支持文字，带图时会被拦截并提示切换。自定义 Base URL 可填写代理端模型名。
4. 选择快速 / 标准 / 详细模式；新安装默认标准，旧 `deepseek-chat` 迁移为快速、旧 `deepseek-reasoner` 迁移为详细。
5. 点击「保存密钥」先保存到本机，再点「测试连接」确认 Key 可用，点「验证模型」确认当前模型存在；无网络时也可先保存。

API Key 仅写入应用私有的 `EncryptedSharedPreferences`，不会写入日志、Room 数据库或导出文件。卸载应用会删除本地数据。

## 使用流程

1. 首页选择题型（默认自动识别）。
2. 拍照或从相册选图；可拖动排序、旋转、裁剪。
3. 可选填写题干文字；图片和文字可以同时提交。
4. 勾选「已确认图片不含患者姓名、住院号等身份信息」。
5. 点击「开始解析」，等待结构化结果。
6. 在结果页复制/分享答案、收藏、修改重答或继续追问。
7. 历史页可搜索题目与答案，支持收藏筛选。

## 项目结构

```text
app/src/main/java/com/xjb/medicalquestionai/
├── MainActivity.kt                 # 入口、导航壳
├── MainViewModel.kt                # 页面状态与业务编排
├── data/
│   ├── Models.kt                   # 领域模型与 JSON 序列化
│   ├── QuestionDatabase.kt         # Room 实体、DAO、仓库
│   ├── ImageStore.kt               # 图片导入、压缩、旋转、裁剪
│   ├── DeepSeekClient.kt           # Chat Completions 客户端（SSE/非流式）
│   ├── SseParser.kt                # 增量 UTF-8 / SSE 解析
│   ├── QuestionAnalyzer.kt         # 提示词、流式解析、追问上下文
│   ├── AnswerMode.kt               # 快速/标准/详细参数
│   ├── ModelCatalog.kt             # 官方模型能力与地址判定
│   ├── SettingsMigrations.kt       # 旧模型设置幂等迁移
│   └── SettingsRepository.kt       # 设置与加密 Key 存储
└── ui/
    ├── components/                 # 通用组件、解析结果视图
    ├── screens/                    # 首页、相机、结果、历史、设置
    └── theme/                      # Material 3 主题
web/                                # 零依赖网页版（Node 静态服务 + API 代理）
├── index.html
├── server.mjs
└── assets/                         # 页面、样式、IndexedDB、图片处理
```

更详细的设计说明见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，测试说明见 [docs/TESTING.md](docs/TESTING.md)，网页版说明见 [web/README.md](web/README.md)。

## 数据与隐私

- 题目文字、图片路径、解析结果保存在应用私有目录与 Room 数据库。
- 图片默认最长边 1800 px、JPEG 质量 86；单次请求图片总量上限 18 MB。
- 调用 AI 时，题干文字与压缩后的图片会发送到用户配置的 API 服务商。请勿上传患者身份信息、病历原件或任何敏感个人数据。
- 可在「设置」中关闭新解析的历史保存（未收藏的新解析不进入历史列表，收藏题始终显示）；历史页可一键清空。
- 应用不申请存储读写权限，相册通过系统 Photo Picker / 文档选择器访问。

## 已知限制

- 医学题目识别可能出错，尤其是低清晰度图片、手写体和复杂表格；请以教材与课堂讲解为准。
- 追问上下文会随轮次增长，长对话按预算裁剪；建议新问题新建题目。
- 本次交付未在 Android 真机/模拟器上执行，也没有真实 DeepSeek Key，因此真机 UI、相机、覆盖安装和真实接口质量/速度数据尚未验收；详见 `docs/DEVICE_FEEDBACK_IMPLEMENTATION_REPORT.md`。
- Android 端目前仅提供浅色/深色手机布局，未适配平板与横屏专门布局；网页版已提供响应式桌面/手机布局。
- 未内置离线模型；无网络或 API 配额不足时无法解析。

## 开发

```bash
./gradlew testDebugUnitTest   # 单元测试
./gradlew lintDebug           # Android Lint
./gradlew assembleDebug       # Debug APK
```

提交前请确认：

- `./gradlew testDebugUnitTest` 通过
- `./gradlew assembleDebug` 通过
- 不提交 `local.properties`、签名文件、API Key 或 `build/` 产物

## 许可证

本项目为个人学习项目，尚未指定开源许可证。使用或分发前请与仓库作者确认。
