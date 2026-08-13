# Android TV 控制与播放信息层修复设计

## 1. 目标与范围

本次仅修改 `airplayTV-android`，修复以下问题：

- Web 控制器的音量和静音指令在电视端正确生效。
- `/ctl_qrCode` 在当前播放器之上显示二维码浮层，不切换页面、不清空媒体、不暂停播放。
- 诊断日志在最后一条新日志到达 5 秒后隐藏；新日志到达时重新计时。
- WebSocket 状态始终显示，文案精简为“连接中 / 已连接 / 重连中 / 已断开”。
- 播放状态使用图标，不再显示“播放中 / 已暂停”文字。
- 播放信息或诊断日志可见时显示当前最终播放地址。
- 采用 A“分层 HUD”布局，避免日志与进度条重叠。
- 电视遥控器支持暂停、播放、快进和快退。

不修改 Vue WebSocket 事件名、Go 转发协议、视频解析 API 或后台播放策略。

## 2. 已确认根因

### 2.1 音量与静音

`Media3PlayerController` 使用 `AudioManager.adjustStreamVolume()` 和
`setStreamVolume()`，但 Manifest 未声明 `android.permission.MODIFY_AUDIO_SETTINGS`。
控制指令解析和 ViewModel 分发链路已经存在，因此修复点在 Android 权限声明和现有音量实现的回归验证。

### 2.2 二维码停止播放

`ControlCommand.ShowQrCode` 当前调用 `showPairingPage()`。该方法会：

- 取消解析任务；
- 清空当前视频和剧集上下文；
- 调用 `playerController.clear()`；
- 导航到配对页面。

二维码展示与退出播放被错误绑定。二维码应是播放器页面上的独立 UI 状态。

### 2.3 日志常驻与布局重叠

旧诊断设计把日志定义为全局常驻右下角面板，与本轮需求冲突。播放信息层又把状态文字、时间和进度条放在同一行，日志面板会占用相同区域。

### 2.4 遥控器无效

`PlayerView.useController=false`，`MainActivity`、Compose 和 ViewModel 均没有电视遥控器媒体键或方向键入口，因此遥控器事件没有到达 `PlayerController`。

## 3. 总体架构

采用状态驱动的独立浮层设计。`SessionViewModel` 是唯一业务状态和播放器控制入口，Activity 只把遥控器事件转换成语义化控制，不直接操作 Media3 Player。

```text
WebSocket command ─┐
                   ├─> SessionViewModel ─> PlayerController
TV remote key ─────┘          │
                              └─> SessionUiState
                                   ├─ ConnectionStatus（常驻）
                                   ├─ DiagnosticOverlay（5 秒）
                                   ├─ PlayerInfoOverlay（5 秒）
                                   └─ QrOverlay（显式开关）
```

## 4. 状态模型

`SessionUiState` 增加：

```kotlin
data class SessionUiState(
    // existing fields
    val playbackUrl: String = "",
    val qrVisible: Boolean = false,
    val diagnosticLogs: List<DiagnosticLogEntry> = emptyList(),
    val diagnosticVisible: Boolean = false,
)
```

状态规则：

- 视频解析成功且确认由当前 generation 接受后，写入 `playbackUrl`。
- 新视频开始加载、返回配对页或播放清理时清空旧地址，防止显示陈旧 URL。
- `/ctl_qrCode` 将 `qrVisible` 切换为 `true`；重复命令保持显示，不作为关闭操作。
- 遥控器返回键优先关闭二维码，其次关闭播放信息层，最后执行现有返回配对逻辑。
- 新诊断日志追加后设置 `diagnosticVisible=true`，重启独立的 5 秒计时器。
- 播放控制继续使用现有 `infoVisible` 和独立 5 秒计时器；诊断日志计时不得延长播放信息层计时，反之亦然。

## 5. UI 与布局

采用已确认的 A“分层 HUD”。

### 5.1 常驻连接状态

- 固定在右上角电视安全区。
- 始终显示，不受 `infoVisible` 或 `diagnosticVisible` 控制。
- 颜色点继续表达状态，文案精简：
  - `Connecting`：连接中
  - `Connected`：已连接
  - `Reconnecting`：重连中
  - `Closed`：已断开

### 5.2 诊断日志层

- 位于左上角安全区，不占用底部进度区域。
- 只在 `diagnosticVisible && diagnosticLogs.isNotEmpty()` 时渲染。
- 最多保留最近 20 条，最新记录在底部。
- 最后一条新日志到达 5 秒后整体隐藏；后续新日志到达时重新显示并重新计时。
- 保持不可点击、不可聚焦，不参与 D-pad 焦点遍历。
- 日志继续执行现有脱敏约束，不显示完整 URL、Header、原始 JSON 或源密钥。

### 5.3 底部播放信息层

使用两行布局：

- 第一行：标题、剧集、最终播放地址；地址单行省略，但保留完整语义文本供 UI 测试检查。
- 第二行：播放/暂停图标、当前时间、完整宽度进度条、总时长。

播放状态图标语义：

- `isPlaying=true`：暂停图标，表示当前正在播放且按键动作是暂停。
- `isPlaying=false`：播放图标，表示当前已暂停且按键动作是播放。

图标提供 `contentDescription`，保证测试和无障碍语义明确，但不渲染状态文字。

### 5.4 二维码浮层

- 仅在播放器页且 `qrVisible=true` 时覆盖在视频上方。
- 背景使用半透明遮罩，中间显示二维码和简短说明。
- 浮层不修改 `SessionPage`，不调用 `clear()`、`pause()` 或导航。
- 播放器继续播放；浮层打开前后的 `isPlaying` 与媒体位置自然延续。
- 遥控器返回键关闭浮层。
- 配对页仍沿用现有二维码页面；不重复叠加浮层。

## 6. 遥控器输入

新增纯函数将 `KeyEvent` 转换为语义控制，Activity 只在按键按下且播放器页有效时消费匹配事件：

| 遥控器键 | 行为 |
|---|---|
| `KEYCODE_MEDIA_PLAY` | 播放 |
| `KEYCODE_MEDIA_PAUSE` | 暂停 |
| `KEYCODE_MEDIA_PLAY_PAUSE`、DPAD 中键、Enter | 根据当前 `isPlaying` 切换播放/暂停 |
| `KEYCODE_MEDIA_FAST_FORWARD`、DPAD 右键 | 快进 15 秒 |
| `KEYCODE_MEDIA_REWIND`、DPAD 左键 | 快退 15 秒 |
| Back | 优先关闭二维码，再沿用现有返回层级 |

处理约束：

- 只消费已映射的 `ACTION_DOWN`，忽略 `ACTION_UP`，避免一次按键执行两次。
- 长按重复事件沿用系统 `repeatCount`：播放/暂停只处理首次；快进/快退允许重复，以支持连续寻址。
- 配对页不消费方向键和媒体键，保持系统默认行为。
- Activity 调用 ViewModel 公共语义方法；禁止直接访问 `playerController`。

本轮不引入 `MediaSessionService`，避免扩大到后台播放、音频焦点和系统通知生命周期。

## 7. 音量与静音

- Manifest 增加普通权限：

```xml
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```

- 不申请运行时权限。
- 保留现有“记住最后一个非零音量并恢复”的静音切换算法。
- 补充边界测试：最大音量为 0、记录音量超出当前最大值、初始音量为 0 时至少尝试恢复到 1（设备最大值允许时）。
- 音量加减后更新最后可听音量，静音后再次加音量的设备行为以系统 `AudioManager` 为准。

## 8. 数据流与并发

### 8.1 二维码命令

```text
/ctl_qrCode
  -> SocketMessageParser
  -> ControlCommand.ShowQrCode
  -> SessionViewModel.showQrOverlay()
  -> uiState.qrVisible = true
  -> AppNavigation 在 PlayerScreen 上层渲染二维码
```

播放器、解析 generation、当前剧集和 MediaItem 均不改变。

### 8.2 日志自动隐藏

```text
new diagnostic event
  -> append bounded log
  -> diagnosticRevision++
  -> cancel previous diagnosticOverlayJob
  -> diagnosticVisible = true
  -> delay(5s)
  -> revision still current ? hide : ignore stale timer
```

诊断日志和播放信息使用不同的 Job 与 revision，避免互相覆盖。

### 8.3 播放地址

只在 `VideoResolver.resolve()` 成功、generation/前台/待加载身份检查通过后，将 `resolved.url` 同时交给 `playerController.load()` 并写入 UI 状态。失败、取消或过期解析不得覆盖当前地址。

## 9. 错误与安全处理

- 日志面板不得记录或展示完整播放地址；播放信息层按用户明确要求展示当前地址。
- 播放地址只在本机电视 UI 瞬时展示，不持久化、不上传。
- 解析失败时清空未确认地址，继续显示固定友好错误，不显示异常正文。
- 二维码生成失败时显示固定提示，不影响后台视频播放。
- 遥控器未知键返回 `false`，交由系统处理。
- 权限缺失不使用异常吞噬兜底；Manifest 声明是根因修复。

## 10. 测试与验收

严格执行 RED-GREEN：

1. Manifest 测试先证明缺少 `MODIFY_AUDIO_SETTINGS`。
2. Session 测试先证明二维码命令会清空播放器，再改为只显示浮层。
3. Session 测试覆盖二维码返回键优先级和播放状态不变。
4. 日志测试覆盖 5 秒隐藏、新日志重置计时、与播放信息计时相互独立。
5. Session 测试覆盖最终播放地址写入、过期解析不覆盖、失败/清理时清空。
6. 纯 JVM 按键映射测试覆盖播放、暂停、切换、快进、快退、ACTION_UP、长按策略和未知键。
7. Compose 测试覆盖：
   - WebSocket 状态在播放器信息层隐藏时仍存在；
   - 状态文案精简；
   - 日志位于独立节点并按状态显隐；
   - 播放状态只有图标语义，不存在“播放中/已暂停”文字；
   - 地址与进度条同时显示；
   - 二维码浮层在播放器页显示。
8. 运行 Debug/Release 单测、Lint、AndroidTest 编译和 APK 构建。
9. 如有电视设备，真机验证遥控器、系统音量、二维码播放连续性和 5 秒日志消失；无设备时明确保留真机验收边界。

## 11. 非目标

- 不修改 Web 控制器 UI 或增加电视到控制器的状态回执。
- 不引入 MediaSessionService、后台播放通知或音频焦点策略。
- 不修改 WebSocket 协议或 Go 服务。
- 不增加日志上传、日志持久化、筛选或复制功能。
- 不更换播放器内核。
