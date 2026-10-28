# 测试说明

## 1. 自动化测试

### 单元测试

```bash
./gradlew testDebugUnitTest
```

覆盖内容：

- `AnalysisResult` 的 JSON 序列化/反序列化往返
- 旧版字段别名兼容（`type`、`question`、`input_tokens` 等）
- 空答案的待确认状态
- 填空题 `answer_text` 不误判为缺失答案
- 题型存储值与中文标签
- 纯图片题目的历史列表兜底文案

报告位置：

```text
app/build/reports/tests/testDebugUnitTest/index.html
```

### 静态检查

```bash
./gradlew lintDebug
```

Lint 报告：

```text
app/build/reports/lint-results-debug.html
```

### 构建验证

```bash
./gradlew assembleDebug
```

## 2. 手工验收清单

### 首次启动

- [ ] 首页显示「尚未配置 API Key」提示
- [ ] 点击提示可进入设置页
- [ ] 设置页可以保存 Key，并显示脱敏后的 Key 提示
- [ ] 「测试连接」成功/失败都有明确提示
- [ ] 重启应用后 Key 仍然存在且不明文展示

### 图片与相机

- [ ] 相机权限拒绝时显示授权引导，不崩溃
- [ ] 拍照后回到首页并出现缩略图
- [ ] 相册可多选，最多 9 张
- [ ] 第 10 张被拒绝并提示上限
- [ ] 缩略图可以左移/右移、旋转、裁剪、删除
- [ ] 删除后列表与数据一致

### 解析

- [ ] 只有文字可以解析
- [ ] 只有图片可以解析
- [ ] 文字+图片可以解析
- [ ] 未勾选隐私确认时按钮不可用或给出提示
- [ ] 解析中显示进度，重复点击不会发起第二次请求
- [ ] 成功后进入结果页，题干、答案、思路、关键点完整
- [ ] 多选题答案显示多个选项
- [ ] 低置信度/空答案显示风险提示
- [ ] 断网时显示错误并保留草稿

### 结果页

- [ ] 复制答案到剪贴板
- [ ] 系统分享只含文字
- [ ] 收藏/取消收藏状态可切换
- [ ] 编辑题干或图片后可以重新分析
- [ ] 追问可以发送文字
- [ ] 追问可以附加图片
- [ ] 追问失败后输入内容保留

### 历史

- [ ] 新解析按时间倒序出现
- [ ] 搜索题干命中
- [ ] 搜索答案命中
- [ ] 收藏筛选正确
- [ ] 删除单条后列表刷新
- [ ] 清空全部有二次确认
- [ ] 关闭「保存解析历史」后未收藏的新解析不出现在历史列表，收藏题仍显示

### 设置与隐私

- [ ] 模型名修改后用于下一次请求
- [ ] Base URL 修改后用于下一次请求
- [ ] 删除 Key 后首页恢复未配置状态
- [ ] 数据库与 SharedPreferences 中不存在明文 Key（可用 Android Studio Device File Explorer 检查）
- [ ] 应用未申请 READ/WRITE_EXTERNAL_STORAGE

## 3. API 联调

### 验证 Key

```bash
curl -sS https://api.deepseek.com/models \
  -H "Authorization: Bearer $DEEPSEEK_API_KEY"
```

### 验证 Chat Completions

```bash
curl -sS https://api.deepseek.com/chat/completions \
  -H "Authorization: Bearer $DEEPSEEK_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "deepseek-chat",
    "messages": [
      {"role": "user", "content": "用一句话解释什么是心肌梗死"}
    ],
    "temperature": 0.15
  }'
```

### 图片请求格式

应用发送 OpenAI 兼容的多模态消息：

```json
{
  "role": "user",
  "content": [
    {"type": "text", "text": "请解析这道医学题……"},
    {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,..."}}
  ]
}
```

若使用不支持视觉输入的模型，带图请求会返回 400/422，应用会提示切换模型或仅使用文字。

## 4. 本机验证记录（2026-10-28）

- 环境：Windows、JDK 17.0.10、Gradle 8.9、AGP 8.7.3、Android SDK 35 / Build-Tools 35.0.0。
- `./gradlew testDebugUnitTest`：通过，7/7 单元测试，0 失败。
- `./gradlew lintDebug`：通过，0 errors、18 warnings（均为依赖版本更新提示，无阻断项）。
- `./gradlew assembleDebug`：通过，生成可安装的 debug APK，约 21 MB。
- `aapt2 dump badging`：包名 `com.xjb.medicalquestionai`，versionCode 1、versionName 1.0.0，minSdk 26、targetSdk 35。
- `apksigner verify`：APK Signature Scheme v2 验证通过（debug 签名）。
- 产物副本：`dist/medical-question-ai-debug.apk`，SHA-256 `98964be069e33fda39f31c2b649849532796c185f0bf38c243196a09e3a6306a`。
- 尚未连接真实安卓手机，因此第 2 节的手工验收项需要在设备上继续执行。

## 5. 回归重点

1. Room 版本升级：当前数据库版本为 3，包含 `MIGRATION_1_2`（追问表）和 `MIGRATION_2_3`（`questions.isEphemeral` 列）；升级时必须提供 Migration，禁止直接 `fallbackToDestructiveMigration()` 上线。
2. 图片文件生命周期：删除题目、重答替换图片、清空历史时都要删除对应文件，避免私有目录膨胀。
3. JSON 契约变化：新增字段必须保持可选；旧记录反序列化不能崩溃。
4. 大图内存：低端机连续导入 9 张 4000px 图片时不应 OOM。
5. API Key 泄漏：日志、崩溃报告、分享内容中不得出现 Key。
