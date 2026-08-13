# AirPlayTV 投屏指令可靠发送与关联状态修复设计

## 背景

手机扫码后选择视频，H5 会进入控制器页面，但 TV 仍停留在扫码页，且没有收到播放指令的诊断日志。

线上 WebSocket、视频解析 API、HLS 地址和 Android ExoPlayer 已分别通过真实请求验证；使用当前 Android APK、真实二维码房间号和 H5 格式的 `/ctl_load_Video` 消息，在 Android TV 模拟器上能够正常播放。故障发生在 H5 的发送边界。

## 根因

`airplayTV-vue/src/helpers/websocket.js` 的 `send()` 在 WebSocket 处于 `CONNECTING`、`CLOSING` 或 `CLOSED` 时直接丢弃消息，且不返回发送结果。视频列表随后无条件跳转 `/control`，导致 UI 显示已经进入控制器，但播放指令可能从未发送。

此外，Android 当前的“已连接”仅表示 TV 到 WebSocket 服务端的传输连接已建立，不代表手机控制器已经关联；扫码页也未显示 `roomId`。

## 目标

- 播放指令不得因 WebSocket 尚未就绪而静默丢失。
- H5 仅在指令实际写入已打开的 WebSocket 后进入控制器页面。
- 发送失败必须保留当前页面并给出明确错误提示。
- TV 区分“WebSocket 服务在线”和“手机已关联”两种状态。
- TV 扫码页显示完整房间号，方便人工核对 H5 与 TV 是否处于同一房间。
- 关联、播放和控制命令到达 TV 后继续触发现有临时诊断日志。
- 不修改视频解析、媒体地址校验或 ExoPlayer 加载实现。

## H5 可靠发送

### WebSocket API

将控制指令发送改为 Promise 接口：

- WebSocket 已打开：立即发送并 resolve。
- WebSocket 正在连接：等待 `open` 后发送。
- WebSocket 已关闭：触发一次重连，等待 `open` 后发送。
- 5 秒内未打开：reject，不发送过期命令。
- 同一个调用只允许发送一次；不得在 `open`、超时和重连回调间重复发送。
- 等待结束后必须释放事件监听器和超时任务。

不实现无限持久队列，避免页面切换或长时间离线后发送过期的播放命令。

### 视频投屏入口

视频和音频投屏入口统一执行：

1. 读取并校验房间号和播放参数。
2. 显示短暂的发送中状态，阻止同一入口重复点击。
3. `await sendControl(...)`。
4. 成功后进入 `/control`。
5. 失败时停留原页面、解除锁并提示“连接电视失败，请重试”。

控制器页面上的播放、暂停、音量等命令仍需检查连接状态；发送失败时提示用户，不更新本地按钮状态。

## 手机关联协议

新增控制事件 `/ctl_pair`，沿用现有 `send-to-group` 数据结构，不修改服务端：

```json
{
  "event": "/ctl_pair",
  "group": "<tv-room-id>",
  "from": "<h5-client-id>"
}
```

H5 在以下时机向已保存的 TV 房间发送关联事件：

- 扫码页保存 `room_id` 后；
- H5 WebSocket 重新打开后，如果本地仍有 TV 房间号。

Android 的消息解析器新增 `ControlCommand.ControllerPaired`。收到 `/ctl_pair` 或任一合法控制命令时，将手机关联状态设为 true；WebSocket 进入 `Reconnecting` 或 `Closed` 时重置为 false，重新连接后等待 H5 再次发送 `/ctl_pair`。

`from` 只用于协议兼容和后续诊断，不在 TV 界面展示或写入日志。

## TV 状态与扫码页

`SessionUiState` 新增 `controllerConnected`：

- WebSocket `Connecting`：连接中
- WebSocket `Reconnecting`：重连中
- WebSocket `Closed`：已断开
- WebSocket `Connected` 且手机未关联：等待连接
- WebSocket `Connected` 且手机已关联：已连接

扫码页在二维码附近显示：

```text
房间号：f8069d155aee4e168c6c548f33f72110
```

房间号使用等宽字体、完整显示，不截断，不提供复制或编辑操作。

播放器二维码浮层复用同一房间信息，并继续保持“不切页、不清空、不暂停”的行为。

## 日志

- `/ctl_pair`：记录固定短文案“手机控制器已关联”。
- `/ctl_load_Video`：继续记录“收到加载视频指令”。
- 其他控制命令：沿用现有固定短文案。
- 不记录原始 WebSocket 消息、视频参数、房间号或媒体 URL。
- 日志仍在最后一条事件后 5 秒隐藏，新事件重置计时。

## 错误处理

- H5 WebSocket 超时或发送异常：不跳转控制器页，显示可重试提示。
- 房间号为空：不发送，提示重新扫码。
- Android 收到非法 `/ctl_pair` 或错误房间的事件：由现有房间校验直接忽略。
- H5 发送成功但 TV 已离线：服务端当前无目标确认机制，本次不引入服务端 ACK；TV 房间号展示和重新发送用于排查及恢复。

## 测试

### H5

- OPEN 状态只发送一次并成功 resolve。
- CONNECTING 状态在 open 后发送。
- CLOSED 状态触发重连并在 open 后发送。
- 超时 reject，清理监听器，不发送。
- 视频入口只有发送成功才进入 `/control`。
- 发送失败不跳转并解除重复提交锁。
- 扫码保存房间号后发送 `/ctl_pair`。
- WebSocket 重连后向已保存房间重发 `/ctl_pair`。

### Android

- 解析正确房间的 `/ctl_pair`。
- 拒绝错误房间和非法字段。
- 初始服务连接成功时显示“等待连接”。
- 收到关联事件后显示“已连接”。
- 重连/断开时清除手机关联状态。
- 扫码页和播放器二维码浮层显示完整房间号。
- `/ctl_pair` 触发固定诊断日志并遵守 5 秒隐藏规则。
- 既有 LoadVideo、二维码浮层、遥控器和播放器测试继续通过。

## 交付与验收

- 分别构建 H5 和 Android APK。
- 使用真实随机房间完成：扫码、关联、选择视频、TV 切换播放器、视频出帧。
- 在 WebSocket 尚未打开时立即点击视频，验证指令不会丢失。
- 模拟断线重连后再次投屏，验证关联状态和指令发送恢复。
- 不执行 Git 暂存、提交或推送。
