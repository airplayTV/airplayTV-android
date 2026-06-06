# airplayTV Android TV

Android TV 视频播放应用，Jetpack Compose + ExoPlayer + Retrofit + Coil。

## 项目结构
- MainActivity.kt — 入口，初始化 Retrofit/DataStore/Room
- AppNavigation.kt — 页面路由（home/player/search/settings）
- data/api/ — Retrofit 接口和 API 模型
- data/repository/ — VideoRepository 封装 API 调用
- data/db/ — Room 数据库（TimelineEntity 记录播放进度）
- ui/screens/ — HomeScreen、PlayerScreen、SearchScreen、SettingsScreen
- ui/components/ — TopBar、TagRow、VideoCard

## API 要点
- /api/video/list 返回分页结构 data: { total, pages, page, limit, list }
  → 对应 ApiResponse<VideoListResponse>，用 resp.data?.list 提取
- /api/video/detail 和 /api/video/source 必须传 _source 参数

## UI 约定
- 背景 #121218，选中色 #6C63FF
- 焦点态：scale(1.05~1.08) + 文字变亮 + #3D3D4A 背景
- LazyColumn 内不嵌套 LazyVerticalGrid，用 chunked(4) + Row 替代
- VideoCard 用 weight(1f) 铺满网格列宽

## 播放器
- ExoPlayer (Media3) + PlayerView (AndroidView)
- 时间 HH:MM:SS 格式
- 选集列表：Menu键/方向下键调出，DPAD 导航选择
- 进度条每 500ms 刷新，位置每 3s 自动保存到 Room

## 导航参数
- onVideoClick 签名 (vid, pid, source)，所有 API 调用都传 source
