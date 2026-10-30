# 手机本机唱录平台桥

Flutter 仅发送控制命令与接收摘要。人声、伴奏 PCM 和编码数据不经过 Dart，不新增 Java/compute 作品接口。Android 最低 API 24；Media3 与 Kotlin 引擎归 `PlaybackService`，不随页面销毁。

## 通道与操作

`singverse/player`：`open` 接收歌曲 JSON、`instanceId`、原媒体 `positionMs`、`force`、`autoplay`；`snapshot` 返回实际服务状态；`command` 接收 `action/value`。

新增播放命令：

| action | value | 约束 |
|---|---|---|
| pitch | 整数半音 -6…6 | 实际 Sonic 处理；录唱/倒数时冻结 |
| speed | 0.6…1.2 | 实际 Sonic 处理；录唱/倒数时冻结 |
| volume | 0…1 | 监听播放音量，独立于成品伴奏比例 |
| channel | STEREO / LEFT / RIGHT / MONO | 非立体声不提供左右声道能力；不消除人声 |
| role / useRole | backing / original | 按 NAS、歌曲、revision、资源模式保存实际轨道与声道映射 |
| range | `{a,b}`，原媒体毫秒 | b=0 表示整首；练唱循环，录唱单次结束 |
| record | true 表示已明确确认当前素材 | 原生三秒倒数后打开 AudioRecord；冻结歌曲及音频参数 |
| endRecord / cancelCountdown | 无 | 结束为独立片段，不自动拼接或重新开麦 |

`singverse/karaoke`：

| method | 参数 / 返回 |
|---|---|
| permission | 返回 granted / denied / settings；只在用户主动试音或录唱时调用 |
| notifications / settings | 录唱时申请通知权限 / 打开应用系统设置 |
| test | `{start:bool}`；真实试音电平，无文件 |
| end | `{id:takeId}`；验证稳定标识后结束当前录音 |
| render | `{id,backing,voice,shift}`；比例 0…2，对齐 -1000…1000ms；异步编码 |
| works / get | 无 / `{id}`；返回原生本机作品元数据 JSON |
| save | `{id,name}`；校验预览与原子发布；同 id 重复提交幂等 |
| rename | `{id,name}`；非空且最多 120 字 |
| play | `{id}`；只能播放原生作品索引指向的私有本机文件 |
| export | `{id}`；ACTION_CREATE_DOCUMENT，返回是否完成；取消不删除原作品 |
| delete | `{id}`；仅删除此手机作品，不删除 NAS 媒体；界面确认后调用 |

所有录音后续操作携带原生生成的稳定 UUID。原始音频不作为参数或返回值，作品文件名不能由外部输入决定。状态冲突、文件丢失及权限错误通过平台异常返回，界面显示实际原因。

## 事件与状态

`singverse/playback-events` 返回真实播放状态、原媒体时间、音轨/字幕、声道、移调、速度、区间及 `karaoke` 摘要。`karaoke` 包含 `phase/takeId/recordedMs/level/clipped/input/freeBytes/error`。计时取实际采样数，不使用页面计时器。`countdown` 为原生倒数；录音通知点击附带一次性 `openSinging`，由应用导航返回舞台。

阶段为 IDLE、TESTING、RECORDING、FINALIZING、RENDERING、PREVIEW、SAVED、FAILED；准备能力由实际播放器状态和已识别音轨共同确定。暂停、焦点丢失、路由变化、读取/写入错误结束片段；不自动重新开麦。

## 文件、时间与恢复

48kHz 单声道人声与 48kHz 双声道伴奏分别写 WAV。伴奏在声道处理和 Sonic 移调/变速之后采集；播放器禁用 offload/直通。记录 AudioRecord 与 AudioTrack 起点、采样数及偏移；无完整硬件时间戳时标记 `timestampAligned=false`，提示回听校准。回听偏移正值延后人声，负值提前人声；混音进行限幅，输出 AAC-LC / 48kHz / stereo / 192kbps M4A。

原生 SQLite 索引保存歌曲快照、NAS instanceId、修订、冻结参数、完整度、采样数、波形与混音参数。编码先写 `.part`，校验音轨/采样率/声道/时长后原子发布；发布后才标记成功。保存后清理临时伴奏 PCM，保留干声；已保存混音无需 NAS 可回放。

启动修复中断 WAV 头与待处理状态，缺失成品明确标记 MISSING，不把半成品算作已保存。缓存被系统回收后，缺失的伴奏不能凭空恢复；干声仍留在本机。产品没有测试声源入口；androidTest 的可控输入只存在于测试构造代码。
