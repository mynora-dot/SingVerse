# 视频转码与兼容版本

## 已实现的边界

Go 保留业务授权、播放决策编排、离线任务队列和唯一业务库写入；FFmpeg/ffprobe、硬件编码器探测、执行进程与临时产物全部在 `services/singverse-compute`。客户端仅访问 Go 的同源 `/api/v1`，计算节点不对客户端开放。

播放依次尝试原件直放、已有兼容版本、重封装、仅音频转码、视频转码。决策考虑容器、编码、profile/level、位深、HDR、分辨率、帧率、码率、声道；返回需要转换的原因。未配置/不可达的计算节点不阻断原件播放；强制兼容播放需要可用节点。已有离线版本保存在 Go 独立目录，节点离线仍可读取。

管理端歌曲播放弹窗只保留歌名与播放器，统一进度/播放/音量控件，常驻原唱/伴奏角色及编码选择（自动、原始编码、H.264/AAC 兼容；纯音频为 AAC）。角色使用共享标记或独立生成的伴奏，未就绪时禁用；独立伴奏按原时间轴与 HLS offset 同步，视频原声静音，暂停、缓冲及音量同时控制。切换保留位置和播放状态，重新协商时可能等待缓冲；原始文件不能直接选轨或改变画质/字幕时，页面回到自动编码。画质自动/1080p/720p/480p、字幕、生成/取消/删除兼容版本位于收起的设置区；歌曲批量操作可提交最多 100 首。TV 报告 MediaCodec 能力并接入同一播放策略、HLS、失败时一次兼容重试、画质切换和原时间轴回报。微信 H5 提供点歌/遥控；Flutter 已有本机播放、兼容播放及设备端唱录。

统一能力 DTO 可标记 browser、Android、Android TV、Fire TV、iOS、iPadOS、tvOS、Roku、Tizen、webOS、Kodi、desktop；能力仍由播放器实测上报，类型名称不授予额外解码能力。本次没有为缺失平台创建新客户端，也不兼容 Jellyfin 客户端协议。

管理端自动编码在浏览器实际报告解码错误/不支持格式（或 HLS 致命媒体错误）时，保留原时间轴位置、角色及播放意图，内部只强制重试一次 H.264/AAC 兼容播放；兼容尝试再失败则显示真实错误。手动原始编码不自动转换，网络错误单独提示，媒体取消事件及旧执行回调不会触发解码失败或重复转码。

手机与 TV 使用实际 MediaCodec profile/level、尺寸与帧率能力协商；能力快照在后台构建，自动画质遵循终端上限。Media3 允许解码器初始化回退，运行中的解码或格式错误最多强制兼容一次，网络、认证和音频输出错误不触发转码。兼容重试和重新协商保留原时间轴、暂停意图、角色及设置；强制兼容执行真正的视频转码和 AAC，避免继续复制已失败的编码。旧请求取消会关闭在途连接，准备状态按 750ms 串行查询，旧会话释放与新歌曲协商分开；TV 准备过程不占用命令锁，期间仍可暂停或停止。

已知可 seek 的 EVENT HLS 时间线内，手机与 TV 直接按 `原时间轴目标 - offset` 跳转，避免每次操作都重新探测和启动转码；超出当前已发布时间线才重新协商。手机录唱倒数结束后保留已合并的独立伴奏源，不能用只含主媒体的 MediaItem 重建而丢失伴奏。

WebDAV 原始媒体由 Go 代理读取，要求 GET 能直接返回数据，不跟随重定向。206 的数值范围及正文长度必须与请求一致，压缩正文或忽略非零/后缀 Range 的 200 会明确失败，避免播放器下载并丢弃整段前缀造成长时间等待；零起点 200 与 HEAD 忽略 Range 仍允许。Nginx 对 TV 原件、双方生成伴奏、计算回源、HLS 和离线版本关闭响应缓冲；`make nginx-media-check` 使用隔离服务器验证阻塞剩余正文时首段仍可到达。

## 编解码和硬件

默认 H.264 8-bit + AAC 192 kbps。1080p/720p/480p 的视频上限分别为 8/4/1.5 Mbps，不放大或提高原帧率。H.264 输出 profile/level、尺寸、帧率和码率同时受终端能力限制，编码命令显式应用协商结果；Baseline 软件输出关闭 CABAC 和 B 帧。已有兼容版本按实际探测到的 profile/level、帧率及位深核对，不能把缺失这些信息的旧产物当成已验证兼容。可通过 API 请求 HEVC/AV1；实时输出要求客户端报告对应编码及 fMP4，编码器必须通过短样本执行。默认界面仍使用 H.264。

NVENC 的 H.264 在显式 level 和单参考帧约束下禁用 B 帧参考模式（`-b_ref_mode disabled`），保留普通 B 帧；避免 A3000 默认参考 B 帧组合在 Chrome/macOS 中被判为畸形码流。该约束同时用于实时与离线编码。仅 FFmpeg 软件解码成功不足以判定浏览器兼容，验收需实际播放。升级此修复时共同更新 Go 与 compute：Go 的兼容播放缓存键升至 v2，新会话重新生成，旧缓存及正在使用它的租约保留并按原策略清理；原片缓存不受影响，无需清空数据卷。

管理端播放器与音轨试听在 MSE 可用时优先 hls.js，只有 MSE 不可用时才采用原生 HLS；浏览器原生支持声明不保证能解析冷启动中持续更新的 EVENT 清单。NVENC 资格试跑的最长预算为 10 秒，其它后端保持 3 秒，避免受限 CPU 容器的驱动初始化被误判。这些行为沿用既有前端和 compute；[2026-11-08 A3000 播放记录](../services/singverse-compute/VERIFICATION.playback-2026-11-08.md) 当时使用 Java 后端，不能计为 Go 的 GPU 或浏览器真机通过。本次 Go 结果见 [Go 迁移验证](go-migration-verification.md)。

HLS 支持 MPEG-TS 和 fMP4 分片，实时视频转码按 2 秒对齐关键帧并使用 2 秒分片目标；离线视频保持 4 秒关键帧间隔。从零开始的重封装仍为 4 秒分片目标并保持原关键帧，仅音频转码虽设 2 秒目标，复制的视频关键帧也可能延长实际分片，不能保证所有素材 2 秒起播。等待 master 中全部音视频 rendition 的首段和初始化文件齐备后才发布可播放状态；就绪后不在每次轮询中重复遍历全部分片，完整性校验留在导出和缓存发布。非零起点请求使用准确解码转码，避免复制前一个关键帧却声称从指定秒开始，导致歌词错位；执行结果返回 `effectiveMode` 和原时间轴偏移。音轨独立 rendition；输出包含稳定的源 stream ID 映射，TV 新的原唱/伴奏标记按源 ID 保存，原有本机标记保留兼容。左右声道保持独立；不默认合并为单声道。录音、升降调和音响延迟仍留设备端。

文本字幕可提取 WebVTT；ASS/SSA 和图形字幕有烧录流水线。HDR10/HLG 可使用经素材试跑的 GPU 色调映射或软件回退，保留原件直放路径。Dolby Vision 单层 HEVC P5/P8 必须确认真实 RPU 帧数据，并通过 metadata-aware 的 tonemapx、Metal/CUDA/OpenCL 路径转换至 SDR；未知 profile、缺失 RPU、双层增强流明确拒绝。客户端需分别报告 HDR 类型、DV profile 和基础层兼容 ID，普通 HDR 屏幕不能自动直放 DV。重封装 MP4/fMP4 HLS 显式保留 DV 配置，并检查离线结果未丢失 HDR 标记。

| 系统 | 编码适配器 |
|---|---|
| Linux Intel | QSV，或 VA-API |
| Windows Intel | QSV |
| Linux/Windows NVIDIA | NVENC |
| Linux AMD | VA-API |
| Windows AMD | AMF |
| macOS Intel/Apple Silicon | VideoToolbox |
| Linux Rockchip | RKMPP；RK3566 的 H.264 硬解/RGA/硬编真机结果见验证记录，其他芯片/格式逐台验收 |
| 通用 CPU | libx264/libx265/libsvtav1，以工具实际构建和短样本为准 |

已增加固定的完整硬件图：CUDA/NVDEC、QSV VPP、VA-API VPP/OpenCL、D3D11/AMF、VideoToolbox/Metal、RKMPP/RGA/OpenCL。每份素材先用所选编码器执行实际解码/滤镜/编码试跑，通过才启用；硬件不可用或阶段不兼容时使用兼容路径。烧录字幕需要 CPU 合成时，支持硬解回传并明确标记非零拷贝；图形字幕在 SDR 色调映射后合成，避免字幕也被当成 HDR 处理。

`pipeline` 返回真实选用的 `hardwareDecode`、`zeroCopy`、`gpuToneMapping`、`dolbyVision` 和回退原因。能力页只汇总本节点已经通过素材试跑的图，不把硬编测试成功等同于硬解成功。当前 Mac 已实际执行 VideoToolbox/Metal SDR/HDR/DV 路径；其他厂商图仍需对应实机验证，不能因模板存在宣称已完成所有 GPU 验收。树莓派硬编不列为保证项。

## 工具和启动

Docker 镜像使用固定 `jellyfin-ffmpeg 8.1.3-1`，分别校验官方 bookworm amd64/arm64 包的 SHA-256，不安装发行版 FFmpeg 替代。原生节点可设置 `COMPUTE_FFMPEG`、`COMPUTE_FFPROBE` 为外部绝对路径。

仓库内安装固定的官方便携版：

```sh
make compute-setup PYTHON=python3.12
make transcode-tools
export PATH="$PWD/.tools/jellyfin-ffmpeg/bin:$PWD/.tools/jellyfin-ffmpeg:$PATH"
```

下载入口的版本、平台文件名及摘要在 `services/singverse-compute/tools/jellyfin-ffmpeg.lock.json`。安装只写被忽略的 `.tools/jellyfin-ffmpeg/`，不改全局工具；如已有其他版本，先自行保存或移动，脚本不会覆盖。Windows 使用 Python 3.12+ 执行 `scripts/setup-transcode-tools.py`，把对应 `.tools\jellyfin-ffmpeg` 和其 `bin` 子目录放入当前进程 PATH；虚拟环境命令为 `.venv\Scripts\python.exe`。

本地示例（绝对路径换成自己的受限目录）：

```sh
export COMPUTE_AUTH_TOKEN_FILE=/absolute/path/to/private/compute-token
export COMPUTE_TRANSCODE_ROOT=/absolute/path/to/transcode-cache
make compute-run
```

Go 连接配置沿用性能节点设置。所有本地／WebDAV 媒体均通过任务 UUID 和 `callbackOrigin` 授权回源，计算节点无需原始曲库挂载或目录映射。Go 保留文件路径校验、WebDAV 凭据和唯一业务写入；compute 仅读取固定内部接口，FFmpeg 只访问进程内随机 loopback 路由，不跟随上游重定向。

回源地址由 Go 随每个探测／执行任务发送；compute 不再使用 `COMPUTE_JAVA_ORIGIN`。业务侧可设置 `SINGVERSE_CALLBACK_ORIGIN`，否则使用既有公开入口或已认证管理员访问入口；代理头只在可信代理范围内生效。连接测试分别验证节点认证和业务回源；原路径式任务须在升级后重建。

| 配置 | 默认/行为 |
|---|---|
| `COMPUTE_TRANSCODE_ROOT` | 原生默认空，转码停用；镜像为 `/var/cache/singverse-transcode` |
| `COMPUTE_FFMPEG` / `COMPUTE_FFPROBE` | `ffmpeg` / `ffprobe`；镜像固定 Jellyfin 工具路径 |
| `COMPUTE_HARDWARE_BACKEND` | `auto`，也可指定 software/qsv/vaapi/nvenc/amf/videotoolbox/rkmpp |
| `COMPUTE_HARDWARE_DEVICE` | `/dev/dri/renderD128` |
| `COMPUTE_TRANSCODE_CONCURRENCY` | 1，允许 1～16 |
| `COMPUTE_CACHE_BYTES` | 10 GiB 计算任务临时产物预算，不用于长期歌曲缓存 |
| `COMPUTE_MINIMUM_FREE_BYTES` | 2 GiB 最小可用空间 |
| `COMPUTE_PROCESS_MEMORY_BYTES` | 1 GiB，每个媒体进程的观测 RSS 预算（至少 64 MiB） |
| `COMPUTE_CONTAINER_MEMORY_LIMIT` | 应用/独立 Compose 默认 `2g`，Linux cgroup 硬限制 |
| `COMPUTE_CPU_LIMIT` | Compose 默认 2 CPU |
| `callbackOrigin` | Go 随媒体任务发送的业务回源根地址，不是节点环境变量 |
| `TRANSCODE_VERSION_PATH` | Go `./data/transcoding`；应用 Compose 使用 `/data/transcoding` |
| `SINGVERSE_SONG_CACHE_PATH` | Go 服务节点缓存目录，默认 `TRANSCODE_VERSION_PATH/cache`；应用 Compose 使用 `/data/transcoding/cache` |
| `SINGVERSE_SONG_CACHE_BYTES` | Go 歌曲缓存初始预算 10 GiB，后台保存后以业务库设置为准 |
| `SINGVERSE_SONG_CACHE_MINIMUM_FREE_BYTES` | Go 缓存磁盘最低剩余空间，默认 2 GiB |

缓存目录必须与媒体白名单分离，原件只读。原生 Windows 新凭据文件使用仅当前 SID 和 SYSTEM 的 ACL；POSIX 使用 0600。已有凭据不会被覆盖。

Linux 可按设备选择一个覆盖文件：

```sh
# Intel；AMD 设置 COMPUTE_HARDWARE_BACKEND=vaapi
COMPUTE_RENDER_GID=<render-group-id> docker compose --project-directory . -f infra/docker/compose/compose.app.yaml -f infra/docker/compose/compose.compute.dri.yaml config --quiet
# NVIDIA：宿主预先配置 NVIDIA Container Toolkit
 docker compose --project-directory . -f infra/docker/compose/compose.app.yaml -f infra/docker/compose/compose.compute.nvidia.yaml config --quiet
# Rockchip：需要匹配的 BSP、MPP/RGA 和实际存在的设备节点
COMPUTE_RENDER_GID=<device-group-id> docker compose --project-directory . -f infra/docker/compose/compose.app.yaml -f infra/docker/compose/compose.compute.rockchip.yaml config --quiet
```

上述先验证配置；确认实际设备后将 `config --quiet` 换为 `up -d --build`。独立部署将主文件换成 `infra/docker/compose/compose.compute.yaml` ，无需媒体目录参数。不使用 privileged；只传递需要的设备和组。macOS VideoToolbox、Windows AMF/QSV/NVENC 使用原生节点，不通过 Docker Desktop 声称拥有原生 GPU 支持。

## 任务、缓存和恢复

### WebDAV 原片与队列预取

原片直放经过 Go 的相同鉴权媒体入口，NAS 自动按需保存原始字节，不重新编码。完整的 2MiB 块发布后可复用，最后一块按源文件长度截断；未写完的块不会当成可用缓存，已发布块可在重启后继续使用。命中区间读本地，连续缺失区间合并回源并边传边写，不先等待整首下载。首次播放、拖动、手机和 TV 复用相同缓存；HTTP `Cache-Control: private, no-store` 继续保护浏览器/公共代理，不禁止 NAS 自身的受控存储。

TV 的既有播放事件可带原时间轴总长 `durationMs`。服务端只对当前已确认、实际 PLAYING 的有效 attempt，在剩余不超过 30 秒时预取队列中首个 WAITING 项。优先头块和尾块，以便读取位于文件尾部的索引，再顺序补齐未缓存区间；不会因为预取改变歌曲状态或提前开唱。H5 点歌和遥控复用这个队列，手机本机单曲模式没有独立队列预取。已知时长前不推测结束时间，旧事件、旧播放实例和未确认交接不能触发下一首预取。

队列排序、撤歌、跳歌、停止、散场和设备撤销会重新核对目标并释放旧预取。队列事务提交后才更新预取意图，下载使用独立的单路后台执行通道，不把网络等待放进业务事务或转码调度线程。前台新请求先中断预取并获得 2 秒优先窗口，之后即使当前播放连接仍打开也允许预取继续，前台连接存续时后台按 256KiB/s 限速；不会等当前歌曲整条连接结束才下载下一首。暂态错误最多尝试 3 次，间隔 5 秒、10 秒，稳定的指纹错误停止；容量不足等待后台清理代次变化或退避后续取；已发布块保留，预取失败不终止当前歌曲。预取的是原片，不提前创建下一首转码任务；需要转换的歌曲仍在实际切歌时协商输出。

原片和 HLS 共用 `SINGVERSE_SONG_CACHE_PATH`、默认 10GiB 容量及 HYBRID/MANUAL 策略，目录与原件分开，受所有权、符号链接、活动读者及租约保护。原片列表 `target.mode=ORIGINAL`，`cachedBytes/sourceBytes/complete` 分别表示已发布块字节数、源长度和是否整首完整，不能把头尾预取称为整曲完成。清理只删除 NAS 缓存副本；WebDAV 原件、数据库曲库、收藏、历史与离线兼容版本保持原样。来源配置或已扫描素材指纹变化后使用新缓存标识，源文件变化仍须扫描更新；无法确定安全身份/总长时保留原有流式回源。上游不支持 Range 时不复用部分缓存，仍保留从零起播；真正的非零定位继续明确拒绝错误的 200 响应。

旧 LRU/LFU/TTL 自动策略迁移为 HYBRID，ttlHours 只保留往返兼容，不再按生成时间强制删除。最近 24 小时真实播放、固定、活动播放/下载及有效预取目标均受硬保护。重启后首次使用某个原片条目时建立完整块索引并校准字节计数，恢复已改名但尚未记账的完整块；热读取只检查当前块，连续缺块用索引确定回源区间，不按每个空块探测磁盘。

### HLS 执行与发布

内部接口前缀 `/internal/v1/transcoding`：`probe`、`negotiate`、`capabilities`、`executions`；执行支持查询、lease、cancel、artifact、acknowledge、删除。固定 DTO 不接受任意命令、滤镜或输入 URL。所有接口需要内部 Bearer；创建/探测请求在 JSON 解析前鉴权并限制为 8 KiB。

Go `/api/v1/transcoding` 提供播放协商、会话/资源、离线任务与兼容版本管理；管理端保留 CSRF。TV 对应 `/api/v1/tv/transcoding` 只允许设备访问自己的执行；兼容版本另行检查歌曲读取权限。内部来源入口仅支持 GET/HEAD，检查计算凭据、任务、来源指纹和状态。

执行 ID 和结构化输入签名保证幂等。离线意图持久化在 Go `media_transcode`，SQL 位于 `internal/execution/sql/`，两数据库均有迁移；compute 只保存受限执行日志簿。重复离线提交复用同指纹/目标/流水线的有效任务。来源配置、扫描文件指纹或目标变化使旧版本失效。

协商探测结果只在相同结构化来源、回源 origin、素材指纹及 60 秒有效期内复用，最多保留 128 项；执行仍重新校验回源授权及素材指纹。播放路径仅试跑本次请求的编码器并复用结果，不为起播串行探测无关编码器。

默认总转码并发 1；实时请求取消占用中的离线执行并短暂预留容量，Go 以新执行 ID 重排离线任务。硬件启动失败在尚未发布 HLS master 时允许一次软件回退，已经发布的片段不混入另一套编码产物。持续速度低于实时要求时明确失败。

实时执行无续租 120 秒取消；计算节点只保留执行期间的临时产物，结束且停止续租 10 分钟后清理。成功实时 HLS 由 Go 服务节点在后台接收，播放查询不会等待整首歌曲复制完成：从受认证的 `/executions/{id}/export` 获取白名单文件清单，逐文件校验长度和 SHA-256，并确认所有 HLS 引用均在完整清单内后，原子发布到服务节点缓存目录及业务索引。Go 确认发布后才调用 `acknowledge`，计算节点随即释放临时音视频文件；转移失败不把半成品发布为缓存，当前临时播放继续，后台可重试接收。发布时使用最新的会话与缓存租约核对，下载期间的续租不能被旧快照覆盖。

后续播放先由 Go 按素材指纹、请求输出参数和终端能力查找本地缓存，命中后直接读取服务节点分片，无需调用计算节点、回源探测或重新解码。非零起点优先复用同参数、零起点的整曲缓存，客户端按 `max(0, 请求起点 - offset)` 定位（整曲 `offset=0`），不再仅因进度变化生成后半段。没有可用整曲缓存时仍精确匹配局部起点，或从原请求起点执行转码；局部缓存不能冒充整曲，已有不同起点条目不自动删除。画质、编码、音轨、字幕、烧录方式、终端能力及已扫描的素材指纹继续隔离。每个播放器仍有独立的授权会话和租约；关闭一个播放器仅释放该会话租约。

管理菜单「曲库 → 歌曲缓存」显示原片块及 HLS 兼容结果，包括音频/视频、实际容量、完整度、画质上限、编码、命中次数和最近使用时间，支持分页、逐项删除、清空及设置共用容量。管理功能不依赖计算节点在线；缓存目录与媒体原件分开，清理只影响 NAS 缓存副本。仍在播放/预取或 120 秒租约内的缓存拒绝删除，清空自动跳过；容量缩小时保护活动内容；空间无法释放时原片继续回源，兼容播放继续使用 compute 当前产物，不能因持久缓存配额不足拒绝播放。目录占用包含正在接收的数据，可能大于列表的已发布字节数合计。

计算节点的旧长期缓存不会自动迁移到服务节点；升级后通过新播放生成服务节点缓存，旧临时执行按生命周期清理。原件修改需扫描更新素材指纹后生效，缓存不提供未经授权的独立公开媒体地址。

| 缓存策略 | 行为 |
|---|---|
| 混合评分（HYBRID，默认） | 真实播放热度 14 天半衰、最近播放 7 天半衰与近 7 天频率组合；默认达到 90% 触发后台清理，达到 75% 停止，硬保护优先 |
| 手动清理（MANUAL） | 不自动淘汰；容量不足时继续播放并跳过新增缓存 |

`Score=.4H+.4R+.2F`；每次有效播放 H 增加 .05 并限幅到 1，F=`min(1,ln(1+plays7d)/ln21)`；24～72 小时使用逐渐减弱的软保护。普通清理排除最近 24 小时播放、歌曲固定、播放/下载占用与有效下一首预取目标。生命周期 HOT/WARM/COLD/EVICT 动态计算，历史热门超过 60 天未播放仍进入候选。HTTP 命中、续租和预取不增加播放热度。

缓存容量默认 10GiB，可设 64MiB～1PiB；高低水位为容量百分数，满足 `1≤low<high≤100`，NAS 最低磁盘空闲量单独计算。用量包含已发布数据、当前预留及尚未释放的待删数据。原片滚动预留不超过 32MiB，按完整块保存；HLS 导出大于配额直接继续当前 compute 资源，失败接收按清理代次/30秒退避避免每次轮询重复下载。

设置、缓存、租约、固定、历史和删除日志统一通过 模块内嵌 SQL 保存到业务数据库，SQLite/MySQL 的 `202611070001_initial_schema.sql` 直接定义完整结构。空库缓存为 HYBRID、revision 0，高/低水位 90%/75%，统计为 `ready=1/phase=READY`，无需旧历史回填；运行中统计未就绪时仍分批准备并重建热度，暂停普通回收，播放继续。认领为 DELETING 的条目禁止再次借出；后台原子改名后删除，失败保留 token 和索引以便重试/重启恢复。全局锁不进行整目录扫描和递归删除，热读取只检查当前文件。

管理员原接口保留，增加水位、歌曲级固定和后台清理任务/查询/取消。旧四字段设置请求继续有效，LRU/LFU/TTL 写入为 HYBRID 别名；ttlHours 保留且不参与淘汰。详细接口、保护边界及核心代码见 [`contracts/song-cache.md`](../contracts/song-cache.md)，真实播放采集见 [`contracts/song-activity.md`](../contracts/song-activity.md)。所有写请求继续使用管理员会话与 CSRF；compute 不提供业务缓存管理接口。

离线临时文件通过探测和时长检查后原子发布；Go 下载时检查长度及 SHA-256，保存成功再确认 compute 释放大文件，避免两份离线文件长期占用缓存。Go 兼容版本继续在歌曲管理入口维护，保留至管理员删除，120 秒内正在读取的版本禁止删除。单个下载限制 10 GiB，并检查至少 2 GiB 可用空间；Go 离线版本总量由管理员管理，与服务节点可淘汰歌曲缓存预算分开。

compute 重启将未结束执行标为失败；Go 离线队列核对后重新执行，实时播放需要客户端重建。取消/失败不发布半成品，已取消状态不能被后台完成回报覆盖。

## 验证和当前限制

`make compute-check` 使用仓库工具优先 PATH；首次应执行 `make transcode-tools`，普通 FFmpeg 可能缺少字幕/HDR 滤镜。`make backend-check` 包含真实 Go/Python/FFmpeg、本地及 WebDAV、离线后节点退出的集成测试。前端执行 `make frontend-check` 和 `npm test`，TV 执行 `make tv-build`。

原生三系统 CI 配置运行同一套真实媒体测试，不代表远程 CI 已通过；具体本次结果和硬件/终端未验收项见 [验证记录](verification.md)。已增加硬件图和 DV P5/P8 转换，并以实际 PGS/DVD 码流验证位图烧录时序；其他厂商 GPU、各终端和持续负载仍需实机验收。新终端适配器仍需实现自身授权、实际能力报告及播放器集成。

参考：[Jellyfin 硬件加速](https://jellyfin.org/docs/general/post-install/transcoding/hardware-acceleration/)、[jellyfin-ffmpeg](https://github.com/jellyfin/jellyfin-ffmpeg)、[Plex 播放决策](https://support.plex.tv/articles/200250387-streaming-media-direct-play-and-direct-stream/)、[VLC 转码](https://docs.videolan.me/vlc-user/desktop/3.0/en/advanced/transcode/transcode_introduction.html)、[mpv 硬解](https://mpv.io/manual/stable/)、[FFmpeg HLS](https://ffmpeg.org/ffmpeg-formats.html#hls-2)。

## 后台硬件加速选择

编码器资格试跑及能力列表统一使用 1280×720、3 帧短样本，避免部分 NVIDIA NVENC 设备拒绝 128×128 而被误判不可用；各编码格式仍须单独通过实际编码，不以硬件识别代替试跑。

入口为“系统设置 → 性能节点配置 → 转码与硬件加速”，可读取当前设置、选择/测试/保存加速方式及查看编码器探测结果。管理员请求经 Go 同源 `/api/v1/transcoding/hardware-settings` 转发；测试使用 `/hardware-settings/test`，保留管理员会话、CSRF 和内部 Bearer 校验。

选择保存到 compute 缓存卷根目录 `hardware-settings.json`，使用原子替换和 revision 乐观锁；不写业务数据库。新任务使用保存值，运行中任务保持已选后端。后台选择优先于部署环境变量；`deployment` 表示遵循 `COMPUTE_HARDWARE_BACKEND`。保存指定硬件前必须通过实时短样本编码；测试草稿不修改当前选择。缓存目录未配置时仍可读取/测试，但不能保存。更换节点后配置不会自动复制到新设备。

## 流水线 v2 与真机核验

流水线升级为 v2，新离线任务和自动版本复用只使用 v2。进行中的旧离线任务先取消原执行，再用新执行 ID 重排；旧实时会话要求客户端重建。已保存的旧文件不擅自删除，管理员仍可管理。

`make transcode-fixtures` 可选下载三份官方 P5/P8.1/P8.4 样本，SHA-256 固定在 `tools/verification-media.lock.json`；普通测试保持离线，不下载第三方素材。测试素材不提交、不进入镜像。真机命令：

```sh
export PATH="$PWD/.tools/jellyfin-ffmpeg/bin:$PWD/.tools/jellyfin-ffmpeg:$PATH"
services/singverse-compute/.venv/bin/python services/singverse-compute/tests/smoke/verify_hardware.py --backend videotoolbox --fixtures .tools/transcode-fixtures
```

支持选择 qsv/vaapi/nvenc/amf/videotoolbox/rkmpp/software，`--device` 指定 Linux 渲染节点。报告含输入编码/HDR/profile、实际流水线、耗时、输出尺寸及观测 RSS；GPU 利用率/显存无法从通用无特权接口获得时报告 null，不虚构数据。没有设备或编码器不可用时命令失败，不跳过断言伪装通过。

每个媒体子进程设置并发、线程、执行时间、输出大小和观测 RSS 预算。RSS 超限终止所属进程树；Windows 使用进程内存计数器、Linux 读取该子进程 `/proc`、macOS 使用该子进程 `ps`。采样不能替代硬内存隔离，Docker 通过 cgroup 设置整体 CPU/内存硬上限；显存占用仍受驱动和硬件会话限制，不能把 RSS 当作 GPU 显存。`sampledPeakRssBytes=0` 代表未采集到，不能解释为零占用。

## 2026-11-01 全曲库音轨角色与独立伴奏

扫描后后台独立 PROBE 保存源 stream ID，双轨在模型可用时提交 ANALYZE；两个片段的人声证据一致且差异明确才自动确认，其余保持待确认，人工纠正跨手机/TV/亲友 H5 全曲库共享，素材指纹变化后旧结果失效。单轨由管理员按需提交 SEPARATE。源媒体只读，Go 的 `AUDIO_DERIVED_PATH` 与 compute 缓存独立保存产物，校验时长、原时间线起点及 SHA-256；前置静音保留，不能把延迟音轨提前到零秒。

固定本地模型及依赖的安装、Compose 可选构建和 4GiB/线程/磁盘预算见 `services/singverse-compute/README.md`。任务不隐式下载模型；管理员可在独立“设备与服务 → 性能节点配置”显式下载或离线导入固定模型，环境预装、试跑后启用。实时请求先停止离线音频进程后重试容量。取消/失败清理，节点重启未完成执行标失败供管理员重试；Go 重启继续核对持久任务。角色纠正不修改当前播放，下次选用时手机/TV 重新协商原轨或伴奏 URL，通过同一播放器合并视频/字幕和音频，处理 HLS offset。

WebDAV v2 索引增加可选 audioRoles，仅分享角色、轨道描述及 GENERATE_LOCALLY 策略，不包含分离音频、任务或本机路径。接收方按相对路径定位原媒体，探测核对轨道/时长后应用为 SHARED，本地人工纠正优先。旧 v2 索引无该字段继续兼容。

### 伴奏管理与推理设备

管理员在“曲库 → 原唱与伴奏”进行批量生成及节点模型管理。单节点的模型、运行环境和推理后端分别校验，只有完整模型试跑通过才参与选择；Intel GPU 使用独立 OpenVINO 适配器及固定 Intel 官方转换模型，实际 GPU 试跑通过才可用；RKNN 已接入 UVR_MDXNET_9482 的实验性本地转换模型执行与试跑门禁，已有 RK3566 隔离 NPU 全曲实测，正式 fnOS 服务接入仍待容器隔离调整的明确授权；模型准备及原生 ARM64 部署边界见 compute README。GPU探测与显存查询不持有实时调度锁，获得音频执行容量前仍检查实时任务及预留。固定权重可显式联网下载或离线导入，只读预置权重继续可用。配置与实际设备限制见compute README及本次验证记录。

## 统一 CPU 自动降级策略

性能节点配置的“加速不可用时自动降级到 CPU”默认开启，同时控制视频编码与伴奏推理，设置保存在计算节点的 `hardware-settings.json`，使用 revision 并发校验；旧文件缺少 `cpuFallback` 时按开启读取。Go 代理现有 `/api/v1/transcoding/hardware-settings` 读写，返回 `cpuFallback` 与 `detectedBackends`；音频节点状态返回后端的 `detected` 证据。设置只对新提交任务生效。

检测到加速硬件后不允许手选 CPU；旧固定 CPU 配置按硬件优先解析。驱动、模型或试跑尚未就绪仍显示该硬件与原因，不把 FFmpeg 编码器安装视为硬件存在。开启降级时可自动选择通过独立试跑的 CPU 模型；关闭则阻止无加速路径的任务。模型运行时仅明确的设备/运行库错误触发一次 CPU 重试，不对无效输入、磁盘不足、进程内存预算或取消进行重试。任务记录实际后端与 `fallbackReason`。视频转码仅在播放清单尚未发布时允许因明确的加速故障切换 CPU；已发布 HLS 清单后失败需重新发起任务，避免混用分片。CPU 过滤步骤与 GPU 神经网络中的必要 CPU 运算不受此开关影响。

## 大曲库性能与资源收尾

管理端、TV 和亲友歌曲读取复用同一 SQL 投影，一次关联歌手订正、WebDAV 文件身份和元数据。TV 索引保留 `byId`，筛选分页后才复制响应；伴奏中心以每批 200 首构建轻量筛选快照，不在 HTTP 请求中逐首查询全库。歌曲、元数据、角色和任务写入使索引失效，后台重建，30 秒兜底刷新；首次准备返回 503 和 `Retry-After: 1`，旧快照可在重建时继续读取。

全库伴奏预览改为后台准备：创建返回 202 和 `PREPARING`，固定所选歌曲及素材指纹，每 200 条短事务保存成员并更新准备进度。进入 `PREVIEW` 后才可确认执行；可中途取消，重启时未完成准备标为失败。执行时仍核对指纹，准备完成不代表当前素材永久有效。

Go 缓存发布时全量校验清单和分片；重启首次使用时重新验证。热播放只校验当前文件，续租不遍历整首歌曲；维护轮转检查缓存，容量驱逐复用一次磁盘用量快照。所有权、符号链接、租约及活动读者保护继续生效。非当前分片后续损坏由读取该片或巡检时发现，不把一次发布校验视为永久完整性保证。

Go 接收计算产物设置 30 秒正文空闲超时，取得正文后的取消主动关闭在途读取；等待响应头或导出清单时仍受各自请求超时约束（产物响应头 30 秒，导出清单 300 秒）。失败清理临时文件和容量预留。音频下载移出服务全局锁，取消和其他状态查询不再被整段网络读取阻塞。compute 的 HLS 导出哈希和目录用量统计也在调度锁外执行，导出期间以引用保护回收；已确认释放的离线执行日志保留 7 天。
