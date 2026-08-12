# Task 7 实施报告

## 范围

- Android TV 二维码等待页、播放器页面、信息覆盖层和错误覆盖层。
- 基于 `SessionUiState` 的 Navigation Compose 页面切换与 TV 返回委托。
- 二维码 URL 构造、ZXing 生成和同一房间会话内缓存。
- 未修改 `MainActivity`；应用装配保留给 Task 8。

## 初始 TDD RED

1. 新增 `PairingUrlBuilderTest` 后运行：

   ```powershell
   .\gradlew.bat testDebugUnitTest --tests com.airplay.tv.feature.pairing.PairingUrlBuilderTest
   ```

   失败点为 `PairingUrlBuilder` 未定义。修正测试自身的 JUnit 4 导入后重新运行，确认只剩目标生产类型缺失。

2. 新增 `AppNavigationTest` 后运行：

   ```powershell
   .\gradlew.bat compileDebugAndroidTestKotlin
   ```

   失败点为 `AppNavigation` 未定义。测试覆盖默认二维码页、状态驱动播放器切换、连接文案、固定错误文案且不泄露 URL、信息层显隐和 TV 返回回调。

3. URL 协议复核时把测试期望从 `timestamp` 修正为设计约定的 `t`，测试以 `ComparisonFailure` 失败，随后修改实现为：

   ```text
   https://airplay-tv.pages.dev/join?room_id=<encoded-room-id>&t=<timestamp>
   ```

## 初始 GREEN

- 实现 `PairingUrlBuilder`、`QrCodeGenerator`、`PairingScreen`、`PlayerScreen`、`AirPlayTheme`、`AppNavigation` 和 `App`。
- 二维码生成在 `Dispatchers.Default` 执行，不阻塞主线程。
- 播放器使用无控制器的全屏 `PlayerView`；纯视频模式不显示信息层和连接状态。
- 错误层只渲染固定友好文案，不渲染原始错误或播放 URL。
- 导航由 `SessionUiState.page` 驱动，避免重复 `navigate`。

## 正式审查修复 TDD

### RED

新增 focused tests：

- `QrCodeGeneratorTest.usesHighErrorCorrectionForTvScanning`
- `PairingQrContentCacheTest.keepsContentStableForSameRoomAndRefreshesForDifferentRoom`

运行：

```powershell
.\gradlew.bat testDebugUnitTest `
  --tests com.airplay.tv.feature.pairing.QrCodeGeneratorTest `
  --tests com.airplay.tv.feature.pairing.PairingQrContentCacheTest `
  --no-parallel
```

编译按预期失败：`encodingHints` 和 `PairingQrContentCache` 未定义。

### GREEN

- `QRCodeWriter.encode` 显式传入 `ERROR_CORRECTION = ErrorCorrectionLevel.H`。
- `PairingQrContentCache` 对同一 `roomId` 返回稳定 content；切换房间才刷新时间戳。
- AppNavigation 在 `NavHost` 外持有 content 和 Bitmap state；Pairing destination 销毁、恢复不会重新生成二维码。冷启动会创建新缓存，不跨进程持久化。
- `PairingScreen` 改为只展示传入 Bitmap 和会话状态。
- `BackHandler` 移入 Player destination，并在 `PlayerScreen` 之后组合。Android 官方 `BackHandler` 语义为多个启用 handler 中最后组合者优先。
- `AndroidView(PlayerView)` 在 `onRelease` 中只执行 `view.player = null`，不释放外部 `Player`。

focused tests 随后通过。

## 最终验证

环境：

```powershell
$env:ANDROID_HOME='E:\cache\android-sdk'
$env:ANDROID_SDK_ROOT='E:\cache\android-sdk'
```

强制重新执行：

```powershell
.\gradlew.bat compileDebugKotlin compileDebugAndroidTestKotlin testDebugUnitTest `
  --rerun-tasks --no-parallel
```

结果：

- `BUILD SUCCESSFUL`
- 30 个 Gradle task 全部重新执行
- 11 个 JVM test suite，67 个测试，0 failure，0 error
- `compileDebugKotlin` 成功
- `compileDebugAndroidTestKotlin` 成功

设备检查：

```powershell
E:\cache\android-sdk\platform-tools\adb.exe devices
```

结果为空，无已连接 Android TV 或模拟器，因此本任务未运行 `connectedDebugAndroidTest`。Instrumentation 测试源码已编译通过，真机执行留待设备可用时完成。

## 质量检查

- Task 7 新增和修改文件均为 UTF-8 无 BOM。
- `git diff --check` 无空白错误。
- MainActivity 和 Task 6 会话状态模型未改动。
