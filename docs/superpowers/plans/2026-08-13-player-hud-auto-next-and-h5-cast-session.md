# Player HUD, Auto-Next, and H5 Cast Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 Android TV 播放页的日志、连接状态和扫码卡片符合控制层交互要求，支持多剧集自然结束自动下一集，并让 H5 遥控器在刷新后恢复当前投射视频与剧集切换能力。

**Architecture:** Android 以 `SessionViewModel` 为唯一会话编排入口，`PlayerController.events` 上抛一次性播放结束事件，复用现有 generation 和剧集游标加载下一集；`infoVisible` 统一控制播放 HUD。H5 以 `cast-session.js` 管理不含敏感字段的版本化本地快照，四个投射入口只提交候选元数据，`sendCastingCommand` 在 ACK 后原子保存并导航，`ControlView` 负责恢复和切集。

**Tech Stack:** Kotlin 2.1、Coroutines Flow、Jetpack Compose、Media3 1.5.1、JUnit 4、Vue 3.5、Naive UI、Node.js `node:test`、Vite 6。

## Global Constraints

- 修改范围仅限 `airplayTV-android` 和 `airplayTV-vue`；不得修改 `api`、Go 服务或 WebSocket 事件协议。
- 所有文本文件必须为 UTF-8 无 BOM。
- 每项生产代码修改前必须先运行对应失败测试，确认失败原因与需求一致。
- H5 5 秒 Presence 租约续期必须保留；仅去除 Android 重复“手机控制器已关联”日志。
- 诊断日志不得包含完整 URL、查询参数、Header、`mode`、原始 JSON 或异常正文。
- H5 投射快照不得保存播放 URL、Header、原始响应或 `mode`。
- Android 自动下一集不向 H5 回传；H5 展示当前浏览器最近一次成功投射 ACK 的状态。
- 最后一集不循环，自动下一集失败不连续重试。
- 不添加第三方依赖，不重构无关旧页面。
- 未经用户明确要求，不执行 `git add`、`git commit` 或 `git push`。

---

## File Structure

### Android

- Modify `airplayTV-android/app/src/main/java/com/airplay/tv/diagnostics/DiagnosticLogEntry.kt`：日志时间和安全阶段工厂。
- Modify `airplayTV-android/app/src/main/java/com/airplay/tv/diagnostics/DiagnosticLogOverlay.kt`：时间、5 行和左下角展示。
- Modify `airplayTV-android/app/src/main/java/com/airplay/tv/feature/player/PlayerController.kt`：定义 `PlaybackEvent` 和事件流接口。
- Modify `airplayTV-android/app/src/main/java/com/airplay/tv/feature/player/Media3PlayerController.kt`：将 `STATE_ENDED` 转为每媒体项一次事件。
- Modify `airplayTV-android/app/src/main/java/com/airplay/tv/session/SessionViewModel.kt`：关联日志去重、详细阶段日志和自动下一集。
- Modify `airplayTV-android/app/src/main/java/com/airplay/tv/feature/player/PlayerScreen.kt`：用 `infoVisible` 统一控制 HUD。
- Modify `airplayTV-android/app/src/main/java/com/airplay/tv/app/AppNavigation.kt`：右上角小二维码卡片和左下角日志定位。
- Modify `airplayTV-android/app/src/main/java/com/airplay/tv/feature/pairing/PairingScreen.kt`：复用房间号中间省略。
- Create `airplayTV-android/app/src/main/java/com/airplay/tv/feature/pairing/RoomIdFormatter.kt`：房间号纯格式化函数。
- Modify existing Android JVM and Compose tests for these components.

### H5

- Create `airplayTV-vue/src/helpers/cast-session.js`：快照规范化、保存、读取、更新。
- Modify `airplayTV-vue/src/helpers/constant.js`：增加快照 storage key。
- Modify `airplayTV-vue/src/helpers/casting.js`：ACK 后保存候选快照。
- Modify four casting entry components：传入标题、封面和剧集元数据。
- Modify `airplayTV-vue/src/views/ControlView.vue`：恢复当前投射卡片并发送切集命令。
- Create `airplayTV-vue/tests/cast-session.test.mjs` and extend `casting.test.mjs`.

---

### Task 1: Timestamped Five-Line Diagnostics and HUD Visibility

**Files:**
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/diagnostics/DiagnosticLogEntry.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/diagnostics/DiagnosticLogOverlay.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/feature/player/PlayerScreen.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/app/AppNavigation.kt`
- Modify: `airplayTV-android/app/src/test/java/com/airplay/tv/diagnostics/DiagnosticLogEntryTest.kt`
- Modify: `airplayTV-android/app/src/test/java/com/airplay/tv/feature/player/PlayerScreenLogicTest.kt`
- Modify: `airplayTV-android/app/src/androidTest/java/com/airplay/tv/app/AppNavigationTest.kt`

**Interfaces:**
- Produces `DiagnosticLogEntry(stage, message, timestampMillis)`.
- Produces `formatDiagnosticTime(timestampMillis): String` using device-local `HH:mm:ss`.
- Produces `shouldShowPlayerDiagnostics(state) = state.infoVisible && logs.isNotEmpty()`.
- Playback page renders at most `MAX_VISIBLE_DIAGNOSTIC_LOGS = 5`.

- [ ] **Step 1: Write failing log model tests**

Add to `DiagnosticLogEntryTest.kt`:

```kotlin
@Test
fun diagnosticTimeUsesHoursMinutesAndSeconds() {
    val zone = java.time.ZoneId.of("Asia/Shanghai")
    assertEquals("21:36:08", formatDiagnosticTime(1_786_636_568_000L, zone))
}

@Test
fun factoryKeepsTimestampWithoutLeakingPayload() {
    val entry = ControlCommand.LoadVideo("v", "p", "s", "secret")
        .toDiagnosticLog(timestampMillis = 123L)
    assertEquals(123L, entry.timestampMillis)
    assertFalse(entry.toString().contains("secret"))
}
```

- [ ] **Step 2: Write failing HUD gating tests**

Add to `PlayerScreenLogicTest.kt`:

```kotlin
@Test
fun diagnosticsAndConnectionFollowPlaybackInfoVisibility() {
    val hidden = SessionUiState(
        roomId = "room-1",
        diagnosticLogs = listOf(DiagnosticLogEntry("CTL", "暂停", 1L)),
    )
    assertFalse(shouldShowPlayerDiagnostics(hidden))
    assertFalse(shouldShowPlayerConnection(hidden))

    val visible = hidden.copy(infoVisible = true)
    assertTrue(shouldShowPlayerDiagnostics(visible))
    assertTrue(shouldShowPlayerConnection(visible))
}
```

Extend `AppNavigationTest.kt` with one visible and one hidden state. When `infoVisible=true`, assert tags `connection-status`, `diagnostic-log-overlay`, and `player-info-overlay` exist; when false, assert all three do not exist. Provide six logs and assert the oldest message does not exist while the newest five do.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
Set-Location D:\repo\github.com\airplayTV\airplayTV-android
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.diagnostics.DiagnosticLogEntryTest" --tests "com.airplay.tv.feature.player.PlayerScreenLogicTest" :app:compileDebugAndroidTestKotlin --no-daemon --no-parallel --rerun-tasks
```

Expected: compilation fails because timestamp APIs and HUD helpers do not exist; existing UI still renders connection independently and permits eight log rows.

- [ ] **Step 4: Implement the timestamped model**

Change the model and factories to accept an injectable timestamp:

```kotlin
data class DiagnosticLogEntry(
    val stage: String,
    val message: String,
    val timestampMillis: Long = System.currentTimeMillis(),
)

fun formatDiagnosticTime(
    timestampMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = Instant.ofEpochMilli(timestampMillis)
    .atZone(zoneId)
    .format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT))

fun ControlCommand.toDiagnosticLog(
    timestampMillis: Long = System.currentTimeMillis(),
): DiagnosticLogEntry = DiagnosticLogEntry(
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
        ControlCommand.ControllerPaired -> "手机控制器已关联"
        ControlCommand.ControllerUnpaired -> "手机控制器已断开"
        ControlCommand.HistoryIgnored -> "收到历史指令"
    },
    timestampMillis = timestampMillis,
)
```

Apply the same optional timestamp to `SocketConnectionState.toDiagnosticLog`; map `Connecting/Connected/Reconnecting/Closed` to `连接中/已连接/重连中/已断开` respectively so tests remain deterministic.

- [ ] **Step 5: Render five timestamped rows**

In `DiagnosticLogOverlay.kt`, set:

```kotlin
internal const val MAX_VISIBLE_DIAGNOSTIC_LOGS = 5
```

Render each row as three texts:

```kotlin
Text(formatDiagnosticTime(entry.timestampMillis), fontFamily = FontFamily.Monospace)
Text(entry.stage, modifier = Modifier.padding(start = 10.dp))
Text(
    entry.message,
    modifier = Modifier.padding(start = 10.dp),
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
)
```

- [ ] **Step 6: Gate playback HUD from `infoVisible`**

Add pure helpers to `PlayerScreen.kt`:

```kotlin
internal fun shouldShowPlayerConnection(state: SessionUiState): Boolean = state.infoVisible

internal fun shouldShowPlayerDiagnostics(state: SessionUiState): Boolean =
    state.infoVisible && state.diagnosticLogs.isNotEmpty()
```

Render `ConnectionStatus` only when `shouldShowPlayerConnection(state)`. Move playback-page diagnostic rendering into `PlayerScreen` at `Alignment.BottomStart` with `start = 48.dp` and `bottom = 132.dp`, and remove player-page diagnostic rendering from `AppNavigation`. Keep Pairing diagnostics in `AppNavigation` unchanged.

- [ ] **Step 7: Verify GREEN**

Run the command from Step 3 again. Expected: focused JVM tests pass, AndroidTest sources compile, and the UI tests show only the newest five rows.

- [ ] **Step 8: Checkpoint review**

```powershell
git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-android diff --check
git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-android diff -- app/src/main app/src/test app/src/androidTest
```

Expected: only Task 1 files changed; no whitespace errors.

---

### Task 2: Compact QR Card and Middle-Ellipsized Room IDs

**Files:**
- Create: `airplayTV-android/app/src/main/java/com/airplay/tv/feature/pairing/RoomIdFormatter.kt`
- Create: `airplayTV-android/app/src/test/java/com/airplay/tv/feature/pairing/RoomIdFormatterTest.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/feature/pairing/PairingScreen.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/app/AppNavigation.kt`
- Modify: `airplayTV-android/app/src/androidTest/java/com/airplay/tv/app/AppNavigationTest.kt`

**Interfaces:**
- Produces `middleEllipsizeRoomId(roomId, maxChars = 18): String`.
- Player QR overlay is a top-right card with no full-screen background.

- [ ] **Step 1: Write failing formatter tests**

```kotlin
class RoomIdFormatterTest {
    @Test fun shortRoomIsUnchanged() =
        assertEquals("room-123", middleEllipsizeRoomId("room-123"))

    @Test fun longRoomKeepsBothEnds() =
        assertEquals("room-12...0abcdef", middleEllipsizeRoomId("room-1234567890abcdef"))

    @Test fun customLimitIsNeverExceeded() {
        assertTrue(middleEllipsizeRoomId("abcdefghijklmnop", 10).length <= 10)
    }
}
```

- [ ] **Step 2: Add failing QR source/UI regression assertions**

In `AppNavigationTest.kt`, assert `player-qr-overlay` exists together with `player-screen`, and assert its semantics bounds are in the right half of the root. Add a source regression assertion or Compose property assertion that the overlay no longer applies `Color(0xB3000000)` to `fillMaxSize()`.

- [ ] **Step 3: Run tests and verify RED**

```powershell
Set-Location D:\repo\github.com\airplayTV\airplayTV-android
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.feature.pairing.RoomIdFormatterTest" :app:compileDebugAndroidTestKotlin --no-daemon --no-parallel --rerun-tasks
```

Expected: formatter test compilation fails; current QR overlay remains centered with a full-screen dim layer.

- [ ] **Step 4: Implement deterministic middle ellipsis**

Create `RoomIdFormatter.kt`:

```kotlin
package com.airplay.tv.feature.pairing

fun middleEllipsizeRoomId(roomId: String, maxChars: Int = 18): String {
    require(maxChars >= 5)
    if (roomId.length <= maxChars) return roomId
    val remaining = maxChars - 3
    val prefix = (remaining + 1) / 2
    val suffix = remaining / 2
    return roomId.take(prefix) + "..." + roomId.takeLast(suffix)
}
```

Use this function in `PairingScreen` and `PlayerQrOverlay` before text rendering; retain `maxLines=1` and `softWrap=false`.

- [ ] **Step 5: Replace the modal QR overlay with a compact card**

In `AppNavigation`, replace `modifier.fillMaxSize()` with:

```kotlin
modifier = Modifier
    .align(Alignment.TopEnd)
    .padding(top = 40.dp, end = 48.dp)
```

`PlayerQrOverlay` becomes a single card `Column` with tag `player-qr-overlay`, `RoundedCornerShape(16.dp)`, dark card background, `196.dp` outer QR box, `172.dp` image content, title `扫码连接`, ellipsized room id, and no full-screen background or `contentAlignment=Center` wrapper.

- [ ] **Step 6: Avoid overlap with connection status**

When `state.qrVisible && state.infoVisible`, place `ConnectionStatus` below the QR card using a top padding of `292.dp`; otherwise retain `40.dp`. Express this in a pure helper `connectionTopPadding(qrVisible): Dp` so the layout rule is testable.

- [ ] **Step 7: Verify GREEN and checkpoint**

Run Step 3 again, then:

```powershell
git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-android diff --check
```

Expected: formatter and UI compilation pass; no full-screen QR dim layer remains.

---

### Task 3: Playback-End Event and Automatic Next Episode

**Files:**
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/feature/player/PlayerController.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/feature/player/Media3PlayerController.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/session/SessionViewModel.kt`
- Modify: `airplayTV-android/app/src/test/java/com/airplay/tv/feature/player/FakePlayerController.kt`
- Modify: `airplayTV-android/app/src/test/java/com/airplay/tv/feature/player/Media3PlaybackLogicTest.kt`
- Modify: `airplayTV-android/app/src/test/java/com/airplay/tv/session/SessionViewModelTest.kt`

**Interfaces:**
- Produces `sealed interface PlaybackEvent { data object Ended : PlaybackEvent }`.
- Produces `PlayerController.events: Flow<PlaybackEvent>`.
- `FakePlayerController.emitEnded()` supports deterministic ViewModel tests.

- [ ] **Step 1: Write failing per-media end gate tests**

Add a small pure `PlaybackEndGate` test to `Media3PlaybackLogicTest.kt`:

```kotlin
@Test
fun endedIsEmittedOncePerLoadedMedia() {
    val gate = PlaybackEndGate()
    gate.onLoad()
    assertTrue(gate.tryEnd())
    assertFalse(gate.tryEnd())
    gate.onLoad()
    assertTrue(gate.tryEnd())
}
```

- [ ] **Step 2: Write failing ViewModel auto-next tests**

Add tests that load details with `p1`, `p2`, `p3`, then call `playerController.emitEnded()`:

```kotlin
@Test
fun playbackEndLoadsNextEpisodeAndPreservesMode() = runTest(dispatcher) {
    api.detailResponses += successfulDetail("Series", "p1" to "01", "p2" to "02")
    startCollectors()
    socket.emit(load("series", "p1", mode = "private-mode"))
    advanceUntilIdle()

    playerController.emitEnded()
    advanceUntilIdle()

    assertEquals("p2", api.sourceRequests.last().pid)
    assertEquals("private-mode", api.sourceRequests.last().mode)
    assertEquals("02", viewModel.uiState.value.episodeName)
}
```

Also add:

- final episode emits no new source request;
- duplicate `Ended` emits only one request;
- `Ended` before delayed details waits and then loads the next episode;
- manual new `/ctl_load_Video` while waiting cancels the old pending auto-next;
- next resolution failure leaves the committed episode and does not retry.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
Set-Location D:\repo\github.com\airplayTV\airplayTV-android
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.feature.player.Media3PlaybackLogicTest" --tests "com.airplay.tv.session.SessionViewModelTest" --no-daemon --no-parallel --rerun-tasks
```

Expected: compilation fails because `PlaybackEvent`, `events`, `PlaybackEndGate`, and `emitEnded` do not exist.

- [ ] **Step 4: Add player event contract and test fake**

In `PlayerController.kt`:

```kotlin
sealed interface PlaybackEvent {
    data object Ended : PlaybackEvent
}

interface PlayerController {
    val state: StateFlow<PlayerState>
    val events: Flow<PlaybackEvent>
    val player: Player
    fun load(url: String, mediaType: ResolvedMediaType)
    fun play()
    fun pause()
    fun seekBy(deltaMs: Long)
    fun adjustVolume(direction: Int)
    fun toggleMute()
    fun clear()
    fun release()
}
```

In `FakePlayerController` add a `MutableSharedFlow<PlaybackEvent>(extraBufferCapacity = 4)`, expose `events = mutableEvents.asSharedFlow()`, and implement:

```kotlin
fun emitEnded() {
    check(mutableEvents.tryEmit(PlaybackEvent.Ended))
}
```

- [ ] **Step 5: Emit each Media3 end once**

Add:

```kotlin
internal class PlaybackEndGate {
    private var ended = false
    fun onLoad() { ended = false }
    fun tryEnd(): Boolean = if (ended) false else true.also { ended = true }
}
```

`Media3PlayerController` owns `MutableSharedFlow<PlaybackEvent>(extraBufferCapacity=1)` and `PlaybackEndGate`. Call `endGate.onLoad()` immediately before `setMediaItem`. In `onPlaybackStateChanged`, when state is `Player.STATE_ENDED` and `endGate.tryEnd()` succeeds, emit `PlaybackEvent.Ended` after publishing final playback state.

- [ ] **Step 6: Orchestrate auto-next in `SessionViewModel`**

Add `pendingAutoAdvanceFor: ControlCommand.LoadVideo?`. Collect `playerController.events` and dispatch `PlaybackEvent.Ended` to `onPlaybackEnded()`.

`onPlaybackEnded()` must:

```kotlin
private fun onPlaybackEnded() {
    val command = currentLoadCommand ?: return
    if (mutableUiState.value.page != SessionPage.Player || pendingLoad != null) return
    if (episodes.isEmpty() && pendingDetailsCommand != null) {
        pendingAutoAdvanceFor = command
        return
    }
    autoAdvance(command)
}
```

`autoAdvance(command)` finds `command.pid`, loads the following episode with `preserveEpisodes=true`, or appends one `SKIP` log for the final/missing episode. On detail success, if `pendingAutoAdvanceFor == command`, clear it and call `autoAdvance(command)`. Clear pending auto-advance at the start of any non-auto `loadVideo`, `Previous`, `Next`, pairing reset, and `onCleared`.

Add fixed detailed logs only:

```kotlin
appendDiagnostic(DiagnosticLogEntry("PLAY", "当前剧集播放结束"))
appendDiagnostic(DiagnosticLogEntry("PLAY", "自动播放下一集"))
appendDiagnostic(DiagnosticLogEntry("SKIP", "已是最后一集"))
appendDiagnostic(DiagnosticLogEntry("ERR", "下一集加载失败"))
```

After successful source resolution, append `API 视频地址解析成功`; after details success append `API 剧集信息加载成功`. Never interpolate IDs, URL, mode, or exception text.

- [ ] **Step 7: Prevent Presence renewal log spam**

In the command collector, do not append `ControllerPaired.toDiagnosticLog()` unconditionally. Let `handleCommand` compare the previous state:

```kotlin
ControlCommand.ControllerPaired -> {
    if (!mutableUiState.value.controllerConnected) {
        appendDiagnostic(command.toDiagnosticLog())
    }
    mutableUiState.update { it.copy(controllerConnected = true) }
}
```

Keep `/ctl_pair` handling and H5 Presence scheduling unchanged. Add a test asserting two consecutive paired events create one log, then unpair + pair creates a second association log.

- [ ] **Step 8: Verify GREEN and checkpoint**

Run Step 3 again. Expected: all focused tests pass and no duplicate auto-next request occurs. Then run `git diff --check`.

---

### Task 4: Versioned H5 Cast Session Snapshot

**Files:**
- Create: `airplayTV-vue/src/helpers/cast-session.js`
- Modify: `airplayTV-vue/src/helpers/constant.js`
- Modify: `airplayTV-vue/src/helpers/casting.js`
- Create: `airplayTV-vue/tests/cast-session.test.mjs`
- Modify: `airplayTV-vue/tests/casting.test.mjs`

**Interfaces:**
- Produces `normalizeCastSession(candidate)`, `saveCastSession(candidate, storage)`, `loadCastSession(room, storage)`, `updateCastSessionEpisode(session, episode)`.
- Extends `sendCastingCommand` with optional `castSession` and injectable `saveSession`.

- [ ] **Step 1: Write failing snapshot tests**

Cover valid normalization, string trimming, invalid JSON, version mismatch, room mismatch, invalid episodes, maximum 500 episodes, and absence of `mode`, `url`, `headers`, and raw response fields. Use an in-memory storage fake implementing `getItem` and `setItem`.

Representative assertion:

```js
const session = normalizeCastSession({
  room: ' room-a ', vid: 7, pid: 8, source: 9,
  title: ' Series ', thumb: 'https://img/thumb.jpg', episodeName: ' 02 ',
  episodes: [{id: 7, name: '01'}, {id: 8, name: '02'}],
  mode: 'secret', url: 'https://cdn/private.m3u8', updatedAt: 123,
})
assert.equal(session.version, 1)
assert.equal(session.pid, '8')
assert.equal('mode' in session, false)
assert.equal('url' in session, false)
```

- [ ] **Step 2: Extend casting ACK-order test**

Update the existing test to pass `castSession` and record calls. Assert exact order: `send`, then `save`, then `navigate`. Add failure test asserting neither save nor navigate occurs.

- [ ] **Step 3: Run Node tests and verify RED**

```powershell
Set-Location D:\repo\github.com\airplayTV\airplayTV-vue
node --test tests/cast-session.test.mjs tests/casting.test.mjs
```

Expected: module-not-found for `cast-session.js` and missing save behavior.

- [ ] **Step 4: Implement the snapshot helper**

Add `KEY_CAST_SESSION = 'tv_cast_session_v1'` to `constant.js`. `cast-session.js` must whitelist fields rather than spreading candidates:

```js
export const CAST_SESSION_VERSION = 1
const MAX_EPISODES = 500
const text = (value, max) => String(value ?? '').trim().slice(0, max)

export const normalizeCastSession = (candidate) => {
  const room = text(candidate?.room, 256)
  const vid = text(candidate?.vid, 256)
  const pid = text(candidate?.pid, 256)
  const source = text(candidate?.source, 256)
  if (!room || !vid || !pid || !source) throw new Error('invalid cast session')
  const episodes = (Array.isArray(candidate?.episodes) ? candidate.episodes : [])
    .map((item) => ({id: text(item?.id, 256), name: text(item?.name, 256)}))
    .filter((item) => item.id && item.name)
    .slice(0, MAX_EPISODES)
  return {
    version: CAST_SESSION_VERSION,
    room, vid, pid, source,
    title: text(candidate?.title, 512),
    thumb: text(candidate?.thumb, 2048),
    episodeName: text(candidate?.episodeName, 512),
    episodes,
    updatedAt: Number.isFinite(candidate?.updatedAt) ? candidate.updatedAt : Date.now(),
  }
}
```

`saveCastSession` serializes only normalized output. `loadCastSession` catches JSON/storage errors, validates `version`, normalizes again, and returns only when `session.room === room`. `updateCastSessionEpisode` returns a new normalized snapshot with matching episode name and fresh timestamp.

- [ ] **Step 5: Save strictly after ACK**

Extend `sendCastingCommand`:

```js
export const sendCastingCommand = async ({
  room, context, castSession, sendControl = sendControlWithAck,
  saveSession = saveCastSession, navigate,
}) => {
  if (!room) throw createMissingRoomError()
  const command = context?.event === '/ctl_load_Video'
    ? normalizeLoadVideoContext(context) : context
  await sendControl(room, command)
  if (castSession) saveSession({...castSession, room, ...command})
  await navigate('/control')
}
```

The snapshot helper whitelist ensures `mode` from `command` is discarded.

- [ ] **Step 6: Verify GREEN and checkpoint**

Run Step 3 again, then `git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-vue diff --check`.

---

### Task 5: Supply Cast Metadata from All Entry Points

**Files:**
- Modify: `airplayTV-vue/src/components/AppSourceList.vue`
- Modify: `airplayTV-vue/src/components/AppPlayVideo.vue`
- Modify: `airplayTV-vue/src/components/AppPlayAudio.vue`
- Modify: `airplayTV-vue/src/components/AppAudioVideoList.vue`
- Modify: `airplayTV-vue/tests/casting.test.mjs`

**Interfaces:**
- Each `sendCastingCommand` call provides `castSession` with `title`, `thumb`, `episodeName`, and normalized `episodes`.

- [ ] **Step 1: Strengthen the existing four-entry source test**

For all four files, assert the call contains `castSession:` and source expressions for `title`, `thumb`, and `episodes`. Also assert no component writes localStorage directly.

- [ ] **Step 2: Run and verify RED**

```powershell
Set-Location D:\repo\github.com\airplayTV\airplayTV-vue
node --test tests/casting.test.mjs
```

Expected: four-entry metadata assertions fail.

- [ ] **Step 3: Add a shared metadata builder**

Export from `cast-session.js`:

```js
export const buildCastSessionCandidate = ({room, video, current, source}) => ({
  room,
  vid: video?.id,
  pid: current?.id,
  source,
  title: video?.name ?? '',
  thumb: video?.thumb ?? '',
  episodeName: current?.name ?? current?.title ?? '',
  episodes: (video?.links ?? []).map((item) => ({
    id: item?.id,
    name: item?.name ?? item?.title ?? '',
  })),
})
```

Each entry calls this builder from data already present in the component. `AppSourceList` must receive the complete `video` object from `AppVideo.vue` via a new `:video="video"` prop; retain `vid` for compatibility. Audio entries use the same shape and may provide a one-item list.

- [ ] **Step 4: Pass candidate into `sendCastingCommand`**

Representative call:

```js
castSession: buildCastSessionCandidate({
  room: room.value,
  video: props.video,
  current: findLink,
  source: getAppSource(),
}),
```

Do not add `mode`, playback URL, request objects, or raw API responses to this candidate.

- [ ] **Step 5: Verify GREEN and checkpoint**

Run Step 2 again. Expected: all casting tests pass and four entries retain ACK/error behavior.

---

### Task 6: H5 Controller Current-Cast Card and Episode Switching

**Files:**
- Modify: `airplayTV-vue/src/views/ControlView.vue`
- Create: `airplayTV-vue/tests/control-cast-session.test.mjs`

**Interfaces:**
- Control page loads `loadCastSession(room)` during `onBeforeMount`.
- Produces `switchCastEpisode(episode)` that updates UI only after ACK.

- [ ] **Step 1: Write failing controller source/logic tests**

Test a pure exported helper from `cast-session.js` for current episode and single-episode visibility:

```js
assert.equal(findCastEpisode(session, 'p2').name, '02')
assert.equal(shouldShowEpisodeSwitcher({...session, episodes: [{id:'p1', name:'01'}]}), false)
assert.equal(shouldShowEpisodeSwitcher(session), true)
```

Read `ControlView.vue` and assert it imports `loadCastSession`, renders `current-cast-card`, iterates `castSession.episodes`, compares `episode.id === castSession.pid`, and calls `sendControlWithAck` through `sendControlCommand`.

- [ ] **Step 2: Run and verify RED**

```powershell
Set-Location D:\repo\github.com\airplayTV\airplayTV-vue
node --test tests/control-cast-session.test.mjs tests/cast-session.test.mjs
```

Expected: helper exports and controller card assertions fail.

- [ ] **Step 3: Restore matching session during mount**

In `ControlView.vue` add:

```js
const castSession = ref(null)
const switchingEpisodeId = ref('')

const onBeforeMountHandler = async () => {
  room.value = getStorageSync(KEY_ROOM_ID)
  clientId.value = getStorageSync(KEY_CLIENT_ID)
  castSession.value = loadCastSession(room.value)
}
```

- [ ] **Step 4: Implement ACK-safe episode switching**

```js
const switchCastEpisode = async (episode) => {
  if (!castSession.value || episode.id === castSession.value.pid || switchingEpisodeId.value) return
  switchingEpisodeId.value = episode.id
  await sendControlCommand({
    room: room.value,
    context: {
      event: ControlEventLoadVideo,
      group: room.value,
      vid: castSession.value.vid,
      pid: episode.id,
      source: castSession.value.source,
      mode: appStore.sourceSecret ?? '',
      from: clientId.value,
    },
    sendControl: sendControlWithAck,
    updateState: () => {
      castSession.value = updateCastSessionEpisode(castSession.value, episode)
      saveCastSession(castSession.value)
    },
    onFailure: () => message.warning('电视未连接，请重新扫码'),
  })
  switchingEpisodeId.value = ''
}
```

Use `try/finally` around the call so synchronous errors also clear `switchingEpisodeId`.

- [ ] **Step 5: Render the card below controls**

Add a scoped card with `data-testid="current-cast-card"`: thumbnail with fixed 88px size when present; title and current episode with `n-ellipsis`; episode tags in a wrapping flex container; current tag uses `type="info"`; pending tag uses loading/disabled styling. Render the switcher only when `episodes.length > 1`.

Retain the current controller icon style and existing page/footer structure.

- [ ] **Step 6: Verify GREEN and H5 build**

```powershell
Set-Location D:\repo\github.com\airplayTV\airplayTV-vue
node --test tests/*.test.mjs
npm run build
```

Expected: all Node tests pass and Vite production build succeeds.

---

### Task 7: Full Regression and Acceptance Evidence

**Files:**
- Verify all files changed in Tasks 1–6.
- Update root `task_plan.md`, `findings.md`, and `progress.md` with evidence only.

- [ ] **Step 1: Run fresh Android Debug/Release validation**

```powershell
Set-Location D:\repo\github.com\airplayTV\airplayTV-android
$env:ANDROID_HOME='E:\cache\android-sdk'
$env:ANDROID_SDK_ROOT='E:\cache\android-sdk'
$env:GRADLE_USER_HOME='E:\cache\gradle'
$env:Path=(($env:Path -split ';') | Where-Object { $_ -and -not $_.Contains('"') }) -join ';'
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin --no-daemon --no-parallel --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`, zero test failures, zero lint errors, both APKs generated.

- [ ] **Step 2: Re-run complete H5 validation**

```powershell
Set-Location D:\repo\github.com\airplayTV\airplayTV-vue
node --test tests/*.test.mjs
npm run build
```

Expected: all tests pass and Vite build succeeds.

- [ ] **Step 3: Audit secrets, scope, encoding, and whitespace**

```powershell
Set-Location D:\repo\github.com\airplayTV
rg -n "mode|playbackUrl|headers|raw|Throwable" airplayTV-vue/src/helpers/cast-session.js airplayTV-android/app/src/main/java/com/airplay/tv/diagnostics
git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-android -C airplayTV-android diff --check
git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-vue -C airplayTV-vue diff --check
git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-android -C airplayTV-android status --short
git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-vue -C airplayTV-vue status --short
git -c safe.directory=D:/repo/github.com/airplayTV/api -C api status --short
```

Expected: snapshot/log output contains no sensitive values; API has no new changes; existing unrelated untracked Vue/API files remain untouched.

Use strict `UTF8Encoding(false, true)` to decode every changed `.kt`, `.js`, `.vue`, `.mjs`, and `.md` file and assert no `EF BB BF` prefix.

- [ ] **Step 4: Record APK identity and hash**

```powershell
$apk='D:\repo\github.com\airplayTV\airplayTV-android\app\build\outputs\apk\debug\app-debug.apk'
& 'E:\cache\android-sdk\cmdline-tools\latest\bin\apkanalyzer.bat' manifest application-id $apk
& 'E:\cache\android-sdk\build-tools\35.0.0\apksigner.bat' verify --verbose --print-certs $apk
Get-FileHash -Algorithm SHA256 $apk
```

Expected: application id `com.airplay.tv`, Debug signature verifies, SHA-256 recorded in `progress.md`.

- [ ] **Step 5: Run device acceptance when a TV is available**

```powershell
& 'E:\cache\android-sdk\platform-tools\adb.exe' devices -l
```

If a device is attached, run `:app:connectedDebugAndroidTest`, install the debug APK, and verify:

1. Trigger the control layer: connection, bottom information, and the latest five timestamped logs appear together; all disappear together.
2. Logs stay in the lower-left safe area and do not overlap the progress/control region.
3. QR appears as a small upper-right card, remains scannable, and playback continues.
4. Long room ids show recognizable prefix and suffix with `...`.
5. A multi-episode HLS/MP4 naturally ends and advances exactly once; final episode stops.
6. Refresh H5 `/control`: current video and episodes restore; switching updates only after TV ACK.
7. Leave H5 connected for more than 10 seconds: Presence stays online without repeated association logs.

If no device is attached, explicitly report these items as pending; do not equate build success with device acceptance.

- [ ] **Step 6: Final scope report**

Update planning files with exact commands, counts, lint result, APK hash, untouched repositories, and device boundary. Do not stage or commit unless the user explicitly asks.

---

## Plan Self-Review Checklist

- [x] All nine requirements map to concrete tasks.
- [x] Android automatic next handles final episode, delayed details, duplicate end callbacks, manual replacement, and failure.
- [x] Presence renewal remains active while association logging is edge-triggered.
- [x] H5 persistence occurs only after ACK and excludes `mode`, URL, headers, and raw responses.
- [x] H5 refresh semantics and lack of TV-to-H5 auto-next sync are explicit.
- [x] Existing unrelated untracked Vue/API files are preserved.
- [x] Every production change has a preceding RED command and a GREEN command.
- [x] Full Debug/Release, H5 build, encoding, whitespace, scope, and device boundaries are covered.
