# TV Playback Resume, Episodes, and History Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Android TV 增加按剧集断点续播、遥控器选集、10 秒控制层、防息屏和向当前关联手机定向同步最新播放记录的能力。

**Architecture:** Android TV 是播放状态和本地进度的唯一事实来源，使用 Preferences DataStore 每 5 秒保存，并每 30 秒或关键事件通过 WebSocket 上报最新快照。Go 服务使用 TV 房间注册表和现有 Presence 注册表定向转发，不持久化；H5 在 App 级监听并原子 upsert 当前一条 `history` 与 `timeline`。

**Tech Stack:** Kotlin 2.1、Jetpack Compose/Android TV、Media3 1.5.1、Preferences DataStore、Coroutines、OkHttp WebSocket、Go/Gin/goWebsocket、Vue 3、Dexie 4、Node Test Runner。

## Global Constraints

- 设计规格：`docs/superpowers/specs/2026-08-20-tv-playback-resume-episodes-and-history-sync-design.md`。
- Android `minSdk=23`、`targetSdk=35`、JVM 17。
- TV 本地保存间隔固定 5 秒；WebSocket 周期固定 30 秒。
- 完成规则固定为“剩余不超过 30 秒或进度达到 95%”；自然结束直接完成。
- 控制层最后一次有效操作 10 秒后隐藏；选集语义焦点存在时不隐藏。
- TV 只同步最新一条；Go 不持久化、不补发离线消息、不全量同步。
- 不发送或持久化播放 URL、Header、`mode`、源密钥或原始解析响应。
- H5 只 upsert 收到的一条，必须保留浏览器其他历史。
- 日志固定右下，连接状态固定右上，选集固定右侧窄版单列。
- 所有修改文件使用 UTF-8 无 BOM；三个仓库分别执行 `git diff --check`。
- 保留 Android 根 `build.gradle.kts` 现有未提交修改；保留 Vue 现有未跟踪文件和 `tests/casting.test.mjs` 修改。
- 自动化验证不能替代 Android TV 真机验收。

---

## File Structure

### `api`

- Create `controller/tv_room_registry.go`: TV socket 与已加入房间的一对一关系。
- Create `controller/playback_history.go`: 载荷校验、ACK、Presence 定向转发。
- Modify `controller/controller_presence.go`: 房间客户端防御性快照。
- Modify `controller/websocket.go`, `controller/websocket_runtime.go`: join/close 生命周期和事件注册。
- Add focused tests beside each new file。

### `airplayTV-android`

- Add Preferences DataStore dependency。
- Create `feature/history/PlaybackRecord.kt`, repository interface and DataStore implementation。
- Extend player model/controller for initial seek、buffering and thumb metadata。
- Create `protocol/PlaybackHistoryProtocol.kt`; extend SocketClient outbound send and ACK flow。
- Extend Session state/ViewModel for restore、5/30-second jobs、immediate flush、episode focus and wake state。
- Extend remote mapper、PlayerScreen、PairingScreen、AppNavigation、MainActivity。
- Extend existing JVM and AndroidTest suites instead of duplicating broad suites。

### `airplayTV-vue`

- Create `src/helpers/playback-history.js` and focused Node tests。
- Modify `src/helpers/db.js` for one Dexie transaction。
- Modify `src/App.vue`, `HistoryView.vue`, `ControlView.vue` for receive、refresh and source display。

---

### Task 1: Go TV 房间注册表与 Presence 快照

**Working directory:** `D:\repo\github.com\airplayTV\api`

**Files:**
- Create: `controller/tv_room_registry.go`
- Create: `controller/tv_room_registry_test.go`
- Modify: `controller/controller_presence.go`
- Modify: `controller/controller_presence_test.go`

**Interfaces:**
- Produces: `Claim(socketID, room string) bool`, `Room(socketID string) (string, bool)`, `Remove(socketID string)`；同一房间只允许一个 owner。
- Produces: `Clients(room string) []string`，返回排序后的防御性副本。

- [ ] **Step 1: 写失败测试**

```go
func TestTVRoomRegistryKeepsFirstRoomOwnerAndRemovesSocket(t *testing.T) {
    registry := NewTVRoomRegistry()
    if !registry.Claim("tv-1", "room-a") { t.Fatal("first owner rejected") }
    if registry.Claim("attacker", "room-a") { t.Fatal("room owner was replaced") }
    if !registry.Claim("tv-1", "room-b") { t.Fatal("owner room switch rejected") }
    room, ok := registry.Room("tv-1")
    if !ok || room != "room-b" { t.Fatalf("Room = %q, %v", room, ok) }
    registry.Remove("tv-1")
    if _, ok := registry.Room("tv-1"); ok { t.Fatal("removed TV remained registered") }
}

func TestControllerPresenceClientsReturnsSortedCopy(t *testing.T) {
    registry := NewControllerPresenceRegistry()
    now := time.Unix(1, 0)
    registry.Touch("phone-b", "room-a", now)
    registry.Touch("phone-a", "room-a", now)
    got := registry.Clients("room-a")
    if !reflect.DeepEqual(got, []string{"phone-a", "phone-b"}) { t.Fatal(got) }
    got[0] = "mutated"
    if registry.Clients("room-a")[0] != "phone-a" { t.Fatal("internal state leaked") }
}
```

- [ ] **Step 2: 运行测试确认 RED**

```powershell
go test ./controller -run 'TestTVRoomRegistry|TestControllerPresenceClients' -count=1
```

Expected: FAIL，提示注册表或 `Clients` 未定义。

- [ ] **Step 3: 实现最小并发安全代码**

```go
type TVRoomRegistry struct {
    mu       sync.RWMutex
    bySocket map[string]string
    byRoom   map[string]string
}

func NewTVRoomRegistry() *TVRoomRegistry {
    return &TVRoomRegistry{bySocket: map[string]string{}, byRoom: map[string]string{}}
}
func (r *TVRoomRegistry) Claim(socketID, room string) bool {
    if socketID == "" || room == "" { return false }
    r.mu.Lock(); defer r.mu.Unlock()
    if owner := r.byRoom[room]; owner != "" && owner != socketID { return false }
    if oldRoom := r.bySocket[socketID]; oldRoom != "" { delete(r.byRoom, oldRoom) }
    r.bySocket[socketID], r.byRoom[room] = room, socketID
    return true
}
func (r *TVRoomRegistry) Room(socketID string) (string, bool) {
    r.mu.RLock(); defer r.mu.RUnlock(); room, ok := r.bySocket[socketID]; return room, ok
}
func (r *TVRoomRegistry) Remove(socketID string) {
    r.mu.Lock(); defer r.mu.Unlock()
    if room := r.bySocket[socketID]; room != "" { delete(r.byRoom, room) }
    delete(r.bySocket, socketID)
}

func (r *ControllerPresenceRegistry) Clients(room string) []string {
    r.mu.Lock(); defer r.mu.Unlock()
    clients := make([]string, 0, len(r.byRoom[room]))
    for id := range r.byRoom[room] { clients = append(clients, id) }
    sort.Strings(clients)
    return clients
}
```

- [ ] **Step 4: 运行测试并提交**

```powershell
gofmt -w controller/tv_room_registry.go controller/tv_room_registry_test.go controller/controller_presence.go controller/controller_presence_test.go
go test ./controller -run 'TestTVRoomRegistry|TestControllerPresenceClients' -count=1
go test -race ./controller -run 'TestTVRoomRegistry|TestControllerPresenceClients' -count=1
git add controller/tv_room_registry.go controller/tv_room_registry_test.go controller/controller_presence.go controller/controller_presence_test.go
git commit -m "feat: track TV rooms for history delivery"
```

Expected: 普通测试 PASS；`-race` 不受工具链支持时记录明确限制。

---

### Task 2: Go 播放记录授权、定向转发与 ACK

**Working directory:** `D:\repo\github.com\airplayTV\api`

**Files:**
- Create: `controller/playback_history.go`
- Create: `controller/playback_history_test.go`
- Modify: `controller/websocket.go`
- Modify: `controller/websocket_runtime.go`

**Interfaces:**
- Consumes Task 1 registries。
- Produces: `TVPlaybackHistory(goWebsocket.EventCtx) bool`。
- Events: `tv-playback-history`, `playback-history-update`, `tv-playback-history-ack`。

- [ ] **Step 1: 写授权、接收者和边界失败测试**

```go
func TestTVPlaybackHistoryRequiresMatchingRoom(t *testing.T) {
    controller, gateway := newPlaybackHistoryController()
    controller.tvRooms.Claim("tv-1", "room-a")
    if controller.TVPlaybackHistory(historyEvent("tv-1", "room-b", "req-1")) {
        t.Fatal("mismatched room accepted")
    }
    assertPlaybackHistoryAck(t, gateway.calls, "tv-1", "req-1", false, 0)
}

func TestTVPlaybackHistoryTargetsOnlyPresentControllers(t *testing.T) {
    controller, gateway := newPlaybackHistoryController()
    controller.tvRooms.Claim("tv-1", "room-a")
    controller.registry.Touch("phone-a", "room-a", fixedClock())
    controller.registry.Touch("phone-b", "room-a", fixedClock())
    if !controller.TVPlaybackHistory(historyEvent("tv-1", "room-a", "req-1")) { t.Fatal("rejected") }
    assertHistoryRecipients(t, gateway.calls, []string{"phone-a", "phone-b"})
    assertPlaybackHistoryAck(t, gateway.calls, "tv-1", "req-1", true, 2)
}
```

表驱动增加空 ID、负位置、超过 7 天、`file:` thumb、超长文本；序列化结果不得出现 `url/mode/header/source_secret`。

- [ ] **Step 2: 运行 RED**

```powershell
go test ./controller -run 'TestTVPlaybackHistory' -count=1
```

- [ ] **Step 3: 实现类型化校验与定向发送**

```go
type playbackHistoryInput struct {
    RequestID string `json:"request_id"`; Group string `json:"group"`; Version int `json:"version"`
    Source string `json:"source"`; VID string `json:"vid"`; PID string `json:"pid"`
    Title string `json:"title"`; EpisodeName string `json:"episode_name"`; Thumb string `json:"thumb"`
    PositionMS int64 `json:"position_ms"`; DurationMS int64 `json:"duration_ms"`; Completed bool `json:"completed"`
}

func (x *WebsocketController) TVPlaybackHistory(event goWebsocket.EventCtx) bool {
    input, err := decodeAndValidatePlaybackHistory(event.Data)
    if err != nil { x.sendPlaybackHistoryAck(event.From, safeRequestID(event.Data), false, 0); return false }
    room, joined := x.tvRooms.Room(event.From)
    if !joined || room != input.Group { x.sendPlaybackHistoryAck(event.From, input.RequestID, false, 0); return false }
    update := playbackHistoryUpdateFrom(input, x.clock().UnixMilli())
    recipients := x.registry.Clients(input.Group)
    for _, id := range recipients { x.gateway.Send(id, goWebsocket.EventCtx{Event: playbackHistoryUpdateEvent, Data: update}) }
    x.sendPlaybackHistoryAck(event.From, input.RequestID, true, len(recipients))
    return true
}
```

`JoinGroup` 成功后尝试 claim；已有 owner 时不覆盖。`Close` 释放 owner；runtime 注册新事件。数值上限固定 7 天，位置超过有效总时长时截断，thumb 只允许 `http/https` 且服务端不请求。

保留 `NewWebsocketController` 现有签名并在构造器内部初始化：

```go
return &WebsocketController{
    gateway: gateway, registry: registry, tvRooms: NewTVRoomRegistry(), clock: clock,
}
```

- [ ] **Step 4: 运行全量 Go 测试并提交**

```powershell
gofmt -w controller/playback_history.go controller/playback_history_test.go controller/websocket.go controller/websocket_runtime.go
go test ./controller -count=1
go test ./... -count=1
git add controller/playback_history.go controller/playback_history_test.go controller/websocket.go controller/websocket_runtime.go
git commit -m "feat: relay latest TV playback history"
```

---

### Task 3: Android 播放记录模型与 DataStore

**Working directory:** `D:\repo\github.com\airplayTV\airplayTV-android`

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create: `app/src/main/java/com/airplay/tv/feature/history/PlaybackRecord.kt`
- Create: `app/src/main/java/com/airplay/tv/feature/history/PlaybackProgressRepository.kt`
- Create: `app/src/main/java/com/airplay/tv/feature/history/DataStorePlaybackProgressRepository.kt`
- Create: `app/src/test/java/com/airplay/tv/feature/history/PlaybackRecordTest.kt`
- Create: `app/src/test/java/com/airplay/tv/feature/history/DataStorePlaybackProgressRepositoryTest.kt`

**Interfaces:**
- Produces: `playbackRecordKey`, `isPlaybackCompleted`, `PlaybackRecord.resumePositionMs()`。
- Produces repository: `find`, `latest`, `save`。

- [ ] **Step 1: 添加依赖并写失败测试**

```toml
datastore = "1.1.1"
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }
```

```kotlin
@Test fun completionUsesBothBoundaries() {
    assertTrue(isPlaybackCompleted(95_000, 100_000, false))
    assertTrue(isPlaybackCompleted(70_000, 100_000, false))
    assertFalse(isPlaybackCompleted(69_999, 100_000, false))
    assertTrue(isPlaybackCompleted(1, 0, true))
}

@Test fun storeKeepsNewestFiveHundred() = runTest {
    repeat(501) { repository.save(record(pid = "p$it", updatedAtMs = it.toLong())) }
    assertNull(repository.find("source", "vid", "p0"))
    assertEquals("p500", repository.latest()?.pid)
}
```

- [ ] **Step 2: 运行 RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests 'com.airplay.tv.feature.history.*'
```

- [ ] **Step 3: 实现模型与单事务淘汰**

```kotlin
data class PlaybackRecord(
    val source: String,
    val vid: String,
    val pid: String,
    val title: String,
    val episodeName: String,
    val thumb: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAtMs: Long,
) {
    fun resumePositionMs(): Long = if (completed) 0L else positionMs.coerceAtLeast(0L)
}

interface PlaybackProgressRepository {
    suspend fun find(source: String, vid: String, pid: String): PlaybackRecord?
    suspend fun latest(): PlaybackRecord?
    suspend fun save(record: PlaybackRecord)
}

internal fun isPlaybackCompleted(positionMs: Long, durationMs: Long, naturalEnd: Boolean): Boolean {
    if (naturalEnd) return true
    if (durationMs <= 0) return false
    val position = positionMs.coerceIn(0L, durationMs)
    return durationMs - position <= 30_000L || position * 100 >= durationMs * 95
}
```

DataStore 使用 `record_<sha256>` 动态 key 与 `latest_record_key`；`save` 单次 `edit` 内淘汰、并仅在新时间不早于现有 latest 时更新指针。单条 JSON 损坏只删除该 key。

- [ ] **Step 4: 运行测试并提交**

```powershell
.\gradlew.bat testDebugUnitTest --tests 'com.airplay.tv.feature.history.*'
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/airplay/tv/feature/history app/src/test/java/com/airplay/tv/feature/history
git commit -m "feat: persist TV playback progress"
```

提交不得包含根 `build.gradle.kts`。

---

### Task 4: Android Media3 初始 seek、缓冲状态与详情元数据

**Working directory:** `D:\repo\github.com\airplayTV\airplayTV-android`

**Files:**
- Modify: `app/src/main/java/com/airplay/tv/feature/player/PlayerController.kt`
- Modify: `app/src/main/java/com/airplay/tv/feature/player/Media3PlayerController.kt`
- Modify: `app/src/main/java/com/airplay/tv/feature/player/VideoModels.kt`
- Modify: `app/src/main/java/com/airplay/tv/feature/player/VideoResolver.kt`
- Modify: `app/src/test/java/com/airplay/tv/feature/player/FakePlayerController.kt`
- Modify: `app/src/test/java/com/airplay/tv/feature/player/Media3PlaybackLogicTest.kt`
- Modify: `app/src/test/java/com/airplay/tv/feature/player/VideoResolverTest.kt`

**Interfaces:**
- Produces: `load(url, mediaType, startPositionMs)`、`PlayerState.isBuffering`、`VideoDetails.thumb`。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun loadAppliesInitialPositionBeforePrepare() {
    val player = recordingPlayer()
    loadPlayer(player, mediaItem(), 42_000)
    assertEquals(listOf("setMediaItem", "seekTo:42000", "prepare", "play"), player.calls)
}

@Test fun detailMapsThumbWithoutFetchingIt() = runTest {
    api.detailResponse = successfulDetail("Title", thumb = "https://img.test/a.jpg")
    assertEquals("https://img.test/a.jpg", resolver.loadDetails(command).thumb)
}
```

- [ ] **Step 2: 运行 RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*Media3PlaybackLogicTest' --tests '*VideoResolverTest'
```

- [ ] **Step 3: 实现接口**

```kotlin
data class PlayerState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

override fun load(url: String, mediaType: ResolvedMediaType, startPositionMs: Long) {
    player.setMediaItem(buildMediaItem(url, mediaType))
    if (startPositionMs > 0) player.seekTo(startPositionMs)
    player.prepare()
    player.play()
}
```

`onPlaybackStateChanged` 映射 `STATE_BUFFERING`；`VideoDetailDto` 与 `VideoDetails` 增加 `thumb`。Fake 记录 `loadedStartPositions`。

- [ ] **Step 4: 运行测试并提交**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*Media3PlaybackLogicTest' --tests '*VideoResolverTest' --tests '*FakePlayerControllerContractTest'
git add app/src/main/java/com/airplay/tv/feature/player app/src/test/java/com/airplay/tv/feature/player
git commit -m "feat: restore Media3 playback position"
```

---

### Task 5: Android WebSocket 播放记录协议与 ACK

**Working directory:** `D:\repo\github.com\airplayTV\airplayTV-android`

**Files:**
- Create: `app/src/main/java/com/airplay/tv/protocol/PlaybackHistoryProtocol.kt`
- Create: `app/src/test/java/com/airplay/tv/protocol/PlaybackHistoryProtocolTest.kt`
- Modify: `app/src/main/java/com/airplay/tv/protocol/SocketClient.kt`
- Modify: `app/src/main/java/com/airplay/tv/protocol/OkHttpSocketClient.kt`
- Modify: `app/src/test/java/com/airplay/tv/protocol/OkHttpSocketClientTest.kt`

**Interfaces:**
- Produces: `PlaybackHistoryMessage`, `PlaybackHistoryAck`。
- Extends SocketClient: `playbackHistoryAcks` and `sendPlaybackHistory(message): Boolean`。

- [ ] **Step 1: 写安全序列化和断线失败测试**

```kotlin
@Test fun messageContainsOnlyAllowlistedFields() {
    val json = PlaybackHistoryProtocol.toJson(message())
    assertTrue(json.contains("tv-playback-history"))
    listOf("playbackUrl", "mode", "header", "sourceSecret").forEach {
        assertFalse(json.contains(it, ignoreCase = true))
    }
}

@Test fun disconnectedSendReturnsFalseWithoutQueueing() {
    assertFalse(client.sendPlaybackHistory(message()))
    assertTrue(fakeWebSockets.sentMessages.isEmpty())
}
```

另测 ACK 被发布到独立 flow、不会成为 ControlCommand。

- [ ] **Step 2: 运行 RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*PlaybackHistoryProtocolTest' --tests '*OkHttpSocketClientTest'
```

- [ ] **Step 3: 实现协议和 SocketClient 扩展**

```kotlin
data class PlaybackHistoryMessage(val requestId: String, val group: String, val record: PlaybackRecord)
data class PlaybackHistoryAck(val requestId: String, val accepted: Boolean, val recipientCount: Int)

interface SocketClient : Closeable {
    val playbackHistoryAcks: Flow<PlaybackHistoryAck>
    fun sendPlaybackHistory(message: PlaybackHistoryMessage): Boolean
}
```

send 在 `lock` 内确认当前 generation、Connected phase 和 active socket，然后直接调用 `webSocket.send`；不得保存待发送数组。`onMessage` 先解析 ACK，未匹配时才进入现有控制命令 parser。

- [ ] **Step 4: 运行测试并提交**

```powershell
.\gradlew.bat testDebugUnitTest --tests 'com.airplay.tv.protocol.*'
git add app/src/main/java/com/airplay/tv/protocol app/src/test/java/com/airplay/tv/protocol
git commit -m "feat: send TV playback history over websocket"
```

---

### Task 6: Android Session 断点恢复、5/30 秒任务与关键事件 flush

**Working directory:** `D:\repo\github.com\airplayTV\airplayTV-android`

**Files:**
- Modify: `app/src/main/java/com/airplay/tv/app/AppContainer.kt`
- Modify: `app/src/main/java/com/airplay/tv/session/SessionViewModelFactory.kt`
- Modify: `app/src/main/java/com/airplay/tv/session/SessionViewModel.kt`
- Modify: `app/src/main/java/com/airplay/tv/session/SessionState.kt`
- Modify: `app/src/test/java/com/airplay/tv/session/SessionViewModelTest.kt`
- Modify: `app/src/test/java/com/airplay/tv/session/SessionConstructionCleanupTest.kt`

**Interfaces:**
- Consumes Tasks 3–5。
- Produces state: `sourceName`, `episodes`, `currentPid`, `syncStatus`。

- [ ] **Step 1: 写恢复、定时和关联边沿失败测试**

```kotlin
repository.save(record(source = "s", vid = "v", pid = "p", positionMs = 42_000))
socket.emit(load("v", "p", source = "s"))
advanceUntilIdle()
assertEquals(42_000, player.loadedStartPositions.single())

player.setState(PlayerState(isPlaying = true, positionMs = 10_000, durationMs = 100_000))
advanceTimeBy(5_000)
assertEquals(10_000, repository.latest()!!.positionMs)
advanceTimeBy(25_000)
assertEquals(1, socket.historyMessages.size)

socket.emit(ControlCommand.ControllerPaired)
advanceUntilIdle()
val count = socket.historyMessages.size
socket.emit(ControlCommand.ControllerPaired)
advanceUntilIdle()
assertEquals(count, socket.historyMessages.size)
```

补充 completed 从 0、暂停/切集/结束/退出立即保存和同步、零接收者 ACK、旧 generation 不覆盖。

自动下一集必须保留现有一次性 ended 门控，并增加具体回归：

```kotlin
player.emitEnded()
advanceUntilIdle()
assertEquals(listOf("p2"), api.sourceCalls.map { it.pid })
assertTrue(repository.find("s", "v", "p1")!!.completed)
assertEquals(savedP2Position, player.loadedStartPositions.last())
player.emitEnded()
advanceUntilIdle()
assertEquals(1, api.sourceCalls.count { it.pid == "p2" })
```

- [ ] **Step 2: 运行 RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests 'com.airplay.tv.session.SessionViewModelTest'
```

- [ ] **Step 3: 实现 generation 绑定任务**

```kotlin
private data class PlaybackIdentity(val generation: Long, val command: ControlCommand.LoadVideo)

private fun startProgressJobs(identity: PlaybackIdentity) {
    localProgressJob?.cancel(); remoteProgressJob?.cancel()
    localProgressJob = viewModelScope.launch {
        while (isActive) { delay(5_000); if (isCurrent(identity) && playerController.state.value.isPlaying) persistSnapshot(identity, false) }
    }
    remoteProgressJob = viewModelScope.launch {
        while (isActive) { delay(30_000); if (isCurrent(identity) && playerController.state.value.isPlaying) syncSnapshot(identity) }
    }
}
```

`resolvePendingLoad` 在 player load 前读取 resume position。切集等关键事件捕获旧 identity 和 PlayerState 后立即 flush；repository 用更新时间决定 latest，防止异步完成顺序倒置。ACK 仅匹配当前 request ID，超时 5 秒后清除 pending。

- [ ] **Step 4: 运行测试并提交**

```powershell
.\gradlew.bat testDebugUnitTest --tests 'com.airplay.tv.session.*'
git add app/src/main/java/com/airplay/tv/app/AppContainer.kt app/src/main/java/com/airplay/tv/session app/src/test/java/com/airplay/tv/session
git commit -m "feat: resume and sync TV playback progress"
```

---

### Task 7: Android 状态感知遥控器选集

**Working directory:** `D:\repo\github.com\airplayTV\airplayTV-android`

**Files:**
- Modify: `app/src/main/java/com/airplay/tv/feature/player/TvRemoteKeyMapper.kt`
- Modify: `app/src/main/java/com/airplay/tv/app/App.kt`
- Modify: `app/src/main/java/com/airplay/tv/session/SessionViewModel.kt`
- Modify: `app/src/main/java/com/airplay/tv/session/SessionState.kt`
- Modify: `app/src/test/java/com/airplay/tv/feature/player/TvRemoteKeyMapperTest.kt`
- Modify: `app/src/test/java/com/airplay/tv/app/AppRemoteKeyHandlerTest.kt`
- Modify: `app/src/test/java/com/airplay/tv/session/SessionViewModelTest.kt`

**Interfaces:**
- Produces actions: `OpenEpisodes`, `EpisodeUp`, `EpisodeDown`, `SelectEpisode`, `ExitEpisodes`。
- Produces state: `episodePanelFocused`, `focusedEpisodeIndex`。

- [ ] **Step 1: 写状态感知按键和冻结计时失败测试**

```kotlin
assertEquals(OpenEpisodes, mapTvRemoteKey(KEYCODE_DPAD_UP, ACTION_DOWN, 0, false))
assertEquals(EpisodeUp, mapTvRemoteKey(KEYCODE_DPAD_UP, ACTION_DOWN, 0, true))
assertEquals(EpisodeDown, mapTvRemoteKey(KEYCODE_DPAD_DOWN, ACTION_DOWN, 0, true))
assertEquals(ExitEpisodes, mapTvRemoteKey(KEYCODE_DPAD_LEFT, ACTION_DOWN, 0, true))
assertEquals(SelectEpisode, mapTvRemoteKey(KEYCODE_DPAD_CENTER, ACTION_DOWN, 0, true))

viewModel.onRemoteControl(OpenEpisodes)
advanceTimeBy(10_001)
assertTrue(viewModel.uiState.value.infoVisible)
viewModel.onRemoteControl(EpisodeDown)
viewModel.onRemoteControl(SelectEpisode)
assertEquals("p2", api.sourceCalls.last().pid)
```

- [ ] **Step 2: 运行 RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*TvRemoteKeyMapperTest' --tests '*AppRemoteKeyHandlerTest' --tests '*SessionViewModelTest'
```

- [ ] **Step 3: 实现根焦点语义状态机**

保持 Compose 根节点实际焦点。App 把 `episodePanelFocused` 传给 mapper；ViewModel 打开列表时聚焦当前 pid 并取消 overlay job，退出后重新启动 10 秒计时。`INFO_TIMEOUT_MS=10_000L`。

```kotlin
private fun openEpisodes() {
    if (episodes.size <= 1) return
    overlayJob?.cancel()
    mutableUiState.update { it.copy(
        infoVisible = true,
        episodePanelFocused = true,
        focusedEpisodeIndex = episodes.indexOfFirst { e -> e.id == currentLoadCommand?.pid }.coerceAtLeast(0),
    ) }
}
```

- [ ] **Step 4: 运行测试并提交**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*TvRemoteKeyMapperTest' --tests '*AppRemoteKeyHandlerTest' --tests '*SessionViewModelTest'
git add app/src/main/java/com/airplay/tv/feature/player/TvRemoteKeyMapper.kt app/src/main/java/com/airplay/tv/app/App.kt app/src/main/java/com/airplay/tv/session app/src/test/java/com/airplay/tv
git commit -m "feat: navigate TV episodes with remote"
```

---

### Task 8: Android TV HUD、窄版选集与共享连接状态位置

**Working directory:** `D:\repo\github.com\airplayTV\airplayTV-android`

**Files:**
- Modify: `app/src/main/java/com/airplay/tv/feature/player/PlayerScreen.kt`
- Modify: `app/src/main/java/com/airplay/tv/feature/pairing/PairingScreen.kt`
- Modify: `app/src/main/java/com/airplay/tv/app/AppNavigation.kt`
- Modify: `app/src/test/java/com/airplay/tv/feature/player/PlayerScreenLogicTest.kt`
- Modify: `app/src/androidTest/java/com/airplay/tv/app/AppNavigationTest.kt`

**Interfaces:**
- Consumes Task 7 episode state。
- Produces tags: `episode-panel`, `episode-row-<pid>`, `episode-focus-<pid>`。

- [ ] **Step 1: 写布局失败测试**

```kotlin
composeRule.onNodeWithTag("episode-panel").assertIsDisplayed()
composeRule.onNodeWithTag("episode-row-p1").assertIsDisplayed()
composeRule.onNodeWithText("源 ffzy").assertIsDisplayed()
assertRightAligned("diagnostic-overlay-container")
assertNoOverlap("diagnostic and progress", "diagnostic-overlay-container", "player-progress")
```

在 `AppNavigationTest.kt` 增加：

```kotlin
private fun assertRightAligned(tag: String) {
    val root = composeRule.onRoot().getUnclippedBoundsInRoot()
    val node = composeRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
    assertEquals(48.dp, root.right - node.right)
}
```

补充：单集无 panel、focused row 高亮、URL 节点宽度大于标题节点、扫码/播放连接状态 top/end 坐标相同、二维码显示时播放器状态仍按既有 292dp 避让。

- [ ] **Step 2: 运行 RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*PlayerScreenLogicTest' compileDebugAndroidTestKotlin
```

- [ ] **Step 3: 实现窄版单列选集与 HUD**

```kotlin
@Composable
private fun EpisodePanel(state: SessionUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.focusedEpisodeIndex) {
        if (state.focusedEpisodeIndex >= 0) listState.animateScrollToItem(state.focusedEpisodeIndex)
    }
    LazyColumn(modifier = modifier.widthIn(min = 180.dp, max = 240.dp).testTag("episode-panel")) {
        items(state.episodes, key = Episode::id) { episode -> EpisodeRow(state, episode) }
    }
}

@Composable
private fun EpisodeRow(state: SessionUiState, episode: Episode) {
    val focused = state.episodes.getOrNull(state.focusedEpisodeIndex)?.id == episode.id
    val current = state.currentPid == episode.id
    Text(
        text = episode.name,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("episode-row-${episode.id}")
            .then(if (focused) Modifier.testTag("episode-focus-${episode.id}") else Modifier),
        color = if (focused || current) MaterialTheme.colorScheme.primary else Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
```

日志改为 `Alignment.BottomEnd` 并让进度行上移；URL 使用 `weight(1.8f)`、标题列 `weight(1f)`。源是日志层固定前缀，不依赖刚发生 SYNC。ConnectionStatus 从 PairingScreen 内容流提取到 AppNavigation 共享右上坐标。

- [ ] **Step 4: 运行测试并提交**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*PlayerScreenLogicTest' compileDebugAndroidTestKotlin
git add app/src/main/java/com/airplay/tv/feature/player/PlayerScreen.kt app/src/main/java/com/airplay/tv/feature/pairing/PairingScreen.kt app/src/main/java/com/airplay/tv/app/AppNavigation.kt app/src/test/java/com/airplay/tv/feature/player/PlayerScreenLogicTest.kt app/src/androidTest/java/com/airplay/tv/app/AppNavigationTest.kt
git commit -m "feat: add TV episode panel and refine playback HUD"
```

---

### Task 9: Android 播放页防息屏生命周期

**Working directory:** `D:\repo\github.com\airplayTV\airplayTV-android`

**Files:**
- Modify: `app/src/main/java/com/airplay/tv/session/SessionState.kt`
- Modify: `app/src/main/java/com/airplay/tv/session/SessionViewModel.kt`
- Modify: `app/src/main/java/com/airplay/tv/MainActivity.kt`
- Modify: `app/src/test/java/com/airplay/tv/session/SessionViewModelTest.kt`
- Modify: `app/src/androidTest/java/com/airplay/tv/MainActivityLifecycleTest.kt`

**Interfaces:**
- Produces: `SessionUiState.keepScreenOn`。

- [ ] **Step 1: 写策略失败测试**

```kotlin
player.setState(PlayerState(isBuffering = true))
assertTrue(viewModel.uiState.value.keepScreenOn)
player.setState(PlayerState())
advanceTimeBy(599_999)
assertTrue(viewModel.uiState.value.keepScreenOn)
advanceTimeBy(1)
assertFalse(viewModel.uiState.value.keepScreenOn)
viewModel.onForegroundChanged(false)
assertFalse(viewModel.uiState.value.keepScreenOn)
```

- [ ] **Step 2: 运行 RED**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*SessionViewModelTest'
```

- [ ] **Step 3: 实现状态与窗口标志**

```kotlin
private fun updateWakeState(active: Boolean) {
    keepScreenOnJob?.cancel()
    mutableUiState.update { it.copy(keepScreenOn = true) }
    if (!active) keepScreenOnJob = viewModelScope.launch {
        delay(10 * 60 * 1_000L)
        mutableUiState.update { it.copy(keepScreenOn = false) }
    }
}
```

播放/缓冲 active 时持续 true；暂停/结束/可恢复错误启动 10 分钟；遥控器操作重置；扫码页、后台、destroy 立即 false。MainActivity 根据 state add/clear `FLAG_KEEP_SCREEN_ON`，并在 `onStop/onDestroy` 防御性 clear。

- [ ] **Step 4: 运行测试并提交**

```powershell
.\gradlew.bat testDebugUnitTest --tests '*SessionViewModelTest' compileDebugAndroidTestKotlin
git add app/src/main/java/com/airplay/tv/MainActivity.kt app/src/main/java/com/airplay/tv/session app/src/test/java/com/airplay/tv/session/SessionViewModelTest.kt app/src/androidTest/java/com/airplay/tv/MainActivityLifecycleTest.kt
git commit -m "feat: keep TV awake during playback"
```

---

### Task 10: H5 规范化与 Dexie 原子 upsert

**Working directory:** `D:\repo\github.com\airplayTV\airplayTV-vue`

**Files:**
- Create: `src/helpers/playback-history.js`
- Create: `tests/playback-history.test.mjs`
- Modify: `src/helpers/db.js`

**Interfaces:**
- Produces: `normalizePlaybackHistoryUpdate(data, room)`。
- Produces: `upsertTvPlaybackHistory(record, database=db)`。
- Produces: `TV_HISTORY_UPDATED_EVENT='tv-history-updated'`。

- [ ] **Step 1: 写规范化和事务失败测试**

```js
test('accepts only version 1 for the current room', () => {
  assert.equal(normalizePlaybackHistoryUpdate(validPayload(), 'other'), null)
  assert.equal(normalizePlaybackHistoryUpdate({...validPayload(), version: 2}, 'room-a'), null)
  assert.deepEqual(normalizePlaybackHistoryUpdate(validPayload(), 'room-a'), {
    source: 'ffzy', vid: 'v1', pid: 'p1', name: 'Title', pname: 'Episode 1',
    thumb: 'https://img.test/a.jpg', lastTime: 75, duration: 100,
    updated_at: 1000, tv_updated_at: 1000,
  })
})

test('upserts one record without deleting unrelated history', async () => {
  await upsertTvPlaybackHistory(record, fakeDb)
  assert.deepEqual(fakeDb.transactionCalls, [['rw', 'history', 'timeline']])
  assert.equal(fakeDb.history.rows.length, 2)
  assert.equal(fakeDb.history.rows.find(row => row.vid === 'v1').lastTime, 75)
})
```

另测旧 `tv_updated_at` 忽略、已有 `url/type` 保留、新记录 `url/type` 为空。

- [ ] **Step 2: 运行 RED**

```powershell
node --test tests/playback-history.test.mjs
```

- [ ] **Step 3: 实现 helper 与事务**

```js
export const normalizePlaybackHistoryUpdate = (data, room) => {
  if (data?.version !== 1 || String(data?.room ?? '') !== String(room ?? '')) return null
  const source = String(data?.source ?? '').trim()
  const vid = String(data?.vid ?? '').trim()
  const pid = String(data?.pid ?? '').trim()
  if (!source || !vid || !pid) return null
  const updatedAt = Number(data.updated_at)
  if (!Number.isSafeInteger(updatedAt) || updatedAt < 0) return null
  return {
    source, vid, pid,
    name: String(data.title ?? '').slice(0, 256),
    pname: String(data.episode_name ?? '').slice(0, 256),
    thumb: /^https?:\/\//i.test(String(data.thumb ?? '')) ? String(data.thumb) : '',
    lastTime: Math.max(0, Number(data.position_ms) || 0) / 1000,
    duration: Math.max(0, Number(data.duration_ms) || 0) / 1000,
    updated_at: updatedAt, tv_updated_at: updatedAt,
  }
}
```

`upsertTvPlaybackHistory` 使用 `database.transaction('rw', history, timeline, fn)`，先 find/比较 `tv_updated_at` 再 update/add，禁止 clear/delete。

- [ ] **Step 4: 运行测试并提交**

```powershell
node --test tests/playback-history.test.mjs
git add src/helpers/playback-history.js src/helpers/db.js tests/playback-history.test.mjs
git commit -m "feat: merge TV playback history into H5"
```

---

### Task 11: H5 App 级接收、历史刷新与投射源展示

**Working directory:** `D:\repo\github.com\airplayTV\airplayTV-vue`

**Files:**
- Modify: `src/App.vue`, `src/views/HistoryView.vue`, `src/views/ControlView.vue`
- Modify: `tests/playback-history.test.mjs`, `tests/control-cast-session.test.mjs`

**Interfaces:**
- Consumes Task 10 helper。
- Dispatches `tv-history-updated` after successful transaction。

- [ ] **Step 1: 写 App 接收、刷新和源文案失败测试**

```js
const [appSource, historySource, controlSource] = await Promise.all([
  readFile(new URL('../src/App.vue', import.meta.url), 'utf8'),
  readFile(new URL('../src/views/HistoryView.vue', import.meta.url), 'utf8'),
  readFile(new URL('../src/views/ControlView.vue', import.meta.url), 'utf8'),
])
assert.match(appSource, /case ['"]playback-history-update['"]/)
assert.match(appSource, /normalizePlaybackHistoryUpdate/)
assert.match(appSource, /upsertTvPlaybackHistory/)
assert.match(historySource, /addEventListener\(TV_HISTORY_UPDATED_EVENT, loadHistoryList\)/)
assert.match(historySource, /removeEventListener\(TV_HISTORY_UPDATED_EVENT, loadHistoryList\)/)
assert.match(controlSource, /当前：[\s\S]*castSession\.episodeName[\s\S]*源：[\s\S]*castSession\.source/)
```

- [ ] **Step 2: 运行 RED**

```powershell
node --test tests/playback-history.test.mjs tests/control-cast-session.test.mjs
```

- [ ] **Step 3: 实现 App 级 handler 与对称监听**

```js
case 'playback-history-update': {
  const record = normalizePlaybackHistoryUpdate(data.data, getStorageSync(KEY_ROOM_ID))
  if (!record) break
  void upsertTvPlaybackHistory(record).then(() => {
    window.dispatchEvent(new CustomEvent(TV_HISTORY_UPDATED_EVENT))
  }).catch(() => console.warn('[playback-history] persistence failed'))
  break
}
```

HistoryView mounted/unmounted 对称注册/移除事件。ControlView 改为：

```vue
当前：{{ castSession.episodeName || castSession.pid }}　源：{{ castSession.source }}
```

- [ ] **Step 4: 运行 H5 全量验证并提交**

```powershell
node --test tests/*.test.mjs
npm run build
git add src/App.vue src/views/HistoryView.vue src/views/ControlView.vue tests/playback-history.test.mjs tests/control-cast-session.test.mjs
git commit -m "feat: receive TV playback history in H5"
```

---

### Task 12: 三仓库全量验证与真机验收交接

**Files:** 只在测试暴露需求内缺陷时修改；禁止提交 APK、`dist/`、数据库或日志。

**Interfaces:** Consumes all previous tasks。

- [ ] **Step 1: Android 强制全量验证**

```powershell
Set-Location D:\repo\github.com\airplayTV\airplayTV-android
.\gradlew.bat --rerun-tasks testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleDebug assembleRelease compileDebugAndroidTestKotlin
```

Expected: 全部任务成功；记录本次实际 suite/test 数量。

- [ ] **Step 2: H5 与 Go 全量验证**

```powershell
Set-Location D:\repo\github.com\airplayTV\airplayTV-vue
node --test tests/*.test.mjs
npm run build
Set-Location D:\repo\github.com\airplayTV\api
go test ./... -count=1
go test -race ./... -count=1
```

Expected: Node、Vite、普通 Go 测试必须成功；`-race` 不支持时记录明确工具链错误。

- [ ] **Step 3: 范围、编码和差异检查**

三个仓库分别执行：

```powershell
git diff --check
git status --short
git log --oneline -12
```

检查本需求修改文件前三字节不是 `EF BB BF`；确认 Android 根 `build.gradle.kts` 和 Vue 无关文件未被误提交。

- [ ] **Step 4: Android TV 真机验收**

```text
1. 播放 35 秒后退出并重新投射相同 source/vid/pid，恢复到最近 5 秒附近。
2. 到达 95% 或剩余 30 秒内后重新打开，从 0 开始。
3. 自然结束只切换一次；下一集应用自己的未完成记录。
4. 控制层 10 秒隐藏；选集打开时不隐藏；遥控器上下/确认/左退可用。
5. 右上连接、右侧窄选集、右下日志和扩宽地址在安全区不重叠。
6. 播放/缓冲持续不息屏；暂停后连续观察 10 分钟仍不息屏；扫码页释放。
7. 手机关联后立即收到一条；继续播放约 30 秒更新；其他手机历史保留。
8. Presence 超过 15 秒失效后不再收到；重连不补发积压，只推当前最新。
```

没有目标 TV 时将全部设备项标记 `PENDING_DEVICE_ACCEPTANCE`，不得用 APK 构建代替。

- [ ] **Step 5: 最终提交边界**

不自动 push、merge 或创建 PR。逐仓库报告提交、未提交用户改动、自动化证据和设备验收状态，等待用户明确集成指令。

---

## Requirement Coverage

| Requirement | Tasks |
|---|---|
| 日志右下且在进度下一层 | 8, 12 |
| TV/H5 显示源 | 6, 8, 11 |
| 播放地址扩宽 | 8 |
| TV 断点保存与恢复 | 3, 4, 6 |
| 自动下一集 | 6 |
| 控制层 10 秒 | 7 |
| TV 单列选集与遥控器 | 7, 8 |
| 扫码页等待连接位置 | 8 |
| 播放页防息屏 | 4, 9 |
| TV 最新记录定向同步 H5 | 1, 2, 5, 6, 10, 11 |
