# TV WebSocket Playback Fix and Diagnostics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 兼容 H5 的 `mode:null` 投屏消息，使 TV 正确进入播放器，并在所有页面右下角实时显示有界、脱敏的 WebSocket 与播放链路日志。

**Architecture:** `SocketMessageParser` 输出结构化解析结果，`SocketClient` 通过独立 `diagnostics` 流发布连接、收包和解析事件，控制命令流保持原职责。`SessionViewModel` 将 socket、解析、视频解析和播放器结果归并为最多 20 条 `DiagnosticLogEntry`，全局 Compose `DiagnosticLogOverlay` 在二维码页和播放器页之上常驻渲染。

**Tech Stack:** Kotlin 2.1、Coroutines Flow、OkHttp WebSocket、Gson、Jetpack ViewModel、Navigation Compose、Material 3、Media3、JUnit 4、kotlinx-coroutines-test、MockWebServer、Compose UI Test。

## Global Constraints

- 仅修改 `airplayTV-android`；不得修改 `airplayTV-vue` 或 Go API。
- 保持线上地址：H5 `https://airplay-tv.pages.dev`、API `https://airplay-api.artools.cc`、WebSocket `wss://airplay-api.artools.cc/api/wss`。
- `mode` 缺失或 JSON `null` 均规范化为空字符串；其他非字符串类型拒绝。
- 日志最多保留 20 条，不持久化、不上传、不显示原始 JSON、完整媒体 URL、Header 或非空 `mode`。
- 日志面板在二维码页与播放器页常驻、不可聚焦、不参与 D-pad 操作。
- 所有新增/修改文本文件使用 UTF-8 无 BOM。
- 每个生产改动必须先有能因缺失行为而失败的测试。

---

## File Structure

- Create `app/src/main/java/com/airplay/tv/diagnostics/DiagnosticEvent.kt`：跨 socket/session/player 的内部结构化诊断事件。
- Create `app/src/main/java/com/airplay/tv/diagnostics/DiagnosticLogEntry.kt`：安全 UI 文本、20 条窗口和时间格式化。
- Create `app/src/main/java/com/airplay/tv/diagnostics/DiagnosticLogOverlay.kt`：全局不可聚焦日志面板。
- Modify `app/src/main/java/com/airplay/tv/protocol/SocketMessageParser.kt`：详细解析结果及 `mode:null` 兼容。
- Modify `app/src/main/java/com/airplay/tv/protocol/SocketClient.kt`：公开只读 `diagnostics: Flow<DiagnosticEvent>`。
- Modify `app/src/main/java/com/airplay/tv/protocol/OkHttpSocketClient.kt`：连接、入组、收包、接受/丢弃诊断事件。
- Modify `app/src/main/java/com/airplay/tv/session/SessionState.kt`：UI 状态携带 `diagnosticLogs`。
- Modify `app/src/main/java/com/airplay/tv/session/SessionViewModel.kt`：归并诊断与视频/播放阶段日志。
- Modify `app/src/main/java/com/airplay/tv/app/AppNavigation.kt`：在 `NavHost` 上层渲染日志面板。
- Modify test fakes implementing `SocketClient`：增加空诊断流，保持测试编译。
- Tests mirror each production unit under `app/src/test` and `app/src/androidTest`.

---

### Task 1: Compatible and Explainable Socket Parsing

**Files:**
- Create: `app/src/main/java/com/airplay/tv/protocol/SocketParseResult.kt`
- Modify: `app/src/main/java/com/airplay/tv/protocol/SocketMessageParser.kt`
- Modify: `app/src/test/java/com/airplay/tv/protocol/SocketMessageParserTest.kt`

**Interfaces:**
- Consumes: root-level WebSocket JSON and current `roomId: String`.
- Produces: `fun parseDetailed(text: String, roomId: String): SocketParseResult` and backward-compatible `fun parse(text: String, roomId: String): ControlCommand?`.

- [ ] **Step 1: Change the existing null-mode expectation and add detailed-result tests**

```kotlin
@Test
fun nullModeIsNormalizedToEmptyString() {
    val result = parser.parse(
        """{"event":"/ctl_load_Video","group":"room-1","vid":"v1","pid":"p2","source":"s","mode":null}""",
        "room-1",
    )

    assertEquals(ControlCommand.LoadVideo("v1", "p2", "s", ""), result)
}

@Test
fun detailedResultExplainsRejectedAndIgnoredMessages() {
    assertEquals(
        SocketParseResult.Rejected(SocketParseReason.InvalidJson),
        parser.parseDetailed("{", "room-1"),
    )
    assertEquals(
        SocketParseResult.Ignored(SocketParseReason.RoomMismatch),
        parser.parseDetailed("""{"event":"/ctl_play","group":"other"}""", "room-1"),
    )
    assertEquals(
        SocketParseResult.Rejected(SocketParseReason.InvalidFieldType("mode")),
        parser.parseDetailed(
            """{"event":"/ctl_load_Video","group":"room-1","vid":"v","pid":"p","source":"s","mode":1}""",
            "room-1",
        ),
    )
}
```

- [ ] **Step 2: Run the parser tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.protocol.SocketMessageParserTest" --no-daemon --no-parallel --rerun-tasks
```

Expected: FAIL because `mode:null` currently returns `null` and `SocketParseResult`/`parseDetailed` do not exist.

- [ ] **Step 3: Add the explicit result model**

```kotlin
sealed interface SocketParseResult {
    data class Accepted(val command: ControlCommand) : SocketParseResult
    data class Ignored(val reason: SocketParseReason) : SocketParseResult
    data class Rejected(val reason: SocketParseReason) : SocketParseResult
}

sealed interface SocketParseReason {
    data object InvalidJson : SocketParseReason
    data object RoomMismatch : SocketParseReason
    data object UnknownEvent : SocketParseReason
    data class MissingField(val field: String) : SocketParseReason
    data class InvalidFieldType(val field: String) : SocketParseReason
    data class InvalidFieldValue(val field: String) : SocketParseReason
}
```

- [ ] **Step 4: Implement detailed parsing and null-mode normalization**

`parseDetailed()` must catch JSON failures as `Rejected(InvalidJson)`, validate room before command fields, distinguish unknown events, and return stable field reasons. Implement `mode` as:

```kotlin
private fun JsonObject.optionalMode(): String? {
    val value = get("mode") ?: return null
    if (value.isJsonNull) return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
        throw FieldParseException(SocketParseReason.InvalidFieldType("mode"))
    }
    return value.asString
}
```

Keep compatibility:

```kotlin
fun parse(text: String, roomId: String): ControlCommand? =
    (parseDetailed(text, roomId) as? SocketParseResult.Accepted)?.command
```

- [ ] **Step 5: Run focused and protocol tests and verify GREEN**

Run the Step 2 command, then:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.protocol.*" --no-daemon --no-parallel --rerun-tasks
```

Expected: all parser and socket protocol tests PASS.

- [ ] **Step 6: Commit Task 1**

```powershell
git add app/src/main/java/com/airplay/tv/protocol/SocketParseResult.kt app/src/main/java/com/airplay/tv/protocol/SocketMessageParser.kt app/src/test/java/com/airplay/tv/protocol/SocketMessageParserTest.kt
git commit -m "fix: accept empty H5 source mode"
```

---

### Task 2: Bounded and Redacted Socket Diagnostics

**Files:**
- Create: `app/src/main/java/com/airplay/tv/diagnostics/DiagnosticEvent.kt`
- Modify: `app/src/main/java/com/airplay/tv/protocol/SocketClient.kt`
- Modify: `app/src/main/java/com/airplay/tv/protocol/OkHttpSocketClient.kt`
- Modify: `app/src/test/java/com/airplay/tv/protocol/OkHttpSocketClientTest.kt`
- Modify: all test fakes implementing `SocketClient` under `app/src/test` and `app/src/androidTest`.

**Interfaces:**
- Consumes: `SocketParseResult`, connection callbacks and redacted command metadata.
- Produces: `SocketClient.diagnostics: Flow<DiagnosticEvent>` with bounded `DROP_OLDEST` delivery.

- [ ] **Step 1: Add failing socket diagnostic ordering and secrecy tests**

```kotlin
@Test
fun inboundLoadEmitsReceivedThenAcceptedWithoutExposingMode() = runTest {
    val connector = RecordingWebSocketConnector()
    val client = newClient(connector = connector)
    val messageEvents = mutableListOf<DiagnosticEvent>()
    val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        client.diagnostics
            .filter {
                it is DiagnosticEvent.MessageReceived ||
                    it is DiagnosticEvent.CommandAccepted
            }
            .take(2)
            .toList(messageEvents)
    }

    client.connect("room-1")
    connector.connections.single().listener.onOpen(connector.connections.single().webSocket, response())
    connector.connections.single().listener.onMessage(
        connector.connections.single().webSocket,
        """{"event":"/ctl_load_Video","group":"room-1","vid":"v","pid":"p","source":"s","mode":"secret"}""",
    )

    assertEquals(DiagnosticEvent.MessageReceived("/ctl_load_Video"), messageEvents[0])
    assertEquals(
        DiagnosticEvent.CommandAccepted(
            event = "/ctl_load_Video",
            summary = "vid=v pid=p source=s mode=<redacted>",
        ),
        messageEvents[1],
    )
    assertFalse(messageEvents.joinToString().contains("secret"))
    collector.cancel()
}
```

Also add rejection and reconnect tests asserting stable reasons rather than raw exception text.

- [ ] **Step 2: Run focused socket tests and verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.protocol.OkHttpSocketClientTest" --no-daemon --no-parallel --rerun-tasks
```

Expected: compilation FAIL because `DiagnosticEvent` and `SocketClient.diagnostics` do not exist.

- [ ] **Step 3: Define safe diagnostic events**

```kotlin
sealed interface DiagnosticEvent {
    data object Connecting : DiagnosticEvent
    data object JoinedRoom : DiagnosticEvent
    data class Reconnecting(val delayMs: Long) : DiagnosticEvent
    data object Closed : DiagnosticEvent
    data class MessageReceived(val event: String) : DiagnosticEvent
    data class CommandAccepted(val event: String, val summary: String) : DiagnosticEvent
    data class MessageIgnored(val reason: SocketParseReason) : DiagnosticEvent
    data class MessageRejected(val reason: SocketParseReason) : DiagnosticEvent
    data object VideoResolving : DiagnosticEvent
    data class VideoLoaded(val mediaType: ResolvedMediaType) : DiagnosticEvent
    data object VideoResolutionFailed : DiagnosticEvent
    data object PlaybackFailed : DiagnosticEvent
}
```

No event may carry raw JSON, URL, Header, Throwable message or unredacted mode.

- [ ] **Step 4: Add bounded diagnostics flow to socket client**

Use a separate flow:

```kotlin
private val mutableDiagnostics = MutableSharedFlow<DiagnosticEvent>(
    extraBufferCapacity = 64,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
override val diagnostics: Flow<DiagnosticEvent> = mutableDiagnostics.asSharedFlow()
```

Emit `MessageReceived` before parsing. Convert `Accepted`, `Ignored` and `Rejected` to diagnostics, then emit commands only for `Accepted`. Command summaries must be produced by a dedicated private formatter that truncates `vid/pid/source` and renders mode only as `<empty>` or `<redacted>`.

- [ ] **Step 5: Update all SocketClient fakes**

Every fake gets:

```kotlin
override val diagnostics: Flow<DiagnosticEvent> = emptyFlow()
```

Fakes used by session diagnostics tests may expose a `MutableSharedFlow<DiagnosticEvent>`.

- [ ] **Step 6: Run focused socket and compilation tests and verify GREEN**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.protocol.OkHttpSocketClientTest" :app:compileDebugAndroidTestKotlin --no-daemon --no-parallel --rerun-tasks
```

Expected: PASS; command delivery tests remain unchanged.

- [ ] **Step 7: Commit Task 2**

```powershell
git add app/src/main/java/com/airplay/tv/diagnostics/DiagnosticEvent.kt app/src/main/java/com/airplay/tv/protocol app/src/test app/src/androidTest
git commit -m "feat: expose safe websocket diagnostics"
```

---

### Task 3: Session Diagnostic Log Window

**Files:**
- Create: `app/src/main/java/com/airplay/tv/diagnostics/DiagnosticLogEntry.kt`
- Create: `app/src/test/java/com/airplay/tv/diagnostics/DiagnosticLogEntryTest.kt`
- Modify: `app/src/main/java/com/airplay/tv/session/SessionState.kt`
- Modify: `app/src/main/java/com/airplay/tv/session/SessionViewModel.kt`
- Modify: `app/src/test/java/com/airplay/tv/session/SessionViewModelTest.kt`

**Interfaces:**
- Consumes: `SocketClient.diagnostics`, accepted `ControlCommand.LoadVideo`, resolver result and `PlayerState.error` transitions.
- Produces: `SessionUiState.diagnosticLogs: List<DiagnosticLogEntry>` limited to 20 entries.

- [ ] **Step 1: Add failing log formatting and window tests**

```kotlin
@Test
fun appendDiagnosticKeepsLatestTwenty() {
    val entries = (1..21).fold(emptyList<DiagnosticLogEntry>()) { current, index ->
        current.appendDiagnostic(DiagnosticLogEntry("00:00:00", "RX", "event-$index"))
    }

    assertEquals(20, entries.size)
    assertEquals("event-2", entries.first().message)
    assertEquals("event-21", entries.last().message)
}

@Test
fun eventFormattingNeverIncludesSecretsOrUrls() {
    val entry = DiagnosticLogFormatter.format(
        DiagnosticEvent.CommandAccepted("/ctl_load_Video", "vid=v pid=p source=s mode=<redacted>"),
        timestampMillis = 0,
    )
    assertFalse(entry.message.contains("http"))
    assertFalse(entry.message.contains("secret"))
}
```

- [ ] **Step 2: Add failing SessionViewModel tests**

Emit socket diagnostics and a `LoadVideo(mode="")`; assert the UI log order contains `RX`, `OK`, `API`, and after resolver success `PLAY`, while `uiState.page == Player` and `loading` transitions correctly. Emit 21 diagnostics and assert only the latest 20 remain.

- [ ] **Step 3: Run focused tests and verify RED**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.diagnostics.*" --tests "com.airplay.tv.session.SessionViewModelTest" --no-daemon --no-parallel --rerun-tasks
```

Expected: compilation FAIL because log entry, formatter and UI state property do not exist.

- [ ] **Step 4: Implement immutable UI logs and formatter**

```kotlin
data class DiagnosticLogEntry(
    val timestamp: String,
    val stage: String,
    val message: String,
)

internal fun List<DiagnosticLogEntry>.appendDiagnostic(
    entry: DiagnosticLogEntry,
): List<DiagnosticLogEntry> = (this + entry).takeLast(MAX_DIAGNOSTIC_LOGS)

internal const val MAX_DIAGNOSTIC_LOGS = 20
```

Use `Instant`/`ZoneId.systemDefault()` or an injected timestamp provider. Tests must pass a deterministic clock; production must not use locale-sensitive free-form text.

- [ ] **Step 5: Collect diagnostics and add session/player stage events**

Add `diagnosticLogs: List<DiagnosticLogEntry> = emptyList()` to `SessionUiState`. In `SessionViewModel.init`, collect socket diagnostics. Before resolve append `API 正在解析视频地址`; on successful `playerController.load` append `PLAY <type> 已加载`; on fixed resolution/player errors append category-only failures. Never interpolate resolver exception messages or URLs.

- [ ] **Step 6: Run diagnostics, Session and resolver tests and verify GREEN**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.airplay.tv.diagnostics.*" --tests "com.airplay.tv.session.*" --tests "com.airplay.tv.feature.player.*" --no-daemon --no-parallel --rerun-tasks
```

Expected: all selected tests PASS, including the existing Session command-order suite.

- [ ] **Step 7: Commit Task 3**

```powershell
git add app/src/main/java/com/airplay/tv/diagnostics app/src/main/java/com/airplay/tv/session app/src/test/java/com/airplay/tv/diagnostics app/src/test/java/com/airplay/tv/session
git commit -m "feat: track bounded TV diagnostic logs"
```

---

### Task 4: Global Non-Focusable TV Log Overlay

**Files:**
- Create: `app/src/main/java/com/airplay/tv/diagnostics/DiagnosticLogOverlay.kt`
- Modify: `app/src/main/java/com/airplay/tv/app/AppNavigation.kt`
- Modify: `app/src/androidTest/java/com/airplay/tv/app/AppNavigationTest.kt`

**Interfaces:**
- Consumes: `SessionUiState.diagnosticLogs`.
- Produces: `@Composable fun DiagnosticLogOverlay(logs: List<DiagnosticLogEntry>, modifier: Modifier = Modifier)` with test tag `diagnostic-log-overlay`.

- [ ] **Step 1: Add failing Compose tests for both routes and focus behavior**

```kotlin
@Test
fun diagnosticOverlayIsVisibleOnPairingAndPlayerRoutes() {
    var state by mutableStateOf(
        sessionState.copy(
            diagnosticLogs = listOf(
                DiagnosticLogEntry("20:31:16", "RX", "/ctl_play"),
            ),
        ),
    )
    composeRule.setContent {
        AppNavigation(
            state = state,
            player = player,
            onBack = {},
        )
    }
    composeRule.onNodeWithTag("pairing-screen").assertExists()
    composeRule.onNodeWithTag("diagnostic-log-overlay").assertExists()

    state = state.copy(page = SessionPage.Player)
    composeRule.onNodeWithTag("player-screen").assertExists()
    composeRule.onNodeWithTag("diagnostic-log-overlay").assertExists()
}
```

Add an assertion that the overlay has no click action and cannot request focus.

- [ ] **Step 2: Compile AndroidTest and verify RED**

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin --no-daemon --no-parallel --rerun-tasks
```

Expected: compilation FAIL because `DiagnosticLogOverlay` and `diagnosticLogs` rendering are absent.

- [ ] **Step 3: Implement the overlay**

Use a root `Box`, align to `BottomEnd`, constrain width/height, and render logs in a non-interactive `Column`/`LazyColumn`. Do not add `clickable`, `focusable`, `focusRequester` or pointer input. Apply:

```kotlin
Modifier
    .widthIn(max = 560.dp)
    .heightIn(max = 220.dp)
    .padding(end = 40.dp, bottom = 36.dp)
    .clip(MaterialTheme.shapes.medium)
    .background(Color(0xCC0B111A))
    .border(1.dp, Color(0x334CD7A5), MaterialTheme.shapes.medium)
    .padding(12.dp)
    .testTag("diagnostic-log-overlay")
```

Render timestamp/stage with monospace typography and truncate each message to one line with ellipsis.

- [ ] **Step 4: Place the overlay above NavHost**

Wrap `NavHost` in a root `Box`; call `DiagnosticLogOverlay(state.diagnosticLogs, Modifier.align(Alignment.BottomEnd))` after `NavHost` so it is visible on both destinations.

- [ ] **Step 5: Compile AndroidTest and run UI logic tests and verify GREEN**

```powershell
.\gradlew.bat :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest --tests "com.airplay.tv.app.*" --no-daemon --no-parallel --rerun-tasks
```

Expected: AndroidTest compilation and app route JVM tests PASS. If an Android TV/device is attached, also run `:app:connectedDebugAndroidTest`; otherwise record the device boundary.

- [ ] **Step 6: Commit Task 4**

```powershell
git add app/src/main/java/com/airplay/tv/diagnostics/DiagnosticLogOverlay.kt app/src/main/java/com/airplay/tv/app/AppNavigation.kt app/src/androidTest/java/com/airplay/tv/app/AppNavigationTest.kt
git commit -m "feat: show websocket logs on TV"
```

---

### Task 5: End-to-End Regression and Deliverable Verification

**Files:**
- Modify: `README.md`
- Test: all Debug/Release JVM and AndroidTest sources.

**Interfaces:**
- Consumes: completed parser, diagnostics stream, session logs and overlay.
- Produces: verified APKs and an explicit real-device acceptance checklist.

- [ ] **Step 1: Add README acceptance sequence**

Document:

```text
扫码后选择视频：TV 应依次显示 RX /ctl_load_Video、OK 已接受、API 正在解析、PLAY 已加载，并进入播放器。
若出现 DROP，按日志原因检查 room/字段；日志中的 mode 必须只显示 <empty> 或 <redacted>。
```

- [ ] **Step 2: Run the full fresh verification command**

```powershell
$env:ANDROID_HOME='E:\cache\android-sdk'
$env:ANDROID_SDK_ROOT='E:\cache\android-sdk'
$env:GRADLE_USER_HOME='E:\cache\gradle'
$env:Path=(($env:Path -split ';') | Where-Object { $_ -and -not $_.Contains('"') }) -join ';'
.\gradlew.bat :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin --no-daemon --no-parallel --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; both variants have zero test failures and zero lint errors.

- [ ] **Step 3: Verify security, encoding and repository scope**

```powershell
rg -n "sourceSecret|mode.*secret|raw.*message|https?://" app/src/main/java/com/airplay/tv/diagnostics app/src/main/java/com/airplay/tv/protocol
git diff --check
git status --short
```

Inspect every match: no raw message/URL/secret is placed in a diagnostic event. Validate all changed text as strict UTF-8 and assert no UTF-8 BOM. Verify `airplayTV-vue` and `api` Git states are unchanged.

- [ ] **Step 4: Verify APK metadata and signatures**

Use SDK `apkanalyzer` and `apksigner` to record package, version, ABI, size, SHA-256 and signing status. Debug must verify v1/v2; release remains explicitly unsigned unless the user separately supplies signing credentials.

- [ ] **Step 5: Run or record real-device boundary**

```powershell
E:\cache\android-sdk\platform-tools\adb.exe devices -l
```

If a TV is attached, install Debug APK and execute the exact scan/play/control flow. If no device is present, do not claim real-device playback verification; hand off the APK and five-stage log acceptance sequence.

- [ ] **Step 6: Commit Task 5**

```powershell
git add README.md
git commit -m "docs: add TV websocket diagnostics verification"
```

---

## Final Review Checklist

- [ ] `mode:null` produces `ControlCommand.LoadVideo(mode="")`.
- [ ] TV switches to Player immediately after an accepted Load command.
- [ ] RX/OK/API/PLAY or stable DROP reason is visible on both routes.
- [ ] Diagnostics and commands use independent bounded flows.
- [ ] Only the latest 20 UI entries remain.
- [ ] No raw JSON, complete URL, Header, Throwable message or source secret reaches UI.
- [ ] Overlay has no focus/click semantics.
- [ ] Vue and Go remain untouched.
- [ ] Debug/Release unit, lint, assemble and AndroidTest compile pass freshly.
- [ ] Real-device result is reported accurately.
