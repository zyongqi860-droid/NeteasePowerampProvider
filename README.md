# 网易云 → Poweramp

在 [Poweramp](https://powerampapp.com/) 里听网易云：搜索、导入歌单、登录 VIP，播放、EQ、锁屏、蓝牙都还是 Poweramp 自己的。

这不是播放器，只是数据源。

## 需要什么

- 手机 Android 5.0 及以上
- 已安装 Poweramp

## 怎么用

1. 安装本 App 并打开
2. 点 **设置** → **打开网易云登录页**，用网易云 App 扫码或账号登录  
   （电脑登录 music.163.com 后，也可以把 Cookie 里的 `MUSIC_U` 粘贴进来）
3. 搜歌，点一首保存；或填公开歌单的数字 ID 导入
4. 点 **同步到 Poweramp**
5. 打开 Poweramp → **设置 → 音乐文件夹** → 添加本 Provider（名称类似「网易云音乐」）
6. 在 Poweramp 曲库里点歌播放

未登录时 VIP 歌曲大约只有 30 秒试听。登录成功后可播整首。

封面和歌词在 Poweramp 扫描后出现；刚同步完可以再扫一次。

## 登录说明

登录凭证只存在你手机上，不会上传。网页登录失败时，用电脑浏览器：

1. 打开 https://music.163.com 并登录
2. F12 → 应用 / Application → Cookie → `music.163.com`
3. 复制 `MUSIC_U` 的值（或整段 Cookie）
4. 粘贴到本 App 设置页 → 保存 → 验证（应显示昵称和 VIP）

## 从源码安装

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 许可

内含 Poweramp 官方 API（`poweramp_api_lib`），见 `POWERAMP_API_LICENSE.txt`。
