# AirPlay TV Android

Android TV 投屏接收端。TV 展示房间二维码，手机 H5 扫码入组并选择视频后，通过 WebSocket 控制 TV 上的 Media3 播放器。

## 构建

要求 JDK 17 与 Android SDK 35：

```powershell
$env:ANDROID_HOME='E:\cache\android-sdk'
$env:ANDROID_SDK_ROOT='E:\cache\android-sdk'
.\gradlew.bat assembleDebug
```

调试 APK：`app\build\outputs\apk\debug\app-debug.apk`。

## 真实 TV 验收流程

1. 将 APK 安装到 Android TV 并冷启动，确认显示二维码和连接状态。
2. 使用手机扫描 TV 二维码，确认手机 H5 成功加入该房间。
3. 在手机 H5 选择 HLS 或 MP4 视频，确认 TV 自动开始播放。
4. 逐项验证播放、暂停、前进、后退、音量、静音、全屏、信息层、上一集、下一集和返回二维码。
5. 播放器页第一次按 TV 返回键应先关闭信息层，第二次返回二维码页。
6. 断开 TV 网络后再恢复，确认 WebSocket 重连退避最多 30 秒，并重新加入原房间。
7. 在 720p 和 1080p 输出下检查二维码、加载态、错误层和播放器信息层的安全区。

每项记录 TV 型号、Android 版本、结果和失败日志摘要。日志不得包含投屏 `mode` 或完整媒体 URL。

## 当前验收边界

- JVM 单测、lint、debug APK 构建、Android instrumentation 源码编译可在无设备环境完成。
- 二维码扫码、真实 HLS/MP4 解码、遥控器按键、Wi-Fi 恢复和不同分辨率安全区必须在真实 Android TV 上验收；未连接设备时不能视为已完成真机验收。
- 网络安全配置允许第三方 HTTP 媒体地址，这是兼容性要求；业务 API 与 WebSocket 地址固定使用 HTTPS/WSS。
