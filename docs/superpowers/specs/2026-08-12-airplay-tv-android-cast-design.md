# AirPlay TV Android 扫码投屏应用设计

## 1. 目标与范围

将 `airplayTV-android` 重写为原生 Android TV 扫码投屏应用。应用冷启动后默认显示二维码，手机扫描二维码后继续使用现有 `airplayTV-vue` H5 页面选择视频和发送控制命令，Android TV 负责解析真实播放地址、播放视频并执行远程控制。

本次不修改 `airplayTV-vue`，也不修改现有 Go WebSocket 协议。

应用主流程：

```text
启动 → 二维码等待 → 接收投屏 → 全屏播放 → 远程控制或返回二维码
```

本次删除 Android 项目中与目标无关的首页、搜索、设置、收藏、历史、本地数据库、视频列表和选集 UI。保留 Gradle Wrapper、根构建配置、包名、应用图标、签名及版本配置。

## 2. 技术方案

采用单 Activity、Navigation Compose、状态驱动 UI：

- Jetpack Compose：页面和 TV 横屏 UI。
- Navigation Compose：当前页面和后续功能页面路由。
- Media3 ExoPlayer：HLS、MP4 等视频播放。
- OkHttp WebSocket：连接、保活、入组和远程控制消息。
- Retrofit/OkHttp：调用视频源解析 API。
- ZXing Core：在 TV 本地生成二维码。
- ViewModel 与 Kotlin Flow：会话状态和单向数据流。

不采用播放前台 Service。应用按前台 TV 播放场景设计，不引入通知渠道和后台播放生命周期复杂度。

## 3. 工程结构

```text
com.airplay.tv
├── app/
│   ├── App.kt
│   ├── AppNavigation.kt
│   └── AppRoute.kt
├── core/
│   ├── config/
│   ├── network/
│   └── ui/
├── feature/
│   ├── pairing/
│   └── player/
├── protocol/
│   ├── ControlCommand.kt
│   ├── SocketMessage.kt
│   └── SocketClient.kt
└── session/
    ├── TvSession.kt
    ├── SessionState.kt
    └── SessionViewModel.kt
```

职责边界：

- `SocketClient` 只负责连接、重连、入组、收发和协议解析，不依赖 UI 或 Media3。
- `PlayerController` 只封装 Media3 播放器和播放命令，不依赖 Compose 页面。
- `SessionViewModel` 负责把 WebSocket 命令转换为导航状态、地址解析请求或播放器操作。
- 新页面放入独立 `feature/*`，通过 `AppRoute` 注册，不修改 WebSocket 和播放器底层。

初始路由为二维码页面。收到有效的 `/ctl_load_Video` 后进入播放器。`/ctl_qrCode` 或 TV 返回操作会停止并清空媒体，然后返回二维码页面。

## 4. 配置

固定使用以下线上地址：

```text
H5:        https://airplay-tv.pages.dev
API:       https://airplay-api.artools.cc
WebSocket: wss://airplay-api.artools.cc/api/wss
```

二维码内容：

```text
https://airplay-tv.pages.dev/join?room_id=<Client ID>&t=<timestamp>
```

地址集中定义在 `core/config`，业务代码不得散落硬编码。

## 5. 配对会话

每次应用冷启动生成新的随机 Client ID，本次进程生命周期内保持不变，不持久化：

1. 生成 Client ID。
2. 连接 WebSocket。
3. 连接成功后发送 `join-group`，组名为 Client ID。
4. 显示包含 Client ID 的 H5 加入链接二维码。
5. 临时断线重连后继续使用同一个 Client ID 并重新入组。
6. TV 冷启动后旧手机房间失效，用户需要重新扫码。

二维码页显示应用标识、二维码、三步说明和连接状态。状态包括正在连接、等待手机连接、连接中断并重试。

## 6. WebSocket 协议

保持 Vue 当前协议：

```json
{
  "event": "join-group",
  "data": {
    "group": "<Client ID>"
  }
}
```

投屏命令的有效载荷：

```json
{
  "event": "/ctl_load_Video",
  "group": "<Client ID>",
  "vid": "视频 ID",
  "pid": "分集 ID",
  "source": "视频源",
  "mode": "源访问凭证"
}
```

Android 仅接受当前房间消息和已知控制事件。字段类型、必填字段和字符串长度必须校验，未知事件直接忽略。协议解析模型不得直接暴露给 UI。

WebSocket 使用 OkHttp `pingInterval` 保活。断线重连退避为 `1s、2s、4s、8s、16s、30s`，30 秒封顶并加入随机抖动。每次连接成功后重新入组。

## 7. 视频地址解析

收到 `/ctl_load_Video` 后调用：

```http
GET /api/video/source?vid=...&pid=...&_source=...&_m3u8p=false
X-Source-Mode: <mode>
X-Client: airplayTV-android
```

规则：

- 新的加载命令到达时取消旧的地址解析任务，始终以最后一次投屏为准。
- 仅把 `http://` 或 `https://` 播放地址交给 Media3。
- 地址解析成功后导航到播放器并自动播放。
- 视频详情请求只用于标题、剧集和上一集/下一集，失败不得阻塞当前视频播放。
- `mode` 仅保存在内存中，不持久化、不写日志。

## 8. 播放控制

支持以下现有事件：

- `/ctl_load_Video`：解析并加载视频。
- `/ctl_play`：播放。
- `/ctl_pause`：暂停。
- `/ctl_forward`：前进 15 秒，限制到视频时长以内。
- `/ctl_back`：后退 15 秒，最小为 0。
- `/ctl_volume`：按系统媒体音量步长增减。
- `/ctl_mute`：记录静音前音量并在再次触发时恢复。
- `/ctl_fullscreen`：隐藏播放信息层，进入纯视频全屏状态。
- `/ctl_fullscreen_exit`：显示播放信息层，5 秒无操作后自动隐藏。
- `/ctl_info`：手动显示或隐藏播放信息层。
- `/ctl_qrCode`：停止并清空当前媒体，返回二维码页。
- `/ctl_prev`、`/ctl_next`：按视频详情 `links` 顺序切换上一集或下一集。
- `/ctl_history`：识别但不执行页面跳转，本次不提供历史页面。

播放器始终占满 TV 窗口，不进入 Android 画中画或窗口化模式。

视频自然播放结束后保留最后一帧，不自动切换下一集。

## 9. 页面设计

### 9.1 二维码等待页

- 16:9 深色背景。
- 左侧或视觉中心区域显示高对比度二维码。
- 右侧显示“扫码投屏”、操作说明和连接状态。
- 保证 720p 与 1080p 横屏安全区内不裁切。

### 9.2 播放页

- 视频始终铺满屏幕。
- 信息层位于底部渐变区域，显示标题、剧集、播放状态、进度和时长。
- 右上角显示手机连接状态。
- 全屏命令隐藏所有信息层，只保留视频。
- 信息层默认在远程操作后短暂显示，5 秒后自动隐藏。

### 9.3 TV 返回键

1. 信息层显示时，第一次返回只隐藏信息层。
2. 信息层已隐藏时，停止播放并返回二维码页。

## 10. 错误处理

- 地址解析失败：在播放器页面显示明确错误层，WebSocket 保持在线。
- Media3 播放错误：自动重试一次；再次失败后显示错误层。
- 错误状态下的新投屏命令可覆盖当前状态并重新播放。
- 快速连续投屏必须取消旧请求，防止旧响应覆盖新视频。
- Activity 销毁时统一关闭 WebSocket、取消协程并释放播放器。

## 11. 安全要求

- API 仅允许 `https://`，WebSocket 仅允许 `wss://`。
- 使用系统 TLS 证书和主机名校验，不信任自签名证书，不实现跳过校验逻辑。
- 播放 URL 仅接受 `http://` 或 `https://`。
- `mode`、完整播放 URL 和敏感响应头不得写入日志。
- Release 构建不得启用 OkHttp BODY 日志。
- 不申请存储、相机或麦克风权限。

## 12. 测试与验收

### 12.1 单元测试

- WebSocket 消息解析与非法消息拒绝。
- 全部控制事件到播放器命令的映射。
- 房间加入、断线重连和重新入组。
- 连续投屏时最后一次命令生效。
- 前进、后退和剧集索引边界。
- 二维码页、加载、播放和错误状态转换。
- API 正确传递 `X-Source-Mode`，日志不包含凭证。

### 12.2 Android UI 测试

- 默认启动进入二维码页。
- 收到投屏命令后进入播放器。
- `/ctl_qrCode` 返回二维码页并释放媒体。
- 返回键两级处理。
- 720p 和 1080p TV 横屏无溢出或裁切。

### 12.3 构建验证

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

最终 APK 还需验证包名、版本、ABI、签名和文件哈希，并在真实 TV 或电视模拟器上完成扫码、投屏、断线重连和全部控制事件验收。

## 13. 非目标

- 不修改 `airplayTV-vue`。
- 不修改 Go WebSocket 协议。
- 不实现 TV 端视频浏览、搜索、收藏、历史或设置页面。
- 不实现后台播放、前台 Service、通知栏媒体控制或画中画。
- 不持久化播放记录、视频源凭证或配对房间。
