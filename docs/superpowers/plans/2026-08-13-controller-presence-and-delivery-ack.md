# AirPlayTV 控制器在线状态与投递 ACK 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 通过服务端 Presence、投递 ACK、Android 协议兼容和全局日志，修复 H5 扫码后无法播放、TV 扫码页无日志及手机关闭后仍显示已连接。

**Architecture:** Go API 维护进程内控制器租约并对 Presence/控制投递返回 request-scoped ACK；H5 用一次性 ACK 等待器决定是否导航并周期续约；Android 兼容 `mode:null`、处理 `/ctl_unpair`，并由全局导航层渲染诊断日志。部署依赖顺序固定为 API → H5 → Android。

**Tech Stack:** Go 1.25、goWebsocket、Vue 3、原生 WebSocket、Node `node:test`、Kotlin、Coroutines/StateFlow、Jetpack Compose、JUnit4、Media3。

## Global Constraints

- Presence 周期固定 5 秒，服务端租约固定 15 秒，清理扫描固定 5 秒。
- ACK 超时固定 5 秒；ACK 仅表示目标 TV 房间存在且消息已加入发送队列。
- 同一 TV 房间支持多个 H5；只有最后一个控制器离线才发送 `/ctl_unpair`。
- 旧 H5 未携带 `request_id` 的 `send-to-group` 必须继续广播。
- 日志、ACK 和错误提示不得输出房间号、socket/client ID、原始消息、视频参数或媒体 URL。
- 不修改视频源 API、URL 校验、ExoPlayer 加载和遥控器语义。
- 保留三个仓库已有未提交改动；不执行 `git add`、`git commit` 或 `git push`。
- 新增和修改文件均使用 UTF-8 无 BOM。

---

## 文件结构

### `api`

- `controller/controller_presence.go`：线程安全 Presence Registry 与租约清理。
- `controller/websocket_gateway.go`：将 goWebsocket 操作收口为可测试接口。
- `controller/websocket.go`：Presence、ACK、Close 与旧广播兼容。
- `controller/controller_presence_test.go`、`controller/websocket_test.go`：并发状态和协议测试。
- `main.go`：注册 Presence/Close 事件并启动、停止清理循环。

### `airplayTV-vue`

- `src/helpers/websocket-ack.js`：request ID、一次性 ACK 等待器和连接级清理。
- `src/helpers/controller-presence.js`：Presence 首发、续约和停止。
- `src/helpers/websocket.js`：ACK 事件分发、连接 generation、可靠发送。
- `src/helpers/casting.js`：ACK 驱动配对与控制投递、load payload 规范化。
- `src/App.vue`、`src/views/JoinRoomView.vue`：重连/扫码 Presence 生命周期。
- 四个投屏入口与 `ControlView.vue`：ACK 成功后导航/更新状态。
- `tests/websocket-ack.test.mjs`、`tests/controller-presence.test.mjs`、`tests/casting.test.mjs`。

### `airplayTV-android`

- `protocol/ControlCommand.kt`、`SocketMessageParser.kt`：`mode:null` 和 `ControllerUnpaired`。
- `diagnostics/DiagnosticLogEntry.kt`：固定断开日志。
- `session/SessionViewModel.kt`：解除关联且不影响播放。
- `diagnostics/DiagnosticLogOverlay.kt`：可复用日志 UI。
- `app/AppNavigation.kt`、`feature/player/PlayerScreen.kt`：全局日志布局。
- 现有 JVM/Compose 测试：协议、状态、日志和页面覆盖。

---

### Task 1: Go Presence Registry

**Files:**
- Create: `api/controller/controller_presence.go`
- Create: `api/controller/controller_presence_test.go`

**Interfaces:**
- Produces: `NewControllerPresenceRegistry() *ControllerPresenceRegistry`
- Produces: `Touch(socketID, room string, now time.Time) (first bool, emptiedRoom string)`
- Produces: `Remove(socketID string) string`
- Produces: `ExpireBefore(cutoff time.Time) []string`

- [ ] **Step 1: 写 Registry RED 测试**

覆盖首次 Touch、重复续约、切房、多 socket 同房间、删除最后一个、过期去重和并发 `Touch/Remove/ExpireBefore`。核心断言：

```go
func TestPresenceKeepsRoomOnlineUntilLastControllerLeaves(t *testing.T) {
    registry := NewControllerPresenceRegistry()
    now := time.Unix(100, 0)
    registry.Touch("socket-a", "room-1", now)
    registry.Touch("socket-b", "room-1", now)
    if room := registry.Remove("socket-a"); room != "" { t.Fatalf("unexpected empty room %q", room) }
    if room := registry.Remove("socket-b"); room != "room-1" { t.Fatalf("got %q", room) }
}
```

- [ ] **Step 2: 运行 RED**

Run: `go test ./controller -run ControllerPresence -count=1`

Expected: 编译失败，缺少 Registry。

- [ ] **Step 3: 实现带锁的双索引 Registry**

```go
type ControllerPresenceRegistry struct {
    mu       sync.Mutex
    bySocket map[string]ControllerPresence
    byRoom   map[string]map[string]struct{}
}
```

广播不在锁内执行；`ExpireBefore` 返回排序并去重的空房间列表，使测试稳定。

- [ ] **Step 4: 运行 GREEN 和 race detector**

Run: `go test -race ./controller -run ControllerPresence -count=1`

Expected: PASS，无 race。

- [ ] **Step 5: 边界检查**

Run: `gofmt -w controller/controller_presence.go controller/controller_presence_test.go`

Run: `git -c safe.directory=D:/repo/github.com/airplayTV/api diff --check`

Expected: exit 0；不暂存、不提交。

---

### Task 2: Go Presence、ACK 与断线协议

**Files:**
- Create: `api/controller/websocket_gateway.go`
- Create: `api/controller/websocket_test.go`
- Modify: `api/controller/websocket.go`
- Modify: `api/main.go`

**Interfaces:**
- Consumes: Task 1 Registry。
- Produces: `ControllerPresence(EventCtx) bool`、`Close(EventCtx) bool`、`ExpireControllerPresence(time.Time)`。
- Produces ACK events: `controller-presence-ack`、`send-to-group-ack`。

- [ ] **Step 1: 抽象并写协议 RED 测试**

```go
type WebsocketGateway interface {
    Send(clientID string, data interface{})
    SendToGroup(group string, data interface{})
    JoinGroup(clientID, group string)
    ListGroupClient(group string) []string
}
```

Fake Gateway 记录调用，测试：无 TV Presence 失败；首次 Presence pair 一次；重复 Presence 只续约；控制房间为空失败；存在 TV 时先入队再 ACK；旧请求无 `request_id` 继续广播；Close/Expire 最后一个控制器才 unpair。

- [ ] **Step 2: 运行 RED**

Run: `go test ./controller -run 'Websocket|Presence|Ack' -count=1`

Expected: FAIL，缺少 handler/ACK。

- [ ] **Step 3: 实现 handler 与安全 ACK**

ACK 只包含：

```go
gin.H{"request_id": req.RequestID, "accepted": accepted, "tv_online": tvOnline}
```

控制 ACK 使用 `recipient_count`；不包含 group/client/payload。`SendToGroup` 在 `recipient_count > 0` 时先调用，再发送成功 ACK。

- [ ] **Step 4: 注册事件与清理循环**

```go
AppSocket.On("controller-presence", websocketController.ControllerPresence)
AppSocket.On(goWebsocket.Event(goWebsocket.EventClose).String(), websocketController.Close)
```

用可停止 ticker 每 5 秒调用 `ExpireBefore(now.Add(-15*time.Second))`。测试构造 controller 时注入 clock，不依赖真实 sleep。

- [ ] **Step 5: 全量 Go 验证**

Run: `gofmt -w controller/*.go main.go`

Run: `go test ./... -count=1`

Run: `go test -race ./controller -count=1`

Expected: 全 PASS，无 race；旧协议测试通过。

---

### Task 3: H5 request-scoped ACK 等待器

**Files:**
- Create: `airplayTV-vue/src/helpers/websocket-ack.js`
- Create: `airplayTV-vue/tests/websocket-ack.test.mjs`
- Modify: `airplayTV-vue/src/helpers/websocket.js`

**Interfaces:**
- Produces: `createRequestId(): string`
- Produces: `createAckRegistry({ timeoutMs }): { wait, resolve, rejectGeneration }`
- Produces: `sendControlWithAck(group, context): Promise<Ack>`、`sendPresenceWithAck(group): Promise<Ack>`。

- [ ] **Step 1: 写 ACK RED 测试**

覆盖成功、`accepted=false`、5 秒超时、close/error、generation 切换、重复 ACK、未知 request ID、清理后 Map 为 0。必须先注册 waiter 再调用 socket send，防止同步 ACK 竞态。

- [ ] **Step 2: 运行 RED**

Run: `node --test tests/websocket-ack.test.mjs`

Expected: `ERR_MODULE_NOT_FOUND`。

- [ ] **Step 3: 实现最小 ACK Registry**

每个 entry 保存 `resolve/reject/timer/generation`；所有结算统一删除 entry 和 timer。`request_id` 使用 `crypto.randomUUID()`，回退为随机字节字符串，不使用递增可猜 ID。

- [ ] **Step 4: 接入 WebSocket 消息分发**

`onmessage` 对两个 ACK 事件只调用 registry；其他消息仍走既有 `EventNameMessage`。`onclose/onerror` 拒绝当前 generation 所有 pending 请求。

- [ ] **Step 5: 运行 GREEN**

Run: `node --test tests/websocket-ack.test.mjs tests/reliable-websocket.test.mjs`

Run: `npm run build`

Expected: PASS，Vite build 成功。

---

### Task 4: H5 Presence 生命周期

**Files:**
- Create: `airplayTV-vue/src/helpers/controller-presence.js`
- Create: `airplayTV-vue/tests/controller-presence.test.mjs`
- Modify: `airplayTV-vue/src/App.vue`
- Modify: `airplayTV-vue/src/views/JoinRoomView.vue`

**Interfaces:**
- Consumes: `sendPresenceWithAck(group)`。
- Produces: `createControllerPresence({ intervalMs: 5000, sendPresence })`，包含 `start(room)`、`refresh()`、`stop()`。

- [ ] **Step 1: 写 Presence RED 测试**

使用 fake timers 验证立即首发、每 5 秒续约、同房间 start 幂等、切房停止旧循环、stop 清 timer、失败不产生并行循环、重连 refresh 立即发送。

- [ ] **Step 2: 运行 RED**

Run: `node --test tests/controller-presence.test.mjs`

- [ ] **Step 3: 实现生命周期并接入扫码/重连**

扫码流程：保存 room → `await presence.start(room)` → 成功跳首页；失败留页并显示 `电视未连接，请重新扫码`。`EventNameOpen` 先加入自身组，再对已存 room `refresh()`；`EventNameClose` 停止当前 timer，重连后重启。

- [ ] **Step 4: 运行 GREEN 与源码接线测试**

Run: `node --test tests/controller-presence.test.mjs tests/casting.test.mjs`

Expected: 全 PASS，无无条件跳转 timer 或 discarded Promise。

---

### Task 5: H5 ACK 投屏与 load payload 规范化

**Files:**
- Modify: `airplayTV-vue/src/helpers/casting.js`
- Modify: `airplayTV-vue/tests/casting.test.mjs`
- Modify: `airplayTV-vue/src/components/AppAudioVideoList.vue`
- Modify: `airplayTV-vue/src/components/AppPlayAudio.vue`
- Modify: `airplayTV-vue/src/components/AppPlayVideo.vue`
- Modify: `airplayTV-vue/src/components/AppSourceList.vue`
- Modify: `airplayTV-vue/src/views/ControlView.vue`

**Interfaces:**
- Consumes: `sendControlWithAck(group, context)`。
- Produces: `normalizeLoadVideoContext(context)`。

- [ ] **Step 1: 写 RED 测试**

验证 `mode:null -> ''`、数字 ID 转字符串、空 vid/pid/source 拒绝、ACK 前不导航、失败 ACK/超时不导航、控制状态 ACK 后才更新。

- [ ] **Step 2: 实现统一规范化**

```js
export const normalizeLoadVideoContext = (context) => {
  const normalized = {
    ...context,
    vid: String(context.vid ?? ''),
    pid: String(context.pid ?? ''),
    source: String(context.source ?? ''),
    mode: String(context.mode ?? ''),
  }
  if (!normalized.vid || !normalized.pid || !normalized.source) throw new Error('invalid load command')
  return normalized
}
```

四个入口必须只调用此 helper，避免各自修补。

- [ ] **Step 3: 接入 ACK 导航与控制器状态**

所有业务入口继续使用现有 local guard；错误文案统一为 `电视未连接，请重新扫码`。保留现有目标路由、query 和控制 payload。

- [ ] **Step 4: H5 全量验证**

Run: `node --test tests/*.mjs`

Run: `npm run build`

Run: `rg -n "\b(sendControl|sendControlWithAck|sendPresenceWithAck)\s*\(" src`

Expected: 所有 Promise 均显式 await/catch。

---

### Task 6: Android `mode:null` 与 `/ctl_unpair`

**Files:**
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/protocol/ControlCommand.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/protocol/SocketMessageParser.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/diagnostics/DiagnosticLogEntry.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/session/SessionViewModel.kt`
- Modify relevant parser/diagnostic/session tests.

**Interfaces:**
- Produces: `ControlCommand.ControllerUnpaired`。

- [ ] **Step 1: 写 RED 测试**

断言 `mode:null` 解析为 `LoadVideo(..., mode="")`；非字符串 mode 仍拒绝；`/ctl_unpair` 只对当前房间生效；收到 unpair 后清关联、固定日志、页面/播放/二维码状态和 PlayerController 调用均不变。

- [ ] **Step 2: 运行聚焦 RED**

Run: `gradlew :app:testDebugUnitTest --tests parser --tests session --tests diagnostics`。

Expected: 现有 nullMode 断言和缺失命令失败。

- [ ] **Step 3: 实现最小协议修复**

`optionalMode()` 对缺失或 JsonNull 返回 `null`，`loadVideo()` 继续用 `(mode ?: "")`；其他类型抛 `JsonParseException`。`ControllerUnpaired` 在 `handleCommand` 前必须特殊处理，不能被通用“合法命令置 true”逻辑重新覆盖。

- [ ] **Step 4: 运行 GREEN 与全 debug JVM**

Run: `:app:testDebugUnitTest --no-daemon --no-parallel`

Expected: 全 PASS。

---

### Task 7: Android 全局诊断日志

**Files:**
- Create: `airplayTV-android/app/src/main/java/com/airplay/tv/diagnostics/DiagnosticLogOverlay.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/feature/player/PlayerScreen.kt`
- Modify: `airplayTV-android/app/src/main/java/com/airplay/tv/app/AppNavigation.kt`
- Modify: `airplayTV-android/app/src/androidTest/java/com/airplay/tv/app/AppNavigationTest.kt`

- [ ] **Step 1: 写 Compose RED 测试**

Pairing 页 `diagnosticVisible=true` 时日志可见；隐藏时节点不存在；连接状态仍在右下角；Player 页日志与进度条 testTag 边界不相交；日志自动隐藏仍由既有 ViewModel 测试覆盖。

- [ ] **Step 2: 提取可复用 Overlay**

从 `PlayerScreen` 移出私有组件。`AppNavigation` 在 `NavHost` 上方统一渲染；Pairing 左下、Player 使用现有 HUD 位置。不得复制两份日志 UI。

- [ ] **Step 3: Android UI 验证**

Run: `:app:compileDebugAndroidTestKotlin :app:connectedDebugAndroidTest --no-daemon --no-parallel`

Expected: instrumentation 全 PASS。

---

### Task 8: 三端全量回归与真实闭环

**Files:**
- Modify only if verification exposes a directly related defect.

- [ ] **Step 1: API 全量与 race**

Run: `go test ./... -count=1`

Run: `go test -race ./controller -count=1`

- [ ] **Step 2: H5 全量**

Run: `node --test tests/*.mjs`

Run: `npm run build`

- [ ] **Step 3: Android 强制全量**

Run debug/release JVM、lint、assemble、AndroidTest compile、connected tests，使用 `--rerun-tasks --no-daemon --no-parallel`。

- [ ] **Step 4: 本地三端协议集成测试**

启动本地 API，使用两个真实 WebSocket 客户端验证 Presence ACK、控制 ACK、无 TV 失败、双 H5 最后离线才 unpair、15 秒租约。测试使用随机临时房间且不写入报告。

- [ ] **Step 5: 按部署顺序验收**

获得部署授权后依次发布 API、H5、APK；每一步验证兼容性再继续。部署不是本计划默认授权的一部分。

- [ ] **Step 6: 真实设备验收**

验证扫码配对、默认空 mode 视频出帧、无房间不跳页、双手机离线语义、断网重连、扫码页日志及日志自动消失。

- [ ] **Step 7: 安全与 Git 边界**

检查三个仓库 `diff --check`、BOM、敏感日志、暂存区和工作区状态；确认没有 add/commit/push。
