# 应用内更新发布指南

更新时间：2026-10-29

## 1. 首次安装与升级路径

- 首次安装：`dist/medical-question-ai-update-guide.apk`（versionCode 3 / versionName 1.2.0）。
- 旧版本：先安装 guide APK，再打开应用进入「设置 → 应用更新」；旧版本没有更新代码时也可直接覆盖安装 guide。
- 后续更新：CI 发布新版本后，应用检查 GitHub Release，下载并校验 APK，校验通过后打开系统安装器。

## 2. GitHub Secrets

在仓库 `Settings → Secrets and variables → Actions` 配置：

| 名称 | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 正式 keystore 文件的 base64 字符串 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名证书别名 |
| `ANDROID_KEY_PASSWORD` | 证书密码 |
| `ANDROID_EXPECTED_CERT_SHA256` | 固定签名证书 SHA-256，用于发布前比对 |

未配置时 release workflow 会在构建前明确失败，不会产出未签名 Release。

## 3. 发布流程

1. 修改 `app/build.gradle.kts` 的 `versionCode`/`versionName`，合并到 `main`。
2. `.github/workflows/release.yml` 自动执行：
   - `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`
   - 使用 Secrets 解码 keystore 并签名 APK；
   - 校验 APK 的 SHA-256、包名、versionCode、签名证书；
   - 生成 `dist/update.json` 和 GitHub Release；
   - 上传固定资产名 `medical-question-ai.apk`、`mapping.txt`（存在时）和 `update.json`。
3. Release tag 格式：`v<versionName>`。
4. 发布后检查 `dist/update.json` 的 `apkSha256`、`apkSizeBytes` 与实际资产一致，再用真机执行下载 → 校验 → 安装 → 重启 → 再次检查更新。

## 4. 本地验证

```text
# 构建 guide 与候选包（debug，证书相同）
./gradlew.bat :app:assembleDebug -PversionCode=3 -PversionName=1.2.0
./gradlew.bat :app:assembleDebug -PversionCode=4 -PversionName=1.2.1-test.1

# 校验签名证书是否一致
apksigner verify --print-certs dist/medical-question-ai-update-guide.apk
apksigner verify --print-certs dist/medical-question-ai-update-candidate.apk
```

真实 GitHub Release、真机下载/安装和覆盖升级必须在配置 Secrets 后执行；没有真实 Release 时不得把应用内更新写成已验收通过。
