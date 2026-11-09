# 拍照后图片编辑回归

## 根因与修复边界

- 原裁剪层的 `pointerInput(Unit)` 长期持有首次组合的裁剪框；每次拖动都从初始框加上单次位移，无法连续累加。此问题不依赖华为专有接口。
- 裁剪层使用 `rememberUpdatedState` 获取最新框和回调；每次手势开始同步当前框，同一手势内用局部框逐次累加，避免多次触摸事件早于重组时再次丢失位移。
- `CropGeometry` 单独计算角命中、平移、边界和最小尺寸（归一化宽高均不小于 0.08）。角命中区域重叠时选最近的角。
- 原生全屏 Compose Dialog 使用 `safeDrawing`，图片按剩余高度等比缩放，操作区每行最多两个按钮；极矮窗口中操作区可滚动。
- `DraftImage.revision` 在成功旋转或替换裁剪后递增，同时加入编辑预览、缩略图的 Coil 内存和磁盘缓存 key。不改变图片 ID、文件路径、Room 结构或历史记录引用。
- `gradlew` 恢复可执行权限，允许直接运行项目文档中的 `./gradlew` 命令。

## 自动回归

```bash
export JAVA_HOME=/home/box/jdk17 ANDROID_HOME=/home/box/android-sdk
./gradlew testDebugUnitTest --tests '*CropGeometryTest' --tests '*ImageStoreCropTest' --tests '*ImageEditDialogTest'
./gradlew testDebugUnitTest assembleDebug lintDebug
mkdir -p /workspace/mqa-crop-after
cp app/build/crop-after/*.png /workspace/mqa-crop-after/
```

- `CropGeometryTest`：右下角变化、连续增量、四角命中、重叠命中、平移夹紧、四角最小尺寸、无命中不变。
- `ImageEditDialogTest`：真实编辑 Dialog 上多步触摸、第二次手势命中新框、平移、重置、取消、两个裁剪保存分支、移动、旋转、删除确认、完成、两级缓存 key 更新。
- `ImageEditDialogTest` 的窄屏配置为 360 × 800dp，Compose `fontScale = 1.3`；检查操作按钮启用、完整落在编辑区域、触摸高度至少 48dp，并点击验证。
- `ImageStoreCropTest`：Native Bitmap 下验证复制裁剪与原地裁剪均将 800 × 600 变为 400 × 300，复制裁剪不改变原图字节，越界裁剪被夹紧。
- 截图输出到 `app/build/crop-after/`，可用 `CROP_SHOT_DIR` 覆盖：`editor-normal.png`、`crop-before.png`、`crop-after.png`、`editor-narrow-font130.png`、`crop-narrow-font130.png`。

## 本次验证结果

- 定向回归：15 项通过（7 项纯计算、5 项 Compose UI、3 项图片存储）。
- 全量 `testDebugUnitTest assembleDebug lintDebug`：通过，108 项测试，0 failures / errors / skipped。
- Lint：0 errors、37 warnings、3 information；未扩大本次修复范围处理依赖版本、旧图标和偏好设置等告警。
- 负控：临时把拖动累加输入恢复为闭包捕获的旧框，`multiStepCornerDragsAccumulateAndHitTestTheLatestRect` 在首次多步拖动后的尺寸断言失败；恢复修复后全量通过。负控代码不保留。
- 5 张截图已复制到 `/workspace/mqa-crop-after/`，包含正常态和裁剪态的窄屏大字号截图；目录内还保留验证日志和 Lint 文本报告。
- Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。

## 真机补验

Robolectric 不等同于华为 Mate 80 Pro 真机验证。安装 debug APK 后需人工检查：实际拍照返回、连续拖四角及框内平移、左右旋转后预览即时刷新、保留原图新增与替换、删除确认、返回与完成。还应在该机型的挖孔、系统导航模式和系统大字号下确认安全区与触控行为。
