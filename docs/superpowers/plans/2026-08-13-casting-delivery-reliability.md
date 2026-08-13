# AirPlayTV 投屏指令可靠发送与关联状态修复实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除 H5 在 WebSocket 未就绪时静默丢失投屏指令的问题，并让 TV 正确显示手机关联状态和完整房间号。

**Architecture:** H5 将控制消息发送改为有界等待的 Promise API，并将“发送成功后导航”抽成可测试的投屏流程；扫码和重连时发送 `/ctl_pair`。Android 扩展现有控制协议和 `SessionUiState`，以 WebSocket 传输状态与手机关联状态共同计算短状态文案，不修改视频解析或 ExoPlayer。

**Tech Stack:** Vue 3、原生 WebSocket、Node.js `node:test`、Kotlin、Coroutines/StateFlow、Jetpack Compose、JUnit4、Media3。

## Global Constraints

- WebSocket 打开等待超时固定为 5 秒。
- 每次发送调用最多写入一次，不建立无限或持久消息队列。
- H5 发送失败时不得导航到 `/control`。
- Android 日志不得记录房间号、原始消息、视频参数或媒体 URL。
- 不修改 Go WebSocket 服务、视频解析、媒体 URL 校验和 ExoPlayer 加载实现。
- 所有新改文件使用 UTF-8 无 BOM。
- 不执行 Git 暂存、提交或推送；每个任务以测试和 `git diff --check` 代替提交步骤。

---

## 文件结构

### `airplayTV-vue`

- `src/helpers/reliable-websocket.js`：只负责把一个消息在 5 秒内可靠写入已打开的 WebSocket。
- `src/helpers/websocket.js`：管理单例连接、协议封装和 `/ctl_pair` 常量。
- `src/helpers/casting.js`：只负责“发送成功后导航、失败时保留页面”的投屏用例。
- `src/App.vue`：WebSocket 重开后恢复手机与已保存 TV 房间的关联。
- `src/views/JoinRoomView.vue`：扫码保存房间后发送关联事件。
- `src/components/AppAudioVideoList.vue`、`src/components/AppPlayAudio.vue`：使用可靠投屏用例并加本地重复点击锁。
- `src/views/ControlView.vue`：控制命令发送失败时不提前修改本地播放/全屏状态。
- `tests/reliable-websocket.test.mjs`、`tests/casting.test.mjs`：Node 单元测试。

### `airplayTV-android`

- `protocol/ControlCommand.kt`、`SocketMessageParser.kt`：增加 `ControllerPaired`。
- `session/SessionState.kt`、`SessionViewModel.kt`：保存并维护 `controllerConnected`。
- `diagnostics/DiagnosticLogEntry.kt`：新增固定关联日志。
- `feature/pairing/PairingScreen.kt`：显示完整房间号并按两种状态计算短文案。
- `app/AppNavigation.kt`：播放器二维码浮层显示同一房间号。
- 现有 JVM/Compose 测试文件：覆盖解析、状态、日志和 UI。

---

### Task 1: H5 WebSocket 有界可靠发送

**Files:**
- Create: `airplayTV-vue/src/helpers/reliable-websocket.js`
- Create: `airplayTV-vue/tests/reliable-websocket.test.mjs`
- Modify: `airplayTV-vue/src/helpers/websocket.js`
- Modify: `airplayTV-vue/package.json`

**Interfaces:**
- Produces: `sendWhenOpen({ getSocket, connect, payload, timeoutMs }): Promise<void>`
- Produces: `send(data): Promise<void>`、`sendControl(groupName, context): Promise<void>`

- [ ] **Step 1: 写 OPEN、CONNECTING、CLOSED、超时和单次发送失败测试**

测试使用可触发 `open/error/close` 的 `FakeWebSocket`，断言：OPEN 立即发送；CONNECTING 等待；CLOSED 调用一次 `connect()`；超时 reject；`open` 与超时竞争时只发送一次且监听器被清理。

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { sendWhenOpen } from '../src/helpers/reliable-websocket.js'

test('waits for a connecting socket and sends exactly once', async () => {
  const socket = new FakeWebSocket(0)
  const pending = sendWhenOpen({
    getSocket: () => socket,
    connect: () => socket,
    payload: 'video',
    timeoutMs: 50,
  })
  socket.open()
  await pending
  assert.deepEqual(socket.sent, ['video'])
  assert.equal(socket.listenerCount(), 0)
})
```

- [ ] **Step 2: 运行测试并确认 RED**

Run: `node --test tests/reliable-websocket.test.mjs`

Expected: FAIL，提示 `ERR_MODULE_NOT_FOUND` 或 `sendWhenOpen` 未定义。

- [ ] **Step 3: 实现最小可靠发送器**

```js
export const sendWhenOpen = ({
  getSocket,
  connect,
  payload,
  timeoutMs = 5000,
}) => new Promise((resolve, reject) => {
  let settled = false
  let socket = getSocket()

  if (!socket || socket.readyState === 2 || socket.readyState === 3) {
    socket = connect()
  }
  if (!socket) {
    reject(new Error('websocket unavailable'))
    return
  }
  if (socket.readyState === 1) {
    socket.send(payload)
    resolve()
    return
  }

  const cleanup = () => {
    clearTimeout(timer)
    socket.removeEventListener('open', onOpen)
    socket.removeEventListener('error', onFailure)
    socket.removeEventListener('close', onFailure)
  }
  const finish = (callback) => {
    if (settled) return
    settled = true
    cleanup()
    callback()
  }
  const onOpen = () => finish(() => {
    socket.send(payload)
    resolve()
  })
  const onFailure = () => finish(() => reject(new Error('websocket unavailable')))
  const timer = setTimeout(
    () => finish(() => reject(new Error('websocket timeout'))),
    timeoutMs,
  )
  socket.addEventListener('open', onOpen)
  socket.addEventListener('error', onFailure)
  socket.addEventListener('close', onFailure)
})
```

修改 `connect()` 返回当前 `_websocket`，并让 `send()`、`joinGroup()`、`sendControl()` 返回 Promise。加入：

```js
const ControlEventPair = '/ctl_pair'

const send = (data) => sendWhenOpen({
  getSocket: () => _websocket,
  connect,
  payload: data,
  timeoutMs: 5000,
})
```

`joinGroup()` 和 `sendControl()` 必须 `return send(JSON.stringify(...))`，导出 `ControlEventPair`。

- [ ] **Step 4: 运行 H5 单元测试和构建并确认 GREEN**

Run: `node --test tests/reliable-websocket.test.mjs`

Expected: 全部 PASS。

Run: `npm run build`

Expected: Vite build 成功，无未处理 Promise 或语法错误。

- [ ] **Step 5: 检查任务差异**

Run: `git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-vue diff --check`

Expected: exit 0；不执行 `git add` 或 `git commit`。

---

### Task 2: H5 发送成功后才进入控制器

**Files:**
- Create: `airplayTV-vue/src/helpers/casting.js`
- Create: `airplayTV-vue/tests/casting.test.mjs`
- Modify: `airplayTV-vue/src/components/AppAudioVideoList.vue`
- Modify: `airplayTV-vue/src/components/AppPlayAudio.vue`
- Modify: `airplayTV-vue/src/views/ControlView.vue`

**Interfaces:**
- Consumes: `sendControl(groupName, context): Promise<void>`
- Produces: `sendCastingCommand({ room, context, sendControl, navigate }): Promise<void>`

- [ ] **Step 1: 写导航时序和失败回归测试**

```js
test('navigates only after the command is sent', async () => {
  const calls = []
  await sendCastingCommand({
    room: 'room-1',
    context: { event: '/ctl_load_Video' },
    sendControl: async () => calls.push('send'),
    navigate: async () => calls.push('navigate'),
  })
  assert.deepEqual(calls, ['send', 'navigate'])
})

test('does not navigate when sending fails', async () => {
  let navigated = false
  await assert.rejects(() => sendCastingCommand({
    room: 'room-1',
    context: {},
    sendControl: async () => { throw new Error('offline') },
    navigate: async () => { navigated = true },
  }))
  assert.equal(navigated, false)
})
```

- [ ] **Step 2: 运行测试并确认 RED**

Run: `node --test tests/casting.test.mjs`

Expected: FAIL，提示模块或函数不存在。

- [ ] **Step 3: 实现投屏用例并接入视频/音频入口**

```js
export const sendCastingCommand = async ({ room, context, sendControl, navigate }) => {
  if (!room) throw new Error('room required')
  await sendControl(room, context)
  await navigate('/control')
}
```

两个组件各自增加局部 `isCasting`，入口使用 `try/finally`：

```js
if (isCasting.value) return
isCasting.value = true
try {
  await sendCastingCommand({
    room: room.value,
    context,
    sendControl,
    navigate: router.push,
  })
} catch (_) {
  message.warning('连接电视失败，请重试')
} finally {
  isCasting.value = false
}
```

控制器页面将 `sendControlHandler` 改为 `async`：先 `await sendControl(...)`，成功后才更新 `isPlay/isFullscreen`；失败仅提示，不更新本地状态。

- [ ] **Step 4: 运行测试与构建并确认 GREEN**

Run: `node --test tests/casting.test.mjs tests/reliable-websocket.test.mjs`

Expected: 全部 PASS。

Run: `npm run build`

Expected: build 成功。

- [ ] **Step 5: 检查任务差异**

Run: `git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-vue diff --check`

Expected: exit 0；不暂存、不提交。

---

### Task 3: H5 扫码与重连时发送关联事件

**Files:**
- Modify: `airplayTV-vue/src/helpers/casting.js`
- Modify: `airplayTV-vue/tests/casting.test.mjs`
- Modify: `airplayTV-vue/src/views/JoinRoomView.vue`
- Modify: `airplayTV-vue/src/App.vue`

**Interfaces:**
- Produces: `pairController({ room, clientId, sendControl }): Promise<void>`

- [ ] **Step 1: 写关联消息测试**

断言 `pairController()` 发送以下精确上下文，空房间不发送并 reject：

```js
{
  event: '/ctl_pair',
  group: 'room-1',
  from: 'client-1',
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run: `node --test tests/casting.test.mjs`

Expected: FAIL，提示 `pairController` 未定义。

- [ ] **Step 3: 实现关联用例并接入扫码、重连**

```js
export const pairController = ({ room, clientId, sendControl }) => {
  if (!room) return Promise.reject(new Error('room required'))
  return sendControl(room, {
    event: '/ctl_pair',
    group: room,
    from: clientId || '',
  })
}
```

`JoinRoomView.vue` 保存房间后等待 `pairController()`；成功后立即进入首页，失败时显示“连接电视失败，请重试”，不启动无条件跳转计时器。

`App.vue` 的 `EventNameOpen` 回调在加入 H5 自身分组后读取 `KEY_ROOM_ID`；存在房间时调用 `pairController()`。重连恢复关联失败只记录固定警告，不阻止 H5 主页面使用。

- [ ] **Step 4: 运行 H5 全部测试和构建**

Run: `node --test tests`

Expected: 全部 PASS。

Run: `npm run build`

Expected: build 成功。

- [ ] **Step 5: 检查差异**

Run: `git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-vue diff --check`

Expected: exit 0；不暂存、不提交。

---

### Task 4: Android 解析关联命令并维护关联状态

**Files:**
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/protocol/ControlCommand.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/protocol/SocketMessageParser.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/session/SessionState.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/session/SessionViewModel.kt`
- Test: `airplayTV-android/app/src/test/java/com/airplay/tv/protocol/SocketMessageParserTest.kt`
- Test: `airplayTV-android/app/src/test/java/com/airplay/tv/session/SessionViewModelTest.kt`

**Interfaces:**
- Produces: `ControlCommand.ControllerPaired`
- Produces: `SessionUiState.controllerConnected: Boolean`

- [ ] **Step 1: 写协议和状态 RED 测试**

新增断言：正确房间 `/ctl_pair` 解析为 `ControllerPaired`；错误房间返回 null；ViewModel 初始 false；Connected 仍 false；收到 pair 后 true；收到任一合法控制命令也 true；Reconnecting/Closed 后 false。

- [ ] **Step 2: 运行聚焦测试并确认 RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.airplay.tv.protocol.SocketMessageParserTest" `
  --tests "com.airplay.tv.session.SessionViewModelTest" `
  --no-daemon --no-parallel
```

Expected: 编译失败，缺少 `ControllerPaired` 或 `controllerConnected`。

- [ ] **Step 3: 实现协议和状态转换**

在 `ControlCommand` 增加：

```kotlin
data object ControllerPaired : ControlCommand
```

解析器映射：

```kotlin
"/ctl_pair" -> ControlCommand.ControllerPaired
```

`SessionUiState` 增加：

```kotlin
val controllerConnected: Boolean = false,
```

命令收集器在 `HistoryIgnored` 之外的合法命令到达时先设置 `controllerConnected = true`。`handleCommand(ControllerPaired)` 不操作播放器。连接状态变为 `Reconnecting` 或 `Closed` 时设置 false；`Connecting/Connected` 不凭空设置 true。

- [ ] **Step 4: 运行聚焦测试并确认 GREEN**

使用 Step 2 相同命令。

Expected: 全部 PASS。

- [ ] **Step 5: 检查任务差异**

Run: `git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-android diff --check`

Expected: exit 0；不暂存、不提交。

---

### Task 5: Android 关联日志、状态文案和房间号 UI

**Files:**
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/diagnostics/DiagnosticLogEntry.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/feature/pairing/PairingScreen.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/app/AppNavigation.kt`
- Test: `airplayTV-android/app/src/test/java/com/airplay/tv/diagnostics/DiagnosticLogEntryTest.kt`
- Test: `airplayTV-android/app/src/androidTest/java/com/airplay/tv/app/AppNavigationTest.kt`

**Interfaces:**
- Consumes: `controllerConnected: Boolean`
- Produces: `ConnectionStatus(connection, controllerConnected, modifier)`

- [ ] **Step 1: 写日志和 Compose UI RED 测试**

新增断言：

- `ControllerPaired.toDiagnosticLog()` 为 `CTL / 手机控制器已关联`。
- Connected + false 显示“等待连接”。
- Connected + true 显示“已连接”。
- Pairing 页显示完整 `room-1`。
- Player QR 浮层显示完整 `room-1`。
- Connecting/Reconnecting/Closed 文案分别保持“连接中/重连中/已断开”。

- [ ] **Step 2: 运行 JVM 测试和 Android 测试源码编译并确认 RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest `
  --tests "com.airplay.tv.diagnostics.DiagnosticLogEntryTest" `
  :app:compileDebugAndroidTestKotlin `
  --no-daemon --no-parallel
```

Expected: JVM 断言失败或 Android 测试编译失败，因为 UI 尚未接收关联状态/显示房间号。

- [ ] **Step 3: 实现固定日志和 UI**

日志映射：

```kotlin
ControlCommand.ControllerPaired -> "手机控制器已关联"
```

状态文案：

```kotlin
val label = when (connection) {
    SocketConnectionState.Connecting -> "连接中"
    SocketConnectionState.Reconnecting -> "重连中"
    SocketConnectionState.Closed -> "已断开"
    SocketConnectionState.Connected -> if (controllerConnected) "已连接" else "等待连接"
}
```

扫码页和播放器二维码浮层都增加：

```kotlin
Text(
    text = "房间号：${state.roomId}",
    fontFamily = FontFamily.Monospace,
    maxLines = 1,
)
```

不得使用 `TextOverflow.Ellipsis` 截断房间号；播放器浮层参数从 `qrCode` 扩展为 `qrCode, roomId`。

- [ ] **Step 4: 运行聚焦测试和 Android 测试源码编译并确认 GREEN**

使用 Step 2 相同命令。

Expected: JVM 测试 PASS，Android 测试源码编译成功。

- [ ] **Step 5: 在已启动的 TV 模拟器运行 Compose 测试**

Run: `.\gradlew.bat :app:connectedDebugAndroidTest --no-daemon --no-parallel`

Expected: instrumentation 测试全部 PASS。

- [ ] **Step 6: 检查任务差异**

Run: `git -c safe.directory=D:/repo/github.com/airplayTV/airplayTV-android diff --check`

Expected: exit 0；不暂存、不提交。

---

### Task 6: 全量回归与真实端到端验收

**Files:**
- Modify only if verification exposes a directly related defect.

- [ ] **Step 1: H5 全量验证**

Run: `node --test tests`

Expected: 全部 PASS。

Run: `npm run build`

Expected: build 成功。

- [ ] **Step 2: Android 全量强制重跑**

Run:

```powershell
.\gradlew.bat `
  :app:testDebugUnitTest `
  :app:testReleaseUnitTest `
  :app:lintDebug `
  :app:lintRelease `
  :app:assembleDebug `
  :app:assembleRelease `
  :app:connectedDebugAndroidTest `
  --no-daemon --no-parallel --rerun-tasks
```

Expected: 全部成功，lint 0 errors。

- [ ] **Step 3: 验证 APK 身份与实际文件名**

读取 `app/build/outputs/apk/debug/output-metadata.json` 中的 `outputFile`，不得假设文件名为 `app-debug.apk`。使用 `apkanalyzer` 验证：

```powershell
apkanalyzer manifest application-id <actual-debug-apk>
apkanalyzer manifest version-code <actual-debug-apk>
apkanalyzer manifest version-name <actual-debug-apk>
```

Expected: `com.airplay.tv`、`1`、`1.0.0`；记录 SHA-256。

- [ ] **Step 4: 模拟 WebSocket 未就绪时的端到端投屏**

在 H5 WebSocket 处于 CONNECTING 状态时立即点击视频；确认 H5 等待连接成功后才进入控制器，TV 日志依次出现“手机控制器已关联”“收到加载视频指令”，随后切换播放器并输出视频帧。

- [ ] **Step 5: 验证重连恢复**

中断并恢复网络；确认 TV 显示“重连中”后回到“等待连接”，H5 重新打开 WebSocket 后自动发送 `/ctl_pair`，TV 变为“已连接”；再次选择视频可播放。

- [ ] **Step 6: 编码、安全和 Git 边界检查**

检查所有任务文件均无 UTF-8 BOM；运行两个仓库的 `git diff --check` 和 `git status --short`。确认没有记录原始控制消息、房间号、媒体 URL，没有暂存文件，没有提交和推送。
