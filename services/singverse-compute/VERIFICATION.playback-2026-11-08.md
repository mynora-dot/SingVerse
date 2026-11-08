# 2026-11-08 H.264 浏览器播放修复与 A3000 验收

## 范围与设备

修复「全部歌曲 → 播放 → H.264 / AAC · 兼容」在 0 秒报无法解码的问题。实际设备为 `10.100.1.12` 的 fnOS / NVIDIA A3000，驱动 580.142，独立 compute 容器限 2 CPU / 6 GiB。浏览器为 Mac 上已安装的 Google Chrome 157.0.8103.38，使用独立临时配置和真实 NAS 页面，未改用户浏览器设置。

素材是已有的 `test-video-30s.mkv`，HEVC Main / 1280×720 / 30 fps / AAC 单声道 / 30.023 秒；不是本轮新增歌曲，也不代表真实歌声音质或所有媒体格式。

## 定位证据与修复

1. **NVENC 编码参数问题**：旧 H.264 High / level 4.1 / `-refs 1` 产物在 FFmpeg 软件解码中通过，但直接 MP4、原生 HLS 和 hls.js 浏览器播放均曾失败。Chrome Media 记录为 VideoToolboxVideoDecoder `DecoderStatus::104 / kMalformedBitstream`。A3000 上同源 5 秒参数对照：默认 B 帧参考失败，`-bf 0`、`-b_ref_mode disabled` 或提高参考帧上限可播放。最终只在 NVENC 的显式 level / 单参考帧 H.264 模板禁用 B 帧参考，保留普通 B 帧和原 level 约束；实时与离线共用修复。其它编码器不添加 NVENC 私有选项。
2. **通用播放器选择问题**：浏览器报告原生 HLS 支持，冷启动期间仍曾报 `DEMUXER_ERROR_COULD_NOT_PARSE`。软件转码产物在 hls.js 对照中可播放，缓存完成后的原生 HLS 也可播放，因此不能仅凭 `canPlayType` 判定实时流可用。管理端播放器和音轨试听现优先使用可用的 hls.js/MSE，MSE 不可用时保留原生回退；不改浏览器能力上报或强制 CPU 编码。
3. **NVENC 试跑预算问题**：重建容器后出现 `ACCELERATOR_NOT_READY` 软件回退；隔离试跑实测约 2.995 秒、峰值 RSS 135,753,728 B，原 3 秒预算处于临界。手动测试和自动选择的 NVENC 预算共同改为最多 10 秒，其它后端仍为 3 秒，编码失败仍不能通过资格。
4. **旧缓存复用问题**：Java HLS 缓存键版本升至 v2，使新会话避开旧编码产物。原片缓存身份、旧文件及活动租约保护保持原策略，不清空数据卷。此修复需共同更新前端、Java 和 compute。

NVENC 参数问题属于已确认的 NVIDIA 路径兼容问题，不能外推为 AMD、Intel 或 Rockchip 存在相同缺陷；播放器选择修复则对所有部署生效。

## 实际验证

| 检查 | 本次结果 |
| --- | --- |
| 前端 | `make frontend-check` 通过；46 个测试文件 / 387 项测试通过 |
| compute | `make compute-check`：209 项，7 项可选运行时或模型条件跳过，其余通过 |
| Java | `-Pbackend-check verify`：617 项测试、3 项真实双进程联动测试通过 |
| 前端托管 | `make web-check`：4 项真实 HTTP 测试通过；随后重新打包完整前端 |
| 冷缓存浏览器播放 | 正常页面选 H.264，`cacheHit=false`，4.026 秒时累计 123 帧，无解码错误 |
| 原时间轴跳转 | 跳至 19 秒后继续到 20.765 秒，命中同一整曲缓存，资源 offset=0；暂停恢复后到 21.783 秒，最终自然结束 |
| 缓存连续播放 | 新浏览器会话 `cacheHit=true`，不跳转连续播放至 30.100 秒，自然结束，累计 899 帧，无解码错误 |
| 实际硬件 | `backend=nvenc`，执行 fallbackReason 为空，`hardwareDecode=true`；本轮浏览器参数走 `nvenc-copyback`，`zeroCopy=false`，CPU_FILTERS_REQUIRED，不冒充零拷贝 |

新增行为测试先在旧实现上复现失败，再随修复通过。前端原生回退、音轨切换位置、旧回调隔离及后台执行释放断言保留。Java 回归同时验证旧缓存不会被新键命中、旧活动租约仍能读取。

第一次本机检查受沙箱端口限制，正常权限重跑后 compute 通过；默认 JDK 不支持 release 21，改用本机已有 Android Studio JBR 25.0.3 编译 release 21 后通过，没有安装或切换全局 JDK。前端首次全量测试中的 6 个原生路径用例需要明确模拟 MSE 不可用；保留原断言并补齐能力模拟后，最终 387 项通过。

## 部署与回退

本轮基于已有 NAS 镜像离线构建派生镜像，加入实际修复的 Python 模块、重新打包的 Java JAR 和完整前端 dist；没有重拉 CUDA/Torch 或更改依赖锁。不是全新基础镜像构建验收。

| 服务 | 最终镜像 ID |
| --- | --- |
| compute CUDA | `sha256:7da44ac897183712b7ce8c6a7df81230a6b767d03dd60ff25d1a422a6202bf95` |
| Java | `sha256:5369efbe4dd9e23fee1e6ace61f921bf3447fc643d20a1e84bff1942fad64791` |
| Nginx / 前端 | `sha256:ec0bd0424bdba770c3304e211a3024cbd05a781b4ab6e4068f3af313c59e07ea` |

三个服务均有 `h264-ref-fix-20261108.2` 标签及原 stable 标签；原镜像保留为 `before-h264-ref-fix-20261108` 标签。部署记录和构建上下文在 NAS `state/h264-ref-fix-20261108/`，使用原 Compose 项目名、认证、媒体、模型和数据卷；原应用 CPU compute 未替换。

前端重建曾意外采用配置的回环默认绑定，外部浏览器出现连接拒绝；随后将 NAS 本机 `.env` 的 `SINGVERSE_HTTP_BIND` 显式保存为原管理入口所用的 `10.100.1.12` 私网地址，恢复同一 URL，并保留受保护的旧配置备份。此地址仅为本次现场配置，不写入仓库通用部署模板。

中间版本出现过原生 HLS 解析失败和软件回退，均未作为最终通过。仅删除本轮诊断生成的一份软件 HLS 缓存以验证 NVENC 冷启动；既有旧缓存、原件和数据库保留。最终浏览器报告、截图和检查日志在本机忽略目录 `.tmp/nas-verification-20261108/playback-bug-*`，不提交 Cookie、认证头、日志或构建产物。

最终四个相关容器均 healthy、restart=0、无 OOM，HTTP 健康为 UP；Java 媒体挂载仍为只读。原视频 SHA-256 重算为 `58ba2e350b30c4b9d12bebd87c6ee3a1388ddb4007b7b60015f9f42f8c997e6b`，与此前记录一致。释放本次最终验收的 5 个播放会话，保留成功的 NVENC 整曲缓存和既有缓存；本轮上传包、隔离编码样本及重复临时文件已清理，保留回退镜像和部署审计上下文。

## 其它设备会话的验证提示词

> 请在已连接设备验证 SingVerse 本次 H.264 播放修复，先保留未提交工作并共同更新前端、Java、compute。检查「全部歌曲 → 播放 → H.264/AAC 兼容」的冷缓存与缓存命中、连续播放、拖动进度、暂停恢复及自然结束，记录实际编码后端、回退原因和浏览器解码错误。通用 HLS 选择问题已改为优先 hls.js/MSE；NVENC 的显式 level / refs=1 模板已禁用 B 帧参考，并延长资格试跑预算，不能把 NVENC 选项套到 VAAPI、QSV 或 Rockchip。只有本设备仍复现时才定位并作最小修复，补实际行为测试和旧缓存处理；不能仅以 FFmpeg 解码成功作为浏览器验收。保留媒体原件、数据库、认证、模型与命名卷，未复现无需另改代码。

本轮未连接其它 GPU，也未验收微信、Safari 原生回退、实体 TV、长曲、HDR 或多任务长期负载。
