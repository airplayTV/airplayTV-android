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

## 二次复审：房间切换二维码隔离

### RED

新增 `PairingQrImageTest`，要求异步结果只有在 `result.content == currentQrContent` 时才返回 Bitmap；结果缺失或 content 不匹配时必须返回 `null`，由页面显示 loading。

运行：

```powershell
.\gradlew.bat testDebugUnitTest `
  --tests com.airplay.tv.feature.pairing.PairingQrImageTest `
  --no-parallel
```

编译按预期失败：`PairingQrImage` 和 `bitmapFor` 未定义。

### GREEN

- 新增泛型 `PairingQrImage<T>(content, bitmap)` 和 `bitmapFor(currentContent)` identity 过滤函数。
- `produceState` 改为生成 `PairingQrImage<Bitmap>`，展示层仅接收 `generatedQrImage.bitmapFor(qrContent)`。
- 房间从 A 切换到 B 时，即使 `produceState` 暂时保留 A 的旧 state，A 的 content 与当前 B 不匹配，二维码立即变为 `null/loading`。
- 若旧生成任务非协作取消并迟到返回 A，identity 过滤仍禁止 A Bitmap 作为 B 房间二维码展示。

focused test 随后通过。

二次复审后强制重新执行：

```powershell
.\gradlew.bat compileDebugKotlin compileDebugAndroidTestKotlin testDebugUnitTest `
  --rerun-tasks --no-parallel
```

结果：

- `BUILD SUCCESSFUL`
- 30 个 Gradle task 全部重新执行
- 12 个 JVM test suite，69 个测试，0 failure，0 error
- `compileDebugKotlin` 成功
- `compileDebugAndroidTestKotlin` 成功
- `adb devices` 仍为空，未运行 `connectedDebugAndroidTest`

## 2026-08-20 播放 HUD / 自动下一集 / H5 投射会话最终验证

### H5

```powershell
node --test tests/*.test.mjs
npm run build
```

- Node：60 tests，60 passed，0 failed。
- Vite：15096 modules transformed，生产构建成功。
- 修复 `tests/casting.test.mjs` 中 direct Node runner 无法解析的 Vite `@` alias，恢复为相对路径导入。
- 构建保留既有 CSS `//` 注释、混合 dynamic/static import 和大 chunk 警告；均非本任务新增阻塞。

### Android

使用仓库原始 AGP 8.11.1 / Gradle 8.14，清理 `PATH` 中带引号的无效旧 JDK 项后运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest `
  :app:lintDebug :app:lintRelease `
  :app:assembleDebug :app:assembleRelease `
  :app:compileDebugAndroidTestKotlin `
  --no-daemon --no-parallel --rerun-tasks
```

- `BUILD SUCCESSFUL in 1m 38s`
- 119 actionable tasks，119 executed。
- debug/release XML 合计：44 suites，292 tests，0 failures，0 errors，0 skipped。
- debug/release lint、APK 组装、AndroidTest Kotlin 编译全部成功。
- 首轮 lint 发现日志时间格式使用 API 26 `java.time`；改为每次调用创建 API 23 可用的 `SimpleDateFormat`，避免共享 formatter 线程安全问题，最终 lint 通过。
- 最终审查后增加终止性播放器错误的固定 `ERR 播放器播放失败` 日志，并按 committed media generation 去重；详情失败/当前 pid 不在剧集列表时记录 `SKIP 剧集列表不可用`，不再误报“已是最后一集”。
- ADB 当前连接 `CPH2487` 手机而非 Android TV；未安装/运行 instrumentation，TV HUD 安全区、扫码和自然播放结束仍需目标电视验收。

### 质量与 Git 边界

- Android 17 个 changed files、Vue 1 个 changed file 均严格 UTF-8、无 BOM。
- 两仓库 `git diff --check` 通过。
- Android index 仅含两个新增 RoomId formatter 文件；其余 tracked 修改未暂存。
- 未创建提交；无关 Vue 未跟踪文件未触碰。

## 2026-08-20 Task 7：状态感知遥控器选集与 10 秒控制层冻结

### 范围

- 仅修改 `TvRemoteKeyMapper`、`App`、`SessionViewModel`、`SessionUiState` 及简报列出的三个 JVM 测试文件。
- 保持 App 根 `Box` 已有的 `FocusRequester + focusRequester + focusable + onPreviewKeyEvent` 实际焦点链路；只将 `state.episodePanelFocused` 传给按键 mapper。
- 未修改根 `build.gradle.kts`（该文件在开始前已有用户未提交修改），未触碰 Task 6 的定时同步、revision 或 mediaToken，也未实现 Task 8 UI 布局。

### TDD

所有 Gradle 命令先执行 `gradlew --stop`，并使用 Microsoft JDK 17 与 command-level clean PATH：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.12.7-hotspot'
$env:Path = 'C:\Program Files\Microsoft\jdk-17.0.12.7-hotspot\bin;C:\Windows\System32;C:\Windows'
.\gradlew.bat --no-daemon testDebugUnitTest `
  --tests '*TvRemoteKeyMapperTest' `
  --tests '*AppRemoteKeyHandlerTest' `
  --tests '*SessionViewModelTest'
```

- 基线：`BUILD SUCCESSFUL`。
- RED：先增加状态参数、五个选集 action、焦点字段和选集/10 秒虚拟时间断言；`compileDebugUnitTestKotlin` 如预期失败，错误仅为这些尚未实现的 action、参数及 `SessionUiState` 字段缺失。
- GREEN 首次暴露一个既有断言仍按 5 秒检查 `pendingControlReplayDoesNotExtendOverlayTimer`。它不应因 resolve 回放重启计时，但有效期已由需求改为 10 秒；将断言从剩余 `100ms` 调整为剩余 `5_100ms` 后，定向测试 `BUILD SUCCESSFUL`。

### 行为与虚拟时间

- 非面板焦点：`DPAD_UP` 产生 `OpenEpisodes`；既有媒体键、右键快进和左键回退保持兼容。
- 面板焦点：`UP/DOWN` 夹紧移动焦点，`CENTER/ENTER` 选择，`LEFT` 退出；打开仅在 `episodes.size > 1`，并以当前 `currentLoadCommand.pid` 设置焦点索引。
- `OpenEpisodes` 取消 overlay job、递增 overlay revision，并保持 `infoVisible=true`；虚拟时间推进 `10_001ms` 后仍可见。
- 退出或选择会重新启动 `INFO_TIMEOUT_MS = 10_000L`；退出后 `9_999ms` 仍可见，再推进 `1ms` 隐藏。
- 选择当前 pid 只关闭面板并重启计时，不产生 source 解析请求；选择其他集保留既有 `loadVideo(... preserveEpisodes = true)` 语义。
- 与 5 秒信息层相关的既有 Session 回归均同步为 10 秒；诊断、进度保存、ACK 超时仍保持原 5/30 秒语义。

### 验证

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest `
  --tests '*TvRemoteKeyMapperTest' `
  --tests '*AppRemoteKeyHandlerTest' `
  --tests '*SessionViewModelTest'
```

- `BUILD SUCCESSFUL in 23s`
- `TvRemoteKeyMapperTest`：3 tests，0 failures，0 errors。
- `AppRemoteKeyHandlerTest`：4 tests，0 failures，0 errors。
- `SessionViewModelTest`：78 tests，0 failures，0 errors。

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest `
  --tests 'com.airplay.tv.session.*' :app:compileDebugAndroidTestKotlin
```

- `BUILD SUCCESSFUL in 17s`；Session 回归与 AndroidTest Kotlin 源码编译均通过。
- `git diff --check` 无空白错误；Task 7 触及的 7 个源码/测试文件均为 UTF-8 无 BOM。

### Concerns

- JVM 行为与 AndroidTest 源码编译已验证，未在真实 Android TV 设备执行 instrumentation；实体遥控器长按/repeat 的平台事件时序仍待设备验收。
- Gradle 输出项目既有 Gradle 9 deprecated-features 提示，未引入新的编译或测试警告。

### 审查修复 TDD

独立审查发现两个可复现边界，先补充 focused tests：

- `backExitsFocusedEpisodePanelAndRestartsOverlayTimer`
- `selectingAnotherEpisodeKeepsItsTimerAnchoredToSelectionTime`

RED：两项测试均失败。前者证明 Back 在面板焦点时被 `hideInfo()` 吞掉；后者证明慢速 p2 source 在选择后 `1_000ms` 完成时重新启动了 overlay timer，选择后累计 `10_000ms` 仍显示。

GREEN：

- `onBack()` 优先调用 `exitEpisodes()`；
- 非当前集选择先关闭面板、调用既有 `loadVideo(... preserveEpisodes = true)`，随后立即启动 overlay timer，使 pending load 保存的旧 revision 不满足成功路径重启条件。

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest `
  --tests 'com.airplay.tv.session.SessionViewModelTest.selectingAnotherEpisodeKeepsItsTimerAnchoredToSelectionTime' `
  --tests 'com.airplay.tv.session.SessionViewModelTest.backExitsFocusedEpisodePanelAndRestartsOverlayTimer'
```

结果：`BUILD SUCCESSFUL in 27s`。

### 审查修复：DPAD_LEFT 长按跨焦点 repeat ownership

#### RED

新增 `AppRemoteKeyHandlerTest.consumesOwnedEpisodeLeftRepeatsUntilKeyUpThenAllowsTheNextBackPress`，覆盖完整状态转换：

```text
focused LEFT down(repeat=0) -> ExitEpisodes
state focused=false -> LEFT down(repeat=1) 仅 consume
LEFT up -> 清除 ownership 且 consume
下一次 LEFT down(repeat=0) -> Back
```

```powershell
.\gradlew.bat --no-daemon testDebugUnitTest `
  --tests '*TvRemoteKeyMapperTest' `
  --tests '*AppRemoteKeyHandlerTest'
```

结果：`compileDebugUnitTestKotlin` 按预期失败，唯一根因是 `TvRemoteKeyHandler` 未定义；随后的 lambda 推断错误是该目标类型缺失的派生错误。

#### GREEN

- `App` 根节点以 `remember` 持有 `TvRemoteKeyHandler`，保持实际 Compose 焦点链路不变。
- 首次 `ExitEpisodes` 取得该 LEFT press 的 key ownership；同一 key 在 `ACTION_UP` 前的所有 repeat 均只 consume、不再调用 mapper，因此不会在面板焦点变为 false 后泄漏为 `Back/seek -15s`。
- 匹配 `ACTION_UP` 清除 ownership 并消费；下一次新的 LEFT 按压仍按未聚焦 mapper 产生 `Back`。未修改 mapper 的普通 LEFT repeat，也未影响面板内 UP/DOWN 的连续移动。

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest `
  --tests '*TvRemoteKeyMapperTest' `
  --tests '*AppRemoteKeyHandlerTest' `
  --tests '*SessionViewModelTest' `
  :app:compileDebugAndroidTestKotlin
```

结果：`BUILD SUCCESSFUL in 13s`。

#### 设备验收

`PENDING_DEVICE_ACCEPTANCE`：未连接 Android TV，未伪装实体遥控器长按的 instrumentation/真机验收；仅完成 JVM 状态转换与 AndroidTest 源码编译验证。
