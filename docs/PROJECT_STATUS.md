# NeteasePowerampProvider 项目状态

> **最后更新：2026-08-27（第二轮会话结束）**
> **当前阶段：第五阶段完成 ✅ + VIP 登录功能（待用户实测）**
> **版本：0.6.1-phase5 (versionCode 8)**

---

## 0. 给接手 AI 的快速导航

**你只需要读这一个文件就能知道项目全貌和下一步该做什么。**

- 项目最终目标：让网易云音乐作为数据源接入 Poweramp，用户在 Poweramp 内搜索、浏览、播放网易云歌曲
- **Poweramp = 唯一播放器**，本项目 = Provider/数据源适配层
- **最终目标已达成**：2026-08-27 真机验证，Poweramp 通过 provider 成功播放网易云歌曲出声（用户确认）
- 当前待办：用户实测「手机号登录」→ VIP 歌曲整首播放（功能已实现并装机）
- **最重要的运维教训**：`./gradlew connectedDebugAndroidTest` 跑完会**自动卸载 app**（连带清掉 Room 数据、Poweramp 的 SAF URI 授权、系统 provider 索引），日常装机用 `adb install -r`，切勿随手跑 connected 测试
- 下一步任务：见 §10

---

## 1. 项目概述

**最终目标**：让网易云音乐的歌曲能够通过 Poweramp Track Provider 在 Poweramp 中搜索和播放。

**核心原则**：
- Poweramp 负责全部播放器逻辑（播放、队列、状态、进度、通知、锁屏、蓝牙、EQ、DSP）
- 本项目只负责数据源适配（搜索、metadata、封面、歌词缓存、播放URL解析、远程音频流代理、账号登录）
- 本项目**不创建** MediaSession、不创建播放 Service、不创建自己的播放队列

| 项目 | 内容 |
|------|------|
| 包名 / applicationId | `com.yczz.neteasepoweramp` |
| Provider Authority | `com.yczz.neteasepoweramp.provider` |
| minSdk / targetSdk / compileSdk | 21 / 35 / 36 |
| 工具链 | AGP 8.13.0 / Kotlin 2.1.20 / JDK 17 |
| Poweramp API | `poweramp_api_lib` 源码 module（官方），测试设备 Poweramp build-988-uni |
| 测试设备 | vivo V2425A（Android 16），USB 调试 |

---

## 2. 完成阶段一览

| 阶段 | 状态 | 说明 |
|------|------|------|
| 第一阶段 | ✅ 完成 | 最小 Provider：测试音频、queryRoots/queryDocument/queryChildDocuments/openDocument |
| 第二阶段 | ✅ 完成 | 网易云搜索、Room 缓存歌曲、封面、歌单导入 |
| 第三阶段 | ✅ 完成 | 动态播放 URL（PlaybackResolver/AudioProxyFd/AudioStreamClient/CALL_GET_URL） |
| 第四阶段 | ✅ 完成 | 搜索增强、专辑封面缓存、歌单、Room v4 |
| 第五阶段 | ✅ 完成 | 架构纠偏 + 修复播放链路 + **真机出声**（2026-08-27） |
| VIP 登录 | 🔄 待实测 | 手机号登录(weapi) + Cookie 注入已实现装机，等用户实测整首播放 |

---

## 3. 本轮会话完成的工作（2026-08-27）

### 3.1 修复网易云 song/url API code=400（核心修复）

**根因**：API 参数格式错误。`?id=<songId>`（单数）返回 `{"msg":"参数错误","code":400}`；
正确格式是 **`?ids=%5B<songId>%5D`（数组）**，返回 code=200 + 真实播放 URL。

**curl 实测证据**：
```
id=5257138         → {"msg":"参数错误","code":400}
ids=[5257138]      → {"data":[{"id":5257138,"url":"http://m801.music.126.net/...mp3","br":320001,"size":12764517,"type":"mp3","code":200,"expi":1200,...}]}
```

**修复内容**（`playback/NeteasePlaybackResolver.kt`）：
- `id=` → `ids=[x]`（括号预编码为 %5B/%5D）
- 返回的 CDN URL 从 `http://` 改写为 `https://`（实测 music.126.net 支持 https，避免 cleartext 依赖）
- 码率 fallback：320k 失败（VIP 限定 url=null）→ 自动降 128k
- 实测 CDN 音频 URL **无需任何请求头**即可 Range GET（HTTP 206 + 合法 ID3 MP3 头）

### 3.2 修复 AudioProxyFd 回调线程死亡 bug（严重崩溃）

**现象**：真机测试中 `openDocument` 抛 NPE：
`Attempt to read from field 'android.os.MessageQueue android.os.Looper.mQueue' on a null object reference in Handler.<init>`

**根因**：第五阶段加的"生命周期管理"在最后一个 proxy fd 关闭时调用 `quitSafely()` 杀死共享 HandlerThread。线程死后 `getLooper()` 因 `!isAlive()` 返回 **null**，之后任何播放请求 `Handler(null)` 必 NPE。**用户播完第一首歌后，第二首必崩。**

**修复**：回调线程懒启动后**常驻进程，永不 quit**（一个空闲阻塞线程成本≈0）。删除了零调用者的 `shutdown()` 和 `tryQuitCallbackThread()`。

### 3.3 依赖与目录清理

- `app/build.gradle.kts`：删除 `androidx.media`、`androidx.lifecycle.runtime`、`androidx.lifecycle.viewmodel`（grep 确认零代码引用）
- `gradle/libs.versions.toml`：同步删除孤儿条目
- 删除空目录 `network/`

### 3.4 Manifest 变更

- 新增 `android:usesCleartextTraffic="true"`（https 改写为主，明文兜底）

### 3.5 新增真机端到端测试（ProviderDeviceTest.kt）

| 测试 | 验证内容 |
|------|----------|
| `callGetUrlReturnsPlayableUrlForRealNeteaseSong` | 完全模拟 Poweramp：CALL_GET_URL + 完整 tree URI → 返回 https URL → 实际下载首字节验证 MP3 magic |
| `openDocumentStreamsRealNeteaseSong` | openDocument → proxy fd → Range GET 流式读取 → MP3 magic 验证 |
| `realPlaylistImportFlowsThroughProvider`（修复） | 原测试误用 fixture id 888888002（某用户私人歌单，匿名无曲目）→ 改用真实公共歌单 **3778678（热歌榜，200 首）** |

**结果：connectedDebugAndroidTest 11/11 全部通过**（修复 3.1/3.2 后）。

### 3.6 排查"Provider 从 SAF 消失"事件（重要运维经验）

**现象**：跑完 connected 测试后，Poweramp 找不到 provider、SAF 侧边栏没有「网易云音乐」root、`dumpsys activity providers` 无 yczz 条目。

**根因**：**`connectedDebugAndroidTest` 结束时 Gradle 自动卸载 app 和测试 APK**。卸载连带：
1. 系统清除了 Poweramp 对我们 provider 的 **SAF URI grant**（持久授权绑定安装包）
2. 系统 provider 索引失效
3. Room 数据库被清空（用户保存的歌曲全部丢失）
4. Poweramp 库中残留死条目（点击播放静默失败 → PAUSED 0:00）

**Poweramp 访问 provider 的机制**（本次确认）：Poweramp 不是系统应用、无 MANAGE_DOCUMENTS 权限，靠的是用户在「音乐文件夹」里通过 **SAF 对话框选择 provider root 时获得的持久 URI grant**（provider 声明了 `grantUriPermissions="true"`）。URI grant 随卸载失效。

**恢复方法**：`adb uninstall` + 全新 `adb install`（当时 versionCode 升到 6 确保系统完全重新索引）→ 用户在 Poweramp 音乐文件夹里重新添加 provider → 恢复正常。

**教训**：日常装机一律用 `adb install -r`；只在必要时跑 connected 测试，跑完必须重装 + 重新授权 + 重新播种数据。

### 3.7 最终目标达成：真机出声 ✅

用户重新接入 provider 后，在 Poweramp 中点播 ESCAPE.mp3（(G)I-DLE，网易云 songId=1927421982），**成功播放**。
但只有 30 秒——该曲为 VIP 歌曲，**匿名请求只返回试听片段**（freeTrialInfo），链路本身完全正确。由此引出 VIP 登录需求（§4）。

### 3.8 Cookie 注入基础设施

| 文件 | 内容 |
|------|------|
| `netease/NeteaseCookieStore.kt`（新增） | MUSIC_U 存取（私有 SharedPreferences，绝不打日志），`cookieHeader()` |
| `playback/NeteasePlaybackResolver.kt` | 构造参数 `cookieProvider: (() -> String?)?`，resolve 时携带 Cookie 头 |
| `netease/NeteaseClient.kt` | 构造参数 `cookieStore`，search/fetchPlaylist/verifyAccount 携带 Cookie |
| `provider/NeteasePowerampProvider.kt` | onCreate 装配 cookieStore（**每次 resolve 惰性读取**，改 cookie 免重启进程） |
| `service/SearchService.kt` / `PlaylistService.kt` | NeteaseClient 传入 cookieStore |
| `settings/SettingsActivity.kt` | Cookie 粘贴 UI（保存/验证/清除）+ `verifyAccount()` 显示昵称+VIP 等级 |
| `database/SongDao.kt` | 新增 `clearPlaybackCache()`：**cookie 变更时清空全部缓存 URL**（防止匿名时代的 30 秒试听 URL 被复用 10 分钟） |

### 3.9 手机号登录（weapi 加密协议）

**背景**：明文登录端点已被网易云封锁：
- `/api/login/cellphone` → 401「无权限访问. ENC」
- `/api/sms/cellphone/send` → 404

必须走 **weapi 加密**（网易云网页端自用协议，公开算法）。

**实现**：
| 文件 | 内容 |
|------|------|
| `netease/WeapiCrypto.kt`（新增） | AES-128-CBC 双重加密（preset key + 随机 16 字符 key）+ raw RSA（无 padding，modPow）签名 secKey |
| `netease/NeteaseClient.kt` | `loginCellphone(phone, countrycode, password)`：密码 **md5 小写 32 位**后入 JSON → weapi POST `/weapi/login/cellphone` → 从响应 `Set-Cookie` 提取 MUSIC_U → 返回昵称+vipType |
| `settings/SettingsActivity.kt` | 「手机号登录(推荐)」区块：手机号+密码+登录按钮；成功即存 cookie + 清 URL 缓存 + 显示 VIP 状态；密码框用后即清 |

**算法验证方法**（可复用）：先用 Python + pycryptodome 原型（AES-CBC×2 + RSA modPow + POST）打假凭据，返回 `code=502 "账号或密码错误"` 证明「加密算法+端点+参数」整条链路正确，再翻译成 Kotlin。**Kotlin 版与 Python 原型逐参数一致。**

**安全设计**：密码只用于登录请求（md5 后再加密传输），不落盘不打日志；设备上长期保存的只有 MUSIC_U cookie；cookie 只发给 music.163.com。

**已知风险**：异地/新设备登录可能触发网易云风控（code=405/406「系统繁忙」），此时用设置页下方「手动粘贴 Cookie」兜底。

### 3.10 其他改进

- **保存歌曲后自动重扫描**（MainActivity）：`saveSong()` 后 1.5 秒自动发 `ACTION_SCAN_DIRS` 广播（原需手动点「同步到 Poweramp」）
- SettingsActivity 保留手动 Cookie 粘贴作为兜底路径

---

## 4. 登录 / VIP 架构（当前）

```
SettingsActivity
├── 手机号登录（推荐）
│     → NeteaseClient.loginCellphone(phone, "86", password)
│     → WeapiCrypto.encrypt({phone, countrycode, md5(password), rememberLogin})
│     → POST https://music.163.com/weapi/login/cellphone
│     → Set-Cookie: MUSIC_U=... → NeteaseCookieStore 保存
│     → SongDao.clearPlaybackCache()（丢弃匿名试听 URL 缓存）
└── 手动粘贴 MUSIC_U（兜底，浏览器 F12 获取）
      → 同样走 CookieStore + 清缓存

播放链路（带账号）：
Poweramp CALL_GET_URL / openDocument
  → NeteasePlaybackResolver（cookieProvider 惰性读取 CookieStore）
  → GET /api/song/enhance/player/url?ids=[x]&br=320000  +  Cookie: MUSIC_U=...
  → VIP 账号返回整首 URL（匿名只给 30 秒试听）
  → https 改写 → Poweramp 流式播放整首
```

---

## 5. Provider 播放链路（核心链路）

```
Poweramp 用户点击播放
  ↓
Poweramp 调用 CALL_GET_URL 或 openDocument
  ↓
NeteasePowerampProvider
  ├── CALL_GET_URL 路径（Poweramp 首选，实测走的就是这条）:
  │     extractDocumentId(arg) → parseSongId → handleGetUrl
  │     → CachingPlaybackResolver.resolve(songId)
  │       → Room 缓存有效？直接用 : NeteasePlaybackResolver
  │         → ids=[x] + Cookie(MUSIC_U) → https 改写
  │     → Bundle(url + headers) → Poweramp 自行流式播放
  │
  └── openDocument 路径（备用）:
        → CachingPlaybackResolver.resolve(songId)
        → AudioProxyFd.open(source)  ← 回调线程常驻进程，永不 quit
        → StorageManager.openProxyFileDescriptor + ProxyFileDescriptorCallback
        → AudioStreamClient.readRange (OkHttp Range GET)
        → ParcelFileDescriptor → Poweramp 解码播放
```

**关键参数**：
- `RESOLVE_TIMEOUT_MS = 15s`（binder 线程防无限阻塞）
- URL 缓存 10 分钟（API 返回 expi=1200s，保守取半）+ **cookie 变更时全量清空**
- 码率 fallback：320k → 128k

---

## 6. 文件清单（delta：本轮新增/修改）

### 6.1 本轮新增

| 文件 | 功能 |
|------|------|
| `netease/NeteaseCookieStore.kt` | MUSIC_U cookie 存取（私有 prefs，不打日志） |
| `netease/WeapiCrypto.kt` | weapi 请求加密（AES-CBC×2 + raw RSA） |

### 6.2 本轮修改

| 文件 | 变更 |
|------|------|
| `playback/NeteasePlaybackResolver.kt` | ids=[x] 参数、https 改写、码率 fallback、cookieProvider |
| `playback/AudioProxyFd.kt` | **删除 quit 逻辑**（回调线程常驻） |
| `netease/NeteaseClient.kt` | cookieStore 注入、verifyAccount、loginCellphone、AccountStatus/LoginResult |
| `provider/NeteasePowerampProvider.kt` | 装配 cookieStore 到 resolver |
| `database/SongDao.kt` | clearPlaybackCache() |
| `service/SearchService.kt` / `PlaylistService.kt` | NeteaseClient 带 cookie |
| `settings/SettingsActivity.kt` | 手机号登录 + Cookie 粘贴双区块 |
| `MainActivity.kt` | saveSong 自动重扫描；triggerPowerampRescan() 抽取复用 |
| `AndroidManifest.xml` | usesCleartextTraffic |
| `app/build.gradle.kts` | 删 3 个依赖；versionCode 8 |
| `gradle/libs.versions.toml` | 删孤儿条目 |
| `res/layout/activity_settings.xml` | 登录区 + cookie 区 |
| `res/values/strings.xml` | 登录/cookie 全部文案 |
| `androidTest/.../ProviderDeviceTest.kt` | +2 个真实播放链路测试；修歌单测试 id |

其余文件清单见 git 历史或 §11 目录结构。

---

## 7. 已解决：网易云 API code=400（原阻塞点）

~~网易云 song/url API 返回 code=400，播放失败。~~ **已解决（2026-08-27）**。

- 根因：参数必须用数组格式 `ids=[x]`，单数 `id=x` 返回 400「参数错误」（见 §3.1）
- 修复后真机实测：CALL_GET_URL 返回有效 https MP3 URL，Poweramp 出声
- 网上旧资料常写 `id=`，注意甄别

---

## 8. 真机验证进度（截至本轮结束）

| 验证项 | 结果 |
|--------|------|
| connected 测试 11/11（含 CALL_GET_URL/openDocument 真实链路） | ✅ PASS |
| song/url API 返回有效 URL（ids 数组格式） | ✅ PASS（curl + 真机） |
| 音频 URL https + Range GET 无需头 | ✅ PASS（curl 实测 206 + ID3） |
| 重复打开 proxy fd 不再崩溃（线程修复） | ✅ PASS |
| Poweramp 扫描/元数据/封面 | ✅ PASS |
| **Poweramp 播放网易云歌曲出声（最终目标）** | ✅ **PASS（用户确认）** |
| ESCAPE 播放时长 | ⚠️ 30 秒试听（VIP 曲目匿名限制，非 bug） |
| 手机号登录（weapi） | 🔄 功能就绪，**待用户实测** |
| VIP cookie 下整首播放 | 🔄 待用户实测 |
| 无独立播放器残留 | ✅ PASS |

---

## 9. 构建与装机（标准操作）

```bash
# 编译（Windows Git Bash）
./gradlew assembleDebug

# 装机（日常：保数据、保 Poweramp 授权）
adb install -r app/build/outputs/apk/debug/app-debug.apk

# ⚠️ 危险操作：跑完会自动卸载 app！会丢 Room 数据 + Poweramp SAF 授权 + 系统索引
# ./gradlew connectedDebugAndroidTest
# 跑完必须：adb install → 用户重新在 Poweramp 添加 provider → 重新保存歌曲

# 触发 Poweramp 重扫描（或 app 内点"同步到 Poweramp"；保存歌曲已自动化）
adb shell am broadcast -a com.maxmpz.audioplayer.ACTION_SCAN_DIRS \
  --ez scanProviders true \
  -n com.maxmpz.audioplayer/.player.PowerampAPIReceiver

# 查日志（缓冲区已扩到 4MB，可按需 dump，无需常驻监听）
adb logcat -G 4M          # 重启后需重设一次
adb logcat -d -s NeteasePowerampProvider:V
```

**运维注意事项（本轮踩坑记录）**：
- `connectedDebugAndroidTest` 会卸载 app（§3.6）——最重要的一条
- 后台常驻 `adb logcat` 任务会因 adb server 重启而断（exit 255），改用 `logcat -d` 按需读
- `adb install -r` 同版本覆盖后 provider 可能不重新发布到系统表；若 SAF 侧边栏见不到 root，先 `adb uninstall` 再全新 install
- SettingsActivity 非 exported，shell 不能 `am start` 直启，需从 MainActivity 进
- Poweramp UI 自动化可行：uiautomator dump 找坐标 → input tap；媒体键会被 B 站等其他 MediaSession 截走，先 dispatch pause
- vivo 设备 logcat 缓冲区默认小且刷得快，先 `adb logcat -G 4M`

---

## 10. 下一步任务（给接手 AI）

### 立即（高优先级）

1. **用户实测手机号登录 + VIP 整首播放**（功能已装机）
   - 成功：日志应显示 `resolve playback source (get_url) songId=1927421982` 且 Poweramp 播满 3:29
   - 若登录报 code=405/406 风控：引导用户用「手动粘贴 Cookie」兜底
   - 若仍 30 秒：查 `adb logcat -d -s NeteasePowerampProvider:V` 确认 cookie 头是否带上
2. **更新本文档**验证表 §8

### 后续（低优先级）

3. **登录风控对抗**：若手机号登录频繁触发风控，可考虑实现二维码登录（`/weapi/login/qrcode/unikey` + 轮询，同样走 WeapiCrypto，端点字段名需实测）
4. **歌词暴露**：LyricService 已实现但未接入 Provider（Poweramp 支持 `FLAG_HAS_LYRICS`）
5. **播放历史完善**：目前只记录 play request（completed=false）
6. **Room 正式迁移**：version 4 仍是 `fallbackToDestructiveMigration()`，升级丢数据（卸载场景已经历过一次）
7. **封面缓存验证**：AlbumArtService 已实现，Poweramp 中显示效果待确认

---

## 11. 项目目录结构（当前）

```
NeteasePowerampProvider/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/libs.versions.toml
├── poweramp_api_lib/              # Poweramp 官方 API（源码 module）
├── docs/
│   ├── PROJECT_STATUS.md          # ← 本文件
│   └── PHASE5_AUDIT.md            # 第五阶段架构审计报告
└── app/
    ├── build.gradle.kts           # versionCode 8 / 0.6.1-phase5
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml    # +usesCleartextTraffic
        │   ├── java/com/yczz/neteasepoweramp/
        │   │   ├── MainActivity.kt              # 搜索/保存(自动重扫描)/歌单/同步/状态
        │   │   ├── provider/
        │   │   │   ├── NeteasePowerampProvider.kt  # +cookieStore 装配
        │   │   │   ├── ProviderConstants.kt
        │   │   │   ├── ProviderCursorBuilder.kt
        │   │   │   ├── TestAudioManager.kt
        │   │   │   └── TestTrack.kt
        │   │   ├── playback/
        │   │   │   ├── AudioProxyFd.kt           # 回调线程常驻（已修复 quit bug）
        │   │   │   ├── CachingPlaybackResolver.kt
        │   │   │   ├── NeteasePlaybackResolver.kt # ids=[x] + https + cookie + 码率fallback
        │   │   │   ├── MockPlaybackResolver.kt
        │   │   │   ├── PlaybackResolver.kt
        │   │   │   ├── PlaybackSource.kt
        │   │   │   └── PlaybackException.kt
        │   │   ├── service/
        │   │   │   ├── AudioStreamClient.kt
        │   │   │   ├── SearchService.kt          # +cookie
        │   │   │   ├── PlaylistService.kt        # +cookie
        │   │   │   └── AlbumArtService.kt
        │   │   ├── netease/
        │   │   │   ├── NeteaseClient.kt          # +login/verify/cookie
        │   │   │   ├── NeteaseCookieStore.kt     # 新增
        │   │   │   └── WeapiCrypto.kt            # 新增：weapi 加密
        │   │   ├── database/                     # Room v4
        │   │   │   ├── AppDatabase.kt / SongDao.kt(+clearPlaybackCache) / SongEntity.kt
        │   │   │   ├── PlaylistDao.kt / PlaylistEntity.kt / PlaylistTrackEntity.kt
        │   │   │   ├── LyricDao.kt / LyricEntity.kt
        │   │   │   ├── PlaybackHistoryDao.kt / PlaybackHistoryEntity.kt / PlaybackHistoryRepository.kt
        │   │   │   └── SongMappers.kt
        │   │   ├── lyrics/                       # 歌词（数据层，未接入 Provider）
        │   │   ├── model/
        │   │   │   ├── Song.kt / Playlist.kt / SessionManager.kt / UserSession.kt
        │   │   └── settings/
        │   │       └── SettingsActivity.kt       # +手机号登录 +Cookie 粘贴
        │   └── res/
        │       ├── layout/activity_main.xml
        │       ├── layout/activity_settings.xml  # +登录区 +cookie区
        │       ├── values/strings.xml
        │       ├── drawable/ / mipmap-*/
        │       └── raw/test_audio.mp3
        └── androidTest/
            └── java/com/yczz/neteasepoweramp/provider/ProviderDeviceTest.kt  # 11 tests
```

---

## 12. 关键代码位置索引

| 需要修改什么 | 文件 | 方法/位置 |
|-------------|------|-----------|
| song/url 请求（参数/码率/cookie） | `playback/NeteasePlaybackResolver.kt` | `resolveNeteaseUrl()` + companion 常量 |
| weapi 加密 | `netease/WeapiCrypto.kt` | `encrypt()` |
| 手机号登录 | `netease/NeteaseClient.kt` | `loginCellphone()` |
| cookie 存取 | `netease/NeteaseCookieStore.kt` | 全部 |
| cookie 变更清缓存 | `database/SongDao.kt` | `clearPlaybackCache()` |
| 登录/cookie UI | `settings/SettingsActivity.kt` | onCreate |
| URL 缓存过期/重试 | `playback/CachingPlaybackResolver.kt` | `resolve()` |
| resolve 超时 | `provider/NeteasePowerampProvider.kt` | `RESOLVE_TIMEOUT_MS` |
| CALL_GET_URL URI 解析 | `provider/NeteasePowerampProvider.kt` | `extractDocumentId()` |
| ProxyFileDescriptor | `playback/AudioProxyFd.kt` | `open()`（线程常驻，勿加 quit） |
| Range HTTP 请求 | `service/AudioStreamClient.kt` | `readRange()` |
| 保存后自动重扫描 | `MainActivity.kt` | `saveSong()` → `triggerPowerampRescan()` |
| 真机播放链路测试 | `androidTest/.../ProviderDeviceTest.kt` | `callGetUrlReturnsPlayableUrlForRealNeteaseSong` 等 |

---

## 13. 本轮会话时间线（供追溯）

1. curl 定位 code=400 根因（ids 数组格式）→ 修复 resolver + https 改写
2. 发现音频 URL 为 http → Manifest 加 cleartext + https 改写双保险
3. 修依赖清理 + 删空目录
4. 新增 2 个端到端真机测试 → 首跑 9/11，暴露 AudioProxyFd 线程 bug → 修复 → **11/11**
5. 测试自动卸载 app → SAF root 消失、Poweramp 失联 → 排查（系统 provider 表为 0 条）→ 卸载重装恢复 → 用户重新授权接入
6. 重播种 ESCAPE（搜索→保存→自动同步）→ **Poweramp 播放成功（30 秒试听，链路正确）**
7. VIP 需求 → Cookie 注入全套（存储/注入/验证/清缓存/设置页）
8. 用户要手机号登录 → 明文端点被封 → Python 原型验证 weapi → Kotlin 实现 WeapiCrypto + loginCellphone + 设置页登录 UI → 装机（versionCode 8）
9. 待用户实测：登录 → 整首播放
