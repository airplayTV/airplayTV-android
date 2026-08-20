# Android 最终审查修复报告

## 修复范围

- 首次有效 `/ctl_pair` 关联边沿立即推送最新一条播放记录；重复 pair/heartbeat 幂等。
- WebSocket 发送 `join-group` 后保持 `Connecting`，仅收到 `join-group` 且 `data.code == 200` 后进入 `Connected`。
- 多集面板由 `infoVisible && episodes.size > 1` 控制显示，语义焦点仅控制高亮、遥控动作与信息层计时冻结。
- source 与同步/诊断日志合并到右下同一个 Row；无日志仍显示 source，数据模型不读取 URL、header 或 mode。

## 实现细节

### 关联边沿历史推送

- 复用连接 generation 与既有关联边沿状态，只有 `ControllerPaired` 的 false -> true 边沿触发推送。
- 存在 committed media 时，使用当前 `PlayerState` 重建 `PlaybackRecord`，先进入本地持久化队列，再走现有 `syncSnapshot`/ACK 状态机。
- 不存在 committed media 时，读取 `PlaybackProgressRepository.latest()`，通过现有 socket 直接发送；重复 pair 不再次读取或发送。
- `recipient_count = 0` 的 accepted ACK 仍标记为已同步，不取消或重建 30 秒周期任务。

### join ACK 状态机

- `onOpen` 只发送 join 帧，不提前发布 `Connected`。
- join ACK 严格校验 event、对象 data、整数 code 与可选匹配 room；200 接受，非 200/非法 join ACK 关闭当前 socket 并进入现有指数回退重连。
- join 完成前忽略控制命令、历史 ACK，且 `sendPlaybackHistory` 返回 false；join 成功后恢复原控制命令与历史 ACK 分流语义。
- 成功 ACK 才重置 reconnect attempt；冲突 409 不产生表面 Connected。

### UI

- 信息层可见且多集时始终显示窄版单列选集；未进入选集语义焦点时不产生 `episode-focus-*` 高亮。
- source 与日志通过 `player-diagnostic-row` 同行渲染；`player-source` 独立语义标签便于设备几何验收。

## TDD 证据

### RED

- 选集 helper 缺失：`shouldShowEpisodePanel`、`isEpisodeFocused` unresolved reference。
- 首次 pair 无媒体上下文：send 列表为空，`single()` 抛 `NoSuchElementException`。
- socket open 后旧实现立即为 `Connected`；非 200 join ACK 后仍为 `Connected`。
- 诊断同行安全内容 helper 缺失：`playerDiagnosticRowContent` unresolved reference。

### GREEN

Clean JDK 17、最小 PATH、`GRADLE_USER_HOME=E:\cache\gradle`、Android SDK `E:\cache\android-sdk`：

```powershell
.\gradlew.bat --stop
.\gradlew.bat --no-daemon :app:testDebugUnitTest `
  :app:compileDebugAndroidTestKotlin --rerun-tasks --no-parallel --max-workers=1
```

- `BUILD SUCCESSFUL in 56s`
- 33/33 Gradle tasks 重新执行。
- debug JVM：25 suites，223 tests，0 failure，0 error，0 skipped。
- AndroidTest Kotlin 编译通过。
- 完整 `com.airplay.tv.protocol.*` JVM 回归通过。
- 完整 `com.airplay.tv.session.*` JVM 回归通过。

Android TV 1080p API 28 模拟器：

```powershell
.\gradlew.bat --no-daemon :app:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.class=com.airplay.tv.app.AppNavigationTest' `
  --no-parallel --max-workers=1
```

- `AppNavigationTest`：20/20 通过，0 skipped，0 failed。

## 边界与关注项

- API 契约依赖服务端对 owner 冲突返回 join-group `code=409`，旧 owner 关闭后新连接返回 `code=200`；Android 已覆盖 200/409 分支。
- 未进行实体 Android TV、实体遥控器和线上 WebSocket 端到端验收；当前设备证据来自 Android TV 1080p API 28 模拟器。
- 构建仅存在仓库既有 Gradle 9 deprecated-features 提示，无新增编译或测试警告。
- 未修改或暂存根 `build.gradle.kts`、`.superpowers/sdd/task-7-report.md`。

## 第二轮复审修复

### 关联代次与 committed media

- 新增独立 `controllerAssociationRevision`；pair 新边沿、unpair、断线和 connection generation 变化都会递增 revision 并取消旧查询 job。
- 异步 `latest()` 返回后再次校验关联 revision、connection generation 和 committed identity；即使旧查询不可协作取消，也不能跨 unpair -> pair 新边沿发送。
- pending load 不再使旧 committed identity 失效；首次 pair 直接使用旧 committed context 与当前 PlayerState 刷新快照，不回退 repository latest。
- latest 查询期间若新媒体完成 commit，则丢弃旧查询结果，避免发送过期记录。

### 选集当前位置

- details 返回后按 current pid 更新 `focusedEpisodeIndex`；未聚焦常显面板自动滚动到当前集，进入语义焦点仍从当前集开始。
- preserve episodes 的切集入口同样按目标 pid 初始化 index，不再无条件回到首集。

### join ACK 超时

- join 帧发送成功后启动 generation/socket/phase 约束的 10 秒握手超时。
- 超时原子 claim 当前 Connecting 连接，关闭后进入现有指数回退；200 ACK、close、room switch 和 disconnect 均取消旧 timeout job。
- ACK 与 timeout 竞争由同一 lock 和 phase 转换裁决，不会由旧 generation timeout 关闭新连接。

### 第二轮 TDD

RED：

- 不可协作取消的旧 latest 在同 connection generation 的 unpair -> pair 后与新查询一起发送，`single()` 因两条消息失败。
- pending load 时 pair 错误发送 repository stale record，而非 committed p1 当前 41 秒快照。
- latest 查询期间新媒体 commit 后仍发送 stale record。
- 非首集 p3 details 加载后 `focusedEpisodeIndex` 仍为 0。
- join ACK 缺失推进 10 秒后仍为 `Connecting`，未关闭、未重连。

GREEN：对应 focused Session/Protocol 测试全部通过。

第二轮 clean 验证：

- `:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin --rerun-tasks`：`BUILD SUCCESSFUL in 47s`，33/33 tasks 重新执行。
- Debug JVM：25 suites，228 tests，0 failure，0 error，0 skipped。
- 完整 `com.airplay.tv.protocol.*` 与 `com.airplay.tv.session.*` 套件通过。
- AndroidTest Kotlin 编译通过。
- Android TV 1080p API 28 模拟器 `AppNavigationTest`：20/20 通过，0 skipped，0 failed。
