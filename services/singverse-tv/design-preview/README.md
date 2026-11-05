# SingVerse TV 首页视觉原型

这是独立、离线的首页设计评审稿。正式 TV 使用 Kotlin / Compose TV；原有首页位于 `TvScreens.kt` 的 `Home`，继续使用真实本场状态与曲库。此次未替换该页面，也未更改任何 API、数据库、认证、播放或队列逻辑。

## 浏览器预览

在仓库根目录运行（无需安装依赖）：

```sh
python3 -m http.server 18767 --bind 127.0.0.1 --directory services/singverse-tv
```

打开 <http://127.0.0.1:18767/design-preview/>。也可直接打开本目录 `index.html`，资源均使用相对路径，不依赖 CDN。

- 1920×1080 设计画布，在 1280×720 等 16:9 窗口等比适配；其他比例居中留边。
- 方向键选择、Enter/空格确认；Escape/Backspace 返回。Tab 也可操作。
- 初始焦点为「立即点唱」；向下进入快捷入口、歌曲，再进入歌单。曲库横向滚动；页面纵向滚动；弹层关闭恢复触发按钮焦点。
- 演示点唱只修改内存中的演示数字；刷新恢复。没有网络请求、浏览器存储或真实媒体播放。

## 原生电视预览

正式首页接入后，APK 已移除独立的静态原生预览及重复的电视启动图标。Debug Manifest 仅保留 `HomePreviewActivity` 兼容别名，旧快捷方式会直接打开正式 `MainActivity`。浏览器视觉原型仍保留在本目录，仅用于历史设计对比。

在仓库根目录构建：

```sh
make tv-build
```

连接电视后安装并打开正式应用：

```sh
adb install -r services/singverse-tv/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.mynora.singverse.tv/.MainActivity
```

多设备连接时，给两个命令添加 `-s <设备序列号>`。APK 与现有安装签名须一致；无需清除现有数据。正式应用连接 NAS 并显示真实曲库，封面由后台「系统设置 → 电视设置」配置。

## 历史原型视觉范围

保留首页、找歌、歌手、分类、歌单、已点、设置和手机加入入口。首页采用轻量导航、海岸主视觉、四个快捷入口、大封面曲库和下方共享歌单示例。所有入口可聚焦、打开静态说明或歌曲展示弹层；没有制作新的业务页面。歌曲和歌单取自演示数组，不声称为真实 NAS 的内容、热门排名或历史记录；连接状态亦明确标注演示。

原生以 960×540 逻辑单位适配 1080p/720p，焦点约放大 1.035 倍、170ms 过渡，并使用暖白按钮/描边。最小必要滚动避免 TV 默认焦点定位将主视觉推离首屏。使用现有 Compose TV Material、系统中文字体和代码绘制的线性图标，未新增依赖。浏览器版本按相同设计提供快速对比，字体渲染、图标和滚动细节与原生存在平台差异，电视验收以原生为准。

## 素材

`../app/src/debug/assets/home-hero.png` 为内置 imagegen 生成的原创氛围图。其余封面为代码绘制的统一演示插画；均非真实官方专辑封面。没有抓取真实曲库或媒体文件。

生成提示词（内置工具，未使用 CLI）：

> Use case: photorealistic-natural. Asset type: cinematic background photograph for a premium home karaoke smart television UI. Create a very wide cinematic landscape 16:9 with rich analog film texture: an empty winding road beside the sea at blue hour, tall dark mountains on right, distant soft amber sunset on the horizon at right center, silver blue water, a very small lone human silhouette near the far right watching the horizon. Photorealistic fine art music video still, beautiful atmospheric natural light, dark navy, muted cyan, soft peach gold, black shadows. Composition: left 45 percent is uncluttered deep dark navy sky and mountains to provide excellent space for UI text overlay; striking vista and sunset on right half. Premium, contemplative, emotionally evocative, restrained colors. No text, no typography, no logo, no UI, no borders, no collage, no watermark. This is an original fictional music visual, not album art for a real song.

## 检查

本次实际执行记录及截图见仓库本地 `docs/mockups/tv-home/`。该路径沿用仓库现有的 `/docs` 忽略规则，仅保留在本机；可复用源码和原创背景均在 `services/singverse-tv/`，不会随文档目录被忽略。

未安装到物理设备，模拟器与浏览器检查不代表真实电视遥控器、4K、客厅可读性或音视频验收。
