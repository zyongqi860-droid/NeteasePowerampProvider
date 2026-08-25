# NeteasePowerampProvider 项目状态

| 项目 | 内容 |
|------|------|
| 项目名称 | NeteasePowerampProvider |
| 包名 / applicationId | `com.yczz.neteasepoweramp` |
| Provider Authority | `com.yczz.neteasepoweramp.provider` |
| 当前版本 | versionName `0.1.0-phase1`（versionCode 1，debug 变体） |
| 当前阶段 | **第二阶段**（网易云搜索 → Room 缓存 → Provider 展示；代码与构建完成，真机联调待执行） |
| 状态更新日期 | 2026-08-25 |
| 任务依据 | `C:\Users\zyongqi\Documents\# 项目任务：NeteasePowerampProvider 第一阶段.docx` |

---

## 1. 项目概述

**最终目标**：让网易云音乐的歌曲能够通过 Poweramp Track Provider 在 Poweramp 中搜索和播放。

**第一阶段目标**（当前阶段）：不接入网易云音乐，只实现一个最小、稳定、可安装的 Poweramp Track
Provider——Poweramp 能发现它、添加它，并通过它播放一首固定的测试音频
（`Poweramp Provider Test`）。第一阶段的完整任务边界与验收标准见上述任务文档（三十一节）。

## 2. 总体进度一览

| 模块 / 事项 | 状态 | 说明 |
|-------------|------|------|
| 官方 API 调研与集成 | ✅ 完成 | `poweramp_api_lib` 以官方源码 module 方式集成（同官方 provider 示例做法） |
| 工程骨架（Gradle Kotlin DSL） | ✅ 完成 | `settings.gradle.kts` / `build.gradle.kts` / 版本目录 `libs.versions.toml` |
| DocumentsProvider 核心实现 | ✅ 完成 | `NeteasePowerampProvider`：roots / document / children / thumbnail / openDocument 全部实现 |
| 测试音频与封面资源 | ✅ 完成 | `res/raw/test_audio.mp3`（官方示例 bensound-dubstep.mp3 副本）+ `test_cover.jpg` |
| MainActivity / SettingsActivity | ✅ 完成 | 按"保持简单"要求实现自检与快捷入口 |
| 日志与错误处理 | ✅ 完成 | 统一 Tag `NeteasePowerampProvider`；错误路径全部返回 `FileNotFoundException` 类异常 |
| 编译（`assembleDebug`） | ✅ 成功 | APK 已生成（见 §4），lint 0 错误；2026-08-25 已二次复验（up-to-date 通过） |
| Lint 检查 | ✅ 已运行 | 0 errors / 16 warnings（均为版本类提示，见 §4.2） |
| 单元测试 | ⚠️ 未配置 | 项目未创建 test 源集；第一阶段无可测纯逻辑，暂不阻塞 |
| git 基线 | ✅ 完成 | 仓库已初始化，基线提交 `5b440d2`（不含 build 产物 / local.properties） |
| 真机验证辅助脚本 | ✅ 完成 | `tools/phase1_device_check.ps1`：自动安装 + SAF 查询 + openDocument 读流 + logcat 核对 |
| 真机安装与 Poweramp 联调 | ❌ 待执行 | 任务文档第二十五/二十六节测试 1–7 尚无执行记录（本机当前无 ADB 设备） |
| 第一阶段完成标准（第二十七节） | 🟡 部分满足 | 编译相关条目已满足，设备相关条目待验证后勾选 |

图例：✅ 已完成　🟡 部分完成　❌ 未开始/待执行　⚠️ 有缺口

## 3. 已完成内容明细

### 3.1 工程结构

```
NeteasePowerampProvider/
├── settings.gradle.kts            # include :app 与 :poweramp_api_lib
├── build.gradle.kts
├── gradle/libs.versions.toml      # AGP 8.13.0 / Kotlin 2.1.20 / AndroidX 版本目录
├── POWERAMP_API_LICENSE.txt       # 官方 API 许可文件
├── poweramp_api_lib/              # Poweramp 官方 API（源码 module，来自 github.com/maxmpz/powerampapi）
└── app/
    ├── build.gradle.kts           # minSdk 21 / targetSdk 35 / compileSdk 36 / JVM 17
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/yczz/neteasepoweramp/
        │   ├── MainActivity.kt                    # 自检 + 快捷入口
        │   ├── provider/
        │   │   ├── NeteasePowerampProvider.kt     # DocumentsProvider 实现
        │   │   ├── ProviderConstants.kt           # authority / 固定 Document ID / 显示名
        │   │   ├── TestAudioManager.kt            # raw → filesDir 一次性提取（线程安全）
        │   │   └── TestTrack.kt                   # 测试曲目元数据常量
        │   └── settings/SettingsActivity.kt       # 静态信息 + Poweramp 检测
        └── res/raw/{test_audio.mp3, test_cover.jpg}
```

### 3.2 Provider 实现（对照任务文档）

| 任务条款 | 要求 | 实现情况 |
|----------|------|----------|
| 五 / AndroidManifest | 正确注册 Provider | `androidx.provider` DocumentsProvider，authority `com.yczz.neteasepoweramp.provider`，`exported=true`，intent-filter `android.content.action.DOCUMENTS_PROVIDER`，meta-data `com.maxmpz.PowerampTrackProvider=true`；**未申请 INTERNET 权限**（第一阶段离线原则） |
| 六 / 层级结构 | Root → Test Music → 测试曲 | `root` → `root/test_folder` → `root/test_folder/track_test_001` |
| 七 / Document ID | 稳定、非随机 | 全部为固定字符串常量（`ProviderConstants.kt`） |
| 八 / queryRoots | Root 字段齐全 | 返回 rootId/title/summary/icon/documentId，并带 `Root.FLAG_SUPPORTS_IS_CHILD`（Poweramp 子文件夹选择所需） |
| 九 / queryDocument | 区分文件夹与音频 | root/test_folder 为 `MIME_TYPE_DIR`，track 行含完整元数据；未知 ID 抛 `FileNotFoundException` |
| 十 / queryChildDocuments | 正确处理 projection | 所有查询按调用方 projection 构建 `MatrixCursor`，缺省时使用默认投影 |
| 十一 / Track Metadata | 必需字段 | TITLE/ARTIST/ALBUM/TRACK/DURATION(125000ms，与实际音频一致)/GENRE/MIME_TYPE/DISPLAY_NAME/SIZE（真实文件大小 + 兜底值）；提供元数据列使 Poweramp 免做标签扫描 |
| 十二 / openDocument | 按官方推荐方式 | 返回指向已提取本地文件的**可 seek 只读 `ParcelFileDescriptor`**（官方推荐做法，进度条拖动/wave-seekbar 天然可用）；仅拒绝写模式 |
| 十三 / 音频读取 | 不重复复制 | `TestAudioManager.ensureTestAudioFile()`：首次从 `res/raw` 复制到 `filesDir`，之后存在性检查即返回；Provider 启动时预热一次 |
| 十四 / 多 Track 并发 | 无全局可变播放状态 | 共享状态在 `onCreate` 后不可变；唯一懒加载对象由锁保护 |
| 十五 / 线程安全 | 各方法无数据竞争 | 同上；无 static mutable Cursor / currentTrack |
| 十六 / MainActivity | 简单 UI | 状态文本 + 「检查 Provider 配置」（SAF 查询自身 roots + 探测 Poweramp）+ 直达 Poweramp 音乐文件夹设置 + 设置页入口 |
| 十七 / 设置页 | 简单展示 | Authority/状态/测试歌曲静态信息 + best-effort Poweramp 检测（失败不影响 Provider） |
| 十八 / 日志 | 规定事件全覆盖 | initialized/queryRoots/queryDocument/queryChildDocuments/openDocument/track requested/test audio opened 均有日志；不含任何敏感数据 |
| 十九 / 错误处理 | 不崩溃客户端 | 业务异常统一捕获记日志并转 `FileNotFoundException`；`queryXxx` 出错返回 null 而非 crash |
| 二十一 / 依赖管理 | 最小依赖 | 仅 AndroidX（annotation/appcompat/core）+ 官方 API lib；无 Retrofit/Room/Compose 等 |
| 二十二 / API 集成 | 官方方式 | `poweramp_api_lib` 源码 module（官方示例同款集成方式） |
| 二十三 / Gradle | Kotlin DSL | 全部 `.kts`；AGP/Kotlin 版本对齐官方 powerampapi 仓库 |
| 二十八 / 代码质量 | null-safe、无多余权限 | Kotlin 编写，manifest 零权限声明 |

另外两个超出最低要求的细节：

- `COLUMN_LAST_MODIFIED` 使用 APK 安装/更新时间，便于 Poweramp 增量扫描。
- 文件夹行通过 `TrackProviderConsts.COLUMN_FLAGS` 上报 `FLAG_HAS_SUBDIRS` / `FLAG_NO_SUBDIRS`，
  让 Poweramp 在用户展开前不必加载子节点。

### 3.3 使用的 Poweramp 官方 API 类

- `com.maxmpz.poweramp.player.TrackProviderConsts`（COLUMN_FLAGS / FLAG_HAS_SUBDIRS / FLAG_NO_SUBDIRS / COLUMN_GENRE）
- `com.maxmpz.poweramp.player.PowerampAPI`（`ACTIVITY_SETTINGS`、Settings extras）
- `com.maxmpz.poweramp.player.PowerampAPIHelper`（Poweramp 包名探测）
- `com.maxmpz.poweramp.player.TableDefs.kt`（随官方库引入的表定义参考）

## 4. 构建与检查结果

### 4.1 编译

- `assembleDebug`：**BUILD SUCCESSFUL**
- APK 路径：`app/build/outputs/apk/debug/app-debug.apk`（约 5.7 MB，生成于 2026-08-25 15:22）
- 工具链：AGP 8.13.0 / Kotlin 2.1.20 / JDK 17；minSdk 21、targetSdk 35、compileSdk 36

### 4.2 Lint

`lint` 已运行：**0 errors, 16 warnings**。警告主要为两类，均不影响功能：

1. `OldTargetApi`：targetSdk=35 非最新（属任务文档二十三节"不为最新盲目升级"的既定取舍）；
2. AGP/依赖新版本提示（同样按"以兼容 Poweramp API 为先"保持现状）。

### 4.3 单元测试

项目尚未创建 `src/test` / `src/androidTest` 源集。第一阶段逻辑基本是 Android 框架胶水代码，
暂无独立可测纯逻辑；如后续需要，可优先为 Document ID 组装与元数据行填充补测试。

## 5. 待完成事项（第一阶段收尾）

以下均来自任务文档第二十五～二十七节，需要连接 Android 设备（安装 Poweramp 后）逐项执行。

**自动化前置检查**：连接设备后先运行
`powershell -ExecutionPolicy Bypass -File tools\phase1_device_check.ps1`，
脚本会依次完成：设备检测 → `adb install -r` → 启动 MainActivity → SAF 查询
（roots / root 子节点 / test_folder 子节点）→ openDocument 读流校验（>100KB）→
logcat 事件核对（initialized / queryRoots / queryDocument / queryChildDocuments /
openDocument / test audio opened），全部通过后再打印下方人工测试清单。
任一自动步骤失败即中止，先修复再继续。

1. `adb install -r app-debug.apk` 安装并确认 App 启动无 Crash、Provider 已注册；
2. Poweramp → Settings → Library → Music Folders 中添加本 Provider，确认能看到
   「网易云音乐」→「Test Music」→「Poweramp Provider Test」；
3. 播放测试音频，验证暂停/继续/上下曲不崩溃、退出重进后 Provider 仍在；
4. 特别测试（第二十六节）：重启手机后 Provider 存在；强制停止本 App 后 Poweramp 正常报错而非崩溃；
   连续播放两次；快速点击播放/暂停；拖动进度条（0:10 → 0:30 seek 验证）；
5. 以上全部通过后，对照第二十七节完成标准清单勾选收尾，第一阶段方可宣告关闭。

> 备注：git 仓库已于 2026-08-25 初始化并提交第一阶段基线（`5b440d2`）；
> `.gitignore` 已补充 `.kotlin/` 缓存目录。后续提交请保持不含 build 产物与 `local.properties`。

## 6. 未实现功能（按任务边界明确不做）

以下属于第二阶段及以后范围，第一阶段**有意不实现**（任务文档开头及第二十节）：

网易云搜索 / 登录（账号密码、二维码、WebView）/ Cookie / 歌单 / 歌词 /
真实网易云歌曲与播放 URL / VIP 相关 / 评论 / 推荐 / 排行榜 / 每日推荐 /
在线播放缓存 / 下载 / 任何接口逆向或 DRM、版权保护绕过。

对应地，第一阶段 Manifest 未申请 INTERNET 权限；第二阶段已按任务书放开 INTERNET（仅用于搜索），仍无其他权限。

## 7. 第二阶段进度（2026-08-25）

按第二阶段任务书完成"搜索 → metadata → 本地数据库 → Provider 显示"，**未实现**播放 URL / 登录 / Cookie / 歌词 / 缓存下载。

| 模块 | 状态 | 说明 |
|------|------|------|
| `model/Song.kt` | ✅ | songId/title/artist/album/durationMs/coverUrl |
| `netease/NeteaseClient.kt` | ✅ | OkHttp + org.json；`music.163.com/api/search/get` 免登录歌曲搜索；超时/非 200/坏 JSON 均转异常不崩溃 |
| `service/SearchService.kt` | ✅ | 单线程 Executor 编排：search（回调式，UI 不做网络）与 saveSongs（REPLACE 入库）；日志 search start/result count/database insert |
| `database/{SongEntity,SongDao,AppDatabase}.kt` | ✅ | Room 2.6.1 + KSP（Kotlin 2.1.20-1.0.32）；songs 表含 updated_at；insertSongs/getSongs/searchSongs/getSong/clearSongs |
| `provider/ProviderCursorBuilder.kt` | ✅ | 从 Provider 抽出的行构建器：folder/testTrack/song 三类行统一投影处理 |
| Provider 层级扩展 | ✅ | `root → root/netease_search("搜索结果") + root/test_folder(保留)`；song 文档 ID `root/netease_search/song_<songId>`（稳定，第三阶段播放复用） |
| openDocument 对 song 的行为 | ✅ 按任务书兜底 | NetEase 歌曲暂返回第一阶段测试音频 fd（日志明确标注 placeholder），真实播放留给第三阶段 |
| MainActivity 搜索 UI | ✅ | 关键词输入 + 搜索按钮 + 双行结果列表；点击行保存入库并 Toast；网络/DB 全在后台线程 |
| Manifest | ✅ | 新增 INTERNET 权限（第二阶段首次放开），仍无其他权限 |
| 编译 / lint | ✅ | `assembleDebug` BUILD SUCCESSFUL；lint 0 errors / 32 warnings（均为依赖版本类提示） |

数据流：

```
MainActivity 输入"周杰伦"
→ SearchService.search()（后台单线程）
→ NeteaseClient.request()  [music.163.com/api/search/get]
→ List<Song>（org.json 解析）
→ UI 列表展示；点击行
→ SearchService.saveSongs()
→ Room songs 表
→ Poweramp 扫描 queryChildDocuments(root/netease_search) 读库展示
```

第二阶段真机测试清单（任务书十一节）：搜索周杰伦出结果；Poweramp 刷新见「网易云音乐」；进入「搜索结果」看到歌曲；点击歌曲播测试音频或提示；重启手机后数据库仍在。另：第一阶段 §5 清单仍未执行（无设备）。

## 8. 第三阶段建议

实现网易云播放 URL 获取（评估 `CALL_GET_URL` 动态 URL 机制与 `openProxyFileDescriptor` 流式方案），让 Poweramp 通过 `root/netease_search/song_<songId>` 直接播放真实网易云歌曲；届时需处理 URL 过期、VIP/灰色曲目降级与日志红线（禁止记录 Cookie/Token）。

---
*本文件由开发过程自动维护；阶段推进或验证完成后请同步更新 §2、§5 与完成标准勾选情况。*
