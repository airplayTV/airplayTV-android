# AirPlayTV 控制器在线状态与投递 ACK 设计

## 1. 背景与根因

当前故障由三个独立缺口叠加产生：

1. H5 未配置 `sourceSecret` 时会发送 `"mode": null`，Android `SocketMessageParser.optionalMode()` 将其判为非法字段并丢弃整条 `/ctl_load_Video`。配对命令仍能成功，因此 TV 显示“已连接”，但不会播放且没有控制命令日志。
2. `diagnosticLogs` 已在 `SessionViewModel` 中生成，但日志组件只由 `PlayerScreen` 渲染，扫码页无法显示任何诊断日志。
3. `controllerConnected` 只在收到配对或控制命令时置为 `true`，没有权威的手机在线状态、断线通知或租约超时，因此手机关闭后 TV 仍保持“已连接”。

线上 WebSocket 已使用随机诊断房间验证：`join-group -> send-to-group -> /ctl_pair` 广播链路正常。基础转发不是本次根因。

## 2. 目标

- H5 只有在服务端确认 TV 房间存在且控制消息已加入发送队列后，才进入控制器页面。
- 服务端权威维护手机控制器与 TV 房间的在线关系。
- 手机正常关闭时立即解除关联；异常断网或进程终止最多 15 秒解除关联。
- 支持同一 TV 房间同时存在多个手机控制器；关闭其中一个不得误报全部离线。
- Android 兼容 `mode:null`，恢复真实视频加载。
- 扫码页和播放器页都能显示自动消失的脱敏诊断日志。
- 保持旧 H5 的基础 `send-to-group` 协议可用，按 API、H5、APK 顺序平滑部署。

## 3. 非目标

- ACK 不表示 Android 已解析命令、媒体地址已解析或视频已经出帧。
- 不引入持久化控制器会话、Redis 或跨进程共享状态。
- 不修改视频来源 API、媒体 URL 校验、ExoPlayer 加载策略或播放器控制语义。
- 不在日志、ACK 或错误提示中暴露房间号、客户端标识、原始控制消息或媒体 URL。

## 4. 协议设计

### 4.1 控制器 Presence

H5 新增 WebSocket 事件：

```json
{
  "event": "controller-presence",
  "data": {
    "group": "<tv-room>",
    "request_id": "<random-id>"
  }
}
```

服务端响应发送该请求的 WebSocket 连接：

```json
{
  "event": "controller-presence-ack",
  "data": {
    "request_id": "<same-random-id>",
    "accepted": true,
    "tv_online": true
  }
}
```

语义：

- `accepted=true` 仅在 `group` 非空、`request_id` 合法且目标房间至少存在一个 WebSocket 客户端时返回。
- 首次成功 Presence 将当前真实 WebSocket 连接绑定到目标 TV 房间，并向 TV 广播一次：

```json
{
  "event": "/ctl_pair",
  "group": "<tv-room>"
}
```

- 同一连接、同一房间的后续 Presence 只刷新租约，不重复向 TV 写配对日志。
- 同一连接切换房间时，先从旧房间解除，再绑定新房间；若旧房间已没有其他控制器，则向旧房间发送 `/ctl_unpair`。
- H5 在首次扫码成功、WebSocket 重连成功时立即发送 Presence，此后每 5 秒续约。
- 服务端租约有效期固定为 15 秒；每 5 秒扫描一次过期连接。

### 4.2 控制器断开

服务端同时处理两类断开：

- WebSocket `EventClose`：立即移除真实 socket ID 的 Presence。
- 租约超过 15 秒：移除未续约 Presence。

仅当目标 TV 房间的最后一个控制器被移除时，向 TV 广播：

```json
{
  "event": "/ctl_unpair",
  "group": "<tv-room>"
}
```

Android 收到后只执行：

- `controllerConnected=false`；
- 追加固定日志 `CTL / 手机控制器已断开`；
- 保持当前页面、媒体实例和播放状态不变。

### 4.3 控制消息投递 ACK

新版 H5 的 `send-to-group` 内层控制上下文增加 `request_id`：

```json
{
  "event": "send-to-group",
  "data": {
    "event": "/ctl_load_Video",
    "group": "<tv-room>",
    "request_id": "<random-id>",
    "vid": "...",
    "pid": "...",
    "source": "...",
    "mode": ""
  }
}
```

服务端在检查目标房间后向发送者回复：

```json
{
  "event": "send-to-group-ack",
  "data": {
    "request_id": "<same-random-id>",
    "accepted": true,
    "recipient_count": 1
  }
}
```

处理规则：

- 服务端必须先取得目标房间客户端快照。
- `recipient_count == 0` 时不得报告成功，返回 `accepted=false`。
- `recipient_count > 0` 时将控制上下文加入现有组发送队列，再返回 `accepted=true`。
- `recipient_count` 只用于 H5 判断成功，不显示到用户界面或 Android 日志。
- 旧 H5 未携带 `request_id` 时，服务端继续执行原有广播，不要求 ACK，保证兼容。
- Android 忽略未知的 `request_id` 字段。

ACK 的精确定义是“目标 TV 房间存在，控制消息已加入服务端发送队列”，不承诺客户端已处理或播放器已经出帧。

## 5. H5 状态与错误处理

### 5.1 ACK 等待器

H5 在发送前注册以 `request_id` 为键的一次性等待器：

- 固定超时 5 秒；
- ACK、WebSocket `close`、`error` 或超时均结算一次；
- 结算后删除事件监听器、定时器和 Map 项；
- 不建立持久消息队列；
- WebSocket 重连后不得复用旧连接的待确认请求。

### 5.2 页面行为

- 扫码：Presence ACK 成功后保存关联状态并进入首页；失败保留扫码页，显示 `电视未连接，请重新扫码`。
- 选片：控制 ACK 成功后进入 `/control`；失败保留选片页并显示相同提示。
- 控制器按钮：ACK 成功后才更新本地播放、暂停或全屏状态；失败保持本地状态。
- WebSocket 重开：重新发送 Presence，并启动新连接代次的 5 秒心跳。
- 页面隐藏或卸载：停止本地心跳；真实断线和服务端租约共同负责最终清理。

### 5.3 Payload 规范化

所有 H5 视频加载入口必须在统一 helper 中规范化：

```js
{
  ...context,
  vid: String(context.vid ?? ''),
  pid: String(context.pid ?? ''),
  source: String(context.source ?? ''),
  mode: String(context.mode ?? ''),
}
```

空 `vid`、`pid` 或 `source` 在发送前失败，不进入 ACK 等待。

## 6. 服务端状态模型

新增进程内 `ControllerPresenceRegistry`：

```go
type ControllerPresence struct {
    SocketID string
    Room     string
    LastSeen time.Time
}
```

内部索引：

- `bySocket map[string]ControllerPresence`
- `byRoom map[string]map[string]struct{}`
- 所有读写由同一 `sync.Mutex` 保护。

必须提供以下原子操作：

- `Touch(socketID, room, now) (firstForSocketRoom bool, oldRoomBecameEmpty string)`
- `Remove(socketID) (roomBecameEmpty string)`
- `ExpireBefore(cutoff) []string`，返回去重后的、已没有控制器的房间。

任何广播均在释放 Registry 锁后执行，避免网络写入阻塞状态锁。

服务端日志只允许固定事件名和计数，不记录 socket ID、房间号或控制 payload。

## 7. Android 行为

### 7.1 Parser 兼容

- `mode` 缺失或 JSON `null` 均解析为 `""`。
- `mode` 为数字、布尔值、数组或对象时仍拒绝。
- `vid`、`pid`、`source` 继续要求非空字符串且不超过既有限制。

### 7.2 控制命令

新增：

```kotlin
data object ControllerUnpaired : ControlCommand
```

映射 `/ctl_unpair`。收到后：

- 清除 `controllerConnected`；
- 不触发播放器动作；
- 不改变 `page`、`qrVisible` 或媒体状态；
- 生成固定诊断日志。

现有连接 generation 过滤规则保持不变，旧连接命令不得影响新连接。

### 7.3 全局日志

将诊断日志 Composable 从 `PlayerScreen` 的私有实现提取为可复用组件，由 `AppNavigation` 统一渲染：

- Pairing 页：左下角，避开右下角连接状态和中间二维码。
- Player 页：保持当前 HUD 区域和进度条无重叠。
- `diagnosticVisible=false` 时完全不占布局空间。
- 保持当前自动消失时长和有界日志条数。

解析拒绝日志只显示固定原因类别，例如 `消息格式无效`，不得包含字段值或原始消息。

## 8. 并发与失败语义

- 同一 H5 连接重复 Presence：幂等刷新。
- 同一房间多个 H5 连接：服务端维护集合，只有集合变空才 `/ctl_unpair`。
- 旧连接迟到 Close：按真实 socket ID 删除，不得移除新连接租约。
- Presence ACK 丢失：H5 本次显示失败，但后续心跳可恢复；服务端 Touch 必须幂等。
- 控制 ACK 丢失：H5不得跳页，可能存在 TV 已收到消息但 H5 超时的保守失败；用户重试可能再次发送同一控制。服务端暂不做 request ID 持久去重。
- API 进程重启：Presence 内存清空；H5 最多 5 秒后重新续约并恢复，TV 可能在这段时间保留旧状态，直到收到新的 pair/unpair 或自身 WebSocket 重连。

## 9. 测试与验收

### 9.1 Go

- Registry Touch、切房、Remove、Expire、多控制器和并发测试。
- Presence：无 TV、首次配对、重复心跳、最后控制器离线。
- ACK：无房间失败、存在房间成功、旧请求无 request ID 兼容。
- `go test ./...` 与 race detector 覆盖 Registry 包。

### 9.2 H5

- ACK 成功、失败、超时、close/error、监听器清理、相同 request ID 不串包。
- Presence 首次、5 秒续约、页面卸载停止、重连重启代次。
- `mode:null` 规范化、空必要字段拒绝。
- 发送失败不导航，ACK 前不导航。
- Node 全量测试和 Vite build。

### 9.3 Android

- `mode:null` RED/GREEN 回归。
- `/ctl_unpair` 解析、状态、固定日志、无播放器副作用。
- Pairing 页显示诊断日志；Player 页布局不回归。
- debug/release JVM、lint、assemble 与 connected instrumentation。

### 9.4 真实端到端

按部署顺序使用真实服务验证：

1. TV 冷启动显示“等待连接”及完整房间号。
2. H5 扫码后 Presence ACK 成功，TV 显示“已连接”和关联日志。
3. 选择默认无 `sourceSecret` 的视频，TV 显示加载日志、进入播放器并出帧。
4. 不存在的房间返回失败 ACK，H5 不进入控制器。
5. 同房间打开两个 H5，关闭一个 TV 仍显示已连接；关闭最后一个后正常关闭立即、异常关闭最多 15 秒显示“等待连接”。
6. 断网恢复后 H5 Presence 自动恢复。
7. 扫码页与播放器页日志均可见、自动消失且不泄漏敏感数据。

## 10. 部署与回滚

部署顺序固定为：

1. API；
2. H5；
3. Android APK。

API 首先保持旧 H5 无 `request_id` 的广播兼容。H5 启用 ACK 后依赖新版 API，不能先于 API 部署。Android 最后发布，可同时接受旧控制消息和新增 `/ctl_unpair`。

回滚顺序与部署相反。若只回滚 H5，API 继续兼容旧消息；若回滚 API，必须同时回滚依赖 ACK 的新 H5。

## 11. Git 与编码边界

- 保留三个仓库已有未提交改动，不覆盖无关文件。
- 不执行 `git add`、`git commit` 或 `git push`。
- 所有新增和修改文件使用 UTF-8 无 BOM。
