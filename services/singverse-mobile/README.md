# SingVerse 手机端

Flutter 3.47.5 / Dart 3.13.4，Android application ID `com.mynora.singverse.mobile`，minSdk 沿用 Flutter 模板当前的 24。日常页面采用蓝色强调、紧凑列表与清晰分隔，普通歌名约 15 逻辑像素、辅助文字约 12–13 逻辑像素，支持浅色、深色及跟随系统主题，系统大字号时自然重排。提供 NAS 选歌、练唱、麦克风录音、混音回听及本机作品；媒体画面使用暗色舞台。标准与长辈模式都保留同位置的“点歌、常唱、作品”三个主导航，长辈模式使用独立的大字简化布局。扫码电视单独加载 NAS 的现有 `/party/` H5。

## 开发与构建

```sh
flutter pub get
dart format --output=none --set-exit-if-changed .
flutter analyze
flutter test
flutter run
flutter build apk --debug
```

根目录 `make mobile-check` 运行格式、静态分析和行为测试，`make mobile-build` 生成 `build/app/outputs/flutter-apk/app-debug.apk`。需要 Android SDK 36、模板要求的 NDK 与可兼容的 JDK；CI 使用 JDK 21。提交 pubspec.lock，不提交 SDK 路径、会话、本机数据库或构建产物。调试包沿用模板调试签名，不代表正式分发签名。

## 连接与认证

填写手机可达的 NAS 根地址（例如 `http://nas.local:18080`），验证 `/api/v1/server-info` 后以固定管理员 admin 登录；支持已有 TOTP/恢复码、Cookie、CSRF 及限流，不创建手机专用账号。使用局域网 HTTP 时没有传输加密；HTTPS 使用系统证书校验，不跳过错误证书。反向代理应提供根路径，不填 `/admin/`、localhost 或容器地址。

Android 原生 `NasTransport` 统一执行 API 与图片请求，以 Android Keystore AES-GCM 加密保存会话，不保存密码。服务端空闲/绝对有效期不变，启动时验证会话，过期重新登录。媒体数据源逐请求检查同源并附带 Cookie，禁止重定向，不将管理员 Cookie 注入 WebView。

## 功能与边界

- 点歌首屏提供搜索、歌手、分类、家庭歌单和歌曲列表；筛选按语种、年代、风格等分组，默认隐藏零歌曲分类。点击歌曲直接进入播放器并开始练唱，详情为播放器内主动打开的信息弹层。歌曲、浏览和作品列表按需构建。使用真实媒体条目 ID，不按同名猜测合并歌曲版本；格式标记不等于可解码、可伴奏或有歌词。
- 原生 Media3 ExoPlayer + MediaSessionService；支持直放、NAS 已有兼容版本及实时 HLS，锁屏/通知控制、视频横屏、音轨/字幕选择、同名 LRC/SRT/VTT 独立歌词。
- 单轨 HLS 若把同一音频 URI 同时声明为变体与音频分组，原生侧核对清单及实际解码参数后排除重复占位，避免误判双轨导致本机声道角色标记失效；真实多轨继续保留。从本机声道伴奏切回原唱时恢复立体声，保留音高和速度。此修复使用既有播放状态与命令，没有新增平台桥方法或 NAS 接口。
- HLS 查询与续租在原生服务执行，页面销毁不停止播放。来电/丢失焦点/拔耳机暂停，不在焦点恢复后自动出声；后台不显示视频画面，继续音频。
- 首版单曲播放，默认播放结束停止，可单曲循环；HLS 拖动需按原媒体位置重新协商。实际解码、音轨和字幕以设备/来源为准，兼容播放仍需 NAS 的 compute 可用。
- 收藏/最多 100 项播放历史保存应用本地 SQLite，按 NAS instanceId + songId 隔离；只有实际开始播放才创建历史。不保存离线歌曲、不上传记录，删除/清空本机记录不影响 NAS 媒体。
- `mobile_scanner` 仅扫码时申请相机权限；`webview_flutter` 加载确认后的同源 `/party/`，拒绝网页麦克风/相机请求，未提供 JS 文件/播放器桥。退出页面保留本次进程内的 H5 会话，显式断开清理；进程被系统终止后需重新扫码。扫码不依赖本机 NAS 登录，也不替换本机配置。
- 电视工具页支持扫码点歌和扫码授权电视登录两种用途。授权要求手机已登录对应 NAS 管理账号，扫描电视登录二维码后显示设备名称供管理员确认；二维码中的授权码自动提交，无需手动输入。该功能只批准电视设备访问，不向电视授予管理员权限。
- 仅在播放器内主动点击“开始录音”，或在“调音 → 麦克风试音”主动试音时申请麦克风。练唱不采集声音；拒绝权限仍可练唱。HLS 录音前从歌曲开头重新准备，确认可播放后再倒数，保留已选声音、音高和速度；准备失败不进入采集。原生三秒倒数后以实际 AudioRecord 状态显示录音。未知用途音轨需要本机确认，支持真实双声道选择，不自动消除原唱。
- 原生 AudioRecord 保存 48kHz 单声道人声，Media3 PCM 链路按声道处理 → Sonic 移调/变速 → 伴奏采集顺序执行；关闭音频直通与 offload。实际输入/播放时间戳记录两轨偏移，音频线程与文件写入线程独立。队列溢出、空间不足、焦点/路由变化或媒体失败停止并保留片段。
- 麦克风前台服务及明确录音通知支持可见页面开始后锁屏/后台继续，通知可结束录音。暂停录唱会结束当前片段，恢复需要再次主动开始。没有软件耳返；模拟器时间戳校验不能替代真机声学同步及蓝牙延迟验收。
- 结束录音自动保留草稿并进入回听。回听优先提供播放、真实波形与进度、前后 15 秒及保存；“调整声音”提供人声/伴奏比例，独立的“声音不同步”弹层提供 ±1000ms 对齐及每次 50ms 的文字按钮。修改后需点击“应用调整并试听”统一生成新混音，显示实际处理阶段，失败可重试，完成后才可保存；使用限幅混音并原生编码 AAC-LC 48kHz 双声道 192kbps M4A。播放音量与成品比例独立。保存后的混音可以离线回放，通过系统文件选择导出；修改名称、删除位于“更多操作”，重新演唱与查看作品也有直接入口。删除仍需确认且仅影响手机作品。
- 原生独立 SQLite 索引保存歌曲快照、修订、录音参数及完整度。文件先临时写入，再原子发布；启动核对中断录音与缺失成品。草稿保留两轨，保存后清理临时伴奏，保留干声。作品不上传 NAS，不建立离线曲库。
- “常唱”直接访问收藏和最近练唱，“作品”优先显示可继续处理的草稿，再显示已保存作品；缺失文件单独分组，读取失败保留已有记录并提供重试。三个主入口保留各自的搜索和滚动位置。迷你播放器在布局中预留空间并避开横屏左右安全区，显示播放、暂停、缓冲及失败等实际状态；长辈模式提供“暂停 / 继续 / 查看”文字操作，录音时优先显示计时与返回入口。
- 播放器普通点击歌词跳转，选段通过独立按钮进入；大字号和长辈模式及时跟随当前歌词，长句完整换行。视频以完整比例显示；原唱/伴奏、调音、重唱直接可见，音轨、声道、字幕和兼容播放归入“音轨与播放设置”。
- 横屏播放器使用全部可用画布，视频、同步歌词音频和无歌词音频都采用播放器内浮层操作，没有常驻右侧控制台。轻点画面呼出或收起控制，播放且无操作时标准模式约 3.5 秒收起、长辈模式约 6 秒；暂停、拖动进度、操作弹层、倒数、键盘和无障碍操作期间保留控制。控制隐藏后仍显示真实录音计时与结束录音按钮。同步歌词为控件预留空间，原生视频字幕按底部控制区高度上移；隐藏控制或回到竖屏时恢复默认字幕边距。横竖切换只调整界面，保留播放位置、原唱/伴奏选择和录音状态。
- “设置 → 长辈模式”保留三个主导航的位置，使用独立的单列大字列表、带文字的大按钮及至少 56 逻辑像素的主要触控区域，常用操作不依赖长按、侧滑或隐藏手势。本机偏好 `uiMode=standard|senior`，默认标准模式，重启恢复。切换布局不重建播放和录音服务。主题和减少透明效果同样保存本机。
- 标准模式顶部“扫一扫”及长辈模式顶部“扫码”直接打开相机，包含扫描框、手电筒和说明；已有电视会话可继续点歌或重新扫码。进入电视场景暂停本机练唱，录音中确认结束并保留草稿；返回不自动续播。长辈模式只影响原生页面，扫码后的共享 H5 保持原有界面。
- 本轮没有评分、自动修音、实时软件耳返、多 take 补录、家庭接唱、私人歌单编辑、AI 聊天、投屏和画中画。不申请整盘存储权限。

平台方法、状态、文件和时间映射契约见 [本机唱录平台桥](../../contracts/mobile-karaoke.md)。

## 工程组织

`lib/app` 为主题、两种模式共用的主导航、长辈简化布局及统一迷你播放器；`lib/core` 为 API、状态、数据模型和本机索引；`features/library`、`features/player`、`features/singing`、`features/tv_control` 分别负责曲库、演唱舞台、录音操作/回听/作品及电视工具。Android `NasTransport` 管理网络和凭据，`PlaybackService` 管理播放器和 HLS 生命周期，`KaraokeEngine` 管理采集、状态、文件和混音，`MainActivity` 桥接界面、权限、视频及系统导出。

手机曲库三个只读接口由 Java `/api/v1/mobile/library/songs` 提供，继续使用管理员认证，契约见 `contracts/openapi/singverse.openapi.json`。歌手、分类、共享歌单及播放协商复用现有业务接口，不直接连接 Python 或数据库。

未来增加 iOS：在有 Xcode/iOS SDK/CocoaPods 的 macOS 上生成 iOS 工程，并单独实现原生网络/凭据与播放桥接；当前没有 iOS 工程或验证结果。

本轮检查结果与未完成的真机验收见 [验证记录](../../docs/verification.md)。本机数据测试使用系统 SQLite（pubspec 的 sqlite3 hook 配置），不在测试启动时下载动态库。原生应用测试为 `cd android && ./gradlew :app:testDebugUnitTest`，不运行第三方插件自身的测试套件。

受控界面预览可在手机工程执行 `flutter --no-version-check test test/mobile_redesign_test.dart --plain-name 'capture redesign reference screens with controlled fixtures' --dart-define=CAPTURE_UI=true`，输出根目录 `tmp/mobile清晰舞台预览/`。测试使用随测试保存的开源 CJK 字形子集及自绘 PNG，包含有图、无图、明暗和长辈界面；字体不随应用打包，不需要本机苹果字体路径。此类组件截图使用受控数据及原生视频占位，不能作为真实 NAS、麦克风或设备播放验收。

播放排错：曲库正常但“准备播放”返回 422 时，先确认已安装修正设备类型上报的新 APK。手机使用内部契约的 `family=android`，不是界面端名称 `android-mobile`；原生测试会对照 OpenAPI 校验该字段。更新 App 不要求为此重建 NAS 服务。

登录排错：旧版 App 在重启后恢复 Cookie 时可能产生无域信息的旧 Cookie；过期后它与 NAS 新发的同名 Cookie 并存，导致“请求未通过安全校验”。已改为通过 CookieManager 的 Set-Cookie 流程恢复，确保刷新和退出能替换或清除旧值。安装修复版即可保留本机作品和收藏，不需要清除应用数据或关闭 NAS 的 CSRF 校验。

## 模拟器唱录验收

使用既有 `Pixel_9_Pro`，保留 minSdk、版本和既有设备数据。`make mobile-audio-test` 以独立 `.e2e` application ID 执行原生仪器测试，检查实际 Sonic PCM 顺序、变速时间映射、两路混音/限幅、100ms 人声偏移、AAC 解码、状态保护、重复保存及中断文件恢复。测试结束后通过 `adb logcat -d -s SingVerseAudioTest` 可找到公开样本 `Music/SingVerse-tests/controlled-mix-*.m4a` 的路径并取得合成测试混音；其中仅包含测试生成的两路正弦波。测试输入仅通过 androidTest 构造注入，产品没有切换到假麦克风的命令。CI 只编译该测试，未宣称已运行模拟器。

真实 UI 验收必须使用独立 Java/compute、独立数据库/密钥/缓存及自制素材，不能指向日常 NAS：

```sh
# FFmpeg 使用仓库 .tools 中的工具；目录必须新建且为空。
python3 scripts/setup-mobile-karaoke-fixtures.py /tmp/singverse-karaoke-fixtures
# 启动独立服务后，再通过正常管理员 API 扫描与审核测试素材。
python3 scripts/setup-mobile-karaoke-fixtures.py /tmp/singverse-karaoke-fixtures --seed
python3 scripts/check-mobile-karaoke.py --nas http://10.0.2.2:18181 --voice-sample /tmp/singverse-karaoke-fixtures/voice-test.wav
python3 scripts/check-mobile-karaoke.py --suite visual --voice-sample /tmp/singverse-karaoke-fixtures/voice-test.wav
```

可用 `--output /tmp/singverse-mobile-acceptance/本次目录` 为每轮验收指定独立输出目录，保留旧截图、日志和报告；省略时沿用手机工程 `build/reports/karaoke-emulator/` 默认目录。

以上命令从仓库根目录运行。隔离 Java 监听 `127.0.0.1:18181`，指定单独 `--singverse.sqlite.path`、`--singverse.auth.key-file`、`--singverse.transcoding.directory`，compute 监听 `127.0.0.1:18191`，compute 不设置媒体根目录；Java 随任务发送样本的授权回源引用，两个服务共用本次临时 token 文件。Java 使用 `--singverse.compute.base-url=http://127.0.0.1:18191`、`--singverse.compute.token-file` 指向它，并指定 `--singverse.compute.callback-origin=http://127.0.0.1:18181`：模拟器中的 `10.0.2.2` 是手机访问宿主的地址，宿主 compute 的回源需使用自己的 loopback 地址。样本生成器的 `--seed` 仅允许 loopback 18181、隔离数据库的工厂管理员，不保存密码；每次使用新的临时目录与数据库。

UI 脚本安装独立测试包，通过真实界面直接选歌练唱、试音、录音、锁屏、回听、保存、系统导出、离线回放及作品管理。完整屏幕截图、操作视频及实际混音位于 `build/reports/karaoke-emulator/`，均为本机忽略产物。宿主需要实际可用麦克风；脚本打开 hostmicon 并播放自制声源供麦克风拾取，不把它直接注入产品。宿主电平为零时，末尾断言仍失败，不能以原生控制输入测试代替通过。麦克风音质、声学对齐、蓝牙延迟仍需要真实手机验收。

### 共享音轨与独立伴奏

原唱/伴奏标记经 Java 全曲库保存，以已探测的源 stream ID 和 revision 校验；自动结果或其他设备纠正会在下次选用时读取。伴奏生成仍由 NAS 管理员启动。本机播放器选择生成伴奏时保留原视频和字幕，以 Media3 MergingMediaSource 合并独立音频，并用 HLS offset 裁切和换算拖动位置。现有音高、声道、监听和录音链继续在设备端工作；真实手机音画同步与蓝牙输出需单独验收。

单轨左右声道的既有标记继续保存在本机并按素材版本隔离；全曲库纠正仅保存两条源音轨角色，立体声不会被自动当作双音轨。
