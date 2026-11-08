# SingVerse Python 计算服务

容器从零部署、只读媒体挂载和独立节点回源见 [Docker 部署指南](../../infra/docker/README.md)；ROCm/CUDA/OpenVINO、模型安装与验收见 [GPU 与 AI 指南](../../infra/docker/GPU.md)。

Python 3.12+ / FastAPI。所有 FFmpeg/ffprobe 执行都由本工程处理，Java 通过内部 HTTP 编排、校验和入库，前端不直连计算服务；Python不访问业务库、不管理业务队列。当前通过任务授权回源读取本地／WebDAV 音视频标签，以及实时 HLS/离线兼容版本、六类硬件编码适配。已增加按素材试跑的硬解、零拷贝、GPU 色调映射与 DV P5/P8 转 SDR；已支持可选本地 htdemucs 原唱识别与离线伴奏分离；详见[转码说明](../../docs/transcoding.md)。

## 运行与验证

首次 `make compute-setup PYTHON=python3.12`，依赖使用本工程 `.venv` 与 `requirements.lock`。本地运行 `make compute-run` 默认只监听127.0.0.1:8090；需安装FFmpeg以提供标签能力。`make compute-check`执行随机端口HTTP、真实生成音视频、超时/输出限制测试；`make backend-check`执行Java/Python整体及真实双进程集成测试，使用隔离数据。

`python -m singverse_compute --host 127.0.0.1 --port 8090`启动。配置：

- 原始曲库仅挂载到 Java。性能节点不配置媒体目录或业务地址；每项媒体任务携带 `reference` 和 `callbackOrigin`，凭任务授权通过 HTTP Range 读取业务节点。
- `COMPUTE_AUTH_TOKEN_FILE`：内部Bearer凭据文件。CLI首次启动以0600生成随机凭据；Java仅只读挂载该文件，禁止提交或输出内容。没有有效凭据或ffprobe时不声明标签能力。
- `COMPUTE_LAN_PAIRING_ENABLED`：默认 `true`，允许受限局域网内由 Java 服务端发现和首次配对；设置 `false` 关闭配对，已配置的内部 Bearer 调用继续生效。没有有效凭据文件时不能配对。
- Java用 `COMPUTE_NODE_URL` 与 `COMPUTE_AUTH_TOKEN_FILE` 连接。Java不启动Python或FFmpeg子进程，节点离线/失败回退文件名，不影响主服务健康。

应用 Compose 仅给 Java 挂载媒体卷；compute 只挂载内部凭据、模型和缓存卷，不发布端口。跨主机计算节点同样无需共享文件系统；保留认证、TLS／受限网络。Java 镜像不含 Python／FFmpeg。

## 工程结构与依赖

```text
singverse_compute/
├── __main__.py / bootstrap.py   # CLI 与应用依赖组装
├── app.py                      # ASGI 导入入口
├── core/                       # 不可变配置、错误与安全日志
├── domain/transcoding.py        # 媒体值对象、播放协商
├── application/                # 转码任务、音轨分析、模型管理与 LAN 配对
├── infrastructure/
│   ├── media/remote_tags.py     # 有界 Range 提取与任务内共享块缓存
│   ├── media/ffprobe.py         # 标签归一化
│   ├── transcoding/            # 回源网关、媒体探测、编码模板与进程预算
│   └── security.py             # 内部凭据初始化、读取、鉴权
└── api/                        # 请求 DTO、中间件、认证与内部路由

tests/
├── callback_source.py          # Java 侧授权回源的隔离 HTTP fixture
├── unit/                       # 适配器、凭据、日志与用例边界
└── integration/                # 真实 HTTP、媒体处理、契约与认证
```

Java 负责业务授权、调度和入库，compute 仅执行任务。HTTP 路由经用例调用固定工具适配器，不能接受命令或过滤器片段，不访问业务数据库。源媒体仅存在于业务侧；compute 不配置媒体白名单、不接收本地源文件路径，`COMPUTE_MEDIA_ROOTS` 和部署级业务地址配置已移除。

每个应用实例拥有独立配置、凭据适配器和任务并发门，不使用模块全局Semaphore或动态全局环境作为请求状态。CLI使用工厂模式启动，`app.py`仅保留既有ASGI导入兼容。日志只包含请求ID、方法、路径、状态和耗时，不记录请求体、Authorization或内部凭据；响应带`X-Request-ID`，错误统一为Problem Details，参数错误不回显输入。

扩展与详细职责见[计算服务架构](../../docs/compute-architecture.md)。

## 内部接口

| 路径 | 行为 |
|---|---|
| GET /health | 进程健康，不证明媒体可处理 |
| GET /internal/v1/capabilities | 按工具、凭据、模型与已验证后端返回实际能力，无媒体目录依赖 |
| GET /internal/v1/status | Bearer 鉴权后的相同身份与能力 |
| GET /internal/v1/lan/discovery | 受限私网单播发现；仅节点身份、名称、绑定状态，不返回凭据 |
| POST /internal/v1/lan/challenge | 为 Java 身份和真实来源创建 30 秒一次性签名挑战 |
| POST /internal/v1/lan/pair | 验证 RSA-3072 签名，首次绑定或同身份恢复；凭据只以该身份公钥加密返回 |
| POST /internal/v1/media/remote-tags | Bearer 鉴权；接收任务引用与回源地址，提取标签、封面、歌词及技术信息 |
| GET /openapi.json | 受版本管理的内部契约；交互文档关闭 |

输入超限 413、未认证 401、并发繁忙 429、能力不可用 503；所有任务由 Java 发起。节点单进程，每个应用实例独立限制一个远程标签任务；标签提取、同步模型检测和能力查询在线程池运行，不占用健康接口的事件循环。

## 标签读取边界

本地和 WebDAV 均使用 Java 短期授权的 HTTP Range 回源。`infrastructure/media/remote_tags.py` 实施字节、请求数与期限预算，FFprobe／FFmpeg 子进程另设输出、内存和执行时限；标签、封面、歌词和技术字段共享同一任务缓存，具体预算见下方“WebDAV 远程内嵌元数据”。`infrastructure/media/ffprobe.py` 仅负责标签归一化，容器值优先于流值，分号分隔歌手转为数组。

任务回源代理按 20ms 间隔检查停止，结束后关闭监听与服务线程。空标签、工具缺失、损坏或预算耗尽允许保留部分结果，Java 继续使用文件名回退；源媒体保持只读。

更新内部契约后重新生成并执行测试：

```sh
cd services/singverse-compute
.venv/bin/python -c 'import json; from pathlib import Path; from singverse_compute.app import app; Path("../../contracts/openapi/compute-internal.openapi.json").write_text(json.dumps(app.openapi(), ensure_ascii=False, indent=2)+"\n",encoding="utf-8")'
```

参考 [ffprobe官方文档](https://ffmpeg.org/ffprobe.html)。本机容器验证不能代替NAS/微信/TV真机或其他架构验收。

## 管理端配置与独立部署

“系统设置 → 性能节点配置”支持 AUTO/MANUAL/DISABLED。同套 `infra/docker/compose/compose.app.yaml` 通过已知服务名和共享凭据自动识别，无需填写。独立节点可由 Java 通过受限候选私网地址进行 HTTP 单播发现和配对，不依赖 Docker bridge 难以转发的广播或 mDNS。`GET /internal/v1/status` 使用 Bearer 认证返回身份、API 版本和实际能力；健康页可用不代表认证或媒体能力可用。

独立部署示例（在目标设备的仓库根执行，媒体原件只读）：

```sh
docker compose --project-directory . -f infra/docker/compose/compose.compute.yaml up -d --build
```

默认仅发布 `127.0.0.1:18090`。跨主机部署时显式设置 `COMPUTE_HTTP_BIND` 为该设备受限内网地址，限制入站访问；公网使用可信 TLS 反向代理，保留认证与证书验证。主应用 Compose 的计算端口保持不公开。`infra/docker/compose/compose.compute.yaml` 使用独立项目与凭据卷，不启动业务数据库。

节点初次启动生成认证 token，路径为 `/run/singverse-compute/token`。局域网内可在管理端发现并连接节点，凭据交换仅在 Java 与 compute 之间进行，不返回浏览器。仍需手动接入的受信 TLS 部署，可由有权限的部署管理员在目标设备读取 token 并填写管理页面的“认证凭据”；该值是敏感信息，不粘贴到日志或工单：

```sh
docker compose --project-directory . -f infra/docker/compose/compose.compute.yaml exec -T singverse-compute cat /run/singverse-compute/token
```

手动连接时在管理页面选择独立部署，填写 Java 可达的计算节点地址及凭据。曲库仍由 Java 的本地来源或 WebDAV 来源管理；不需要把歌曲复制或挂载到计算机器。所有任务统一使用授权引用回源。

### 局域网配对边界与恢复

三个 `/internal/v1/lan/*` 接口只接受真实 socket 来源为 loopback、RFC1918 IPv4 或 IPv6 ULA，IPv4-mapped IPv6 先归一；公网、link-local、组播及其他保留地址均拒绝。请求 `Host` 必须是允许范围内的 IP literal；带 `Origin`、`Forwarded` 或 `X-Forwarded-*` 的请求直接拒绝，CLI 不采信代理头。不要把这些接口通过公网反向代理转发。它们不要求既有 Bearer，但 POST 在 JSON 解析前限制为 8 KiB，且严格拒绝未知字段。发现只提供 `service/apiVersion/nodeId/name/ownerId/pairingEnabled`。

首次绑定采用受限局域网 TOFU：首次成功签名的 Java 身份成为节点唯一 owner，不宣称抵御同一局域网内主动中间人或抢先绑定。挑战绑定节点、真实来源 IP 和 Java 身份，30 秒过期、使用一次；最多保留 64 个，每个来源 30 秒内最多 16 次挑战或配对请求。签名使用 RSA-PSS/SHA-256、MGF1/SHA-256、32 字节 salt，消息为精确 UTF-8 `singverse-compute-lan-v1\n{nodeId}\n{challenge}\n{clientId}`，无尾换行；`clientId` 为 RSA-3072 DER-SPKI 公钥的 SHA-256 小写十六进制。返回 `encryptedToken` 使用 RSA-OAEP/SHA-256、MGF1/SHA-256，label 为 UTF-8 `singverse-compute-lan-v1:{nodeId}:{challenge}`。只有持有对应私钥的 Java 能解密；浏览器不参与签名或接收凭据。

节点身份和 owner 公钥原子保存在 token 同目录的 `lan-pairing.json`，Unix 权限 0600，Windows 仅当前 SID 和 SYSTEM；跨进程文件锁防止并发首次配对覆盖 owner。重启、IP 变化和内部 token 重新生成均保留 owner，同一 Java 身份可再次签名恢复当前凭据，其他身份返回 409。保留认证卷和 Java 身份备份；目录不可写或已有身份文件损坏时，LAN 接口返回 503，不自动清除 owner，现有 Bearer 计算接口继续可用。修复目录权限或恢复身份文件后重启节点。`COMPUTE_LAN_PAIRING_ENABLED=false` 可禁用新配对与恢复，不创建或修改旁置身份文件；身份可读取时发现仍返回无凭据的节点信息和 `pairingEnabled=false`。

测试连接先检查节点身份、认证和能力，再由节点反向验证业务回源身份。`CALLBACK_FAILED` 表示节点可连接但业务回源不可用。配置变更只影响新任务；扫描固定连接快照，节点不可用时保留文件名回退。

## 业务回源地址及升级

Java 随任务发送回调地址，计算节点不保存部署级业务地址，也不接受任意文件路径。业务侧 `SINGVERSE_CALLBACK_ORIGIN` 为可选可信部署地址；未设置时优先使用已有 `SINGVERSE_PUBLIC_ORIGIN`，否则从已认证管理员请求的入口推导并供本次进程内后台任务复用。`X-Forwarded-Host/Proto` 只信任 `SINGVERSE_TRUSTED_PROXIES` 列出的代理。后台任务在 Java 重启后，需先通过节点可达的地址访问管理页，或由业务部署提供稳定的回源地址。同套 Compose 的 `SINGVERSE_COMPUTE_AUTO_CALLBACK_ORIGIN` 仅用于与 `COMPUTE_NODE_URL` 匹配的自动节点；切换到独立节点时使用业务公开／管理员入口，不沿用同套内部地址。独立节点无法通过 `localhost` 访问业务机，请使用业务机器的局域网入口或显式配置 `SINGVERSE_CALLBACK_ORIGIN`。

接口仅允许任务 UUID、节点 Bearer、固定内部 GET／HEAD／Range 回源路径；授权结束、源文件变化、取消或节点重启不能复用旧媒体授权。连接测试有独立的 15 秒短期认证检查，不读取歌曲。回源需双向网络可达；只测通 Java → compute 不代表媒体读取可用。

升级前停止提交新任务，等待或取消在途任务，再共同更新 Java、前端和 compute。不执行 `down -v`，保留认证、模型和缓存卷。旧路径式任务不能继续执行，失败后管理员可重试；更新 compute 后重新执行模型资格试跑。部署包是源码构建包，首次构建及模型下载仍需网络。

## 后台硬件加速配置

管理员可在“系统设置 → 性能节点配置 → 转码与硬件加速”测试及保存后端，设置持久化在转码缓存根目录 `hardware-settings.json`。后台选择优先于 `COMPUTE_HARDWARE_BACKEND`，选择“遵循部署配置”恢复部署默认。仅影响新任务，不自动修改容器设备和驱动；接口、生命周期及部署前提见[转码说明](../../docs/transcoding.md)。

歌曲解码缓存由 Java 持久保存和管理，compute 仅保存 FFmpeg 临时工作产物；相同素材使用不同执行 ID 仍会独立解码，本节点没有长期歌曲缓存、缓存策略或缓存管理接口，也不读取旧 `cache-settings.json`。`COMPUTE_CACHE_BYTES` 仍为临时工作目录预算（默认 10 GiB），满时新任务返回 507。实时任务租约为 120 秒，成功、失败或取消后未再续租的执行及临时文件在 10 分钟后回收；离线成品继续由 Java 校验下载后确认释放；已确认释放的离线执行记录保留 7 天，失败／取消的离线记录从结束起保留 7 天，随后回收。尚未确认下载的成功离线成品不会因记录 TTL 自动删除。缓存统计合并并发请求，磁盘扫描与删除均在调度锁外进行；无变化的终态目录不重复统计。

Java 可在实时执行成功后读取 `GET /internal/v1/transcoding/executions/{id}/export`，获取 `artifacts` 文件名到 `{size,sha256}` 的映射。导出在调度锁外校验哈希，期间保护该任务的文件，确认释放／删除返回 409，其他任务可继续读取分片、续租与取消。导出会验证主/媒体播放列表、所有白名单本地 URI、分片与初始化文件、媒体 `ENDLIST`、非符号链接、每个文件长度与 SHA-256，并续租，未完成返回 409。Java 应校验全部下载数据并原子保存，再调用 `POST /executions/{id}/acknowledge` 删除 compute 副本；确认可重试且保留执行记录，`released=true`、`ready=false`，再次导出/读产物返回 410。必须先把仍在播放的会话切换至 Java 缓存，避免释放临时文件后继续读取旧播放地址。所有内部接口继续使用 Bearer，不对客户端开放；完整行为见[转码说明](../../docs/transcoding.md)。

完整硬件图通过 `infrastructure/transcoding/pipelines.py` 的固定模板选择，执行前用实际素材试跑。任务返回 `pipeline` 各阶段和观测 RSS；能力页的硬解/零拷贝/GPU 色调映射只来源于已通过的素材试跑。`COMPUTE_PROCESS_MEMORY_BYTES` 默认 1 GiB，以观测 RSS 检查并终止超限子进程；Linux 容器额外默认 2 GiB 内存/2 CPU 硬限制，可按 NAS 资源调整。

播放冷启动只试跑当前请求的输出编码，复用该编码的试跑结果；管理员能力查询仍返回完整编码清单。内部 `/negotiate` 可携带 `fingerprint`，同一 `reference + callbackOrigin + fingerprint` 的探测快照仅供随后执行复用一次，60 秒失效、最多 128 条；实际编码继续通过当前授权回源读取，不持久化媒体探测缓存。`target.profile` 仅接受空值／`baseline`／`main`／`high`，`target.level` 使用 ffprobe 的 H.264 整数单位（31 表示 3.1、9 表示 1b），只适用于 H.264。Java 按设备明确报告的 profile/level 限制输出尺寸、帧率和码率，Baseline 禁止 B 帧和 CABAC；未报告时保持原编码默认值。

实时 `TRANSCODE` 的 HLS 分段及强制关键帧周期为 2 秒，`AUDIO_TRANSCODE` 目标分段为 2 秒但保留原视频 GOP，`REMUX` 与离线模板保持原设置。HLS 为 EVENT、保留本执行从 `offset` 起的所有分片。只有主列表引用的每条视频／音频列表、各自首片及 fMP4 初始化文件均已发布才返回 `ready=true`；就绪结果在执行内记忆，续租不重复遍历全曲，失败／取消／释放后立即为 false。完整文件校验仍在导出时执行。Java 在后台复制整曲缓存，播放 READY 和续租不等待复制完成。

管理员完整能力查询会试跑所有适用编码，仍需占用 CPU/GPU 与探测锁；并发播放可能等待这次完整探测结束。按需复用消除正常播放中不相关编码的试跑，不表示手工完整探测可与播放完全无争用。

通用 `/internal/v1/status`／`capabilities` 的音频能力查询不会主动启动或等待模型环境探测；后台探测尚未完成或持锁时暂不声明重计算音频能力，视频探测／转码能力仍正常返回。实际音频任务继续校验固定版本、模型摘要及后端资格。H.264 profile 匹配允许 Constrained Baseline → Baseline、Constrained High → High 的受限子集方向，反向仍要求转码。

## WebDAV 远程内嵌元数据

`media-remote-tags` 统一处理本地与 WebDAV 媒体标签。Java POST `/internal/v1/media/remote-tags`，请求 `{reference, callbackOrigin, filename, size, deep}`；固定回源 `/api/v1/internal/metadata-input/{id}`，不接受任意媒体 URL、路径或 WebDAV 凭据。原路径式 `/internal/v1/media/tags` 已移除。

工作器用有界、可 seek 的 Range 文件对象读取，Mutagen 读取标签、封面、USLT/SYLT、MP4/Vorbis 歌词；ffprobe 读取容器和流信息，FFmpeg 仅提取附图或明确的封面附件，不截图。Mutagen、ffprobe 和 FFmpeg 共享任务内字节块缓存，不拼接头尾伪造文件。普通预算 16 MiB/16 请求/60 秒，深度预算 128 MiB/64 请求/180 秒；Python 与 Java 分别约束，Java 另计入重定向请求和实际流量，保留访问组限速与冷却，不设每日累计提取额度。超限保留已有信息，字段状态区分 PRESENT/ABSENT/UNKNOWN。

结果包含 tags、technical、cover（仅内部传输的有界 Base64）、lyrics、字段覆盖状态、提取器版本及统计。封面最大 2 MB、歌词最大 256 KiB，Java 校验和保存本地衍生文件，Python 不写业务库。普通歌词与同步歌词保留原格式；SYLT 帧时间不误标为毫秒。MP3/FLAC/M4A/MP4/OGG/Opus/MKV 的常见结构纳入测试，未知结构允许部分完成。远程读取需要 Range 支持，忽略范围的来源不会降级完整下载。

本地媒体同样使用任务回源，不保留路径式标签接口；旧文档中“WebDAV 不读取内容”适用于未启用远程提取的来源。运行 `make compute-setup` 安装锁定的 Mutagen 1.47.0，再运行 `make compute-check`；新增测试使用本机随机端口、临时音视频、尾部 MP4 索引和 MKV 附件，不连接真实网盘。

## 原唱识别与伴奏分离

### 扫描快速识别（可选模型）

Mac 原生节点首次安装：

```bash
make compute-fast-audio-setup
services/singverse-compute/.venv/bin/python scripts/macos-compute.py restart
```

复用固定 `requirements-audio.lock` 中的 Torch 2.5.1 / NumPy 1.26.4，不引入 TensorFlow 或在线推理服务；安装此目标只下载分类权重，不下载 Demucs 分离权重。现有音频运行环境已满足时，也可仅在“性能节点配置 → 伴奏模型”下载快速人声分类模型。PANNs 权重独立存放在 `COMPUTE_AUDIO_MODEL_REPO` 同级 `panns-cnn6/Cnn6_mAP=0.343.pth`，脚本默认 `.tools/panns-cnn6`；自定义目录使用 `scripts/setup-fast-audio-model.py --directory <绝对路径>`。缺少路径时需先配置模型目录；不会在扫描时自动联网安装。Docker 使用已有固定音频运行库镜像（如 `infra/docker/compose/compose.audio-cpu.yaml`），随后在网页下载快速分类模型；快速识别不要求下载 Demucs 权重或完成分离设备试跑。原生 setup 命令不能替换正在运行的容器环境。

作者发行版 Zenodo 3987831，23,698,321 字节，SHA-256 `8a17541944f2623a2a0164e072ac2f915dcedca99968083cc44bfbc7eb682275`；权重 CC BY 4.0，归属 Qiuqiang Kong 等，PANNs: Large-Scale Pretrained Audio Neural Networks for Audio Pattern Recognition。网络结构适配固定修订 `d2f4b8c18eab44737fcc0de1248ae21eb43f6aa4` 的 Cnn6；MIT 归属见 `singverse_compute/infrastructure/PANNS_LICENSE.txt`。加载前检查固定体积及完整摘要，使用 `weights_only=True`，不执行上传模型代码。离线导入接受只含此 PTH 文件的根目录 ZIP，上限 32MiB；保留下载/导入/取消及原子安装规则。

分类使用固定 CPU 小模型，不参与分离/转码的设备选择；无需 GPU 试跑资格。独立常驻子进程复用已加载模型，线程数 2，单次最多 6×6 秒，45 秒超时；RSS 上限取节点音频预算与 1GiB 的较小值。推理取消（含实时播放抢占）、异常或节点关闭时停止分类进程；不确定结果保留人工纠正及显式深度识别。`audio-role-fast` 表示标签快速路径可用，`audio-role-fast-classifier` 才表示分类权重与固定运行环境齐备；节点 `fastClassifier.ready` 与分离模型就绪状态独立。

显式本地权重检查（不会下载模型）：

```bash
SINGVERSE_TEST_FAST_MODEL_REPO="$PWD/.tools/demucs-htdemucs" \
  services/singverse-compute/.venv/bin/python -m unittest discover \
  -s services/singverse-compute/tests/integration -p test_fast_audio.py -v
```


常规模型运行时固定为 Demucs 4.0.1、torch/torchaudio 2.5.1；CPU依赖见 `requirements-audio.lock`，Linux x86_64 CUDA 12.4 / ROCm 6.2 分别见 `requirements-audio-cuda.lock` / `requirements-audio-rocm.lock`。gfx1103 的原生 ROCm 配置独立固定为 torch/torchaudio `2.11.0+rocm10.0.0`，见 `requirements-audio-rocm-native.lock`，不替换其它后端。原生节点先执行 `make compute-setup`，CPU/Mac可用 `make compute-audio-setup`（同时显式下载模型到 `.tools/demucs-htdemucs`）；CUDA/旧 ROCm 分别执行 `make compute-audio-cuda-setup` / `make compute-audio-rocm-setup`，Linux x86_64 / Python 3.12 的 gfx1103 节点使用 `make compute-audio-rocm-native-setup`（均仅安装环境）。节点不能在 HTTP 请求中安装 Python 依赖或驱动。

### 深度识别与伴奏分离

管理端“设备与服务 → 性能节点配置”可显式下载官方权重到当前节点，也可导入官方原始 `.th` 文件（不是压缩包或任意PyTorch模型），上限100MiB。固定文件大小84,141,911字节及完整SHA-256校验通过后，自动写入 `htdemucs.yaml` 并原子安装；没有推理任务会隐式下载权重。断网、取消、摘要错误或节点重启均保留已有模型；目录内容冲突时须管理员先保留或移动旧目录。

原生节点设置 `COMPUTE_AUDIO_MODEL_REPO` 为模型目录绝对路径，且父目录预先存在并可写；安装到已有只读目录时可正常使用预置模型，但不能通过网页下载/导入。下载完成还需“重新检测环境 → 对所需后端执行模型试跑 → 自动选择或指定后端”。试跑使用8秒合成音，只验证执行、声道、时间线及数值有效性，不能代替真实歌曲音质和长曲验收。未通过环境与模型试跑不声明识别/分离能力，探测和人工纠正仍可用。

- Java 提交 `/internal/v1/audio-roles/tasks`，`mode=PROBE|FAST|ANALYZE|SEPARATE`，沿用内部 Bearer、本地／WebDAV 统一的授权引用回源。稳定 UUID 绑定输入和模式；查询、取消、读取及释放产物均在该任务路径下。
- FAST 是扫描默认识别：明确的原唱/伴奏音轨标签成对出现时直接使用；否则先抽取 25%、50%、75% 附近各最长 6 秒的双轨片段，一次批量调用轻量 PANNs Cnn6。`fast-vocal-v2` 使用两轨相对差异，不要求模型分数达到 0.4：有效片段要求较高分 ≥0.05、分差 ≥0.03、平滑后比例 ≥2.5，至少两个片段方向一致且无反向强证据才标记。仅证据不足且歌曲 ≥60 秒时，在 10%、40%、90% 补采最多三个独立片段，总计最多六个；冲突和两轨强人声不通过补采强行决定。每段两轨一起读取；不生成分离音频。双轨强人声、相反证据、标签冲突或缺少模型/运行环境均保持 UNKNOWN，不自动转去跑 ANALYZE。分类得分不是准确率概率，不能承诺 90%。
- ANALYZE 是管理员显式选择的“深度识别”，只接受两条源音轨。`vocal-activity-v2` 把歌曲分为五个区域，先分析两个、不确定时最多五个互不重叠的片段（每段最长 20 秒，60～100 秒歌曲缩短片段）；全曲不足 60 秒仍保留 UNKNOWN。按半秒帧计算人声/输入 RMS 比，在排除静音后取中位数，至少四帧有声才有有效数值，减少间奏、静音及瞬时强音干扰。强方向判断继续要求人声比 ≥0.25、另一轨 ≤0.06、两轨比例 ≥6；至少两个一致片段且占非弱人声片段的 2/3，无反向强证据，才能自动标记。弱人声片段不投票，方向或标签冲突保留 UNKNOWN；深度识别中的标签不会单独决定角色，`No Vocals` 不再被当作原唱标签。立体声左右声道不会被当作两条音轨。
- 完成采样的正常/不确定分析均保存版本、片段起点/时长、人声比及活跃帧数，供 Java 管理界面诊断。两轨均有较强人声也保留 UNKNOWN。能量比不是准确率，也未校准为概率；真实歌曲的自动标记覆盖率与标反率仍须分别验收。不确定歌曲允许管理员重新分析，后台发现任务不会循环自动重试；FAST_MODEL_MISSING 在安装分类器后允许自动再识别一次；新版节点具备 `audio-role-fast-v2` 时，旧版 FAST 的 UNKNOWN 也由 Java 自动补跑一次，同版本不循环重试。快速识别不覆盖任何已确认角色，深度识别仍保护人工/共享确认结果。
- SEPARATE 仅对单音轨执行，保持原始时间线的前置静音和尾部填充，输出独立 AAC M4A；校验起点、时长、体积与 SHA-256。临时工作目录、取消/失败产物清理；重启后未完成任务报告 NODE_RESTARTED，Java 展示失败并允许管理员重试。
- 音频任务一次一个，默认 CPU 线程 2、进程 RSS 上限 4 GiB（`COMPUTE_AUDIO_THREADS`、`COMPUTE_AUDIO_MEMORY_BYTES`），共享磁盘预算及最小空余限制，超时 2 小时。实时播放先终止音频任务，再重试获取执行容量；音频任务不能进入实时预留期间。compute被抢占任务显示 CANCELLED / REALTIME_PREEMPTED，Java记录为失败，批量页面可显式重试；默认开启的统一 CPU 自动降级仅对明确的加速后端故障重试一次，记录实际设备及原因；关闭后不切换。无效媒体、磁盘不足、取消和进程内存预算错误不重试。
- Java 校验下载的产物后保存至自己的衍生目录，确认成功后释放 compute 副本；未收到确认的 compute 音频在后续任务回收时保留最长一天。不要把衍生目录放入原始媒体目录。

Compose默认不安装音频运行环境或下载权重，但已提供持久卷 `singverse-audio-models`，默认模型目录 `/var/lib/singverse-models/htdemucs`。CPU部署使用 `infra/docker/compose/compose.audio-cpu.yaml`，叠加该覆盖文件后重建并重新创建 compute（覆盖文件已选择 CPU target 和默认 6g 容器上限）；随后从网页下载模型和试跑。原有只读模型挂载可通过override继续使用。默认2g容器不满足默认4GiB音频预算，不会因识别到CPU就启用任务。Java的 `AUDIO_DERIVED_PATH` 为持久卷 `/data/audio-roles`；原媒体卷只读。

### Mac 原生伴奏节点

现有 Docker 应用按根 README 的 `infra/docker/compose/compose.compute.macos.yaml` 连接本机节点；音频和 VideoToolbox 共用同一个原生进程。安装及重新加载：

```sh
make compute-audio-setup
services/singverse-compute/.venv/bin/python scripts/macos-compute.py restart
```

启动器将固定模型目录 `.tools/demucs-htdemucs` 传入节点，默认音频预算 4096MiB；内存较紧张时可显式 `restart --audio-memory-mib 2048`，后续重启保留预算。本机 M2 的 8／60 秒验收使用 2048MiB；这不是任意歌曲的内存保证。模型安装仍是显式操作，网页也可下载或导入。进入“设备与服务 → 性能节点配置”，对 CPU 和 MPS 分别试跑；自动选择优先使用通过验证的 MPS。升级代码或运行环境后重新试跑。

`mps_audio.py` 仅对超长一维卷积及转置卷积进行分块，按原始 stride／padding／dilation 保留感受野，转置卷积累加重叠贡献并只加一次偏置。没有缩短模型片段、变更权重或开启 PyTorch CPU 回退。频谱处理仍由 Demucs 在 CPU 完成，任务记录 `mps+cpu-stft`。原生节点忙碌及 RAM 暂时不足返回可重试状态，Java 保留后续批量成员排队。

本机复验 `make macos-audio-check`，包含卷积数值回归和实际 MPS／CPU 分离对照；要求已安装固定模型及原生 Apple GPU 访问。完整 Java 批量入库检查使用已构建的 JAR、随机端口与临时 SQLite，不触碰当前曲库：

```sh
JAVA_HOME=/absolute/path/to/jdk-21-or-newer \
PATH="$PWD/.tools/jellyfin-ffmpeg/bin:$PWD/.tools/jellyfin-ffmpeg:$PATH" \
SINGVERSE_TEST_MACOS_APP_JAR="$PWD/services/singverse-java-backend/target/singverse-java-backend-0.1.0-SNAPSHOT.jar" \
SINGVERSE_TEST_MPS_MODEL_REPO="$PWD/.tools/demucs-htdemucs" \
  services/singverse-compute/.venv/bin/python -m unittest discover \
  -s services/singverse-compute/tests/integration -p test_macos_app.py -v
```

CI 可手动启用 `mps_model_smoke`，执行器须实际暴露 Apple GPU；普通 macOS 虚拟执行器不保证可用。本次仅本机验收通过，未运行远程 GPU CI。真实歌声音质、数分钟以上长曲及其他 Mac 仍须分别验收。

### Linux GPU容器与设备验收

- NVIDIA 3070/A3000：宿主安装兼容驱动和NVIDIA Container Toolkit，然后使用 `docker compose --project-directory . -f infra/docker/compose/compose.app.yaml -f infra/docker/compose/compose.audio-cuda.yaml build singverse-compute`，再用同样的两个配置文件启动。override安装固定CUDA运行环境并申请一张GPU，不安装宿主驱动。
- 兼容ROCm的AMD GPU：先配置宿主驱动、`/dev/kfd`、`/dev/dri`和实际render/video组数字ID（`SINGVERSE_RENDER_GID` / `SINGVERSE_VIDEO_GID`），使用 `infra/docker/compose/compose.audio-rocm.yaml` 代替CUDA override。8845HS/255HS按节点实际GPU标识检测，不承诺集显支持，不设置伪造GPU标识。
- Intel 8400T/N100：提供CPU路径；QSV/VA-API是编解码功能。新增独立OpenVINO适配器与Intel官方转换模型，但这两种设备仍需分别试跑及效果验收；PyTorch XPU路径明确为 `MODEL_UNSUPPORTED`。
- RK3566：Linux ARM64 CPU路径需实际依赖安装成功和内存充足；RKNN实验适配支持本地转换模型，须安装专用运行时并完成板端试跑；见下方部署说明。不能凭板卡名称宣布NPU可用。
- Apple M2：macOS原生CPU/MPS，Docker内不开放MPS。关闭PyTorch隐式CPU算子回退；Demucs自身的频谱变换明确在CPU执行，网络使用MPS，页面及任务标记 `mps+cpu-stft`。固定运行时的超长一维卷积通过保留重叠边界的 GPU 分块处理；其他算子不支持时仍明确失败，可选择通过试跑的CPU。

CPU/CUDA/ROCm/MPS的执行选择及试跑门禁已接入；M2 原先的“Output channels > 65536”限制已通过数学等价的卷积分块解决，本机 2GiB 预算下资格试跑及 8／60 秒分离通过。Intel GPU和RKNN转换/音质验收尚未完成，不把硬件识别或待适配状态算作加速实现。各型号的长曲、音质、内存和速度仍须逐台验证。运行环境、内核/驱动、设备或模型版本改变后旧试跑凭据失效；可用后端才进入自动选择，默认顺序CUDA → ROCm → OpenVINO → RKNN → MPS → CPU。每个任务保存实际后端与模型版本；检查RAM、磁盘及CUDA/ROCm可用显存（至少2GiB），仍可能因模型/歌曲峰值资源不足失败。

实际模型冒烟测试（只验证执行链路，合成音不是音质基准）：

```sh
SINGVERSE_TEST_AUDIO_MODEL_REPO="$PWD/.tools/demucs-htdemucs" \
  services/singverse-compute/.venv/bin/python -m unittest discover \
  -s services/singverse-compute/tests/integration -p test_audio_model.py -v
```

原生 CI 默认不下载模型；手动触发 `singverse-compute-native` 并启用 `audio_model_smoke` 才执行真实模型测试。其他架构与 NAS 内存/耗时仍需分别验收。

## 分设备计算镜像（CPU／CUDA／ROCm／OpenVINO）

镜像按推理后端划分，不按处理器型号命名。统一 Java 和 compute 发行版本：

| 标签后缀 | 架构 | 环境 | 设备验收状态 |
|---|---|---|---|
| `-cpu` | linux/amd64、linux/arm64 | Torch 2.5.1 CPU、Demucs 4.0.1 | ARM64 镜像本次构建通过；各设备音乐效果与长曲分别记录 |
| `-cuda` | linux/amd64 | Torch 2.5.1+cu124 | 各设备需重新试跑及完整歌曲验收 |
| `-rocm` | linux/amd64 | Torch 2.5.1+rocm6.2 | AMD APU 按实际 GPU 标识与运行时验证，不能由型号推定兼容 |
| `-rocm-native` | linux/amd64、Python 3.12 | Torch 2.11.0+rocm10.0.0、gfx1103 设备库 | Radeon 780M 单机实测见 [ROCm 记录](../../docs/verification-rocm-2026-11-08.md)；不泛化到其它 GPU |
| `-openvino` | linux/amd64 | OpenVINO 2025.4.0、CPU Torch 2.5.1、固定 Intel OpenCL 用户态库 | N100／8400T 分别待真机验证 |

CPU 镜像由 Docker 清单选择 amd64／arm64，GPU 后端需要显式选择。RKNN 采用实验性原生部署，尚无已验收转换模型，因此不发布 `-rknn` 镜像；RK3566 可验证 ARM64 CPU，资源不足时阻止任务。M2 使用 macOS 原生环境及下述 MPS 分块适配器。视频转码后端与 AI 推理后端独立检测，QSV 可用不等于 OpenVINO 可用。

### gfx1103 原生 ROCm 与固件

`rocm-native` 是独立配置，SDK 包版本为 10.0.0，实际 `torch.version.hip` 为 7.15.26333。锁文件直接固定官方 AMD 仓库的已测试文件和实测完整 SHA256；AMD 未提供这些文件的完整摘要，不能把本地计算的摘要称为厂商校验和。共享依赖从 `requirements-audio-dependencies.lock` 读取，旧配置仍固定 SymPy 1.13.1，原生配置按 Torch 要求单独固定 1.13.3。

显式构建：`docker buildx bake -f infra/docker/build/docker-bake.hcl rocm-native`。应用或独立 compute 的基础 Compose 后依次叠加 `infra/docker/compose/compose.audio-rocm.yaml`、`infra/docker/compose/compose.audio-rocm-native.yaml`，设置实际 render/video 组 ID，选择对应 `COMPUTE_IMAGE`。ROCm 配置包含无限 memlock 和 256MiB 共享内存，仍受容器内存和音频进程预算限制。数字 UID 使用可写的 `/tmp` HOME 和明确用户名，避免编译缓存写入 `/` 或依赖不存在的 passwd 条目。

该配置通过专用适配器读取既有官方权重与 44.1kHz 双声道 PCM，保持模型原始 7.8 秒分段、0.25 重叠和 shifts=0。每次工作进程从同一已核验大小/SHA256的文件描述符加载官方 checkpoint，局部使用 `weights_only=False`，不全局修改 Torch 加载行为；音频读写使用 SoundFile，避免新 torchaudio 对额外 TorchCodec 的要求。实际模型参数和每次 forward 的输入/输出必须留在指定设备；GPU 不可用或架构不符立即失败，不在适配器内隐式转 CPU。CPU 仅接受显式选择或上层已有的、带结果标记的降级流程。

原生 ROCm 的连续任务复用一个独立子进程和已校验模型，空闲默认 60 秒后退出；`COMPUTE_AUDIO_WORKER_IDLE_SECONDS` 范围 0～3600，0 使用每次独立启动。模型文件的设备、inode、大小、mtime/ctime 或后端变化会重启进程并完整校验权重；不缓存不同后端的多份模型。GPU 空闲显存查询复用此进程，但仍在每次准入时检查。取消、执行失败、120 秒无推理进展、内存超限、节点停止及实时播放抢占都会销毁进程，后续任务重新启动。播放抢占同时释放闲置模型，不在播放调度锁内等待进程回收。

适配器先分块计算整首歌曲的全局均值及无偏标准差，再按原始分段、三角重叠权重与尾段上下文推理并逐段写出，CPU 不再持有整首四条音轨。只生成伴奏时不写无人使用的人声 WAV；模型试跑和深度识别仍输出人声。源 PCM、伴奏 WAV、AAC 编码及完整校验仍按顺序执行，尚不是边生成边播放。其它运行时保留原适配器，不宣称已获得相同的模型驻留优化。

音频任务使用专用、可取消的 ffprobe 投影，跳过视频帧/HDR 检查，并在一次产物探测中校验时长和起点。任务结果增加 `stage`、`progress`、`timingsMs` 和原生 `inference` 证据：`queue` 为 Java 本地采集并合并的排队时间，不扩展内部提交请求；`admission/probe/extract/separate/encode/validate/hash/total` 为单调时钟毫秒，Java 接收后补充 `transfer`。`extract` 包含回源和解码；`separate` 包含 IPC、模型加载、推理及 WAV 写出，嵌套 `inference.timingsMs` 为其中的细分，不能将所有字段直接相加。`progress` 只表示当前分离任务已完成重叠合并的音频帧，不是整任务进度。旧节点缺省这些字段仍兼容。

本地有固定模型时，可显式比较流式合并与完整 Demucs 参考输出（CPU 正确性测试，不授予 GPU 资格）：

```sh
SINGVERSE_TEST_AUDIO_MODEL_REPO="$PWD/.tools/demucs-htdemucs" \
  services/singverse-compute/.venv/bin/python -m unittest discover \
  -s services/singverse-compute/tests/integration -p test_native_audio_streaming.py -v
```

本轮变更入口、本地回归、1 万／10 万索引测量及隔离 NAS 实际歌曲结果见 [伴奏优化验证记录](VERIFICATION.audio-2026-11-08.md)。记录区分适配器推理、端到端耗时和生产部署状态。

ROCm 6.2 的现有轮子缺少这台 gfx1103 所需的原生算子库，不能靠伪造 `HSA_OVERRIDE_GFX_VERSION` 修复。仅换运行时仍曾触发 MES/GPU Hang。该 NAS 的 GC IP 是 11.0.1，与 `gfx1103` 名称不同；试验采用 linux-firmware 固定提交 `9ec083c3aba932190e3c160e52cbb7e264148b61` 中的 ME/MEC/MES_2/PFP 四个文件，安装到 `/lib/firmware/updates/amdgpu/` 并校验 initramfs，保留原文件及启动镜像回滚副本。固件文件名、哈希、驱动重载限制和证据见上述真机记录；不由容器或网页自动更新宿主固件。

镜像、内核或固件改变后重新执行环境检测与所选后端试跑，再用实际歌曲验收。8 秒试跑成功不代表长时间稳定，也不等同于音质验收。

### 本地构建与预构建镜像

RTX 3070／A3000 独立节点使用根目录 `infra/docker/compose/compose.compute.nvidia.yaml`，参数直接写在 YAML，无需 `.env`。节点默认仅绑定 `127.0.0.1:18090`；跨主机部署时改为该 GPU 主机实际的受限局域网地址，并限制入站仅业务节点可访问。宿主安装兼容 NVIDIA 驱动与 NVIDIA Container Toolkit；无需媒体目录或 Java 地址配置。

```sh
docker compose --project-directory . -f infra/docker/compose/compose.compute.nvidia.yaml up -d --build
docker compose --project-directory . -f infra/docker/compose/compose.compute.nvidia.yaml ps
```

此命令需在完整仓库根目录执行，构建 CUDA 镜像，不自动下载模型。首次启动自动生成并持久保存认证凭据；在管理端配置独立节点地址及凭据，再下载／导入模型并进行 CUDA 试跑。视频 NVENC 与音频 CUDA 分别验证；各设备需重新试跑及完整歌曲验收。停止使用 `docker compose --project-directory . -f infra/docker/compose/compose.compute.nvidia.yaml down`，保留命名卷。该文件与原 `infra/docker/compose/compose.compute.yaml` 使用相同项目名及卷，作为替代入口使用，不同时启动两份独立节点。

从源码在实际节点构建，例如 Intel GPU 独立节点：

```sh
COMPUTE_RENDER_GID=109 \
  docker compose --project-directory . -f infra/docker/compose/compose.compute.yaml -f infra/docker/compose/compose.audio-openvino.yaml up -d --build
```

`109` 只是示例，使用宿主 `/dev/dri/renderD128` 的实际组 ID。应用内节点把 `infra/docker/compose/compose.compute.yaml` 换成 `infra/docker/compose/compose.app.yaml`；CPU 使用 `infra/docker/compose/compose.audio-cpu.yaml`，NVIDIA 使用 `infra/docker/compose/compose.audio-cuda.yaml`，AMD 使用 `infra/docker/compose/compose.audio-rocm.yaml`。AMD 配置实际 `SINGVERSE_RENDER_GID` 与 `SINGVERSE_VIDEO_GID`。这些 override 默认容器预算 6g；音频进程默认预算 4GiB，不能把默认轻量节点的 2g 限制用于分离任务。媒体只读，模型持久卷 `singverse-audio-models` 与任务／编译缓存卷 `singverse-transcode-cache` 独立。

音频运行时探测和 GPU 空闲显存查询也受 `COMPUTE_AUDIO_MEMORY_BYTES` 配置的音频进程内存上限约束（默认 4GiB），各自保留 30 秒超时，避免 ROCm 导入超过通用媒体进程默认 1GiB 上限而被误报为运行环境缺失。识别运行环境后仍须满足资源门槛并通过实际模型试跑。

公开正式 release 工作流 `.github/workflows/singverse-compute-images.yml` 构建并发布 `ghcr.io/<仓库所有者>/singverse-compute:<版本>-<后端>`。版本必须与 Java POM 对应；PR 和手动工作流只构建检查，发布需正式 release。仓库中有工作流不代表镜像已经在注册表发布，也不代表 GPU 已验收。

使用已经发布的镜像时，设置完整固定标签并禁止本地构建：

```sh
COMPUTE_IMAGE=ghcr.io/<仓库所有者>/singverse-compute:<版本>-openvino \
COMPUTE_RENDER_GID=109 \
  docker compose --project-directory . -f infra/docker/compose/compose.compute.yaml -f infra/docker/compose/compose.audio-openvino.yaml pull singverse-compute
COMPUTE_IMAGE=ghcr.io/<仓库所有者>/singverse-compute:<版本>-openvino \
COMPUTE_RENDER_GID=109 \
  docker compose --project-directory . -f infra/docker/compose/compose.compute.yaml -f infra/docker/compose/compose.audio-openvino.yaml up -d --no-build
```

尖括号参数必须先替换；也可把相同配置写入部署目录的 `.env`，不要把凭据写进公开文件。`infra/docker/build/docker-bake.hcl` 提供矩阵，`make compute-images` 构建全部变体。单后端本机示例：

```sh
VERSION=0.1.0-SNAPSHOT docker buildx bake -f infra/docker/build/docker-bake.hcl cpu --set cpu.platforms=linux/arm64 --load
```

NVIDIA 需要宿主驱动和 NVIDIA Container Toolkit；ROCm 需要兼容内核驱动、设备和对应运行库。OpenVINO 镜像固定用户态 OpenCL 包，不修改宿主驱动；N100 与老核显的运行库、算子和资源需求须分别试跑。镜像没有 Docker socket，不会从网页变更容器配置。

仅支持单个 Compose 文件的部署工具，可以把 override 中 `singverse-compute` 的 `image`、`build.target`、`mem_limit`、`devices`、`group_add` 或 NVIDIA `deploy.resources` 合并到基础服务。例如 OpenVINO：

```yaml
services:
  singverse-compute:
    image: ghcr.io/<仓库所有者>/singverse-compute:<版本>-openvino
    mem_limit: 6g
    devices:
      - /dev/dri/renderD128:/dev/dri/renderD128
    group_add:
      - '109' # 替换为实际 render 组 ID
    # 其余环境、认证与持久卷照基础 Compose 保留；媒体使用任务回源。
```

离线镜像交付：联网设备先拉取目标架构的固定镜像，`docker save -o compute-image.tar <完整镜像标签>`，复制到目标设备后 `docker load -i compute-image.tar`，再按同一设备配置 `up -d --no-build --pull never`。导出的镜像仅适用于已拉取的架构；模型仍需单独导入。

### Intel 官方转换模型和离线包

`htdemucs` 保持基准模型。`htdemucs-openvino` 使用 Intel 官方仓库固定修订 `efb3d2bc54f93899b13b72b12c57001e19f09ae2` 下 `htdemucs_v4/htdemucs_fwd.xml` 与 `.bin`；文件大小、SHA-256、采样率和输出源顺序显式记录在 `application/audio_catalog.py`。神经网络在指定 `GPU.*` 执行，STFT／逆变换／重叠拼接复用 Demucs 4.0.1 的 CPU 流程；不使用 `AUTO`／`HETERO` 隐式回退。编译缓存按模型摘要、环境指纹及构建身份隔离，不与权重混用。

管理员可在「设备与服务 → 性能节点配置」选择模型后下载，或在有网络的源码设备执行：

```sh
services/singverse-compute/.venv/bin/python scripts/setup-audio-model.py \
  --model htdemucs-openvino --bundle /absolute/path/to/htdemucs-openvino.zip
```

ZIP 根目录必须恰好包含上述 XML／BIN 两个文件。基准模型继续接受原始 `.th`。Java 和 compute 均按固定模型设置上传上限（基准 100MiB，OpenVINO 128MiB），拒绝多余成员、路径、符号链接、错误大小和摘要；临时目录验证后原子启用，旧的有效模型保留。只读预置目录继续可用，网页不执行 pip、安装驱动或隐式下载。

更换镜像、模型、运行库或设备会使旧试跑资格失效。仅通过当前环境试跑的后端进入自动选择，历史任务保存构建、模型摘要、运行库、环境指纹及实际设备。CPU 重试必须有独立校验过的基准模型与足够资源。

显式本机 OpenVINO 流程检查（`CPU` 只验证转换流程，不授予 Intel GPU 资格）：

```sh
SINGVERSE_TEST_OPENVINO_MODEL_REPO=/absolute/path/to/.tools/htdemucs-openvino \
  services/singverse-compute/.venv/bin/python -m unittest discover \
  -s services/singverse-compute/tests/integration -p test_openvino_audio.py -v
```

官方参考：Immich 的硬件镜像与远程 ML 文档、Intel `demucs-openvino` 模型库。镜像构建、合成音试跑不替代真实歌曲质量、长曲、NAS 和对应 GPU 真机验收。

模型状态查询共用一次后端快照。RKNN 权重校验按 manifest 与模型的设备、inode、大小、mtime／ctime 缓存，使用独立锁避免等待环境探测；文件变化立即失效，实际执行前仍重新验证完整摘要。视频转码与伴奏推理共用节点 `hardware-settings.json` 的 `cpuFallback`，旧配置默认开启；通过现有硬件设置 API 保存并校验 revision，只对新任务生效。返回 `detectedBackends`，音频后端返回 `detected` 区分物理存在与运行就绪；仅安装编码器不算硬件存在。有加速硬件时拒绝手选 CPU，旧固定 CPU 配置按硬件优先解析。降级须满足 CPU 运行条件，推理还需独立试跑；任务结果记录 `fallbackReason`。HLS 已发布播放清单后不切换后端。

节点启动后在后台检测推理环境，自动为已安装模型的未验证设备执行一次真实分离试跑，优先加速设备；允许 CPU 自动降级时同时验证 CPU。复用当前环境与权重仍匹配的资格，不自动下载模型，也不循环重试已失败的设备；缺失依赖或模型继续给出配置原因。实时播放或模型操作占用时等待，取消试跑或停止节点会中止后续自动检查。模型状态返回 `startupCheck`（检测、等待、试跑、完成、失败或取消）；启动健康检查不等待模型推理完成。


### RK3566 / fnOS：UVR ONNX 基准与 RKNN 实验部署

RKNN 路径现在使用 **UVR_MDXNET_9482**，不再使用 HTDemucs forward 模型。固定上游发布文件为 29,704,738 字节，SHA-256 为 `9d78f8566fa8198065214ab628be1de966a500c57786695aa4b13e2b27a7727d`。下载入口为 sherpa-onnx 的 `source-separation-models` release；模型使用条件以 UVR 上游说明为准。任务不隐式联网下载模型。

先在可运行 Python 3.12 的机器上建立 CPU 基准：

```sh
make compute-setup compute-audio-uvr-setup
cd services/singverse-compute
.venv/bin/python -m singverse_compute.infrastructure.uvr_audio \
  --model ../../.tools/uvr-mdxnet-9482/UVR_MDXNET_9482.onnx \
  --output /absolute/path/to/test-output /absolute/path/to/stereo-44100.wav
```

输入须为双声道 44.1kHz WAV，输出为 `test-output/htdemucs/<文件名>/vocals.wav` 与 `no_vocals.wav`（目录名沿用现有任务约定）。独立 CLI 的 ONNX CPU 基准不参与管理端后端自动选择，也不替换既有 Demucs CPU 降级。DSP 使用模型内置参数：4096 点 Hann FFT、1024 hop、2048 频率 bin、256 帧；保留双声道，分块重建，伴奏由源信号减去预测人声生成。

RKNN 转换必须在独立的、RKNN Toolkit2 支持的转换环境执行，不在飞牛网页请求中安装工具：

```sh
# 转换环境安装 rknn-toolkit2==2.3.2 后，从仓库根目录执行
python scripts/convert-rknn-audio.py \
  .tools/uvr-mdxnet-9482/UVR_MDXNET_9482.onnx \
  --target rk3566 --output /absolute/path/to/uvr-mdxnet-9482-rknn
```

脚本先校验固定 ONNX 摘要，再将动态 batch 固定为 1，按 `[1,4,2048,256]` 转换；不量化。不支持的算子或构建失败会明确失败，不生成可部署 manifest。输出目录须不存在；成功输出 `uvr.rknn` 和 `manifest.json`。目前没有实际 RKNN 转换成功或板端执行结果，不能把脚本存在视作 NPU 已可用。

板端原生部署步骤：

1. fnOS 所用内核必须提供与 RKNN Lite2 2.3.2 匹配的 RKNPU 驱动及 `librknnrt.so`；服务用户应有 NPU 设备权限。没有驱动时仅复制 Python 包不能启用 NPU。未远程检测你的设备，因此具体 fnOS 镜像的驱动条件尚未确认。
2. 执行 `make compute-setup compute-audio-rknn-setup`，将上述转换目录放到 `COMPUTE_AUDIO_MODEL_REPO` 同级的 `uvr-mdxnet-9482-rknn/`，避免运行时替换。旧 `htdemucs-rknn/` 不再读取，旧试跑资格自动失效。
3. 沿用现有 compute 认证与缓存配置启动节点，媒体使用任务回源；默认音频预算 4GiB，内存不足会阻止任务。可选 Dockerfile `rknn` 构建阶段及设备映射配置见部署包说明，不使用特权容器。
4. 刷新管理端节点，RKNN 模型应显示 `UNVERIFIED`，执行试跑后才能选择。试跑校验约 8 秒输出的时间轴与非静音，不能替代真实歌曲音质验收。网络下载和网页导入 RKNN 模型仍不支持。
5. 首次真机测试关闭 CPU 自动降级，确认结果实际后端为 `rknn`、模型为 `uvr-mdxnet-9482-rknn`；对比 ONNX 基准的人声残留、乐器损失、声道及起止对齐，记录全曲耗时、内存峰值与模型摘要。随后测试取消、重启、实时播放抢占和 CPU 降级；降级仍需安装 Demucs 基准模型并完成 CPU 试跑。

本机真实 ONNX 合成音推理只能验证接口与处理流程；RKNN 编译兼容性、RK3566/fnOS 驱动、NPU 性能及自备歌曲音质仍需真机验证。


### RK3566 Compose 源码部署包

模板位于 `infra/deploy/rk3566/`，用于打包后的根目录。默认构建 `uvr` ARM64 阶段，手动执行 ONNX CPU 基准；可选 `compose.rknn.yaml` 构建 `rknn` 阶段并映射单个 NPU 设备，仍须另行转换模型与真机验证。部署包仅包含计算节点，不包含 Java/管理端，不是离线镜像。

先通过 `scripts/setup-uvr-model.py` 准备固定模型，再用项目 Python 打包：

```sh
services/singverse-compute/.venv/bin/python scripts/package-rk3566.py \
  --model .tools/uvr-mdxnet-9482/UVR_MDXNET_9482.onnx \
  --output /absolute/path/to/singverse-rk3566.zip
```

打包使用文件白名单，内含 Compose、配置示例、源码、锁文件、固定 ONNX、操作说明及 `SHA256SUMS`，不收集本地 `.env`、凭据、数据库或 Python 缓存。解压后按包内 README 填写 NAS 局域网 IP，执行 `docker compose --project-directory . up -d --build`；首次构建需要网络。

NVIDIA 源码上传包可用 `make compute-package-nvidia` 生成，或执行 `python3 scripts/package-nvidia.py --output /tmp/SingVerse-NVIDIA.zip`。包内 `docker-compose.yaml` 固定配置、无曲库目录、无 `.env`，包含构建依赖源码和部署说明，并统一目录 755／文件 644。输出文件已存在时失败，避免覆盖正在使用的包；重新打包请指定新输出路径。
