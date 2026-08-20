# TV 播放断点续播、选集与手机历史同步设计

## 1. 目标

在现有 Android TV 播放、H5 投射控制和 Go WebSocket 服务基础上完成以下能力：

1. 播放页日志显示在右下方，位于进度控制区域下一层。
2. TV 播放页显示当前源；H5 当前投射卡片显示源信息。
3. TV 播放地址区域扩宽，显示更多 URL 内容。
4. Android TV 按剧集保存播放进度，再次播放时恢复。
5. 多剧集视频自然结束后自动播放下一集。
6. 播放控制区域最后一次有效操作后 10 秒隐藏。
7. TV 控制层显示时展示可用遥控器操作的单列选集列表。
8. TV 扫码页的“等待连接”状态与播放页连接状态使用相同位置。
9. 播放或缓冲期间持续防息屏；暂停、结束或可恢复错误后至少 10 分钟不息屏。
10. TV 仅将最新一条播放记录同步到当前关联手机的 H5 历史，不做全量同步。

## 2. 已确认决策

| 主题 | 决策 |
|---|---|
| 断点续播范围 | 仅 Android TV 本地恢复；不修改 H5 自身播放恢复逻辑 |
| TV 本地保存频率 | 播放中每 5 秒一次 |
| TV 到 H5 同步频率 | 播放中每 30 秒一次 |
| 立即保存与同步 | 暂停、切集、播放结束和退出播放页时执行 |
| 关联后同步 | 手机 Presence 关联成功后，TV 立即推送本地最新一条记录 |
| 完成判定 | 剩余不超过 30 秒或进度达到 95% 时视为完成，下次从 0 播放 |
| H5 写入 | 只 upsert 收到的一条，保留手机其他历史 |
| 服务端方案 | 基于 Presence 注册表定向转发，不持久化记录 |
| 控制层隐藏 | 最后一次有效操作 10 秒后隐藏；选集持有焦点时不隐藏 |
| TV 选集布局 | 右侧窄版单列，每行一集 |
| 日志位置 | 右侧底部，位于进度控制区域下一层 |
| 连接状态位置 | 保持现有右上角位置 |
| 源信息位置 | TV 放入右下同步日志行；H5 放在当前剧集之后 |

## 3. 范围

### 3.1 修改范围

- `airplayTV-android`
  - 本地播放记录存储、定时保存、断点恢复和完成判定。
  - TV 到服务端的播放记录上报与 ACK 处理。
  - 右侧选集、遥控器焦点、控制层计时、日志/地址布局、扫码页状态位置和防息屏。
- `api`
  - TV 房间成员注册、播放记录校验、ACK 和向当前 Presence 手机定向转发。
- `airplayTV-vue`
  - 应用级播放记录监听、IndexedDB 原子 upsert、历史页刷新和投射卡片源信息。

### 3.2 不修改范围

- 不同步 TV 全量播放历史。
- 不在 Go 服务端持久化播放记录。
- 不修改 H5 自身视频播放的断点续播规则。
- 不发送或保存 TV 播放 URL、请求 Header、`mode`、源密钥或原始解析响应。
- 不改变现有 `/ctl_load_Video`、控制命令、投射 ACK 和 Presence 续租语义。
- 不引入后台播放服务、通知栏媒体控制或跨房间账号系统。

## 4. 总体架构

```text
H5 /ctl_load_Video
        |
        v
Android SessionViewModel --> Media3 Player
        |                       |
        |                       +--> position/duration
        |
        +--> PlaybackProgressRepository
        |      - 5 秒本地保存
        |      - source + vid + pid
        |
        +--> tv-playback-history（30 秒/关键事件/关联后）
                    |
                    v
             Go WebSocket 服务
               - 校验 TV 房间身份
               - 查询 Presence 手机
               - 定向转发
                    |
                    v
              H5 App 级监听器
               - history upsert
               - timeline upsert
```

Android 是播放状态和 TV 本地进度的唯一事实来源。Go 服务只执行在线授权与转发。H5 只把收到的最新记录合并到当前浏览器 IndexedDB。

## 5. Android TV 设计

### 5.1 本地播放记录

使用 Android `Preferences DataStore`，通过协程串行执行事务，避免主线程同步 I/O。

记录键由以下原文拼接后计算 SHA-256：

```text
source + "\u001F" + vid + "\u001F" + pid
```

记录结构：

```kotlin
data class PlaybackRecord(
    val source: String,
    val vid: String,
    val pid: String,
    val title: String,
    val episodeName: String,
    val thumb: String,
    val positionMs: Long,
    val durationMs: Long,
    val completed: Boolean,
    val updatedAtMs: Long,
)
```

约束：

- 最多保留 500 个剧集记录；超出后在同一事务中删除更新时间最早的记录。
- 同一事务更新剧集记录和 `latestRecordKey`，确保“最近一条”可恢复。
- 播放地址不参与键，也不进入持久化记录。
- 扩展视频详情映射以读取标题、剧集名称和缩略图；接口不提供缩略图时保存空字符串。
- `durationMs > 0` 时，剩余不超过 30 秒或位置达到总时长 95% 即写入 `completed=true`。
- 播放时长未知时保存位置但不使用比例/剩余时间判定；收到自然结束事件时直接标记完成。
- 单条记录损坏时只删除该记录并从 0 播放，不清空其他记录。

### 5.2 播放身份与定时任务

每个已提交媒体使用不可变身份：

```kotlin
data class PlaybackIdentity(
    val generation: Long,
    val source: String,
    val vid: String,
    val pid: String,
)
```

媒体解析成功并提交给播放器后启动两个任务：

- 播放中每 5 秒获取位置并写入 DataStore。
- 播放中每 30 秒获取最新快照并上报 WebSocket。

任务规则：

- 每次读写前确认身份 generation 仍是当前已提交媒体。
- 暂停时停止循环写入，并立即保存和上报一次。
- 切集、重新投射、播放结束、退出播放页或进入后台时，先使用旧身份立即保存和上报，再取消旧任务。
- 断线期间不缓存 30 秒消息；重连并收到手机 `/ctl_pair` 后只发送当前最新记录。
- 同一时刻只允许一个待确认上报；新周期不形成历史消息队列。

### 5.3 断点恢复

扩展 `PlayerController.load`，允许传入初始位置：

```kotlin
fun load(
    url: String,
    mediaType: ResolvedMediaType,
    startPositionMs: Long = 0,
)
```

在 Media3 `prepare/play` 前设置初始 seek。所有媒体加载入口复用同一恢复逻辑：

- 手机投射；
- TV 选集；
- 上一集/下一集；
- 自然结束后的自动下一集。

未完成记录从保存位置恢复；已完成记录从 0 播放。恢复位置无效或 Media3 无法应用时记录安全 `ERR`，从 0 播放。

### 5.4 自动下一集

沿用每个媒体项只消费一次的 Media3 `STATE_ENDED` 门控：

1. 以当前身份保存完成记录并立即同步。
2. 从已接受的剧集列表定位当前 `pid`。
3. 有下一集时通过现有 generation 加载链路加载下一集，并应用该集断点记录。
4. 最后一集保持结束状态，不循环。
5. 详情仍在加载时只保留一个待自动切集意图。
6. 手工加载会清除旧的自动切集意图。
7. 下一集解析失败时保留当前完成记录，不连续重试或跨集跳过。

### 5.5 UI 布局

`SessionUiState.infoVisible` 继续控制播放控制层整体显隐。

- 右上：连接状态保持现有安全区位置。
- 右侧：宽度约占播放器 17% 的窄版单列选集抽屉。
- 左下：标题和当前剧集。
- 中下：播放状态、当前时间、进度条和总时长。
- 地址：分配约 65% 可用宽度，单行尾部省略。
- 右下：最多 5 行诊断日志，放置在进度条下方。
- TV 源信息加入右下同步日志状态行，例如：

```text
源 ffzy  21:36:09  SYNC  播放记录已提交
```

`源 ffzy` 是日志层的固定状态前缀，不依赖是否刚好产生 `SYNC` 事件；只要控制层可见且当前媒体已提交，源信息就必须可见。

完整 URL 只显示在明确要求的地址区域；诊断日志仍禁止包含完整 URL、查询参数、Header、源密钥或原始 WebSocket JSON。

### 5.6 遥控器与控制层计时

- 最后一次有效遥控器操作后 10 秒隐藏控制层。
- 切集、暂停、播放、快进和快退重新计时。
- 选集列表持有焦点时暂停隐藏计时。
- `DPAD_UP` 从播放器控制进入选集列表并聚焦当前剧集。
- 列表内 `UP/DOWN` 逐集移动焦点。
- `ENTER/DPAD_CENTER` 播放聚焦剧集。
- 列表内 `LEFT/BACK` 退出选集焦点并返回播放器控制。
- 列表外 `LEFT/RIGHT` 继续快退/快进 15 秒。
- 当前剧集自动滚动到可见区域。
- 只有一集或无有效剧集时不显示抽屉，`DPAD_UP` 不进入列表。

返回键优先级：关闭播放中二维码卡片、退出选集焦点、隐藏控制层、返回扫码页。

### 5.7 扫码页连接状态

将连接状态放置提取为共享顶层组件。扫码页“等待连接”和播放页连接状态使用相同的右上角安全区坐标。播放中二维码卡片出现时继续使用现有下移避让规则，不移动扫码页主体二维码。

### 5.8 防息屏

- 播放或缓冲期间持续设置窗口 `KEEP_SCREEN_ON`。
- 暂停、结束或可恢复错误后保留 10 分钟。
- 10 分钟内发生遥控器操作或恢复播放时重置策略。
- 返回扫码页、应用进入后台或 Activity 销毁时立即清除窗口标志。

窗口标志由页面/播放状态驱动，不由播放器内部直接操作 Activity。

## 6. WebSocket 协议与 Go 服务设计

### 6.1 TV 上报事件

```json
{
  "event": "tv-playback-history",
  "data": {
    "request_id": "request-id",
    "group": "room-id",
    "version": 1,
    "source": "ffzy",
    "vid": "video-id",
    "pid": "episode-id",
    "title": "视频标题",
    "episode_name": "第 03 集",
    "thumb": "https://example/thumb.jpg",
    "position_ms": 751000,
    "duration_ms": 1758000,
    "completed": false
  }
}
```

上报不包含播放 URL、Header、`mode`、源密钥或原始接口响应。

### 6.2 服务端授权与校验

增加内存 `TVRoomRegistry`，维护 WebSocket TV 客户端与成功加入房间的关系。现有 `ControllerPresenceRegistry` 继续维护房间与有效手机连接关系。

`TVRoomRegistry` 只在 `JoinGroup` 完成后登记；同一 TV 重新加入其他房间时替换旧关系，连接关闭时删除。`ControllerPresenceRegistry` 提供锁保护的房间客户端快照，转发过程不持有注册表内部锁。

服务端只在以下条件全部满足时接受记录：

- 发送连接已成功执行 `join-group`。
- 载荷 `group` 与发送连接注册房间完全一致。
- `request_id`、`source`、`vid`、`pid` 存在且长度受限。
- 文本字段裁剪到协议上限。
- `position_ms`、`duration_ms` 非负且不超过合理视频时长上限。
- 位置超过有效总时长时截断到总时长。
- `thumb` 只允许 `http/https`；服务端不主动请求该 URL。

服务端使用自己的时钟生成 `updated_at`，不信任 TV 的墙上时钟。连接关闭时清理对应 TV 房间注册。

### 6.3 定向转发

服务端只向同一房间 Presence 未过期的手机连接发送：

```json
{
  "event": "playback-history-update",
  "data": {
    "version": 1,
    "room": "room-id",
    "source": "ffzy",
    "vid": "video-id",
    "pid": "episode-id",
    "title": "视频标题",
    "episode_name": "第 03 集",
    "thumb": "https://example/thumb.jpg",
    "position_ms": 751000,
    "duration_ms": 1758000,
    "completed": false,
    "updated_at": 1787190000000
  }
}
```

多个当前关联手机均接收同一条最新记录；未关联、Presence 过期或其他房间客户端不接收。

### 6.4 ACK

服务端向 TV 回复：

```json
{
  "event": "tv-playback-history-ack",
  "data": {
    "request_id": "request-id",
    "accepted": true,
    "recipient_count": 1
  }
}
```

- `accepted=true` 表示校验通过并完成定向入队。
- `recipient_count=0` 表示当前没有有效关联手机，不是播放错误。
- ACK 不宣称手机 IndexedDB 已写入成功。
- Android 只对匹配当前待确认 `request_id` 的 ACK 更新同步状态。

## 7. H5 设计

### 7.1 应用级接收

在 `App.vue` 的现有 WebSocket 应用级监听器中处理 `playback-history-update`，不把同步绑定到控制页或历史页生命周期。

处理前必须验证：

- `version` 可识别；
- `room` 等于当前 `KEY_ROOM_ID`；
- `source`、`vid`、`pid` 有效；
- 数值字段可安全转换；
- `updated_at` 不旧于该记录上一次 TV 同步时间 `tv_updated_at`。

### 7.2 IndexedDB 原子写入

在一个 Dexie 事务中更新：

```js
history: {
  source,
  vid,
  pid,
  name: title,
  pname: episode_name,
  thumb,
  lastTime: position_ms / 1000,
  duration: duration_ms / 1000,
  updated_at,
  tv_updated_at: updated_at,
}

timeline: {
  source,
  vid,
  pid,
  lastTime: position_ms / 1000,
  duration: duration_ms / 1000,
  updated_at,
  tv_updated_at: updated_at,
}
```

- `history` 按现有 `[source+vid]` upsert。
- `timeline` 按现有 `[source+vid+pid]` upsert。
- 不删除手机的其他历史和时间线。
- 只使用 `tv_updated_at` 判断 TV 消息乱序，不把手机本地播放产生的墙上时间与服务端时间直接比较。
- 更新已有记录时保留其 `url`、`type` 等 H5 本地字段；新增 TV 记录时这些字段为空，不影响历史页按 `source + vid + pid` 打开详情。
- 写入失败仅输出安全警告，不断开 WebSocket 或阻断页面。
- 历史页已打开时通过轻量应用事件重新加载列表，不强制导航。

### 7.3 投射卡片

H5 当前投射卡片在当前剧集之后显示源：

```text
当前：第 03 集  源：ffzy
```

继续从经过规范化的本地投射会话读取，不展示 `sourceSecret` 或 `mode`。

## 8. 关联后立即同步

现有 Presence 处理仍向 TV 发送幂等 `/ctl_pair`。Android 仅在有本地 `latestRecordKey` 时响应：

1. 读取本地最近记录。
2. 如果当前正在播放，则用当前播放器位置刷新该记录。
3. 发送一次 `tv-playback-history`。
4. 继续保持正常 30 秒周期。

服务端不为历史同步改变 5 秒 Presence 续租、15 秒租约或现有控制器关联日志去重规则。

## 9. 异常与安全处理

- 旧媒体 coroutine 必须通过 generation 校验，不能写入或同步成当前媒体。
- DataStore 单条损坏只影响对应剧集。
- 自动下一集解析失败不连续重试。
- 无关联手机时继续本地保存，不产生播放错误。
- 服务端拒绝未加入房间或房间不匹配的 TV 上报。
- H5 把 WebSocket 数据视为不可信输入，规范化后才进入 Dexie。
- H5 仅使用 Vue 文本绑定展示标题、剧集和源，不执行 HTML。
- 服务端不抓取缩略图 URL，避免引入 SSRF。
- 日志不得记录完整播放 URL、查询参数、Header、源密钥、`mode` 或原始载荷。

## 10. 测试策略

### 10.1 Android JVM 与 Compose 测试

- DataStore 键隔离、500 条上限、最旧淘汰和损坏记录降级。
- 95%、剩余 30 秒、未知时长和自然结束完成判定。
- 5 秒本地保存、30 秒上报和关键事件立即保存/同步。
- generation 变化后旧任务失效。
- 未完成恢复、已完成从 0、所有加载入口统一恢复。
- 上报载荷不包含敏感字段。
- ACK 匹配、超时、断线和零接收者。
- 关联后立即发送最新记录。
- 控制层 10 秒隐藏和选集焦点暂停计时。
- D-pad 进入、移动、确认、退出及列表外快进快退。
- 单集不显示抽屉，当前剧集自动可见。
- 日志右下、连接状态右上、地址宽度和源信息语义。
- 扫码页与播放页连接状态使用同一布局规则。
- 播放/缓冲持续唤醒、暂停后 10 分钟和页面退出清理。

### 10.2 Go 测试

- 已加入同房间的 TV 上报成功。
- 未加入、房间不匹配和伪造房间被拒绝。
- 只向 Presence 有效手机定向发送。
- Presence 过期和断开连接不接收。
- 多个有效手机均接收。
- 字段长度、数值范围、缩略图协议和载荷大小校验。
- ACK 的请求 ID、接受状态和接收者数量。
- 连接关闭后注册表清理。
- 环境支持时执行竞态检测。

### 10.3 H5 测试

- 协议规范化、版本和房间匹配。
- `history` 与 `timeline` 同事务 upsert。
- 只更新一条 TV 记录，保留手机其他历史。
- 旧消息不覆盖新记录。
- 无效字段和 IndexedDB 失败安全降级。
- App 级监听器在控制页之外仍可接收。
- 历史页打开时刷新。
- 投射卡片显示当前剧集和源。
- 现有投射、ACK、Presence、切集和历史测试不回归。

### 10.4 自动化命令

```powershell
# airplayTV-android
.\gradlew.bat testDebugUnitTest testReleaseUnitTest `
  lintDebug lintRelease `
  assembleDebug assembleRelease `
  compileDebugAndroidTestKotlin

# airplayTV-vue
node --test tests/*.test.mjs
npm run build

# api
go test ./...
go test -race ./...  # 环境支持时
```

三个仓库分别执行 `git diff --check`，并检查全部修改文件为 UTF-8 无 BOM。现有无关修改和未跟踪文件不进入本需求提交。

## 11. 真机验收

自动化构建不能替代 Android TV 真机验收：

- 播放至少 35 秒后退出并重新播放，恢复到最近 5 秒记录附近。
- 接近结尾的视频重新打开时从 0 播放。
- 多剧集自然结束后只进入一次下一集。
- 遥控器可操作右侧窄版单列选集；选集聚焦时控制层不消失。
- 控制层静置 10 秒后隐藏。
- 地址、日志、选集和连接状态在目标 TV 安全区内不重叠。
- 播放或缓冲期间持续不息屏；暂停后 10 分钟内不息屏。
- 手机关联后立即收到记录；继续播放时约每 30 秒更新。
- 手机原有历史保留，TV 只更新当前一条。
- 手机 Presence 失效后不再接收 TV 记录。
- 断网重连后不回放积压旧同步消息。

## 12. 需求追踪

| 原需求 | 设计落点 |
|---|---|
| 1 | 5.5 日志右下并位于进度区域下一层 |
| 2 | 5.5 TV 源状态行；7.3 H5 投射卡片源信息 |
| 3 | 5.5 地址约 65% 可用宽度 |
| 4 | 5.1 至 5.3 TV 本地记录与恢复 |
| 5 | 5.4 自动下一集 |
| 6 | 5.6 控制层 10 秒计时 |
| 7 | 5.5、5.6 TV 单列选集与遥控器焦点 |
| 8 | 5.7 扫码页连接状态位置 |
| 9 | 5.8 防息屏策略 |
| 10 | 6 至 8 Presence 定向同步与 H5 单条 upsert |
