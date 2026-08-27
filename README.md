# NeteasePowerampProvider

把网易云音乐接到 [Poweramp](https://powerampapp.com/) 里用：搜索、导入歌单、登录 VIP，然后在 Poweramp 里播放、用 EQ / DSP / 锁屏 / 蓝牙。

本项目**不是播放器**。Poweramp 负责播放；这里只做数据源（DocumentsProvider / Track Provider）。

## 功能

- 在 Poweramp 的「音乐文件夹」里添加「网易云音乐」根目录
- 关键词搜索网易云歌曲，保存后同步到 Poweramp
- 按公开歌单数字 ID 导入曲目
- 网页登录 / 粘贴 `MUSIC_U` Cookie，VIP 歌曲可播整首（匿名只有约 30 秒试听）
- 封面缩略图、LRC 歌词（Poweramp 扫描后显示）
- 曲库多选删除，深色界面（接近 Poweramp 配色）

## 要求

- Android 5.0+（minSdk 21）
- 已安装 Poweramp（测试过 build-988-uni）
- USB 调试（装 debug 包时）

## 使用

1. 安装 debug APK，打开本 App
2. 设置 → **打开网易云登录页**（或电脑浏览器登录后粘贴 `MUSIC_U`）
3. 搜索歌曲并点选保存，或导入歌单 ID
4. 点「同步到 Poweramp」
5. Poweramp → 设置 → 音乐文件夹 → 添加本 Provider 根目录

VIP 登录 Cookie 只存在本机 `SharedPreferences`，不会写入仓库、也不会打进日志。

## 构建

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

不要随手跑 `./gradlew connectedDebugAndroidTest`：跑完会卸载 App，清掉 Room 数据和 Poweramp 的 SAF 授权。

## 技术要点

| 项 | 值 |
|----|----|
| 包名 | `com.yczz.neteasepoweramp` |
| Provider Authority | `com.yczz.neteasepoweramp.provider` |
| 版本 | 0.9.4-pa-theme |
| 播放 | Poweramp `CALL_GET_URL` → 网易云 `song/url`（`ids=[x]`） |

`poweramp_api_lib` 来自 Poweramp 官方 API，许可证见 `POWERAMP_API_LICENSE.txt`。

## 隐私

仓库里**没有**账号、Cookie、密钥、本机路径。登录凭证只留在设备上。
