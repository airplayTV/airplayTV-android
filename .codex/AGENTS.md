# AirPlay TV - Android TV 应用

## 项目概述
基于 airplayTV-vue (Web 版) 的 Android TV 原生应用，使用 Jetpack Compose for TV 构建。
后端 API 地址：`https://airplay-api.artools.cc`

## 技术栈
- **语言**: Kotlin
- **UI**: Jetpack Compose for TV (androidx.tv:tv-material)
- **播放器**: Media3 ExoPlayer
- **网络**: Retrofit + OkHttp + Gson
- **数据库**: Room (替代 Dexie/IndexedDB)
- **设置存储**: DataStore Preferences (替代 localStorage)
- **图片加载**: Coil
- **导航**: Compose Navigation
- **异步**: Kotlin Coroutines + Flow
- **构建**: Gradle Kotlin DSL
- **最低 API**: 21 (Android TV), 目标 API: 35

## API 接口
- `GET /api/video/provider` - 获取视频源列表
- `GET /api/video/list?tag=X&page=Y&_source=Z` - 视频列表
- `GET /api/video/detail?id=X&_source=Y` - 视频详情
- `GET /api/video/source?vid=X&pid=Y&_source=Z&_m3u8p=W` - 视频播放地址
- `GET /api/video/search?query=X` - 搜索
- `POST /api/collect/add` - 添加收藏
- `GET /api/collect/list` - 收藏列表
- `POST /api/collect/remove` - 移除收藏
- 请求头: `X-Client: airplayTV-web`, `X-Source-Mode: <兑换码>`, `X-Username: <用户名>`

## 项目包结构
```
com.airplay.tv/
├── AirPlayTVApp.kt          # Application 类
├── MainActivity.kt          # 主 Activity (LEANBACK)
├── data/
│   ├── api/                 # Retrofit API 定义和数据模型
│   ├── db/                  # Room 数据库
│   ├── repository/          # 数据仓库
│   └── preferences/         # DataStore 偏好设置
├── player/                  # ExoPlayer 管理器
├── ui/
│   ├── navigation/          # Compose 导航
│   ├── screens/             # 各页面
│   └── components/          # 可复用组件
└── util/                    # 工具类和常量
```

## 核心功能
1. **视频列表浏览**: 按源和标签分类的视频网格
2. **搜索**: 关键词搜索，键盘输入
3. **快捷切换源**: 首页/设置页面切换视频源
4. **视频详情+播放列表**: 剧集列表，分组展示
5. **播放器控制**: 快进/快退/暂停, 下一集/上一集
6. **续播**: 跳转到上次播放位置
7. **兑换码**: 设置页输入兑换码解锁更多资源
8. **账号输入**: 同步收藏夹的账号
9. **本地缓存清理**: 一键清除历史和缓存
10. **遥控器支持**: 所有操作支持 DPAD 方向键控制
