# AirPlay TV Android Cast Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 Android TV 应用重写为默认显示二维码、通过现有 Vue H5 和 WebSocket 接收投屏与控制命令、使用 Media3 全屏播放视频的可扩展多页面应用。

**Architecture:** 使用单 Activity、Navigation Compose 和 `SessionViewModel` 驱动页面状态；`SocketClient`、`VideoResolver`、`PlayerController` 分别隔离通信、地址解析和 Media3。依赖通过轻量 `AppContainer` 手工装配，业务状态通过 `StateFlow` 单向下发，后续页面通过 `AppRoute` 和独立 `feature/*` 扩展。

**Tech Stack:** Kotlin 2.1.0、AGP 8.7.3、Jetpack Compose、Navigation Compose、Media3 1.5.1、OkHttp 4.12.0、Retrofit 2.11.0、Gson、ZXing Core、Kotlin Coroutines、JUnit、MockWebServer。

## Global Constraints

- 只修改 `airplayTV-android`，不得修改 `airplayTV-vue` 或 Go API。
- 保留包名 `com.airplay.tv`、`minSdk 23`、`targetSdk 35`、Gradle Wrapper、图标、签名及版本配置。
- 固定 H5 地址 `https://airplay-tv.pages.dev`、API 地址 `https://airplay-api.artools.cc`、WebSocket 地址 `wss://airplay-api.artools.cc/api/wss`。
- 冷启动生成一次临时 Client ID；进程内重连复用，冷启动后旧房间失效。
- Release 构建不得记录 `mode`、完整播放 URL、响应头或 OkHttp BODY。
- 仅允许 HTTPS API、WSS WebSocket，以及 HTTP/HTTPS 媒体 URL；不得绕过系统 TLS 校验。
- 不申请存储、相机或麦克风权限，不实现历史、搜索、设置、收藏、后台 Service、画中画或播放记录持久化。
- 所有新增和修改文本文件使用 UTF-8 无 BOM。
- 开始实施前记录现有未提交改动；删除或覆盖 Android 旧业务文件属于已确认重写范围，但不得提交 `airplayTV-vue` 或其他仓库文件。

---

## File Structure

### 保留并修改

- `app/build.gradle.kts`：移除 Room、KSP、Coil 等旧业务依赖，增加 Navigation、ZXing 和测试依赖。
- `gradle/libs.versions.toml`：集中声明新增依赖版本与坐标。
- `app/src/main/AndroidManifest.xml`：TV 启动入口、横屏和网络安全配置。
- `app/src/main/res/xml/network_security_config.xml`：禁止全局明文流量，仅信任系统证书。
- `app/src/main/res/values/strings.xml`：二维码、连接、播放错误等文案。
- `app/src/main/java/com/airplay/tv/AirPlayTVApp.kt`：创建应用级 `AppContainer`。
- `app/src/main/java/com/airplay/tv/MainActivity.kt`：沉浸式横屏 Activity 和 Compose 根节点。

### 新建

- `app/src/main/java/com/airplay/tv/app/App.kt`：主题、导航和 ViewModel 宿主。
- `app/src/main/java/com/airplay/tv/app/AppContainer.kt`：应用级依赖装配和释放。
- `app/src/main/java/com/airplay/tv/app/AppNavigation.kt`：Navigation Compose 路由。
- `app/src/main/java/com/airplay/tv/app/AppRoute.kt`：可扩展路由常量。
- `app/src/main/java/com/airplay/tv/core/config/AppConfig.kt`：H5、API、WebSocket 固定地址。
- `app/src/main/java/com/airplay/tv/core/network/NetworkFactory.kt`：安全 OkHttp 与 Retrofit。
- `app/src/main/java/com/airplay/tv/core/ui/AirPlayTheme.kt`：TV 深色主题。
- `app/src/main/java/com/airplay/tv/protocol/ControlCommand.kt`：强类型控制命令。
- `app/src/main/java/com/airplay/tv/protocol/SocketEnvelope.kt`：WebSocket JSON 模型。
- `app/src/main/java/com/airplay/tv/protocol/SocketMessageParser.kt`：输入校验和解析。
- `app/src/main/java/com/airplay/tv/protocol/SocketClient.kt`：连接抽象。
- `app/src/main/java/com/airplay/tv/protocol/OkHttpSocketClient.kt`：保活、重连和入组实现。
- `app/src/main/java/com/airplay/tv/feature/pairing/QrCodeGenerator.kt`：二维码 Bitmap 生成。
- `app/src/main/java/com/airplay/tv/feature/pairing/PairingScreen.kt`：默认等待页。
- `app/src/main/java/com/airplay/tv/feature/player/VideoApi.kt`：播放源和详情接口。
- `app/src/main/java/com/airplay/tv/feature/player/VideoModels.kt`：API 模型。
- `app/src/main/java/com/airplay/tv/feature/player/VideoResolver.kt`：解析播放地址与详情。
- `app/src/main/java/com/airplay/tv/feature/player/PlayerController.kt`：播放器抽象。
- `app/src/main/java/com/airplay/tv/feature/player/Media3PlayerController.kt`：Media3 实现。
- `app/src/main/java/com/airplay/tv/feature/player/PlayerScreen.kt`：全屏视频和信息层。
- `app/src/main/java/com/airplay/tv/session/SessionState.kt`：会话状态模型。
- `app/src/main/java/com/airplay/tv/session/SessionViewModel.kt`：命令编排、竞争控制和导航状态。
- `app/src/main/java/com/airplay/tv/session/SessionViewModelFactory.kt`：显式 ViewModel 构造。

### 删除

- `app/src/main/java/com/airplay/tv/data/`
- `app/src/main/java/com/airplay/tv/ui/`
- `app/src/main/java/com/airplay/tv/util/ApiAuth.kt`
- `app/src/main/java/com/airplay/tv/util/Constants.kt`

### 测试

- `app/src/test/java/com/airplay/tv/protocol/SocketMessageParserTest.kt`
- `app/src/test/java/com/airplay/tv/protocol/OkHttpSocketClientTest.kt`
- `app/src/test/java/com/airplay/tv/feature/player/VideoResolverTest.kt`
- `app/src/test/java/com/airplay/tv/feature/player/FakePlayerController.kt`
- `app/src/test/java/com/airplay/tv/session/SessionViewModelTest.kt`
- `app/src/androidTest/java/com/airplay/tv/app/AppNavigationTest.kt`

---

### Task 1: 建立最小可编译工程骨架

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/xml/network_security_config.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/java/com/airplay/tv/core/config/AppConfig.kt`
- Create: `app/src/main/java/com/airplay/tv/app/AppRoute.kt`
- Delete: `app/src/main/java/com/airplay/tv/data/`
- Delete: `app/src/main/java/com/airplay/tv/ui/`
- Delete: `app/src/main/java/com/airplay/tv/util/ApiAuth.kt`
- Delete: `app/src/main/java/com/airplay/tv/util/Constants.kt`

**Interfaces:**
- Consumes: 当前 Gradle Android 应用骨架和 `com.airplay.tv` 包名。
- Produces: `AppConfig.H5_BASE_URL`、`AppConfig.API_BASE_URL`、`AppConfig.WEBSOCKET_URL`，以及 `AppRoute.Pairing`、`AppRoute.Player`。

- [ ] **Step 1: 先记录重写前工作区边界**

Run:

```powershell
git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-android status --short
git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-android diff -- app/src/main
```

Expected: 输出当前用户改动；后续提交不得包含 `docs/` 之外的既有无关文件，也不得操作相邻 Vue 仓库。

- [ ] **Step 2: 更新版本目录和依赖**

在 `libs.versions.toml` 增加：

```toml
navigation = "2.8.5"
zxing = "3.5.3"
junit = "4.13.2"

androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigation" }
zxing-core = { group = "com.google.zxing", name = "core", version.ref = "zxing" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
```

在 `app/build.gradle.kts` 保留 Compose、Lifecycle、TV Material、Media3、Retrofit、OkHttp 和 Coroutines，移除 Room、KSP、DataStore、Coil；增加：

```kotlin
implementation(libs.androidx.navigation.compose)
implementation(libs.zxing.core)
testImplementation(libs.junit)
testImplementation(libs.okhttp.mockwebserver)
testImplementation(libs.coroutines.test)
androidTestImplementation(platform(libs.compose.bom))
androidTestImplementation("androidx.compose.ui:ui-test-junit4")
debugImplementation("androidx.compose.ui:ui-test-manifest")
```

- [ ] **Step 3: 写入配置和路由常量**

```kotlin
package com.airplay.tv.core.config

object AppConfig {
    const val H5_BASE_URL = "https://airplay-tv.pages.dev"
    const val API_BASE_URL = "https://airplay-api.artools.cc/"
    const val WEBSOCKET_URL = "wss://airplay-api.artools.cc/api/wss"
    const val SEEK_INCREMENT_MS = 15_000L
    const val INFO_OVERLAY_TIMEOUT_MS = 5_000L
}
```

```kotlin
package com.airplay.tv.app

sealed class AppRoute(val route: String) {
    data object Pairing : AppRoute("pairing")
    data object Player : AppRoute("player")
}
```

- [ ] **Step 4: 收紧 Manifest 和网络安全**

Manifest 保留 `INTERNET`、Leanback launcher 和非触屏声明，为 `MainActivity` 增加：

```xml
android:screenOrientation="landscape"
android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenSize"
```

网络安全配置允许 Media3 播放第三方 HTTP 媒体，但 API 和 WebSocket 客户端仍只接受固定的 HTTPS/WSS 地址。Android 网络安全配置无法按运行时媒体域名动态放行，因此使用：

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

- [ ] **Step 5: 删除旧业务目录并验证主源码中不再引用 Room、Coil、DataStore**

Run:

```powershell
rg -n "Room|coil|DataStore|HistoryScreen|HomeScreen|SettingsScreen" app/src/main app/build.gradle.kts
```

Expected: 无匹配；旧业务文件已删除，Gradle 基础配置保留。

- [ ] **Step 6: 提交工程骨架**

```powershell
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main
git commit -m "refactor: reduce Android TV app to casting shell"
```

---

### Task 2: 实现强类型 WebSocket 协议解析

**Files:**
- Create: `app/src/main/java/com/airplay/tv/protocol/ControlCommand.kt`
- Create: `app/src/main/java/com/airplay/tv/protocol/SocketEnvelope.kt`
- Create: `app/src/main/java/com/airplay/tv/protocol/SocketMessageParser.kt`
- Test: `app/src/test/java/com/airplay/tv/protocol/SocketMessageParserTest.kt`

**Interfaces:**
- Consumes: WebSocket 服务发送的根级 JSON 字段 `event`、`group`、`vid`、`pid`、`source`、`mode`、`value`。
- Produces: `sealed interface ControlCommand` 和 `SocketMessageParser.parse(text: String, roomId: String): ControlCommand?`。

- [ ] **Step 1: 写失败测试覆盖加载、控制、房间过滤和非法字段**

```kotlin
class SocketMessageParserTest {
    private val parser = SocketMessageParser()

    @Test fun parsesLoadVideo() {
        val json = """{"event":"/ctl_load_Video","group":"room-1","vid":"v1","pid":"p2","source":"s","mode":"m"}"""
        assertEquals(ControlCommand.LoadVideo("v1", "p2", "s", "m"), parser.parse(json, "room-1"))
    }

    @Test fun rejectsAnotherRoomAndOversizedIds() {
        assertNull(parser.parse("""{"event":"/ctl_play","group":"other"}""", "room-1"))
        val oversized = "x".repeat(513)
        assertNull(parser.parse("""{"event":"/ctl_load_Video","group":"room-1","vid":"$oversized","pid":"p","source":"s"}""", "room-1"))
    }

    @Test fun mapsKnownControlsAndIgnoresUnknownEvent() {
        assertEquals(ControlCommand.Play, parser.parse("""{"event":"/ctl_play","group":"room-1"}""", "room-1"))
        assertEquals(ControlCommand.Volume(1), parser.parse("""{"event":"/ctl_volume","group":"room-1","value":1}""", "room-1"))
        assertNull(parser.parse("""{"event":"/ctl_delete","group":"room-1"}""", "room-1"))
    }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.airplay.tv.protocol.SocketMessageParserTest"`

Expected: FAIL，`SocketMessageParser` 和 `ControlCommand` 尚不存在。

- [ ] **Step 3: 实现命令模型和严格解析**

`ControlCommand` 必须完整定义：

```kotlin
sealed interface ControlCommand {
    data class LoadVideo(val vid: String, val pid: String, val source: String, val mode: String) : ControlCommand
    data class Volume(val direction: Int) : ControlCommand
    data object Play : ControlCommand
    data object Pause : ControlCommand
    data object Forward : ControlCommand
    data object Back : ControlCommand
    data object Mute : ControlCommand
    data object Fullscreen : ControlCommand
    data object FullscreenExit : ControlCommand
    data object ToggleInfo : ControlCommand
    data object ShowQrCode : ControlCommand
    data object Previous : ControlCommand
    data object Next : ControlCommand
    data object HistoryIgnored : ControlCommand
}
```

解析器使用 Gson `JsonParser.parseString`，捕获 `JsonParseException` 和类型异常；要求 `group == roomId`，ID、source、mode 最大 512 字符，`value` 只允许 `-1` 或 `1`。`mode` 缺失时使用空字符串，其他加载字段缺失或为空时返回 `null`。

- [ ] **Step 4: 运行协议测试**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.airplay.tv.protocol.SocketMessageParserTest"`

Expected: PASS。

- [ ] **Step 5: 提交协议层**

```powershell
git add app/src/main/java/com/airplay/tv/protocol app/src/test/java/com/airplay/tv/protocol/SocketMessageParserTest.kt
git commit -m "feat: parse TV casting control protocol"
```

---

### Task 3: 实现 WebSocket 连接、入组和重连

**Files:**
- Create: `app/src/main/java/com/airplay/tv/protocol/SocketClient.kt`
- Create: `app/src/main/java/com/airplay/tv/protocol/OkHttpSocketClient.kt`
- Test: `app/src/test/java/com/airplay/tv/protocol/OkHttpSocketClientTest.kt`

**Interfaces:**
- Consumes: `SocketMessageParser.parse`、进程级 `roomId`、`AppConfig.WEBSOCKET_URL`。
- Produces: `SocketClient.states: StateFlow<SocketConnectionState>`、`SocketClient.commands: Flow<ControlCommand>`、`connect(roomId)`、`close()`。

- [ ] **Step 1: 写失败测试验证连接后入组和消息转发**

```kotlin
@Test fun joinsRoomAfterOpenAndEmitsCommand() = runTest {
    server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            assertTrue(text.contains("join-group"))
            assertTrue(text.contains("room-1"))
            webSocket.send("""{"event":"/ctl_play","group":"room-1"}""")
        }
    }))
    client.connect("room-1")
    assertEquals(ControlCommand.Play, client.commands.first())
}
```

另写测试验证失败后调度 `1、2、4、8、16、30` 秒封顶重连；测试通过注入 `ReconnectPolicy(delaysMs, jitter)` 和测试调度器，不使用真实等待。

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.airplay.tv.protocol.OkHttpSocketClientTest"`

Expected: FAIL，连接抽象尚不存在。

- [ ] **Step 3: 定义连接接口和状态**

```kotlin
enum class SocketConnectionState { Connecting, Connected, Reconnecting, Closed }

interface SocketClient : Closeable {
    val states: StateFlow<SocketConnectionState>
    val commands: Flow<ControlCommand>
    fun connect(roomId: String)
    override fun close()
}

data class ReconnectPolicy(
    val delaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 16_000, 30_000),
    val jitterRatio: Double = 0.2,
)
```

- [ ] **Step 4: 实现 OkHttp WebSocket**

要求：

- `onOpen` 发送 `{"event":"join-group","data":{"group":"..."}}`。
- `onMessage` 只把解析成功的命令写入带缓冲 `MutableSharedFlow`。
- `onFailure` 和非主动 `onClosed` 进入可取消重连循环。
- 新连接成功后重置退避索引。
- `close()` 设置主动关闭标志、取消重连 Job，并使用 1000 正常关闭码。
- 日志只记录状态和异常类型，不记录原始消息内容。

- [ ] **Step 5: 运行连接测试**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.airplay.tv.protocol.OkHttpSocketClientTest"`

Expected: PASS，MockWebServer 至少观察到一次入组消息。

- [ ] **Step 6: 提交通信层**

```powershell
git add app/src/main/java/com/airplay/tv/protocol app/src/test/java/com/airplay/tv/protocol/OkHttpSocketClientTest.kt
git commit -m "feat: add resilient TV websocket session"
```

---

### Task 4: 实现安全的视频地址解析

**Files:**
- Create: `app/src/main/java/com/airplay/tv/core/network/NetworkFactory.kt`
- Create: `app/src/main/java/com/airplay/tv/feature/player/VideoApi.kt`
- Create: `app/src/main/java/com/airplay/tv/feature/player/VideoModels.kt`
- Create: `app/src/main/java/com/airplay/tv/feature/player/VideoResolver.kt`
- Test: `app/src/test/java/com/airplay/tv/feature/player/VideoResolverTest.kt`

**Interfaces:**
- Consumes: `ControlCommand.LoadVideo`。
- Produces: `VideoResolver.resolve(command): ResolvedVideo`、`VideoResolver.loadEpisodes(command): List<Episode>`。

- [ ] **Step 1: 写失败测试验证请求头、参数、URL 校验和脱敏异常**

```kotlin
@Test fun resolvesSourceWithModeHeader() = runTest {
    server.enqueue(MockResponse().setBody("""{"code":200,"data":{"url":"https://cdn.example/v.m3u8","type":"hls"}}"""))
    val result = resolver.resolve(ControlCommand.LoadVideo("v1", "p2", "s1", "secret-value"))
    val request = server.takeRequest()
    assertEquals("secret-value", request.getHeader("X-Source-Mode"))
    assertEquals("airplayTV-android", request.getHeader("X-Client"))
    assertEquals("https://cdn.example/v.m3u8", result.url)
}

@Test fun rejectsUnsafeMediaScheme() = runTest {
    server.enqueue(MockResponse().setBody("""{"code":200,"data":{"url":"file:///sdcard/a.mp4"}}"""))
    assertFailsWith<ResolveVideoException> { resolver.resolve(loadCommand) }
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.airplay.tv.feature.player.VideoResolverTest"`

Expected: FAIL，解析器和 API 模型尚不存在。

- [ ] **Step 3: 定义 API 和结果模型**

```kotlin
interface VideoApi {
    @GET("api/video/source")
    suspend fun source(
        @Query("vid") vid: String,
        @Query("pid") pid: String,
        @Query("_source") source: String,
        @Query("_m3u8p") proxy: Boolean = false,
        @Header("X-Source-Mode") mode: String,
        @Header("X-Client") client: String = "airplayTV-android",
    ): ApiResponse<VideoSourceDto>

    @GET("api/video/detail")
    suspend fun detail(@Query("id") vid: String, @Query("_source") source: String): ApiResponse<VideoDetailDto>
}

data class ResolvedVideo(val vid: String, val pid: String, val source: String, val url: String, val title: String = "", val episodeName: String = "")
data class Episode(val id: String, val name: String)
```

- [ ] **Step 4: 实现解析规则**

`resolve` 检查 HTTP 业务码 200、非空 URL 和 URI scheme；异常只包含固定错误码与服务端非敏感 `msg`，不得包含请求头、`mode` 或完整媒体 URL。`loadEpisodes` 的详情失败返回空列表，不影响 `resolve` 成功结果。

- [ ] **Step 5: 运行解析测试**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.airplay.tv.feature.player.VideoResolverTest"`

Expected: PASS。

- [ ] **Step 6: 提交网络解析层**

```powershell
git add app/src/main/java/com/airplay/tv/core/network app/src/main/java/com/airplay/tv/feature/player app/src/test/java/com/airplay/tv/feature/player/VideoResolverTest.kt
git commit -m "feat: resolve cast media securely"
```

---

### Task 5: 封装 Media3 播放控制

**Files:**
- Create: `app/src/main/java/com/airplay/tv/feature/player/PlayerController.kt`
- Create: `app/src/main/java/com/airplay/tv/feature/player/Media3PlayerController.kt`
- Test: `app/src/test/java/com/airplay/tv/feature/player/FakePlayerController.kt`

**Interfaces:**
- Consumes: `ResolvedVideo.url` 和控制命令。
- Produces: `PlayerController.state: StateFlow<PlayerState>`、`load`、`play`、`pause`、`seekBy`、`adjustVolume`、`toggleMute`、`clear`、`release`。

- [ ] **Step 1: 先定义可测试接口和 Fake**

```kotlin
data class PlayerState(
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

interface PlayerController {
    val state: StateFlow<PlayerState>
    val player: Player
    fun load(url: String)
    fun play()
    fun pause()
    fun seekBy(deltaMs: Long)
    fun adjustVolume(direction: Int)
    fun toggleMute()
    fun clear()
    fun release()
}
```

`FakePlayerController` 记录方法调用和 URL，使 `SessionViewModelTest` 不依赖 Android Media3 实例。

- [ ] **Step 2: 实现 Media3 适配器**

实现要求：

- `load` 使用 `MediaItem.fromUri(url)`、`prepare()`、`play()`。
- `seekBy` 使用 `(currentPosition + delta).coerceIn(0, duration)`；未知时长只限制最小值 0。
- 音量使用 `AudioManager.adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE/LOWER, 0)`。
- 静音记录最近一个大于 0 的系统媒体音量，再设置 0；再次触发恢复并限制在当前最大音量内。
- Player Listener 更新播放状态和非敏感错误文案；首次错误自动 `prepare()` 重试一次，同一媒体第二次错误进入终态。
- `clear` 执行 `stop()` 和 `clearMediaItems()`；`release` 只能执行一次。

- [ ] **Step 3: 编译播放器层**

Run: `.\gradlew.bat compileDebugKotlin`

Expected: BUILD SUCCESSFUL，接口与 Media3 版本 API 匹配。

- [ ] **Step 4: 提交播放器层**

```powershell
git add app/src/main/java/com/airplay/tv/feature/player/PlayerController.kt app/src/main/java/com/airplay/tv/feature/player/Media3PlayerController.kt app/src/test/java/com/airplay/tv/feature/player/FakePlayerController.kt
git commit -m "feat: encapsulate Media3 cast playback"
```

---

### Task 6: 实现会话状态机和竞争控制

**Files:**
- Create: `app/src/main/java/com/airplay/tv/session/SessionState.kt`
- Create: `app/src/main/java/com/airplay/tv/session/SessionViewModel.kt`
- Create: `app/src/main/java/com/airplay/tv/session/SessionViewModelFactory.kt`
- Test: `app/src/test/java/com/airplay/tv/session/SessionViewModelTest.kt`

**Interfaces:**
- Consumes: `SocketClient.commands`、`SocketClient.states`、`VideoResolver`、`PlayerController`。
- Produces: `SessionViewModel.uiState: StateFlow<SessionUiState>`、只读 `player: Player`、`onBack()`、`onCleared()`。

- [ ] **Step 1: 写失败测试覆盖默认页、最后投屏生效和控制映射**

```kotlin
@Test fun startsOnPairingAndLatestLoadWins() = runTest {
    assertEquals(SessionPage.Pairing, viewModel.uiState.value.page)
    commands.emit(ControlCommand.LoadVideo("slow", "p1", "s", "m"))
    commands.emit(ControlCommand.LoadVideo("latest", "p2", "s", "m"))
    advanceUntilIdle()
    assertEquals("https://cdn/latest.m3u8", fakePlayer.loadedUrl)
    assertEquals(SessionPage.Player, viewModel.uiState.value.page)
}

@Test fun mapsPlaybackAndQrCommands() = runTest {
    commands.emit(ControlCommand.Play)
    commands.emit(ControlCommand.Forward)
    commands.emit(ControlCommand.ShowQrCode)
    advanceUntilIdle()
    assertEquals(listOf("play", "seek:15000", "clear"), fakePlayer.calls)
    assertEquals(SessionPage.Pairing, viewModel.uiState.value.page)
}
```

另写测试验证暂停、后退、音量、静音、全屏信息层、上一集/下一集边界、`HistoryIgnored` 无副作用，以及 `onBack()` 先隐藏信息层再返回二维码。

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.airplay.tv.session.SessionViewModelTest"`

Expected: FAIL，会话模型尚不存在。

- [ ] **Step 3: 定义状态模型**

```kotlin
enum class SessionPage { Pairing, Player }

data class SessionUiState(
    val roomId: String,
    val page: SessionPage = SessionPage.Pairing,
    val connection: SocketConnectionState = SocketConnectionState.Connecting,
    val loading: Boolean = false,
    val title: String = "",
    val episodeName: String = "",
    val infoVisible: Boolean = false,
    val error: String? = null,
)
```

- [ ] **Step 4: 实现命令编排**

要求：

- ViewModel 构造时开始收集连接状态和命令。
- `LoadVideo` 保存递增 generation，取消旧 `resolveJob`；仅 generation 仍为最新时才能调用 `player.load` 和切页。
- 地址成功后异步加载详情和剧集；详情失败不覆盖播放成功状态。
- 上一集/下一集从当前 `episodes` 和 `pid` 计算，边界无操作。
- 播放控制后显示信息层，并用可取消 Job 在 5 秒后隐藏。
- `Fullscreen` 立即隐藏信息层；`FullscreenExit` 显示并启动 5 秒计时；`ToggleInfo` 切换。
- `ShowQrCode` 和第二级返回都调用 `player.clear()` 并切换二维码页。
- `HistoryIgnored` 不修改任何状态。
- 通过 `val player: Player get() = playerController.player` 只读暴露 Media3 `Player` 给 Compose `PlayerView`，UI 不得获得 `PlayerController` 控制接口。

- [ ] **Step 5: 运行状态机测试**

Run: `.\gradlew.bat testDebugUnitTest --tests "com.airplay.tv.session.SessionViewModelTest"`

Expected: PASS，虚拟时间下不发生真实 5 秒等待。

- [ ] **Step 6: 提交会话状态机**

```powershell
git add app/src/main/java/com/airplay/tv/session app/src/test/java/com/airplay/tv/session/SessionViewModelTest.kt
git commit -m "feat: orchestrate casting session state"
```

---

### Task 7: 实现二维码、播放器 UI 和可扩展导航

**Files:**
- Create: `app/src/main/java/com/airplay/tv/feature/pairing/QrCodeGenerator.kt`
- Create: `app/src/main/java/com/airplay/tv/feature/pairing/PairingScreen.kt`
- Create: `app/src/main/java/com/airplay/tv/feature/player/PlayerScreen.kt`
- Create: `app/src/main/java/com/airplay/tv/core/ui/AirPlayTheme.kt`
- Create: `app/src/main/java/com/airplay/tv/app/AppNavigation.kt`
- Create: `app/src/main/java/com/airplay/tv/app/App.kt`
- Test: `app/src/androidTest/java/com/airplay/tv/app/AppNavigationTest.kt`

**Interfaces:**
- Consumes: `SessionUiState`、`PlayerController.player`、`SessionViewModel.onBack()`。
- Produces: 默认二维码页、播放器页、信息层和 `NavHost` 扩展入口。

- [ ] **Step 1: 写 UI 失败测试**

```kotlin
@Test fun pairingIsDefaultAndPlayerAppearsAfterStateChange() {
    composeRule.setContent { TestApp(fakeViewModel) }
    composeRule.onNodeWithTag("pairing-screen").assertIsDisplayed()
    fakeViewModel.setState(fakeViewModel.uiState.value.copy(page = SessionPage.Player))
    composeRule.onNodeWithTag("player-screen").assertIsDisplayed()
}
```

增加测试：连接状态文案存在、错误层可见、信息层在 `infoVisible=false` 时不存在、TV 返回回调被调用。

- [ ] **Step 2: 运行 Android 测试并确认失败**

Run: `.\gradlew.bat connectedDebugAndroidTest`

Expected: FAIL，页面和导航尚不存在；若无已连接设备，先执行 `compileDebugAndroidTestKotlin` 验证测试源码失败点。

- [ ] **Step 3: 实现二维码生成器**

```kotlin
class QrCodeGenerator {
    fun generate(content: String, size: Int): Bitmap {
        require(content.startsWith("https://airplay-tv.pages.dev/join?"))
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until size) for (x in 0 until size) {
                setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
    }
}
```

- [ ] **Step 4: 实现二维码等待页**

使用 16:9 深色布局、左侧高对比度二维码、右侧标题与三步说明、右下连接状态；提供 `Modifier.testTag("pairing-screen")`。二维码 URL 使用 `Uri.Builder` 构造 `room_id` 和时间戳，禁止字符串直接拼接未编码参数。

- [ ] **Step 5: 实现播放器与信息层**

使用 `AndroidView(PlayerView)`，设置 `useController=false` 和铺满屏幕；底部渐变信息层显示标题、剧集、播放状态、进度和时长，右上显示连接状态。根节点提供 `Modifier.testTag("player-screen")`，错误层只显示固定友好文案，不显示播放 URL。

- [ ] **Step 6: 实现 Navigation Compose**

```kotlin
@Composable
fun AppNavigation(state: SessionUiState, player: Player, onBack: () -> Unit) {
    val navController = rememberNavController()
    LaunchedEffect(state.page) {
        val route = if (state.page == SessionPage.Pairing) AppRoute.Pairing.route else AppRoute.Player.route
        navController.navigate(route) {
            popUpTo(navController.graph.startDestinationId) {
                inclusive = route == AppRoute.Pairing.route
            }
            launchSingleTop = true
        }
    }
    NavHost(navController, startDestination = AppRoute.Pairing.route) {
        composable(AppRoute.Pairing.route) { PairingScreen(state) }
        composable(AppRoute.Player.route) { PlayerScreen(state, player, onBack) }
    }
}
```

未来页面只需新增 `AppRoute` 和 `composable`，不得把页面判断重新塞入 `MainActivity`。

- [ ] **Step 7: 编译 UI 和运行可用测试**

Run:

```powershell
.\gradlew.bat compileDebugKotlin
.\gradlew.bat compileDebugAndroidTestKotlin
```

Expected: BUILD SUCCESSFUL；有 TV 模拟器时继续执行 `connectedDebugAndroidTest` 并通过。

- [ ] **Step 8: 提交 UI 和导航**

```powershell
git add app/src/main/java/com/airplay/tv/app app/src/main/java/com/airplay/tv/core/ui app/src/main/java/com/airplay/tv/feature/pairing app/src/main/java/com/airplay/tv/feature/player/PlayerScreen.kt app/src/androidTest
git commit -m "feat: add TV pairing and playback screens"
```

---

### Task 8: 装配应用生命周期并完成全量验收

**Files:**
- Create: `app/src/main/java/com/airplay/tv/app/AppContainer.kt`
- Modify: `app/src/main/java/com/airplay/tv/AirPlayTVApp.kt`
- Modify: `app/src/main/java/com/airplay/tv/MainActivity.kt`
- Create: `app/src/main/java/com/airplay/tv/session/SessionViewModelFactory.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `README.md`

**Interfaces:**
- Consumes: 所有前序接口。
- Produces: 可安装 APK、进程级临时 roomId、正确释放的应用会话和真实设备验收说明。

- [ ] **Step 1: 创建进程级依赖容器**

```kotlin
class AppContainer(private val context: Context) {
    private val okHttp = NetworkFactory.okHttpClient(BuildConfig.DEBUG)
    private val api = NetworkFactory.videoApi(okHttp)
    val videoResolver = VideoResolver(api)

    fun createSocketClient(scope: CoroutineScope): SocketClient =
        OkHttpSocketClient(okHttp, SocketMessageParser(), scope)

    fun createPlayerController(): PlayerController = Media3PlayerController(context)
}
```

`AirPlayTVApp` 在 `onCreate` 创建只持有网络基础设施和工厂方法的容器。`SessionViewModelFactory` 在首次创建 ViewModel 时生成 roomId、SocketClient 和 PlayerController；ViewModel 配置变更时保留，`onCleared()` 时关闭 SocketClient、释放播放器并取消会话协程，不依赖 `Application.onTerminate()`。

- [ ] **Step 2: 装配 ViewModel Factory 和 Activity**

`SessionViewModelFactory` 接收 `AppContainer`，创建进程本次 Activity 会话专用的 `roomId`、`SocketClient` 和 `PlayerController`。`MainActivity` 只负责：

```kotlin
enableEdgeToEdge()
WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
setContent { AirPlayApp(viewModel, viewModel.player) }
```

ViewModel 初始化后调用 `socketClient.connect(roomId)`；Activity 不直接持有或关闭 WebSocket/Media3，避免配置变更造成重复释放。

- [ ] **Step 3: 更新 README 的真实设备流程**

README 必须写明：

1. TV 安装并启动 APK。
2. 手机扫描 TV 二维码。
3. 手机 H5 选择视频后 TV 自动播放。
4. 验证播放、暂停、前进、后退、音量、静音、全屏、信息层、上一集、下一集和返回二维码。
5. 断开网络并恢复，确认 30 秒封顶重连和重新入组。

- [ ] **Step 4: 执行完整自动验证**

Run:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

Expected: 三条命令均 BUILD SUCCESSFUL，无测试失败和新增 lint error。

- [ ] **Step 5: 验证 APK 元数据和哈希**

Run:

```powershell
Get-FileHash app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
apkanalyzer manifest application-id app\build\outputs\apk\debug\app-debug.apk
apkanalyzer manifest version-name app\build\outputs\apk\debug\app-debug.apk
apkanalyzer files list app\build\outputs\apk\debug\app-debug.apk | Select-String '^/lib/'
```

Expected: 包名 `com.airplay.tv`、版本 `1.0.0`；记录 SHA-256；若 APK 无原生 `.so`，ABI 列表为空是预期结果。

- [ ] **Step 6: 执行安全扫描**

Run:

```powershell
rg -n "hostnameVerifier|TrustManager|X-Source-Mode|BODY|file://" app/src/main app/build.gradle.kts
```

Expected: 不存在自定义 `hostnameVerifier` 或 `TrustManager`；`X-Source-Mode` 只出现在请求构造代码；Release 路径不启用 BODY 日志；不存在 `file://` 放行。网络安全配置允许第三方 HTTP 媒体是已确认兼容性要求，API 和 WebSocket 地址仍由 `AppConfig` 固定为 HTTPS/WSS。

- [ ] **Step 7: 在真实 TV 验收并记录结果**

验收矩阵：冷启动二维码、扫码入组、HLS/MP4 播放、快速连续投屏、全部控制事件、播放器错误后新投屏恢复、Wi-Fi 断开恢复、TV 返回键两级行为、720p/1080p 安全区。每项记录设备型号、Android 版本、结果和失败日志摘要，日志不得包含 `mode` 或完整播放 URL。

- [ ] **Step 8: 提交最终装配**

```powershell
git add app/src/main README.md
git commit -m "feat: complete Android TV casting app"
```

---

## Final Review Checklist

- [ ] `airplayTV-vue` 工作区哈希和 Git 状态与实施前一致。
- [ ] Android 启动默认显示二维码，冷启动 Client ID 会变化。
- [ ] WebSocket 重连复用本次启动 Client ID 并重新入组。
- [ ] 快速连续投屏不会出现旧响应覆盖新视频。
- [ ] 所有已确认控制事件行为与设计规范一致。
- [ ] `/ctl_history` 被安全忽略，不存在历史页面和 Room 数据库。
- [ ] 信息层 5 秒自动隐藏，全屏命令立即隐藏。
- [ ] Release 日志不含凭证、完整播放 URL 或响应头。
- [ ] 单元测试、lint、debug APK 构建与真实 TV 验收全部完成。
