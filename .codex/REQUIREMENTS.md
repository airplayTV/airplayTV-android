# AirPlay TV Android - 需求设计文档 v1.0

## 1. 项目背景
将 airplayTV-vue (Web 端视频聚合播放应用) 移植到 Android TV 平台。
利用现有后端 API (https://airplay-api.artools.cc) 实现 TV 原生体验。

## 2. 目标用户
Android TV/Google TV 设备用户，通过遥控器操作。

## 3. 功能需求

### 3.1 视频列表首页
- **源选择**: 顶部显示当前视频源，遥控器方向键可切换
- **标签过滤**: 按分类标签(如"电影"、"电视剧"、"动漫")筛选
- **视频网格**: 懒加载网格布局，每行3-5列
- **分页加载**: 上滑/自动加载下一页
- **状态**: 加载中骨架屏、加载失败重试、空数据提示

### 3.2 搜索功能
- **搜索入口**: 首页顶部搜索按钮
- **搜索页面**: 全屏搜索界面
- **键盘输入**: TV 键盘
- **实时搜索**: 输入关键词后请求搜索 API
- **搜索结果**: 网格展示，支持遥控器导航

### 3.3 快捷切换源
- **源选择**: 首页 TopBar 显示当前源名称
- **源列表**: 展示所有可用视频源，点击即切换
- **标签同步**: 切换源后自动更新对应标签列表

### 3.4 视频详情 + 播放列表
- **详情页**: 封面大图、视频名称、简介、演员信息
- **播放列表**: 按分组展示剧集列表
- **高亮**: 当前播放的剧集高亮显示
- **导航**: 遥控器方向键在剧集列表中快速移动

### 3.5 视频播放器
- **播放器**: Media3 ExoPlayer (支持 HLS、MP4)
- **控制**: 暂停/播放、快进、快退
- **进度**: 显示播放进度、当前时间/总时长
- **上下集**: 播放中切换上一集/下一集
- **续播**: 自动跳转到上次播放位置
- **播放结束**: 自动播放下一条

### 3.6 设置页面
- **兑换码**: 输入框输入兑换码解锁资源
- **账号输入**: 输入框输入账号同步收藏夹
- **缓存清理**: 一键清理播放历史、播放进度
- **关于**: 版本号、应用信息

### 3.7 本地数据库
- history 表: source, vid, pid, name, thumb, lastTime, duration, updated_at
- timeline 表: source, vid, pid, lastTime, duration, updated_at

### 3.8 设置存储
- 视频源、标签、兑换码、用户名、播放设置

## 4. 非功能需求
- **遥控器**: 所有可交互元素支持 DPAD 导航
- **焦点**: 清晰的焦点高亮效果
- **性能**: 流畅滚动，图片懒加载
- **容错**: 网络异常提示，支持重试
- **UI**: 深色主题，Material 3 TV 风格

## 5. 页面路由
/ -> HomeScreen
/video/detail/{id} -> VideoDetailScreen
/video/player/{vid}/{pid} -> PlayerScreen
/search -> SearchScreen
/settings -> SettingsScreen

## 6. 数据流
API (Retrofit) -> Repository -> ViewModel -> Compose UI
                                       
                                  Room / DataStore

## 7. 任务拆解

### 任务1: 项目脚手架
Gradle项目结构、配置文件、Wrapper、AndroidManifest、主题、Application

### 任务2: 数据层
API接口、DTO、Room数据库+DAO、DataStore、Repository

### 任务3: 导航 + 首页
Compose TV Navigation、HomeScreen、ViewModel

### 任务4: 搜索页面
TV键盘、搜索请求、结果展示

### 任务5: 视频详情页
详情展示、播放列表、进入播放

### 任务6: 视频播放器
ExoPlayer集成、控制、续播、进度保存

### 任务7: 设置页面
兑换码、账号、缓存清理

### 任务8: APK打包
签名配置、Release构建、APK生成
