# SingVerse Python 计算服务

Python 3.12+ / FastAPI。所有 FFmpeg/ffprobe 执行都由本工程处理，Java 通过内部 HTTP 编排、校验和入库，前端不直连计算服务；Python不访问业务库、不管理业务队列。当前支持本地音视频标签读取，以及实时 HLS/离线兼容版本、六类硬件编码适配。已增加按素材试跑的硬解、零拷贝、GPU 色调映射与 DV P5/P8 转 SDR；已支持可选本地 htdemucs 原唱识别与离线伴奏分离；详见[转码说明](../../docs/transcoding.md)。

## 运行与验证

首次 `make compute-setup PYTHON=python3.12`，依赖使用本工程 `.venv` 与 `requirements.lock`。本地运行 `make compute-run` 默认只监听127.0.0.1:8090；需安装FFmpeg以提供标签能力。`make compute-check`执行随机端口HTTP、真实生成音视频、超时/输出限制测试；`make backend-check`执行Java/Python整体及真实双进程集成测试，使用隔离数据。

`python -m singverse_compute --host 127.0.0.1 --port 8090`启动。配置：

- `COMPUTE_MEDIA_ROOTS`：允许读取的绝对根目录，多个目录用系统路径分隔符（Linux/macOS为冒号）。默认空，不允许读取媒体。Java与计算服务必须挂载同一份媒体，路径不同可在管理员页面配置根目录映射。
- `COMPUTE_AUTH_TOKEN_FILE`：内部Bearer凭据文件。CLI首次启动以0600生成随机凭据；Java仅只读挂载该文件，禁止提交或输出内容。没有有效凭据、根目录或ffprobe时不声明标签能力。
- Java用 `COMPUTE_NODE_URL` 与 `COMPUTE_AUTH_TOKEN_FILE` 连接。Java不启动Python或FFmpeg子进程，节点离线/失败回退文件名，不影响主服务健康。

应用Compose默认启动独立 `singverse-compute` 容器，不发布端口；媒体卷 `/media` 只读，内部凭据使用独立命名卷。Java镜像不含Python/FFmpeg。自定义NAS挂载应同时给Java和compute添加同一份媒体的只读挂载，并在compute根目录白名单中列入。跨主机部署需要额外TLS与受限网络，不能直接暴露计算端口。

## 工程结构与依赖

```text
singverse_compute/
├── __main__.py                  # CLI，只解析启动参数
├── bootstrap.py                 # 应用工厂，唯一依赖组装入口
├── app.py                       # ASGI导入兼容入口，不承载业务
├── core/                        # 不依赖HTTP的基础约定
│   ├── config.py                # 不可变部署配置与路径验证
│   ├── errors.py                # 业务/基础设施错误语义
│   └── logging.py               # JSON日志、安全字段白名单
├── domain/
│   └── media_tags.py            # 不可变媒体标签值对象
├── application/
│   └── media_tags.py            # 用例、端口协议、并发/失败释放
├── infrastructure/
│   ├── filesystem.py            # 媒体根目录授权与规范路径
│   ├── security.py              # 内部凭据初始化、读取、鉴权
│   └── media/ffprobe.py         # FFprobe进程、预算与输出归一化
└── api/
    ├── schemas.py               # HTTP输入/输出DTO
    ├── dependencies.py          # FastAPI依赖注入和Bearer声明
    ├── middleware.py            # 请求追踪、鉴权前置、请求体上限
    ├── errors.py                # Problem Details统一映射
    └── routers/
        ├── health.py           # 健康和真实能力
        └── media.py            # 媒体任务入口，仅调用用例

tests/
├── unit/                       # 适配器、路径/凭据/日志/用例边界
└── integration/                # 真实HTTP、错误契约与认证
```

依赖方向：`api → application → domain/core`；`infrastructure`实现应用端口；`bootstrap`负责组装具体适配器。application/domain不导入FastAPI或subprocess，不访问业务数据库。不得将FFmpeg调用放回Java或路由函数，不建立泛化BaseService/Repository空壳。

每个应用实例拥有独立配置、凭据适配器和任务并发门，不使用模块全局Semaphore或动态全局环境作为请求状态。CLI使用工厂模式启动，`app.py`仅保留既有ASGI导入兼容。日志只包含请求ID、方法、路径、状态和耗时，不记录请求体、Authorization或内部凭据；响应带`X-Request-ID`，错误统一为Problem Details，参数错误不回显输入。

扩展与详细职责见[计算服务架构](../../docs/compute-architecture.md)。

## 内部接口

| 路径 | 行为 |
|---|---|
| GET /health | 进程健康，不证明媒体可处理 |
| GET /internal/v1/capabilities | 仅在工具、根目录和凭据齐备时声明media-tags，否则空列表 |
| POST /internal/v1/media/tags | Bearer鉴权；请求 `{path: "/media/歌曲.mkv"}`，返回title/artists/album/releaseDate。路径必须在白名单内 |
| GET /openapi.json | 受版本管理的内部契约；交互文档关闭 |

输入超限413、未认证401、目录越界403、并发繁忙429、能力不可用503；所有外部调用均由Java发起。标签读取仍是同步任务；转码执行和 Java 离线队列见[转码说明](../../docs/transcoding.md)。节点单进程，标签读取最多2个并发，不阻塞健康接口。

## 标签读取边界

`infrastructure/media/ffprobe.py`是计算服务内的唯一FFprobe适配入口。支持MP3/FLAC/M4A/MP4/OGG/WAV/MKV等常见容器，读取非空歌名、歌手、专辑、发行日期；容器值优先于流值，分号分隔歌手转为数组。无标签/损坏/工具缺失/超时返回空字段，Java保留文件名回退。本次不提取封面、歌词、时长，不转码、不写原文件，WebDAV不下载内容。

ffprobe仅允许本地file协议与明确媒体demuxer，限制探测预算、输出256KiB与3秒超时；Java有5秒HTTP超时及64KiB响应上限，认证请求体最大8KiB。相同文件扫描受Java既有2000首/20000条目/30秒范围限制，超出保留原索引。Python不接收任意命令或URL。

更新内部契约后重新生成并执行测试：

```sh
cd services/singverse-compute
.venv/bin/python -c 'import json; from pathlib import Path; from singverse_compute.app import app; Path("../../contracts/openapi/compute-internal.openapi.json").write_text(json.dumps(app.openapi(), ensure_ascii=False, indent=2)+"\n",encoding="utf-8")'
```

参考 [ffprobe官方文档](https://ffmpeg.org/ffprobe.html)。本机容器验证不能代替NAS/微信/TV真机或其他架构验收。

## 管理端配置与独立部署

“系统设置 → 性能节点配置”支持 AUTO/MANUAL/DISABLED。同套 `compose.app.yaml` 通过已知服务名和共享凭据自动识别，无需填写，也不扫描局域网。`GET /internal/v1/status` 使用 Bearer 认证返回身份、API 版本和实际能力；健康页可用不代表认证或媒体能力可用。

独立部署示例（在目标设备的仓库根执行，媒体原件只读）：

```sh
COMPUTE_MEDIA_PATH=/absolute/path/to/media docker compose -f compose.compute.yaml up -d --build
```

默认仅发布 `127.0.0.1:18090`。跨主机部署时显式设置 `COMPUTE_HTTP_BIND` 为该设备受限内网地址，限制入站访问；公网使用可信 TLS 反向代理，保留认证与证书验证。主应用 Compose 的计算端口保持不公开。`compose.compute.yaml` 使用独立项目与凭据卷，不启动业务数据库。

节点初次启动生成认证 token，路径为 `/run/singverse-compute/token`。有权限的部署管理员可在目标设备通过以下命令读取，并填入管理页面的“认证凭据”；该值是敏感信息，不粘贴到日志或工单：

```sh
docker compose -f compose.compute.yaml exec -T singverse-compute cat /run/singverse-compute/token
```

在管理页面选择独立部署，填写 **Java 能访问到的** 地址（例如受限内网 `http://192.168.1.20:18090`）。两端挂载必须是同一份媒体；本机 `/media`、计算端 `/media` 相同则无需映射；若 Java 使用 `/mnt/music`，添加 `/mnt/music → /media`。映射按最长本机路径前缀匹配；未匹配路径保持原样，计算端仍执行真实路径白名单检查。配置不会上传媒体，不能令计算节点读取未挂载文件。

测试连接只检查身份、认证和能力，不试读媒体；保存后新扫描才使用配置，进行中的扫描固定原连接。节点不可用时保留文件名回退。可用能力包括 `media-tags`；转码目录、工具与认证就绪时另声明 `media-probe`、`transcode-hls`、`transcode-offline`。没有多节点调度或负载均衡。停止用 `docker compose -f compose.compute.yaml down`，保留命名卷。

## 后台硬件加速配置

管理员可在“系统设置 → 性能节点配置 → 转码与硬件加速”测试及保存后端，设置持久化在转码缓存根目录 `hardware-settings.json`。后台选择优先于 `COMPUTE_HARDWARE_BACKEND`，选择“遵循部署配置”恢复部署默认。仅影响新任务，不自动修改容器设备和驱动；接口、生命周期及部署前提见[转码说明](../../docs/transcoding.md)。

完整硬件图通过 `infrastructure/transcoding/pipelines.py` 的固定模板选择，执行前用实际素材试跑。任务返回 `pipeline` 各阶段和观测 RSS；能力页的硬解/零拷贝/GPU 色调映射只来源于已通过的素材试跑。`COMPUTE_PROCESS_MEMORY_BYTES` 默认 1 GiB，以观测 RSS 检查并终止超限子进程；Linux 容器额外默认 2 GiB 内存/2 CPU 硬限制，可按 NAS 资源调整。

## WebDAV 远程内嵌元数据

新增 `media-remote-tags` 能力，需要内部凭据、FFmpeg/ffprobe 和部署参数 `COMPUTE_JAVA_ORIGIN`，不依赖本地媒体目录白名单。Java 通过 Bearer 鉴权 POST `/internal/v1/media/remote-tags`，请求 `{reference, filename, size, deep}`；仅接收任务 UUID，禁止传任意 URL、路径或 WebDAV 凭据。`reference` 对应 Java 临时授权的 `/api/v1/internal/metadata-input/{id}`，不是可公开分享的播放链接。

工作器用有界、可 seek 的 Range 文件对象读取，Mutagen 读取标签、封面、USLT/SYLT、MP4/Vorbis 歌词；ffprobe 读取容器和流信息，FFmpeg 仅提取附图或明确的封面附件，不截图。Mutagen、ffprobe 和 FFmpeg 共享任务内字节块缓存，不拼接头尾伪造文件。普通预算 16 MiB/16 请求/60 秒，深度预算 128 MiB/64 请求/180 秒；Python 与 Java 分别约束，Java 另计入重定向请求和实际流量，保留访问组限速与冷却，不设每日累计提取额度。超限保留已有信息，字段状态区分 PRESENT/ABSENT/UNKNOWN。

结果包含 tags、technical、cover（仅内部传输的有界 Base64）、lyrics、字段覆盖状态、提取器版本及统计。封面最大 2 MB、歌词最大 256 KiB，Java 校验和保存本地衍生文件，Python 不写业务库。普通歌词与同步歌词保留原格式；SYLT 帧时间不误标为毫秒。MP3/FLAC/M4A/MP4/OGG/Opus/MKV 的常见结构纳入测试，未知结构允许部分完成。远程读取需要 Range 支持，忽略范围的来源不会降级完整下载。

保留 `/internal/v1/media/tags` 的本地兼容行为；旧文档中“WebDAV 不读取内容”适用于未启用远程提取的来源。运行 `make compute-setup` 安装锁定的 Mutagen 1.47.0，再运行 `make compute-check`；新增测试使用本机随机端口、临时音视频、尾部 MP4 索引和 MKV 附件，不连接真实网盘。

## 原唱识别与伴奏分离

可选模型运行时固定为 Demucs 4.0.1、torch/torchaudio 2.5.1；CPU依赖见 `requirements-audio.lock`，Linux x86_64 CUDA 12.4 / ROCm 6.2 分别见 `requirements-audio-cuda.lock` / `requirements-audio-rocm.lock`。原生节点先执行 `make compute-setup`，CPU/Mac可用 `make compute-audio-setup`（同时显式下载模型到 `.tools/demucs-htdemucs`）；CUDA/ROCm分别执行 `make compute-audio-cuda-setup` / `make compute-audio-rocm-setup`（仅安装环境）。节点不能在HTTP请求中安装Python依赖或驱动。

管理端“曲库 → 原唱与伴奏 → 节点与模型”可显式下载官方权重到当前节点，也可导入官方原始 `.th` 文件（不是压缩包或任意PyTorch模型），上限100MiB。固定文件大小84,141,911字节及完整SHA-256校验通过后，自动写入 `htdemucs.yaml` 并原子安装；没有推理任务会隐式下载权重。断网、取消、摘要错误或节点重启均保留已有模型；目录内容冲突时须管理员先保留或移动旧目录。

原生节点设置 `COMPUTE_AUDIO_MODEL_REPO` 为模型目录绝对路径，且父目录预先存在并可写；安装到已有只读目录时可正常使用预置模型，但不能通过网页下载/导入。下载完成还需“重新检测环境 → 对所需后端执行模型试跑 → 自动选择或指定后端”。试跑使用8秒合成音，只验证执行、声道、时间线及数值有效性，不能代替真实歌曲音质和长曲验收。未通过环境与模型试跑不声明识别/分离能力，探测和人工纠正仍可用。

- Java 提交 `/internal/v1/audio-roles/tasks`，`mode=PROBE|ANALYZE|SEPARATE`，沿用内部 Bearer、只读根目录及 WebDAV 授权回源。稳定 UUID 绑定输入和模式；查询、取消、读取及释放产物均在该任务路径下。
- ANALYZE 只接受两条源音轨。全曲不足 60 秒、两个 20 秒片段证据不一致、两轨都有人声/都近似伴奏、标签冲突均保留 UNKNOWN。高置信使用两片段的人声与输入 RMS 比例及两轨差异，不能视为已校准的概率；阈值仍需真实歌曲语料验证。立体声左右声道不会被当作两条音轨。
- SEPARATE 仅对单音轨执行，保持原始时间线的前置静音和尾部填充，输出独立 AAC M4A；校验起点、时长、体积与 SHA-256。临时工作目录、取消/失败产物清理；重启后未完成任务报告 NODE_RESTARTED，Java 展示失败并允许管理员重试。
- 音频任务一次一个，默认 CPU 线程 2、进程 RSS 上限 4 GiB（`COMPUTE_AUDIO_THREADS`、`COMPUTE_AUDIO_MEMORY_BYTES`），共享磁盘预算及最小空余限制，超时 2 小时。实时播放先终止音频任务，再重试获取执行容量；音频任务不能进入实时预留期间。compute被抢占任务显示 CANCELLED / REALTIME_PREEMPTED，Java记录为失败，批量页面可显式重试；不会在执行中静默切换到CPU。
- Java 校验下载的产物后保存至自己的衍生目录，确认成功后释放 compute 副本；未收到确认的 compute 音频在后续任务回收时保留最长一天。不要把衍生目录放入原始媒体目录。

Compose默认不安装音频运行环境或下载权重，但已提供持久卷 `singverse-audio-models`，默认模型目录 `/var/lib/singverse-models/htdemucs`。CPU部署使用 `compose.audio-cpu.yaml`，设置 `COMPUTE_AUDIO_RUNTIME=cpu` 和 `COMPUTE_CONTAINER_MEMORY_LIMIT=6g` 后重建compute；随后从网页下载模型和试跑。原有只读模型挂载可通过override继续使用。默认2g容器不满足默认4GiB音频预算，不会因识别到CPU就启用任务。Java的 `AUDIO_DERIVED_PATH` 为持久卷 `/data/audio-roles`；原媒体卷只读。

### Mac 原生伴奏节点

现有 Docker 应用按根 README 的 `compose.compute.macos.yaml` 连接本机节点；音频和 VideoToolbox 共用同一个原生进程。安装及重新加载：

```sh
make compute-audio-setup
services/singverse-compute/.venv/bin/python scripts/macos-compute.py restart
```

启动器将固定模型目录 `.tools/demucs-htdemucs` 传入节点，默认音频预算 4096MiB；内存较紧张时可显式 `restart --audio-memory-mib 2048`，后续重启保留预算。本机 M2 的 8／60 秒验收使用 2048MiB；这不是任意歌曲的内存保证。模型安装仍是显式操作，网页也可下载或导入。进入“曲库 → 原唱与伴奏 → 节点与模型”，对 CPU 和 MPS 分别试跑；自动选择优先使用通过验证的 MPS。升级代码或运行环境后重新试跑。

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

- NVIDIA 3070/A3000：宿主安装兼容驱动和NVIDIA Container Toolkit，然后使用 `docker compose -f compose.app.yaml -f compose.audio-cuda.yaml build singverse-compute`，再用同样的两个配置文件启动。override安装固定CUDA运行环境并申请一张GPU，不安装宿主驱动。
- 兼容ROCm的AMD GPU：先配置宿主驱动、`/dev/kfd`、`/dev/dri`和实际render/video组数字ID（`SINGVERSE_RENDER_GID` / `SINGVERSE_VIDEO_GID`），使用 `compose.audio-rocm.yaml` 代替CUDA override。8845HS/255HS按节点实际GPU标识检测，不承诺集显支持，不设置伪造GPU标识。
- Intel 8400T/N100：提供CPU路径；QSV/VA-API是编解码功能。新增独立OpenVINO适配器与Intel官方转换模型，但这两种设备仍需分别试跑及效果验收；PyTorch XPU路径明确为 `MODEL_UNSUPPORTED`。
- RK3566：Linux ARM64 CPU路径需实际依赖安装成功和内存充足；没有合格RKNN转换模型，NPU明确为 `MODEL_UNSUPPORTED`。不能凭板卡名称宣布NPU可用。
- Apple M2：macOS原生CPU/MPS，Docker内不开放MPS。关闭PyTorch隐式CPU算子回退；Demucs自身的频谱变换明确在CPU执行，网络使用MPS，页面及任务标记 `mps+cpu-stft`。固定运行时的超长一维卷积通过保留重叠边界的 GPU 分块处理；其他算子不支持时仍明确失败，可选择通过试跑的CPU。

CPU/CUDA/ROCm/MPS的执行选择及试跑门禁已接入；M2 原先的“Output channels > 65536”限制已通过数学等价的卷积分块解决，本机 2GiB 预算下资格试跑及 8／60 秒分离通过。Intel GPU和RKNN转换/音质验收尚未完成，不把硬件识别或待适配状态算作加速实现。各型号的长曲、音质、内存和速度仍须逐台验证。运行环境、内核/驱动、设备或模型版本改变后旧试跑凭据失效；可用后端才进入自动选择，默认顺序CUDA → ROCm → OpenVINO → MPS → CPU。每个任务保存实际后端与模型版本；检查RAM、磁盘及CUDA/ROCm可用显存（至少2GiB），仍可能因模型/歌曲峰值资源不足失败。

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
| `-cuda` | linux/amd64 | Torch 2.5.1+cu124 | 3070／A3000 待真机验证 |
| `-rocm` | linux/amd64 | Torch 2.5.1+rocm6.2 | AMD APU 按实际 GPU 标识与运行时验证，不能由型号推定兼容 |
| `-openvino` | linux/amd64 | OpenVINO 2025.4.0、CPU Torch 2.5.1、固定 Intel OpenCL 用户态库 | N100／8400T 分别待真机验证 |

CPU 镜像由 Docker 清单选择 amd64／arm64，GPU 后端需要显式选择。RKNN 没有合格转换模型，因此不发布可用的 `-rknn` 镜像；RK3566 可验证 ARM64 CPU，资源不足时阻止任务。M2 使用 macOS 原生环境及下述 MPS 分块适配器。视频转码后端与 AI 推理后端独立检测，QSV 可用不等于 OpenVINO 可用。

### 本地构建与预构建镜像

从源码在实际节点构建，例如 Intel GPU 独立节点：

```sh
COMPUTE_MEDIA_PATH=/absolute/path/to/media COMPUTE_RENDER_GID=109 \
  docker compose -f compose.compute.yaml -f compose.audio-openvino.yaml up -d --build
```

`109` 只是示例，使用宿主 `/dev/dri/renderD128` 的实际组 ID。应用内节点把 `compose.compute.yaml` 换成 `compose.app.yaml`；CPU 使用 `compose.audio-cpu.yaml`，NVIDIA 使用 `compose.audio-cuda.yaml`，AMD 使用 `compose.audio-rocm.yaml`。AMD 配置实际 `SINGVERSE_RENDER_GID` 与 `SINGVERSE_VIDEO_GID`。这些 override 默认容器预算 6g；音频进程默认预算 4GiB，不能把默认轻量节点的 2g 限制用于分离任务。媒体只读，模型持久卷 `singverse-audio-models` 与任务／编译缓存卷 `singverse-transcode-cache` 独立。

公开正式 release 工作流 `.github/workflows/singverse-compute-images.yml` 构建并发布 `ghcr.io/<仓库所有者>/singverse-compute:<版本>-<后端>`。版本必须与 Java POM 对应；PR 和手动工作流只构建检查，发布需正式 release。仓库中有工作流不代表镜像已经在注册表发布，也不代表 GPU 已验收。

使用已经发布的镜像时，设置完整固定标签并禁止本地构建：

```sh
COMPUTE_IMAGE=ghcr.io/<仓库所有者>/singverse-compute:<版本>-openvino \
COMPUTE_MEDIA_PATH=/absolute/path/to/media COMPUTE_RENDER_GID=109 \
  docker compose -f compose.compute.yaml -f compose.audio-openvino.yaml pull singverse-compute
COMPUTE_IMAGE=ghcr.io/<仓库所有者>/singverse-compute:<版本>-openvino \
COMPUTE_MEDIA_PATH=/absolute/path/to/media COMPUTE_RENDER_GID=109 \
  docker compose -f compose.compute.yaml -f compose.audio-openvino.yaml up -d --no-build
```

尖括号参数必须先替换；也可把相同配置写入部署目录的 `.env`，不要把凭据写进公开文件。`docker-bake.hcl` 提供矩阵，`make compute-images` 构建全部变体。单后端本机示例：

```sh
VERSION=0.1.0-SNAPSHOT docker buildx bake -f docker-bake.hcl cpu --set cpu.platforms=linux/arm64 --load
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
    # 其余环境、认证、只读媒体与持久卷照基础 Compose 保留。
```

离线镜像交付：联网设备先拉取目标架构的固定镜像，`docker save -o compute-image.tar <完整镜像标签>`，复制到目标设备后 `docker load -i compute-image.tar`，再按同一设备配置 `up -d --no-build --pull never`。导出的镜像仅适用于已拉取的架构；模型仍需单独导入。

### Intel 官方转换模型和离线包

`htdemucs` 保持基准模型。`htdemucs-openvino` 使用 Intel 官方仓库固定修订 `efb3d2bc54f93899b13b72b12c57001e19f09ae2` 下 `htdemucs_v4/htdemucs_fwd.xml` 与 `.bin`；文件大小、SHA-256、采样率和输出源顺序显式记录在 `application/audio_catalog.py`。神经网络在指定 `GPU.*` 执行，STFT／逆变换／重叠拼接复用 Demucs 4.0.1 的 CPU 流程；不使用 `AUTO`／`HETERO` 隐式回退。编译缓存按模型摘要、环境指纹及构建身份隔离，不与权重混用。

管理员可在「原唱与伴奏 → 节点与模型」选择模型后下载，或在有网络的源码设备执行：

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
