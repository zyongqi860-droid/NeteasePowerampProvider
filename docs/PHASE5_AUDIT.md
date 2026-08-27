# 第五阶段架构审计报告

> 审计日期：2025-08-26
> 审计范围：第五阶段全部新增/修改文件
> 审计原则：基于实际代码阅读，非文件名推断

---

## 1. 当前项目真实架构

读完所有实际代码后，当前项目同时运行着**两套并行系统**：

### 系统A：Poweramp Provider 数据源（第一至第三阶段建立，第五阶段增强）

```
Poweramp
  ↓ (SAF / DocumentsProvider 调用)
NeteasePowerampProvider (DocumentsProvider)
  ├── queryRoots / queryDocument / queryChildDocuments → Room (songs / playlists)
  ├── openDocumentThumbnail → AlbumArtService (封面)
  └── openDocument / CALL_GET_URL
        ↓
      CachingPlaybackResolver (URL 缓存 + 重试)
        ↓
      NeteasePlaybackResolver (真实网易云 API)
        ↓
      PlaybackSource (URL + headers + expireTime)
        ↓
      AudioProxyFd (ProxyFileDescriptorCallback / Range GET)
        ↓
      AudioStreamClient (OkHttp Range 请求)
        ↓
      ParcelFileDescriptor → Poweramp 播放
```

这套系统**完全自洽**，Poweramp 负责全部播放器逻辑（队列、进度、状态、通知、锁屏、蓝牙耳机），本项目只负责数据源和音频流。

### 系统B：独立播放器（第五阶段新增）

```
MainActivity
  ├── PlaybackQueueManager (独立播放队列：setQueue / next / previous / shuffle / repeat)
  ├── PlaybackStateManager (独立播放状态：PLAYING / PAUSED / BUFFERING / position / duration)
  ├── 播放/暂停/上一首/下一首 按钮 (直接修改 stateManager)
  ├── SeekBar 进度条 (模拟进度推进)
  └── saveSong() → 同时写 Room 和 appendItems 到 queueManager

PlaybackService (ForegroundService)
  ├── MediaSessionCompat (独立媒体会话)
  ├── MediaNotificationManager (通知栏播放器：封面 + 传输按钮 + MediaStyle)
  ├── handlePlayPause / handleNext / handlePrevious (独立传输控制)
  ├── handleMediaButton (蓝牙耳机按键)
  ├── PlaybackHistoryRepository (播放历史记录)
  └── updateMediaSession (锁屏元数据 + 状态)
```

这套系统**与 Poweramp 完全无关**——它创建了自己的播放队列、播放状态、MediaSession、通知栏、ForegroundService，但**从未真正解码任何音频**。`PlaybackStateManager.setPlaying()` 只是改了一个内存 StateFlow 的值，`playItem()` 只是设置了 metadata 然后立刻调用 `setPlaying()`——没有音频输出。

---

## 2. 第五阶段实际新增内容

逐文件列出（基于实际代码阅读，非文件名推断）：

| 文件 | 实际功能 | 与 Provider/数据源的关系 |
|------|----------|--------------------------|
| `NeteasePlaybackResolver.kt` | 调用网易云 `song/enhance/player/url` API 获取真实播放 URL | ✅ 直接服务 Provider |
| `CachingPlaybackResolver.kt` (修改) | URL 缓存 + 重试 + 超时 | ✅ 直接服务 Provider |
| `AudioProxyFd.kt` (修改) | HandlerThread 引用计数生命周期管理 | ✅ 直接服务 Provider |
| `NeteasePowerampProvider.kt` (修改) | 切换 Mock→Netease resolver + runBlocking 超时 | ✅ 直接服务 Provider |
| `LyricService.kt` (修改) | 接入真实网易云歌词 API | ⚠️ 有价值但未接入 Provider |
| `LyricParser.kt` | LRC 解析 | ⚠️ 有价值但未接入 Provider |
| `LyricLine.kt` | 歌词数据类 | ⚠️ 有价值但未接入 Provider |
| `LyricRepository.kt` | 歌词 Room 缓存 | ⚠️ 有价值但未接入 Provider |
| `LyricEntity.kt` + `LyricDao.kt` | 歌词表 | ⚠️ 有价值但未接入 Provider |
| `PlaybackQueueManager.kt` | **独立播放队列** (repeat/shuffle/next/prev) | ❌ 与 Poweramp 队列冲突 |
| `PlaybackStateManager.kt` | **独立播放状态** (PLAYING/PAUSED/position) | ❌ 与 Poweramp 状态冲突 |
| `PlaybackService.kt` | **ForegroundService + MediaSession + 传输控制** | ❌ 独立播放器 |
| `MediaNotificationManager.kt` | **通知栏播放器** | ❌ 与 Poweramp 通知冲突 |
| `NetworkMonitor.kt` | 网络状态监听 | ⚠️ 未被任何代码使用 |
| `PlaybackHistoryRepository.kt` | 播放历史 + 自动截断 | ⚠️ 仅被 PlaybackService 调用 |
| `PlaybackHistoryEntity.kt` + `Dao` | 播放历史表 | ⚠️ 仅被 PlaybackService 调用 |
| `MainActivity.kt` (修改) | 新增播放控制 UI + 队列 + 状态 | ❌ 独立播放器 UI |
| `activity_main.xml` (修改) | 新增 Now Playing + 传输按钮 + SeekBar | ❌ 独立播放器 UI |
| `AndroidManifest.xml` (修改) | 注册 PlaybackService + 权限 | ❌ 为独立播放器服务 |
| 4 个 `ic_notification_*.xml` | 通知栏图标 | ❌ 为独立播放器服务 |

---

## 3. A类：必须保留

这些代码**直接服务于** Poweramp Provider / 网易云数据源 / 远程音频资源：

| # | 文件 | 原因 |
|---|------|------|
| A1 | `NeteasePlaybackResolver.kt` | 核心价值：将 songId → 真实可播放 URL。Provider 的 `openDocument` 和 `CALL_GET_URL` 都依赖它。没有它，Poweramp 无法播放网易云歌曲。 |
| A2 | `CachingPlaybackResolver.kt` | 核心价值：URL 缓存 + 过期刷新 + 重试。避免每次播放都请求 API，减少网络延迟。直接被 Provider `onCreate` 实例化。 |
| A3 | `AudioProxyFd.kt`（引用计数改进） | 核心价值：将 PlaybackSource 转为 Poweramp 可读的 seekable ParcelFileDescriptor。HandlerThread 引用计数是正确的生命周期改进。 |
| A4 | `NeteasePowerampProvider.kt`（resolver 切换 + 超时） | 核心价值：将 MockPlaybackResolver 替换为 NeteasePlaybackResolver；runBlocking 超时防止 binder 线程阻塞。这两项都是 Provider 层面的必要改进。 |
| A5 | `AudioStreamClient.kt`（未改但被 A3 依赖） | 核心价值：Range GET 读取远程音频字节流，被 AudioProxyFd 调用。 |

---

## 4. B类：可以保留，但需要重新定位

这些代码**本身有价值**，但当前定位错误——它们不应服务于一个独立播放器，而应服务于 Provider 数据层：

| # | 文件 | 当前问题 | 应该怎么改 |
|---|------|----------|-----------|
| B1 | `LyricService.kt` | 接入了真实网易云歌词 API，但**没有被任何 Provider 代码调用**。Poweramp 有自己的歌词显示，但目前读的是本地文件 tag。 | 保留歌词获取和缓存逻辑。如果 Poweramp 支持通过 Provider 提供歌词（如 `COLUMN_LYRICS` 或自定义 call），则通过 Provider 暴露；否则作为 App 内 UI 功能保留，但不应与播放器耦合。 |
| B2 | `LyricParser.kt` + `LyricLine.kt` | LRC 解析逻辑正确且通用。 | 保留。随 B1 一起重新定位。 |
| B3 | `LyricRepository.kt` + `LyricEntity.kt` + `LyricDao.kt` | Room 歌词缓存，架构合理。 | 保留。随 B1 一起重新定位。 |
| B4 | `PlaybackHistoryRepository.kt` + `Entity` + `Dao` | 播放历史数据本身有价值（统计、推荐），但当前**仅被 PlaybackService.playItem() 调用**，而 PlaybackService 是 C 类。 | 保留数据层。记录播放历史的调用点应改为 Provider 的 `openDocument`（当 Poweramp 请求播放时记录），而不是独立播放器的 playItem。 |
| B5 | `NetworkMonitor.kt` | 网络监听代码正确，但**完全没有被任何代码 collect**。 | 如果 CachingPlaybackResolver 需要在网络恢复时重试，可以在 Provider 层使用。否则删除。 |

---

## 5. C类：应该删除或停用

这些代码属于**独立播放器功能**，与项目最终目标冲突：

| # | 文件 / 代码 | 删除原因 |
|---|------------|----------|
| C1 | `PlaybackService.kt` | **这是一个完整的独立播放器 Service。** 它创建了 MediaSession、管理播放状态、处理传输控制（play/pause/next/prev）、接收蓝牙耳机按键、构建通知栏播放器。但 Poweramp 已经是播放器——它有自己的 MediaSession、通知栏、锁屏控制、蓝牙耳机支持、ForegroundService。本项目重复实现了 Poweramp 的全部播放器职责，却从未解码任何音频。`playItem()` 只改了内存 StateFlow，没有音频输出。 |
| C2 | `MediaNotificationManager.kt` | 构建了一个完整的媒体通知（封面 + 传输按钮 + MediaStyle + PendingIntent）。Poweramp 自己有通知栏播放器。两个 App 同时显示媒体通知会冲突——Android 系统只会激活一个 MediaSession。 |
| C3 | `PlaybackQueueManager.kt` | 完整的播放队列管理（setQueue / next / previous / insertNext / removeAt / shuffle / repeatMode）。Poweramp 有自己的播放队列和播放模式。本项目的队列与 Poweramp 的队列完全独立、不同步、无关联。 |
| C4 | `PlaybackStateManager.kt` | 播放状态管理（PLAYING / PAUSED / BUFFERING / position / duration / speed）。Poweramp 有自己的播放器状态。这个 StateFlow 的值是假的——`setPlaying()` 不会让任何音频开始播放。 |
| C5 | `activity_main.xml` 中的 Now Playing 区域（第 81-184 行） | 传输按钮（⏮ ▶ ⏭）、SeekBar 进度条、now_playing_title/artist/state。这些是播放器 UI，应该由 Poweramp 提供。 |
| C6 | `MainActivity.kt` 中的播放器代码 | 第 58-62 行（queueManager / stateManager / progressJob）、第 93-95 行（初始化）、第 106-107 行（initPlaybackUi）、第 118-210 行（全部播放 UI 逻辑）、第 309-319 行（saveSong 中 appendItems 到 queue）。 |
| C7 | `AndroidManifest.xml` 中的 PlaybackService 注册 | 第 54-62 行：`<service android:name=".service.PlaybackService" ...>` + intent-filter（MediaBrowserService + MEDIA_BUTTON）。 |
| C8 | `AndroidManifest.xml` 中的权限 | 第 7-9 行：`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_MEDIA_PLAYBACK`、`POST_NOTIFICATIONS`。这些权限只为 PlaybackService 服务。 |
| C9 | 4 个 `ic_notification_*.xml` drawables | 通知栏图标，只被 MediaNotificationManager 使用。 |
| C10 | `strings.xml` 中的播放器通知/状态字符串 | notification_channel_name / notification_channel_desc / notification_play / notification_pause / notification_prev / notification_next / state_playing / state_paused / state_buffering / state_idle / notification_nothing_playing。 |

---

## 6. 是否已经偏离项目最终目标

### **是。**

项目最终目标是：

> "让网易云音乐作为音乐数据源接入 Poweramp，使用户能在 Poweramp 内搜索、浏览并播放网易云音乐内容。"

第五阶段新增了**一整套独立播放器**（PlaybackService + MediaSession + 通知栏 + 队列 + 状态 + 传输控制 + 进度条），这套系统：

1. **重复了 Poweramp 的核心职责**：播放器界面、播放控制、播放队列、播放模式、播放进度、播放器状态、后台播放、通知栏、锁屏控制、蓝牙耳机按键——全部是 Poweramp 已经负责的功能。

2. **没有实际播放能力**：`PlaybackStateManager.setPlaying()` 只改了一个内存变量，没有任何音频解码或输出。用户点击 ▶ 按钮后看到状态变为"播放中"但听不到声音——这是一个假播放器。

3. **与 Poweramp 产生冲突**：两个 App 同时注册 MediaSession、同时显示媒体通知、同时响应蓝牙耳机按键。Android 只能激活一个 MediaSession——如果本项目的 Service 抢占了 MediaSession，Poweramp 的锁屏控制和蓝牙耳机控制反而会失灵。

4. **制造了虚假的完成感**：真机测试时看到"播放中"状态和 ⏸ 按钮切换，但实际上没有音频——这掩盖了真正应该测试的 Provider → Poweramp 播放链路。

5. **增加了不必要的复杂度和耦合**：MainActivity 现在同时管理搜索 UI 和播放器 UI，saveSong 同时写 Room 和 appendItems 到独立队列，PlaybackService 和 Provider 各有各的状态管理——三者之间产生了不必要的耦合。

---

## 7. 推荐的修正架构

### 数据流架构（应该保留的）

```
Poweramp (播放器：队列/进度/状态/通知/锁屏/蓝牙/EQ/DSP)
  ↓
  SAF / DocumentsProvider 调用
  ↓
Poweramp Provider (NeteasePowerampProvider)
  ├── queryRoots        → Root 列表
  ├── queryChildDocuments → 歌曲/歌单/专辑 (Room)
  ├── openDocumentThumbnail → 封面 (AlbumArtService)
  ├── openDocument       → ParcelFileDescriptor (音频流)
  └── CALL_GET_URL      → URL + headers (动态URL)
        ↓
  PlaybackResolver (CachingPlaybackResolver → NeteasePlaybackResolver)
        ↓
  PlaybackSource (URL + headers + expireTime + size)
        ↓
  AudioProxyFd (ProxyFileDescriptorCallback)
        ↓
  AudioStreamClient (OkHttp Range GET)
        ↓
  远程音频字节流 → Poweramp 解码播放
```

### 音频播放链路（应该保留的）

```
Poweramp 用户点击播放
  ↓
Poweramp 调用 Provider.openDocument(docId) 或 CALL_GET_URL
  ↓
Provider 解析 songId
  ↓
CachingPlaybackResolver.resolve(songId)
  ├── Room 缓存有效? → 返回缓存 URL
  └── 缓存过期/不存在
        ↓
      NeteasePlaybackResolver.resolve(songId)
        ↓
      网易云 API: POST /api/song/enhance/player/url
        ↓
      返回 PlaybackSource(url, headers, expireTime)
        ↓
[openDocument 路径]: AudioProxyFd.open(source)
                     → StorageManager.openProxyFileDescriptor
                     → ProxyFileDescriptorCallback.onRead → AudioStreamClient.readRange
                     → ParcelFileDescriptor → Poweramp

[CALL_GET_URL 路径]: Bundle(url + headers) → Poweramp 自行流式播放
```

### 应该删除的（C类全部）

```
PlaybackService          ← 删除
MediaNotificationManager ← 删除
PlaybackQueueManager     ← 删除
PlaybackStateManager     ← 删除
MainActivity 中的播放器 UI ← 删除
activity_main.xml 中的 Now Playing ← 删除
AndroidManifest 中的 Service 注册  ← 删除
FOREGROUND_SERVICE 权限  ← 删除
通知栏图标 drawables      ← 删除
播放器相关 strings        ← 删除
```

### 应该重新定位的（B类）

```
LyricService/Parser/Repository
  → 保留数据层，未来通过 Provider call 或 Poweramp API 暴露
  → 不绑定到独立播放器

PlaybackHistoryRepository
  → 保留数据层
  → 记录调用点改为 Provider.openDocument()（Poweramp 请求播放时记录）
  → 不由独立播放器的 playItem() 调用

NetworkMonitor
  → 如有需要，由 CachingPlaybackResolver 使用
  → 否则删除
```

### 修正后的项目结构

```
Poweramp
  ↓
NeteasePowerampProvider (DocumentsProvider)
  ├── 数据层: Room (songs / playlists / albums / lyrics / history)
  ├── 搜索层: NeteaseClient (search / playlist detail)
  ├── 封面层: AlbumArtService
  ├── 解析层: CachingPlaybackResolver → NeteasePlaybackResolver
  ├── 流式层: AudioProxyFd → AudioStreamClient
  └── MainActivity: 管理界面（搜索/歌单导入/同步到 Poweramp/Provider检查）
                    ← 不含播放器 UI、不含传输控制、不含进度条
```

---

## 审计结论

| 类别 | 文件数 | 处置 |
|------|--------|------|
| A类（必须保留） | 5 | 保留，这些是 Provider 核心价值 |
| B类（重新定位） | 5组 | 保留数据层，脱离独立播放器，未来通过 Provider 暴露 |
| C类（删除/停用） | 10项 | 删除全部独立播放器代码，消除与 Poweramp 的功能冲突 |

**核心判断**：第五阶段在增强 Provider 数据源的同时，意外地引入了一套完整的独立播放器系统。这套系统不播放音频、不连接 Poweramp、与项目目标直接冲突，应予以删除。Provider 数据源增强部分（NeteasePlaybackResolver + CachingPlaybackResolver + AudioProxyFd 改进 + 超时控制）是真正的价值所在，必须保留。
