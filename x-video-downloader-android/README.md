# X 视频下载器（Android MVP）

这是一个面向个人使用的 Android 最小版本：粘贴公开的 `x.com/.../status/...` 或 `twitter.com/.../status/...` 链接，读取公开帖子中的普通视频，然后在 App 内显示下载进度并保存到 `Movies/XDownloader`。

## 重要边界

- App 不内置代理/VPN，也不承诺绕过网络封锁；手机必须能够访问解析接口和视频 CDN。
- App 启动时只在前台读取剪贴板中的文本链接，并以可点击提示的方式填入，不会自动上传剪贴板内容。
- 点击解析后会立即清空输入框，下载进度和成功/失败结果显示在 App 内，不使用系统下载通知。
- 只处理公开帖子、普通 MP4/GIF；私密帖子、外部视频、受限内容不在范围内。
- 解析接口使用当前 v2 路径 `https://api.fxtwitter.com/2/status/{id}`。它是第三方公共接口，可能受限流或 X 改版影响；地址集中在 `MainActivity.java` 的 `API_BASE`，可替换成自己的后端。
- 请仅下载你有权保存和使用的内容，并遵守 X 的条款、版权和当地法律。

## 构建

使用 Android Studio 打开本目录，等待 Gradle 同步后运行 `app`，或执行：

```text
gradlew assembleDebug
```

生成的调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。当前机器没有 Android SDK/Gradle，因此这里没有声称已在本机完成 APK 编译；Android Studio 会自动使用其 SDK/Gradle 环境。

## 下一步可扩展

- 加入下载历史、视频预览和系统分享菜单。
- 增加“解析服务地址”设置，接入自建后端和多个解析源。
- 如果要做公开发布，增加隐私政策、错误上报开关、速率限制和版权投诉入口。
