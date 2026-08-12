# Android TV WebSocket 播放修复与实时诊断设计

## 1. 背景与目标

Android TV 已能显示配对二维码，手机 H5 扫码后也能保存房间并进入控制页，但选择视频后 TV 仍停留在二维码页。

本次目标：

- 修复 H5 投屏消息被 Android 静默丢弃的问题。
- 在二维码页和播放器页右下角常驻显示 WebSocket 与播放链路诊断日志。
- 保持现有 Vue H5 与 Go WebSocket 协议不变。
- 日志不可泄露视频源密钥或完整媒体 URL，也不抢占 TV 遥控器焦点。

## 2. 根因证据

线上链路已直接复现：

1. Android 使用的 `wss://airplay-api.artools.cc/api/wss` 可正常连接。
2. TV 客户端发送 `join-group` 后收到成功确认。
3. H5 的 `send-to-group` 会被 Go 服务端展开为根级控制消息并正确转发。
4. 线上 H5 在未配置 `sourceSecret` 时会发送 `"mode": null`。
5. Android `SocketMessageParser.optionalMode()` 当前把 `null` 判为非法 JSON 字段，导致整条 `/ctl_load_Video` 返回 `null`，而 `OkHttpSocketClient` 对该结果静默忽略。

因此，故障不在二维码、房间号、WebSocket 地址或服务端转发，而在 Android 对 H5 合法空密钥表示的兼容性处理。

## 3. 协议兼容修复

`/ctl_load_Video` 的 `mode` 采用以下规范：

- 字符串：保留原值并传给视频 API 请求头。
- 字段缺失：规范化为空字符串。
- JSON `null`：规范化为空字符串。
- 其他 JSON 类型：拒绝并记录类型错误。

`vid`、`pid`、`source` 仍要求非空字符串；房间号必须匹配当前 TV 会话。

不修改 Vue 或 Go，不改变事件名、入组消息及根级控制消息结构。

## 4. 结构化诊断模型

`SocketClient` 增加只读诊断事件流，诊断事件与控制命令流彼此独立，避免 UI 日志消费影响命令处理。

事件至少覆盖：

- WebSocket：连接、入组成功、断线、重连计划。
- 入站消息：收到的事件名和脱敏摘要。
- 解析结果：接受、忽略、拒绝及稳定原因码。
- 会话处理：进入播放器、开始解析视频、解析失败。
- 播放器：媒体已加载、播放错误。

解析器返回详细结果模型：

- `Accepted(command)`：生成可执行控制命令。
- `Ignored(reason)`：未知事件、其他房间或无需执行的消息。
- `Rejected(reason)`：JSON 损坏、必填字段缺失或字段类型错误。

原有 `parse()` 可作为兼容入口委托详细解析，避免无关调用方大面积变化。

## 5. 日志状态与安全

`SessionViewModel` 收集诊断事件，映射为 UI 日志条目，并维护固定大小环形窗口：

- 最多保留最近 20 条。
- 新日志到达时淘汰最旧条目。
- 每条包含本地时间、级别、阶段和短消息。
- ViewModel 清理后停止收集，不持久化日志。

脱敏规则：

- `mode` 非空时只显示 `<redacted>`，为空时显示 `<empty>`。
- 不显示完整媒体 URL、请求 Header 或原始 WebSocket JSON。
- `vid`、`pid`、`source` 仅显示截断后的可诊断摘要。
- 错误只显示固定分类，不显示可能包含 URL/密钥的异常正文。

## 6. TV 日志面板

日志面板位于应用全局导航层之上，因此二维码页和播放器页均可见。

视觉与交互：

- 右下角固定，遵守 TV 安全区。
- 深色半透明背景、轻量边框、等宽小字体。
- 默认展示最近日志，最新记录位于底部。
- 不可聚焦、不可点击，不参与 D-pad 焦点遍历。
- 限制宽高和每行字符数，避免遮挡二维码主体及播放器主要信息。
- 播放页仍显示日志，不因信息层显隐而消失。

示例：

```text
20:31:08 WS   已连接并加入房间
20:31:16 RX   /ctl_load_Video vid=150758 pid=0-0 mode=<empty>
20:31:16 OK   已接受，进入播放器
20:31:17 API  正在解析视频地址
20:31:18 PLAY HLS 已加载
```

## 7. 数据流

```text
H5 send-to-group
  -> Go 转发根级控制 JSON
  -> OkHttpSocketClient.onMessage
  -> 生成脱敏 RX 诊断事件
  -> SocketMessageParser.parseDetailed
       -> Accepted: 发送命令 + OK 诊断事件
       -> Ignored: 仅发送 IGNORE 诊断事件
       -> Rejected: 仅发送 DROP 诊断事件
  -> SessionViewModel 处理 LoadVideo
  -> 立即切换 Player/loading
  -> VideoResolver / Media3
  -> 追加 API / PLAY 诊断事件
  -> 全局 WebSocketLogOverlay 实时渲染最近 20 条
```

## 8. 错误处理

- `mode: null` 不再视为错误。
- 非法消息不会崩溃或进入播放器，而是在面板显示固定拒绝原因。
- 诊断事件缓冲区有界；高频消息时保留最新事件。
- 日志渲染异常不得影响命令流和播放器。
- 断线重连沿用现有退避策略，并在面板显示连接状态变化。

## 9. 测试与验收

严格采用 RED-GREEN：

1. `mode:null` 的 parser 测试先证明当前返回空，再修复为 `LoadVideo(mode="")`。
2. 详细解析结果覆盖接受、房间不匹配、未知事件、字段缺失、字段类型错误和非法 JSON。
3. 日志格式器覆盖 `mode`/URL 脱敏与字段截断。
4. 日志窗口覆盖 20 条上限和淘汰最旧记录。
5. Socket 测试覆盖收到原始消息后先产生 RX，再产生 Accepted/Rejected 诊断事件，且不影响命令发送。
6. Session 测试覆盖收到 `mode:null` 对应 Load 后立即进入 Player/loading。
7. Compose 测试覆盖二维码页与播放器页都存在日志面板，且面板不可聚焦。
8. 最终运行 Debug/Release 单测、Lint、APK 组装与 AndroidTest 编译。

真机验收：扫码后选择视频，TV 日志依次出现 RX、OK、API、PLAY，页面进入播放器并开始播放；若失败，面板必须明确显示故障停在哪一层。

## 10. 非目标

- 不修改 `airplayTV-vue`。
- 不修改 Go WebSocket 服务。
- 不增加日志上传、文件持久化或远程遥测。
- 不显示完整原始消息、视频源密钥或完整媒体 URL。
- 不增加可交互的日志筛选、复制、翻页功能。
