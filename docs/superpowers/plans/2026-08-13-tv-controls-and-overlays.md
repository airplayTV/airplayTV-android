# Android TV Controls and Overlays Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复电视端音量/静音、二维码中断播放、诊断日志常驻、播放信息布局和遥控器媒体控制，并以状态驱动的 A“分层 HUD”统一呈现连接、日志、地址与进度。

**Architecture:** `SessionViewModel` 继续作为 WebSocket、遥控器和播放器的唯一控制入口，并用互不干扰的状态与计时器管理播放信息、诊断日志和二维码。`AppNavigation` 复用已生成的二维码，在播放器页叠加独立 QR 浮层；`PlayerScreen` 将连接状态、日志和底部播放信息放入三个空间独立的 HUD 层。`MainActivity` 只负责把 Android `KeyEvent` 映射为语义化 `RemoteControlAction`。

**Tech Stack:** Kotlin 2.1、Coroutines Flow、Jetpack ViewModel、Navigation Compose、Material 3、Media3 1.5.1、ZXing、JUnit 4、Compose UI Test、Gradle 8/AGP 8.11.1。

## Global Constraints

- 仅修改 `airplayTV-android`；不得修改 `airplayTV-vue`、`api` 或 WebSocket 事件协议。
- 所有文本文件使用 UTF-8 无 BOM。
- 生产代码修改前必须运行对应失败测试并确认失败原因正确。
- 二维码浮层不得调用 `pause()`、`clear()`、导航或改变当前媒体 generation。
- 日志在最后一条新日志到达 5 秒后隐藏；新日志重置日志计时器。
- WebSocket 状态始终显示，文案固定为“连接中 / 已连接 / 重连中 / 已断开”。
- 日志不得包含完整媒体 URL、Header、原始 WebSocket JSON、异常正文或源密钥；播放信息层可以显示最终播放地址。
- 遥控器快进/快退固定为 15 秒。
- 不引入 `MediaSessionService`、后台播放通知、日志上传或持久化。
- 不执行 `git add`、`git commit` 或 `git push`；每个任务以测试结果和 `git diff --check` 作为检查点。

---

## File Structure

- Create `app/src/main/java/com/airplay/tv/diagnostics/DiagnosticLogEntry.kt`：有界、安全的电视端诊断日志模型与固定文案映射。
- Create `app/src/main/java/com/airplay/tv/feature/player/TvRemoteKeyMapper.kt`：Android key code 到语义化遥控器动作的纯函数。
- Create `app/src/test/java/com/airplay/tv/diagnostics/DiagnosticLogEntryTest.kt`：日志上限和脱敏文案测试。
- Create `app/src/test/java/com/airplay/tv/feature/player/TvRemoteKeyMapperTest.kt`：媒体键、方向键、ACTION_UP 和长按策略测试。
- Create `app/src/test/java/com/airplay/tv/ManifestPermissionTest.kt`：Manifest 音频设置权限回归测试。
- Modify `app/src/main/AndroidManifest.xml`：声明 `MODIFY_AUDIO_SETTINGS`。
- Modify `app/src/main/java/com/airplay/tv/session/SessionState.kt`：增加地址、QR 与诊断 UI 状态。
- Modify `app/src/main/java/com/airplay/tv/session/SessionViewModel.kt`：统一处理日志计时、QR、地址和遥控器动作。
- Modify `app/src/main/java/com/airplay/tv/MainActivity.kt`：分发电视遥控器按键。
- Modify `app/src/main/java/com/airplay/tv/app/AppNavigation.kt`：在播放器页叠加 QR，保留配对页二维码。
- Modify `app/src/main/java/com/airplay/tv/feature/pairing/PairingScreen.kt`：精简连接状态文案。
- Modify `app/src/main/java/com/airplay/tv/feature/player/PlayerScreen.kt`：实现 A“分层 HUD”、日志自动显隐渲染、地址和图标状态。
- Modify `app/src/test/java/com/airplay/tv/session/SessionViewModelTest.kt`：覆盖 QR、日志、地址、遥控器和计时器。
- Modify `app/src/test/java/com/airplay/tv/feature/player/Media3PlaybackLogicTest.kt`：补齐音量恢复边界。
- Modify `app/src/androidTest/java/com/airplay/tv/app/AppNavigationTest.kt`：覆盖 UI 层位置、显隐、图标语义和二维码。
- Modify `app/src/androidTest/java/com/airplay/tv/MainActivityLifecycleTest.kt`：覆盖 Activity 遥控器入口和生命周期不回归。

---

### Task 1: Audio Permission and Mute Regression Guard

**Files:**
- Create: `app/src/test/java/com/airplay/tv/ManifestPermissionTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/test/java/com/airplay/tv/feature/player/Media3PlaybackLogicTest.kt`

**Interfaces:**
- Consumes: existing `Media3PlayerController.toggleMute()` and `calculateRestoreVolume(lastAudibleVolume, maxVolume)`.
- Produces: Manifest permission `android.permission.MODIFY_AUDIO_SETTINGS`; no new runtime API.

- [ ] **Step 1: Write the failing Manifest permission test**

```kotlin
package com.airplay.tv

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestPermissionTest {
    @Test
    fun manifestAllowsChangingMusicStreamVolume() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(
            manifest.contains(
                "<uses-permission android:name=\"android.permission.MODIFY_AUDIO_SETTINGS\" />",
            ),
        )
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.ManifestPermissionTest" --no-daemon --no-parallel --rerun-tasks
```

Expected: FAIL at `assertTrue` because the permission is absent.

- [ ] **Step 3: Add the permission before the `<application>` element**

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

- [ ] **Step 4: Add volume restoration boundary assertions**

Append to `restoreVolumeUsesLastAudibleValueWithinCurrentMaximum()`:

```kotlin
assertEquals(1, calculateRestoreVolume(lastAudibleVolume = 1, maxVolume = 10))
assertEquals(0, calculateRestoreVolume(lastAudibleVolume = 1, maxVolume = 0))
assertEquals(0, calculateRestoreVolume(lastAudibleVolume = -1, maxVolume = 10))
```

- [ ] **Step 5: Verify GREEN and inspect the merged Manifest**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.ManifestPermissionTest" --tests "com.airplay.tv.feature.player.Media3PlaybackLogicTest" :app:processDebugMainManifest --no-daemon --no-parallel --rerun-tasks
rg -n "MODIFY_AUDIO_SETTINGS" app\build\intermediates\merged_manifests app\build\intermediates\merged_manifest -g AndroidManifest.xml
git diff --check
```

Expected: focused tests PASS; merged Debug Manifest contains the permission; `git diff --check` has no output.

---

### Task 2: Safe Diagnostic Log Model

**Files:**
- Create: `app/src/main/java/com/airplay/tv/diagnostics/DiagnosticLogEntry.kt`
- Create: `app/src/test/java/com/airplay/tv/diagnostics/DiagnosticLogEntryTest.kt`

**Interfaces:**
- Consumes: `SocketConnectionState` and `ControlCommand`.
- Produces:
  - `data class DiagnosticLogEntry(val stage: String, val message: String)`
  - `fun List<DiagnosticLogEntry>.appendDiagnostic(entry): List<DiagnosticLogEntry>`
  - `fun SocketConnectionState.toDiagnosticLog(): DiagnosticLogEntry`
  - `fun ControlCommand.toDiagnosticLog(): DiagnosticLogEntry`

- [ ] **Step 1: Write failing bounded-window and secrecy tests**

```kotlin
package com.airplay.tv.diagnostics

import com.airplay.tv.protocol.ControlCommand
import com.airplay.tv.protocol.SocketConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DiagnosticLogEntryTest {
    @Test
    fun appendKeepsOnlyLatestTwentyEntries() {
        val logs = (1..21).fold(emptyList<DiagnosticLogEntry>()) { current, index ->
            current.appendDiagnostic(DiagnosticLogEntry("RX", "event-$index"))
        }

        assertEquals(20, logs.size)
        assertEquals("event-2", logs.first().message)
        assertEquals("event-21", logs.last().message)
    }

    @Test
    fun loadVideoLogDoesNotExposeUrlOrMode() {
        val log = ControlCommand.LoadVideo(
            vid = "video-1",
            pid = "episode-2",
            source = "source-a",
            mode = "secret-mode",
        ).toDiagnosticLog()

        assertEquals("CTL", log.stage)
        assertEquals("收到加载视频指令", log.message)
        assertFalse(log.toString().contains("secret-mode"))
        assertFalse(log.toString().contains("http"))
    }

    @Test
    fun connectionLogsUseShortFixedText() {
        assertEquals("已连接", SocketConnectionState.Connected.toDiagnosticLog().message)
        assertEquals("重连中", SocketConnectionState.Reconnecting.toDiagnosticLog().message)
    }
}
```

- [ ] **Step 2: Run tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.diagnostics.DiagnosticLogEntryTest" --no-daemon --no-parallel --rerun-tasks
```

Expected: compilation FAIL because the diagnostics package and APIs do not exist.

- [ ] **Step 3: Implement the immutable bounded model and fixed mappings**

```kotlin
package com.airplay.tv.diagnostics

import com.airplay.tv.protocol.ControlCommand
import com.airplay.tv.protocol.SocketConnectionState

data class DiagnosticLogEntry(
    val stage: String,
    val message: String,
)

internal const val MAX_DIAGNOSTIC_LOGS = 20

fun List<DiagnosticLogEntry>.appendDiagnostic(
    entry: DiagnosticLogEntry,
): List<DiagnosticLogEntry> = (this + entry).takeLast(MAX_DIAGNOSTIC_LOGS)

fun SocketConnectionState.toDiagnosticLog(): DiagnosticLogEntry = when (this) {
    SocketConnectionState.Connecting -> DiagnosticLogEntry("WS", "连接中")
    SocketConnectionState.Connected -> DiagnosticLogEntry("WS", "已连接")
    SocketConnectionState.Reconnecting -> DiagnosticLogEntry("WS", "重连中")
    SocketConnectionState.Closed -> DiagnosticLogEntry("WS", "已断开")
}

fun ControlCommand.toDiagnosticLog(): DiagnosticLogEntry = DiagnosticLogEntry(
    stage = "CTL",
    message = when (this) {
        is ControlCommand.LoadVideo -> "收到加载视频指令"
        is ControlCommand.Volume -> if (direction > 0) "调高音量" else "调低音量"
        ControlCommand.Play -> "继续播放"
        ControlCommand.Pause -> "暂停播放"
        ControlCommand.Forward -> "快进 15 秒"
        ControlCommand.Back -> "快退 15 秒"
        ControlCommand.Mute -> "切换静音"
        ControlCommand.Fullscreen -> "隐藏播放信息"
        ControlCommand.FullscreenExit -> "显示播放信息"
        ControlCommand.ToggleInfo -> "切换播放信息"
        ControlCommand.ShowQrCode -> "显示二维码"
        ControlCommand.Previous -> "上一集"
        ControlCommand.Next -> "下一集"
        ControlCommand.HistoryIgnored -> "收到历史指令"
    },
)
```

- [ ] **Step 4: Run tests and verify GREEN**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.diagnostics.DiagnosticLogEntryTest" --no-daemon --no-parallel --rerun-tasks
git diff --check
```

Expected: tests PASS; no whitespace errors.

---

### Task 3: Session State, Independent Timers, Playback URL, and QR Overlay State

**Files:**
- Modify: `app/src/main/java/com/airplay/tv/session/SessionState.kt`
- Modify: `app/src/main/java/com/airplay/tv/session/SessionViewModel.kt`
- Modify: `app/src/test/java/com/airplay/tv/session/SessionViewModelTest.kt`

**Interfaces:**
- Consumes: Task 2 diagnostic helpers; existing `ControlCommand`, `VideoResolver`, and `PlayerController`.
- Produces:
  - `SessionUiState.playbackUrl: String`
  - `SessionUiState.qrVisible: Boolean`
  - `SessionUiState.diagnosticLogs: List<DiagnosticLogEntry>`
  - `SessionUiState.diagnosticVisible: Boolean`
  - `SessionViewModel.onBack()` with QR-first behavior.

- [ ] **Step 1: Replace the existing QR expectation with a failing non-destructive test**

```kotlin
@Test
fun qrCommandShowsOverlayWithoutStoppingOrClearingPlayback() = runTest(dispatcher) {
    startCollectors()
    socket.emit(load("video", "p1"))
    advanceUntilIdle()
    playerController.setState(PlayerState(isPlaying = true, positionMs = 12_000))
    playerController.clearCalls()

    socket.emit(ControlCommand.ShowQrCode)
    runCurrent()

    assertTrue(viewModel.uiState.value.qrVisible)
    assertEquals(SessionPage.Player, viewModel.uiState.value.page)
    assertTrue(viewModel.uiState.value.isPlaying)
    assertTrue(playerController.calls.isEmpty())
}
```

Delete/replace assertions that expect `ShowQrCode` to call `clear()` or return to Pairing.

- [ ] **Step 2: Add failing tests for QR back priority and final URL state**

```kotlin
@Test
fun backClosesQrBeforeInfoOrPlayer() = runTest(dispatcher) {
    startCollectors()
    socket.emit(load("video", "p1"))
    advanceUntilIdle()
    socket.emit(ControlCommand.ShowQrCode)
    runCurrent()
    playerController.clearCalls()

    viewModel.onBack()

    assertFalse(viewModel.uiState.value.qrVisible)
    assertEquals(SessionPage.Player, viewModel.uiState.value.page)
    assertTrue(playerController.calls.isEmpty())
}

@Test
fun acceptedResolutionPublishesAndClearRemovesPlaybackUrl() = runTest(dispatcher) {
    startCollectors()
    socket.emit(load("video", "p1"))
    advanceUntilIdle()

    assertEquals("https://cdn/video-p1.m3u8", viewModel.uiState.value.playbackUrl)

    viewModel.onBack()
    viewModel.onBack()

    assertEquals("", viewModel.uiState.value.playbackUrl)
}
```

- [ ] **Step 3: Add failing independent diagnostic timer tests**

```kotlin
@Test
fun diagnosticLogHidesFiveSecondsAfterLatestEvent() = runTest(dispatcher) {
    startCollectors()
    socket.emit(ControlCommand.Play)
    runCurrent()
    assertTrue(viewModel.uiState.value.diagnosticVisible)

    advanceTimeBy(4_999)
    runCurrent()
    socket.emit(ControlCommand.Pause)
    runCurrent()
    advanceTimeBy(4_999)
    runCurrent()
    assertTrue(viewModel.uiState.value.diagnosticVisible)

    advanceTimeBy(1)
    runCurrent()
    assertFalse(viewModel.uiState.value.diagnosticVisible)
    assertTrue(viewModel.uiState.value.diagnosticLogs.isNotEmpty())
}

@Test
fun diagnosticAndPlayerInfoTimersDoNotCancelEachOther() = runTest(dispatcher) {
    startCollectors()
    socket.emit(ControlCommand.Play)
    runCurrent()
    advanceTimeBy(4_000)
    runCurrent()

    socket.mutableStates.value = SocketConnectionState.Reconnecting
    runCurrent()
    advanceTimeBy(1_000)
    runCurrent()

    assertFalse(viewModel.uiState.value.infoVisible)
    assertTrue(viewModel.uiState.value.diagnosticVisible)
}
```

- [ ] **Step 4: Run focused Session tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.session.SessionViewModelTest" --no-daemon --no-parallel --rerun-tasks
```

Expected: compilation failures for new state fields and behavioral failure because QR currently clears playback.

- [ ] **Step 5: Extend `SessionUiState`**

```kotlin
data class SessionUiState(
    val roomId: String,
    // existing fields remain unchanged
    val playbackUrl: String = "",
    val qrVisible: Boolean = false,
    val diagnosticLogs: List<DiagnosticLogEntry> = emptyList(),
    val diagnosticVisible: Boolean = false,
)
```

- [ ] **Step 6: Add an independent diagnostic timer to `SessionViewModel`**

Add fields:

```kotlin
private var diagnosticOverlayJob: Job? = null
private var diagnosticRevision = 0L
```

Add helper:

```kotlin
private fun appendDiagnostic(entry: DiagnosticLogEntry) {
    val revision = ++diagnosticRevision
    diagnosticOverlayJob?.cancel()
    mutableUiState.update {
        it.copy(
            diagnosticLogs = it.diagnosticLogs.appendDiagnostic(entry),
            diagnosticVisible = true,
        )
    }
    diagnosticOverlayJob = viewModelScope.launch {
        delay(DIAGNOSTIC_TIMEOUT_MS)
        if (revision == diagnosticRevision) {
            mutableUiState.update { it.copy(diagnosticVisible = false) }
        }
    }
}
```

Use a distinct constant:

```kotlin
const val DIAGNOSTIC_TIMEOUT_MS = 5_000L
```

In the state collector, append only when the connection enum actually changes. In the command collector, call `appendDiagnostic(command.toDiagnosticLog())` immediately before `handleCommand(command)`.

- [ ] **Step 7: Make QR non-destructive and publish URL only after generation checks**

Change command mapping:

```kotlin
ControlCommand.ShowQrCode -> showQrOverlay()
```

Add:

```kotlin
private fun showQrOverlay() {
    if (mutableUiState.value.page == SessionPage.Player) {
        mutableUiState.update { it.copy(qrVisible = true) }
    }
}
```

Update `onBack()` order:

```kotlin
fun onBack() {
    when {
        mutableUiState.value.qrVisible -> mutableUiState.update { it.copy(qrVisible = false) }
        mutableUiState.value.infoVisible -> hideInfo()
        mutableUiState.value.page == SessionPage.Player -> showPairingPage()
    }
}
```

In the already generation-checked resolver success update, add:

```kotlin
playbackUrl = resolved.url,
```

When starting a non-preserved load, use this field update:

```kotlin
playbackUrl = if (preserveEpisodes) it.playbackUrl else "",
qrVisible = false,
```

In `showPairingPage()`, add these fields to the existing copy:

```kotlin
playbackUrl = "",
qrVisible = false,
diagnosticVisible = false,
```

An adjacent episode load therefore preserves the committed URL until the replacement URL passes the existing generation checks and succeeds.

- [ ] **Step 8: Cancel the diagnostic job during clear and verify GREEN**

Add to `onCleared()` before scope cancellation:

```kotlin
diagnosticRevision += 1
diagnosticOverlayJob?.cancel()
diagnosticOverlayJob = null
```

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.session.SessionViewModelTest" --no-daemon --no-parallel --rerun-tasks
git diff --check
```

Expected: all Session tests PASS, including existing load-generation and five-second player-info tests.

---

### Task 4: Pure TV Remote Key Mapping and ViewModel Control Entry

**Files:**
- Create: `app/src/main/java/com/airplay/tv/feature/player/TvRemoteKeyMapper.kt`
- Create: `app/src/test/java/com/airplay/tv/feature/player/TvRemoteKeyMapperTest.kt`
- Modify: `app/src/main/java/com/airplay/tv/session/SessionViewModel.kt`
- Modify: `app/src/test/java/com/airplay/tv/session/SessionViewModelTest.kt`

**Interfaces:**
- Consumes: Android `KeyEvent` integer constants, `SessionUiState.isPlaying`, and existing media control methods.
- Produces:
  - `enum class RemoteControlAction { Play, Pause, TogglePlayPause, Forward, Back }`
  - `fun mapTvRemoteKey(keyCode: Int, action: Int, repeatCount: Int): RemoteControlAction?`
  - `fun SessionViewModel.onRemoteControl(action: RemoteControlAction)`.

- [ ] **Step 1: Write the failing pure mapper tests**

```kotlin
package com.airplay.tv.feature.player

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvRemoteKeyMapperTest {
    @Test
    fun mapsMediaAndDpadKeysOnKeyDown() {
        assertEquals(
            RemoteControlAction.Play,
            mapTvRemoteKey(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.ACTION_DOWN, 0),
        )
        assertEquals(
            RemoteControlAction.Pause,
            mapTvRemoteKey(KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.ACTION_DOWN, 0),
        )
        assertEquals(
            RemoteControlAction.TogglePlayPause,
            mapTvRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 0),
        )
        assertEquals(
            RemoteControlAction.Forward,
            mapTvRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 0),
        )
        assertEquals(
            RemoteControlAction.Back,
            mapTvRemoteKey(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.ACTION_DOWN, 0),
        )
    }

    @Test
    fun ignoresKeyUpUnknownKeysAndRepeatedToggle() {
        assertNull(mapTvRemoteKey(KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.ACTION_UP, 0))
        assertNull(mapTvRemoteKey(KeyEvent.KEYCODE_MENU, KeyEvent.ACTION_DOWN, 0))
        assertNull(mapTvRemoteKey(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_DOWN, 1))
        assertEquals(
            RemoteControlAction.Forward,
            mapTvRemoteKey(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, 3),
        )
    }
}
```

- [ ] **Step 2: Run mapper tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.feature.player.TvRemoteKeyMapperTest" --no-daemon --no-parallel --rerun-tasks
```

Expected: compilation FAIL because mapper/action do not exist.

- [ ] **Step 3: Implement the pure mapper**

```kotlin
package com.airplay.tv.feature.player

import android.view.KeyEvent

enum class RemoteControlAction {
    Play,
    Pause,
    TogglePlayPause,
    Forward,
    Back,
}

fun mapTvRemoteKey(
    keyCode: Int,
    action: Int,
    repeatCount: Int,
): RemoteControlAction? {
    if (action != KeyEvent.ACTION_DOWN) return null
    return when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY -> RemoteControlAction.Play.takeIf { repeatCount == 0 }
        KeyEvent.KEYCODE_MEDIA_PAUSE -> RemoteControlAction.Pause.takeIf { repeatCount == 0 }
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        -> RemoteControlAction.TogglePlayPause.takeIf { repeatCount == 0 }
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        -> RemoteControlAction.Forward
        KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_DPAD_LEFT,
        -> RemoteControlAction.Back
        else -> null
    }
}
```

- [ ] **Step 4: Add failing ViewModel semantic-control test**

```kotlin
@Test
fun remoteControlsUseSamePlayerPathAsSocketControls() = runTest(dispatcher) {
    startCollectors()

    viewModel.onRemoteControl(RemoteControlAction.Play)
    viewModel.onRemoteControl(RemoteControlAction.Pause)
    viewModel.onRemoteControl(RemoteControlAction.Forward)
    viewModel.onRemoteControl(RemoteControlAction.Back)
    playerController.setState(PlayerState(isPlaying = false))
    runCurrent()
    viewModel.onRemoteControl(RemoteControlAction.TogglePlayPause)

    assertEquals(
        listOf("play", "pause", "seek:15000", "seek:-15000", "play"),
        playerController.calls,
    )
}
```

- [ ] **Step 5: Implement ViewModel entry and verify GREEN**

```kotlin
fun onRemoteControl(action: RemoteControlAction) {
    if (mutableUiState.value.page != SessionPage.Player) return
    val control = when (action) {
        RemoteControlAction.Play -> MediaControl.Play
        RemoteControlAction.Pause -> MediaControl.Pause
        RemoteControlAction.TogglePlayPause -> if (mutableUiState.value.isPlaying) {
            MediaControl.Pause
        } else {
            MediaControl.Play
        }
        RemoteControlAction.Forward -> MediaControl.Forward
        RemoteControlAction.Back -> MediaControl.Back
    }
    handleMediaControl(control)
}
```

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.feature.player.TvRemoteKeyMapperTest" --tests "com.airplay.tv.session.SessionViewModelTest" --no-daemon --no-parallel --rerun-tasks
```

Expected: mapper and Session tests PASS.

---

### Task 5: Activity Remote Dispatch

**Files:**
- Modify: `app/src/main/java/com/airplay/tv/MainActivity.kt`
- Modify: `app/src/androidTest/java/com/airplay/tv/MainActivityLifecycleTest.kt`

**Interfaces:**
- Consumes: `mapTvRemoteKey(...)`, `SessionViewModel.onRemoteControl(...)`, and `SessionUiState.page`.
- Produces: `MainActivity.dispatchKeyEvent(event: KeyEvent): Boolean` that consumes mapped keys only on Player page.

- [ ] **Step 1: Add a failing Activity instrumentation test and controllable recording fakes**

Change `RecordingSocketClient.commands` from `emptyFlow()` to a shared flow and add `emit`:

```kotlin
private class RecordingSocketClient : SocketClient {
    private val mutableStates = MutableStateFlow(SocketConnectionState.Connecting)
    private val mutableCommands = MutableSharedFlow<ControlCommand>(extraBufferCapacity = 8)

    override val states: StateFlow<SocketConnectionState> = mutableStates
    override val commands: Flow<ControlCommand> = mutableCommands
    var closeCalls = 0

    fun emit(command: ControlCommand) {
        check(mutableCommands.tryEmit(command))
    }

    override fun connect(roomId: String) = Unit

    override fun close() {
        closeCalls += 1
    }
}
```

Make the recording controller record seek calls before delegating:

```kotlin
override fun seekBy(deltaMs: Long) {
    calls += "seek:$deltaMs"
    delegate.seekBy(deltaMs)
}
```

Add this complete test next to the lifecycle test:

```kotlin
@Test
fun activityDispatchesRemoteKeysOnlyOnPlayerPage() {
    val application = ApplicationProvider.getApplicationContext<AirPlayTVApp>()
    val socket = RecordingSocketClient()
    lateinit var playerController: RecordingPlayerController
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        playerController = RecordingPlayerController(application)
    }
    application.sessionViewModelFactoryOverride = SessionViewModelFactory(
        roomId = "0123456789abcdef0123456789abcdef",
        socketClient = socket,
        videoResolver = VideoResolver(NoOpVideoApi),
        playerController = playerController,
    )

    try {
    ActivityScenario.launch(MainActivity::class.java).use { scenario ->
        scenario.onActivity { activity ->
            val consumed = activity.dispatchKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY),
            )
            assertFalse(consumed)
            assertTrue(playerController.calls.isEmpty())
        }

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            socket.emit(
                ControlCommand.LoadVideo(
                    vid = "video-1",
                    pid = "episode-1",
                    source = "source-a",
                    mode = "",
                ),
            )
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        scenario.onActivity { activity ->
            assertTrue(activity.dispatchKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE),
            ))
            assertTrue(activity.dispatchKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT),
            ))
            assertTrue(activity.dispatchKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT),
            ))
        }
        assertTrue(playerController.calls.containsAll(listOf("pause", "seek:15000", "seek:-15000")))
    }
    } finally {
        application.sessionViewModelFactoryOverride = null
    }
}
```

Add imports for `android.view.KeyEvent`, `kotlinx.coroutines.flow.MutableSharedFlow`, and `org.junit.Assert.assertFalse`.

- [ ] **Step 2: Compile AndroidTest and verify RED**

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon --no-parallel --rerun-tasks
```

Expected: test compiles, but when run on a device it fails because `MainActivity` does not override dispatch; if no device is attached, retain RED evidence from the pure mapper/ViewModel tests and mark runtime Activity verification as device-bound.

- [ ] **Step 3: Implement Activity dispatch without direct player access**

```kotlin
override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    val state = sessionViewModel.uiState.value
    if (state.page == SessionPage.Player) {
        val action = mapTvRemoteKey(event.keyCode, event.action, event.repeatCount)
        if (action != null) {
            sessionViewModel.onRemoteControl(action)
            return true
        }
    }
    return super.dispatchKeyEvent(event)
}
```

Add imports for `KeyEvent`, `mapTvRemoteKey`, and `SessionPage` only.

- [ ] **Step 4: Verify compilation and existing lifecycle behavior**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.feature.player.TvRemoteKeyMapperTest" :app:compileDebugAndroidTestKotlin --no-daemon --no-parallel --rerun-tasks
```

Expected: JVM mapper tests PASS and AndroidTest Kotlin compilation succeeds.

---

### Task 6: A-Layered HUD and Non-Destructive QR Overlay

**Files:**
- Modify: `app/src/main/java/com/airplay/tv/app/AppNavigation.kt`
- Modify: `app/src/main/java/com/airplay/tv/feature/pairing/PairingScreen.kt`
- Modify: `app/src/main/java/com/airplay/tv/feature/player/PlayerScreen.kt`
- Modify: `app/src/test/java/com/airplay/tv/feature/player/PlayerScreenLogicTest.kt`
- Modify: `app/src/androidTest/java/com/airplay/tv/app/AppNavigationTest.kt`

**Interfaces:**
- Consumes: Task 3 `SessionUiState` fields and the existing generated `qrCode` bitmap.
- Produces:
  - `fun shouldShowPlaybackInfo(state) = state.infoVisible || state.diagnosticVisible`
  - always-visible `ConnectionStatus`
  - conditional `DiagnosticLogOverlay`
  - conditional `PlayerQrOverlay`
  - two-row `PlayerInfoOverlay` with address and icon semantics.

- [ ] **Step 1: Add failing JVM visibility-rule tests**

```kotlin
@Test
fun playbackInfoShowsForControlInfoOrDiagnosticLogs() {
    val base = SessionUiState(roomId = "room-1")

    assertFalse(shouldShowPlaybackInfo(base))
    assertTrue(shouldShowPlaybackInfo(base.copy(infoVisible = true)))
    assertTrue(shouldShowPlaybackInfo(base.copy(diagnosticVisible = true)))
}
```

- [ ] **Step 2: Add failing Compose assertions**

Extend `AppNavigationTest` with these exact behaviors:

```kotlin
@Test
fun playerUsesIndependentHudLayersAndIconState() {
    composeRule.setContent {
        AppNavigation(
            state = SessionUiState(
                roomId = "room-1",
                page = SessionPage.Player,
                connection = SocketConnectionState.Connected,
                diagnosticVisible = true,
                diagnosticLogs = listOf(DiagnosticLogEntry("CTL", "暂停播放")),
                playbackUrl = "https://cdn.example/video.m3u8",
                isPlaying = true,
                positionMs = 10_000,
                durationMs = 20_000,
            ),
            player = player,
            onBack = {},
        )
    }

    composeRule.onNodeWithTag("connection-status").assertIsDisplayed()
    composeRule.onNodeWithTag("diagnostic-log-overlay").assertIsDisplayed()
    composeRule.onNodeWithTag("player-info-overlay").assertIsDisplayed()
    composeRule.onNodeWithText("https://cdn.example/video.m3u8").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("暂停").assertIsDisplayed()
    composeRule.onNodeWithText("播放中").assertDoesNotExist()
    composeRule.onNodeWithText("已暂停").assertDoesNotExist()
}

@Test
fun qrOverlayAppearsAbovePlayerWithoutNavigating() {
    composeRule.setContent {
        AppNavigation(
            state = SessionUiState(
                roomId = "room-1",
                page = SessionPage.Player,
                qrVisible = true,
            ),
            player = player,
            onBack = {},
        )
    }

    composeRule.onNodeWithTag("player-screen").assertIsDisplayed()
    composeRule.onNodeWithTag("player-qr-overlay").assertIsDisplayed()
    composeRule.onNodeWithTag("pairing-screen").assertDoesNotExist()
}
```

- [ ] **Step 3: Run JVM test and AndroidTest compile to verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.feature.player.PlayerScreenLogicTest" :app:compileDebugAndroidTestKotlin --no-daemon --no-parallel --rerun-tasks
```

Expected: compile failures for new UI tags/helpers or behavioral failures because connection status is currently gated by `infoVisible` and state text is rendered.

- [ ] **Step 4: Make `ConnectionStatus` text short and add a stable tag**

```kotlin
val (label, color) = when (connection) {
    SocketConnectionState.Connecting -> "连接中" to Color(0xFFFFC857)
    SocketConnectionState.Connected -> "已连接" to Color(0xFF56E39F)
    SocketConnectionState.Reconnecting -> "重连中" to Color(0xFFFFC857)
    SocketConnectionState.Closed -> "已断开" to Color(0xFFFF7B7B)
}
```

Add `.testTag("connection-status")` to its outer row.

- [ ] **Step 5: Implement the A HUD layer order in `PlayerScreen`**

Inside the root `Box`, keep the Media3 `AndroidView` first, then render in this order:

```kotlin
if (state.diagnosticVisible && state.diagnosticLogs.isNotEmpty()) {
    DiagnosticLogOverlay(
        logs = state.diagnosticLogs,
        modifier = Modifier.align(Alignment.TopStart),
    )
}

ConnectionStatus(
    connection = state.connection,
    modifier = Modifier
        .align(Alignment.TopEnd)
        .padding(top = 40.dp, end = 48.dp),
)

if (shouldShowPlaybackInfo(state)) {
    PlayerInfoOverlay(
        state = state,
        modifier = Modifier.align(Alignment.BottomCenter),
    )
}
```

Implement `DiagnosticLogOverlay` as a non-clickable/non-focusable `Column` at top-left, with `widthIn(max = 560.dp)`, bounded height, one line per entry and tag `diagnostic-log-overlay`. Do not add `clickable`, `focusable`, `pointerInput` or `LazyColumn` focus semantics.

- [ ] **Step 6: Convert bottom HUD to two rows and icon semantics**

First row uses explicit text styles:

```kotlin
Row(verticalAlignment = Alignment.Bottom) {
    Column(Modifier.weight(1f)) {
        Text(
            text = state.title.ifBlank { "正在播放" },
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (state.episodeName.isNotBlank()) {
            Text(
                text = state.episodeName,
                modifier = Modifier.padding(top = 6.dp),
                color = Color(0xFFC5CDD8),
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (state.playbackUrl.isNotBlank()) {
        Text(
            text = state.playbackUrl,
            modifier = Modifier
                .weight(1f)
                .padding(start = 24.dp),
            color = Color(0xFF9DAAB9),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
```

Second row keeps the existing times/progress but replaces the status `Text` with a dependency-free Canvas icon:

```kotlin
@Composable
private fun PlaybackStateIcon(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier
            .size(22.dp)
            .semantics {
                contentDescription = if (isPlaying) "暂停" else "播放"
            },
    ) {
        if (isPlaying) {
            val barWidth = size.width * 0.28f
            drawRect(color = color, size = Size(barWidth, size.height))
            drawRect(
                color = color,
                topLeft = Offset(size.width - barWidth, 0f),
                size = Size(barWidth, size.height),
            )
        } else {
            val path = Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path = path, color = color)
        }
    }
}

PlaybackStateIcon(
    isPlaying = state.isPlaying,
    modifier = Modifier.testTag("playback-state-icon"),
)
```

Add imports for `Canvas`, `Offset`, `Path`, `Size`, `size`, `semantics`, `contentDescription`, `FontFamily`, and `TextAlign`. Do not add a Material icons dependency.

- [ ] **Step 7: Reuse the generated QR bitmap above the Player destination**

Wrap the `NavHost` and overlays in a root `Box`. After `NavHost`, render:

```kotlin
if (state.page == SessionPage.Player && state.qrVisible) {
    PlayerQrOverlay(
        qrCode = qrCode,
        modifier = Modifier.fillMaxSize(),
    )
}
```

`PlayerQrOverlay` must have tag `player-qr-overlay`, a semi-transparent full-screen background, centered card, existing bitmap and text “扫码连接控制器”；it must not own navigation or player callbacks. Back behavior stays in `SessionViewModel.onBack()`.

- [ ] **Step 8: Verify GREEN**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.feature.player.PlayerScreenLogicTest" --tests "com.airplay.tv.session.SessionViewModelTest" :app:compileDebugAndroidTestKotlin --no-daemon --no-parallel --rerun-tasks
git diff --check
```

Expected: JVM tests PASS; AndroidTest sources compile; no whitespace errors.

---

### Task 7: Full Regression, APK, Security, and Device Verification

**Files:**
- Verify all modified/new files from Tasks 1–6.
- No production behavior added in this task.

**Interfaces:**
- Consumes: completed implementation.
- Produces: reproducible build evidence and an explicit device acceptance result.

- [ ] **Step 1: Run all fresh Debug/Release checks**

```powershell
$env:ANDROID_HOME='E:\cache\android-sdk'
$env:ANDROID_SDK_ROOT='E:\cache\android-sdk'
$env:GRADLE_USER_HOME='E:\cache\gradle'
$env:Path=(($env:Path -split ';') | Where-Object { $_ -and -not $_.Contains('"') }) -join ';'
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin --no-daemon --no-parallel --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`, zero test failures and zero lint errors.

- [ ] **Step 2: Inspect security boundaries and exact scope**

```powershell
rg -n "playbackUrl|diagnosticLogs|DiagnosticLogEntry|mode|https?://|Throwable|raw" app\src\main\java\com\airplay\tv
git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-android diff --check
git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-android status --short
git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-vue status --short
git -c safe.directory=D:/repo/github.com/airplayTV/api status --short
```

Expected:

- Diagnostic messages contain only fixed strings.
- `playbackUrl` is used only by Session state and player UI, not diagnostic messages or persistence.
- Vue/API states are unchanged.
- No files are staged or committed.

- [ ] **Step 3: Verify UTF-8 without BOM**

Run a PowerShell strict UTF-8 scan across every changed `.kt`, `.xml`, and `.md` file. For each file, decode using `UTF8Encoding(false, true)` and assert the first three bytes are not `EF BB BF`.

Expected: all changed text files report strict UTF-8 and `BOM=False`.

- [ ] **Step 4: Validate APK metadata and hash**

```powershell
$apk='app\build\outputs\apk\debug\app-debug.apk'
& 'E:\cache\android-sdk\cmdline-tools\latest\bin\apkanalyzer.bat' manifest application-id $apk
& 'E:\cache\android-sdk\cmdline-tools\latest\bin\apkanalyzer.bat' manifest version-name $apk
& 'E:\cache\android-sdk\build-tools\35.0.0\apksigner.bat' verify --verbose --print-certs $apk
Get-FileHash -Algorithm SHA256 $apk
```

Expected: application id `com.airplay.tv`, version `1.0.0`, Debug signature verifies, SHA-256 is recorded in `progress.md`.

- [ ] **Step 5: Check for an attached TV and run device tests when available**

```powershell
& 'E:\cache\android-sdk\platform-tools\adb.exe' devices -l
```

If a TV is attached:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon --no-parallel
& 'E:\cache\android-sdk\platform-tools\adb.exe' install -r 'app\build\outputs\apk\debug\app-debug.apk'
```

Execute this acceptance sequence:

1. Start playback and verify WebSocket status remains at top-right when all other overlays disappear.
2. Send mute twice and verify audible → silent → audible; send volume up/down and verify both directions.
3. Send QR command during playback; verify video and position continue, then press Back and verify only QR closes.
4. Trigger control/log messages; verify log appears top-left, address and progress appear at bottom, then logs disappear 5 seconds after the final message.
5. Press Play/Pause, DPAD center, DPAD right and DPAD left; verify pause/play and ±15-second seeking.
6. Verify no “播放中/已暂停” text is displayed; only the corresponding icon is visible.

If no TV is attached, do not claim device verification; report build/compile evidence and hand off this exact checklist.

- [ ] **Step 6: Update planning records and final diff review**

Update root `task_plan.md`, `findings.md`, and `progress.md` with test commands, failures/resolutions, APK hash and device boundary. Re-run:

```powershell
git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-android diff --stat
git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-android diff --check
git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-android status --short
```

Expected: only intended Android source/tests/docs plus root planning files are changed; nothing staged; no whitespace errors.

---

## Final Review Checklist

- [ ] `MODIFY_AUDIO_SETTINGS` exists in source and merged manifests.
- [ ] Web mute toggles audible/silent state and volume controls work on device.
- [ ] QR command leaves page, current media, position and play state intact.
- [ ] Back closes QR before playback information or player page.
- [ ] Diagnostic logs retain at most 20 fixed-text entries and hide 5 seconds after the latest entry.
- [ ] Diagnostic and player-info timers use separate Jobs/revisions.
- [ ] Connection status is always present with short fixed text.
- [ ] Playback address is generation-safe, cleared at session clear, and never copied into diagnostics.
- [ ] A HUD has logs top-left, connection top-right, and two-row playback information at bottom.
- [ ] Playing/paused state uses accessible icons without visible status words.
- [ ] TV remote media and D-pad controls share the ViewModel playback path.
- [ ] Full Debug/Release tests, lint, assemble and AndroidTest compilation pass.
- [ ] Real-device result is reported accurately.
- [ ] No Git staging, commit or push occurred.
