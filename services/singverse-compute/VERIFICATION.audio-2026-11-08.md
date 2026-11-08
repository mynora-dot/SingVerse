# 伴奏生成优化与验证（2026-11-08）

本记录仅覆盖本轮伴奏生成、调度和状态展示优化。工作区已有的 Docker 整理、ROCm 固件修复、缓存策略和其它未提交修改不计入本轮成果。媒体原件、模型、数据库结构及依赖版本保持既有内容。

## 问题及实现

| 问题 | 接入真实流程的修改 |
|---|---|
| 每首歌重新启动原生 ROCm 推理进程、读取模型 | 应用独占一个可单独终止的子进程，按模型文件身份及后端复用；默认空闲 60 秒退出 |
| 整首四音轨和重叠合并结果驻留内存 | 两遍读取 PCM：先计算全局归一化，再按模型分段推理、重叠合并和写出；只生成伴奏时省去人声 WAV |
| 视频探测和重复 ffprobe 增加等待 | 音频专用投影，一次读取时长和起点；支持取消，不探测视频帧或 Dolby Vision |
| 定时任务及节点发现阻塞后续生成 | 提交和完成立即合并唤醒，运行任务每秒查询；网络任务、批次推进和后台维护各有执行器，繁忙节点保留退避 |
| 批次逐首查询及未变化状态重复写入 | 批量获取歌曲和任务，Java 与 SQL 同时跳过无变化更新，优先跟踪运行任务及显式生成 |
| 单曲任务变更触发全库索引重建 | 传递歌曲 ID，按最多 200 首增量更新；来源、元数据或歌手全局变化及 30 秒兜底仍完整重建 |
| 无法分辨准备、推理、编码和回传耗时 | 可选 `stage/progress/timingsMs/inference` 贯通 compute、Java 和管理页；旧节点不提供字段时仍可使用 |

保持官方 htdemucs 权重、44.1kHz 双声道、7.8 秒分段、25% 重叠、shifts=0 及原始三角权重。尾段仍使用真实前文上下文，不改成独立补零。合并缓冲固定为 343,980 帧；这不代表进程总内存固定，模型、运行时及设备工作区仍占内存。

取消、失败、实时播放抢占、内存超限、120 秒无执行进展及关闭都会销毁推理进程。抢占只发送终止信号，回收在后台完成；下个任务重建进程。模型大小、设备、inode、mtime/ctime 或后端改变时也重新校验和加载。最终响应与进程退出之间增加管道读取保护，异常退出记录有界诊断。

`COMPUTE_AUDIO_WORKER_IDLE_SECONDS` 支持 0～3600；0 仅禁用模型驻留，分段合并仍保留。该复用目前只用于精确匹配的 `rocm-native` 运行时及其显式 CPU 降级，其他后端沿用既有生命周期。修改环境后须重新创建 compute 容器；无需重建数据库或重新下载模型。

## 实际修改入口

- Python：`infrastructure/audio_worker.py`、`infrastructure/rocm_audio.py`、`infrastructure/transcoding/probe.py`；`application/audio_roles.py`、`application/audio_models.py`、`application/transcoding.py`；`core/config.py`。
- Java：`media/audio/AudioDispatch.java`、`AudioJobService.java`、`AudioProcessingService.java`、`AudioCatalog.java`、`AudioRoleMapper.java`；`media/artist/LibraryChangeInterceptor.java`；`mappers/audio/AudioRoleMapper.xml`、`AudioProcessingMapper.xml`。业务 SQL 继续放在 MyBatis XML 中。
- 管理页：`src/admin/modules/audio-processing/api.ts`、`presentation.ts`、`components/BatchesTab.vue`。
- 契约与配置：业务 OpenAPI JSON、根 `.env.example`、`infra/docker/compose/compose.app.yaml` 和 `compose.compute.yaml`；compute/Java README、`infra/docker/GPU.md`。内部提交请求保持原字段，不要求旧 compute 接收新增字段。
- 回归：Python `test_audio_worker.py`、`test_audio_probe.py`、`test_rocm_audio.py`、`integration/test_native_audio_streaming.py`；Java `AudioDispatchTest`、`AudioJobServiceTest`、`AudioProcessingServiceTest`、`AudioCatalogTest`、`AudioProcessingHttpTest`、`LibraryChangeInterceptorTest`、`MediaScanHttpTest`；前端 `audio-processing.test.ts`。

没有新增数据库字段或迁移。阶段证据存入已有任务结果 JSON，Mapper 新增有参数绑定的批量查询和无变化更新判断。修复了 MyBatis 严格参数 Map 读取不存在的可选键时抛错的问题；取消测试使用受控运行中的计算任务，不再依赖旧定时延迟。

排队耗时在 Java 提交时采集，并与后续运行及终态结果合并，不写入 compute 提交请求。回归用严格旧字段白名单模拟旧节点，检查运行轮询和终态均保留该计时，且无变化轮询不重复写库。

## 本地回归

| 检查 | 本次结果 |
|---|---|
| 前端 `make frontend-check` 和 `npm --prefix services/singverse-frontend test` | 类型检查、构建及 384 项测试通过 |
| `make compute-check` | 199 项，7 项按显式环境条件跳过，其余通过 |
| `make backend-check` 中 Python 与完整 Java 检查 | Python 已完成；首次 Java 发现上述两项问题，修复及补充旧节点兼容回归后，使用相同 `-Pbackend-check verify` 重跑 Java：616 项测试、3 项真实双进程集成通过 |
| 固定实际模型 CPU 对照 | 单独显式执行 1 项通过；370,440 帧、跨两个分片，最大绝对误差 0.00003052，即一个 PCM16 量化步长；第二次调用复用模型 |
| `make docker-config-check` | 29 组 Compose 配置通过；未启动本机 Docker 容器 |
| `git diff --check` | 通过 |

Java 使用本机现有 Java 21，未更改全局工具或依赖。Python 使用项目 `.venv`、固定官方模型和既有 FFmpeg。随机端口、测试子进程及实际模型 OpenMP 运行需要本机受限沙箱外执行。默认跳过的项目包括实际模型、MPS、OpenVINO、快速分类器及指定应用环境；CPU 参考测试另行显式执行，不能把默认跳过称作通过。

### 轻量索引规模测量

以下为本机单次受控 Java 测量，仓储用批量 Mock，SQL 次数表示批量 Mapper/仓储调用数，**不是真实数据库时延**。`allocated_bytes` 是当前线程累计分配量，包含临时对象和测试框架开销，不等于常驻堆或进程 RSS。

| 歌曲数 | 操作 | 批量调用 | 耗时 | 分配字节 |
|---|---|---:|---:|---:|
| 10,000 | 全量构建 | 151 | 39ms | 25,192,800 |
| 10,000 | 20 条分页 | 3 | 8ms | 1,562,856 |
| 10,000 | 单曲增量刷新 | 3 | 3ms | 1,222,624 |
| 100,000 | 全量构建 | 1,501 | 151ms | 245,266,280 |
| 100,000 | 20 条分页 | 3 | 14ms | 14,513,200 |
| 100,000 | 单曲增量刷新 | 3 | 29ms | 11,577,376 |

断言确认增量不调用全库 `batchAfter`，只读取一个歌曲集合、角色集合及任务集合，并复用未变化摘要对象。分页仅加载 20 首详情。不可变列表和映射仍需复制，增量索引容器成本仍为 O(N)，没有宣称所有工作均为 O(1)。

## NAS 实际歌曲 ROCm 验证

使用先前通过固件修复验收的同一 NAS、Radeon 780M/gfx1103、原生 ROCm 镜像和固定 htdemucs。单个隔离容器，无网络、系统只读、非 root、5GiB 内存、2 CPU、256MiB 共享内存；只读挂载原片及模型，只写独立验证目录。运行时和模型未升级，未伪造架构、未隐式使用 CPU。

原流程参考使用整曲 PCM 和原始 `apply_model(split=True)`；新流程使用实际 `AudioWorker` 协议及 `NativeRuntime`。比较相同已对齐 PCM 的完整伴奏 WAV，AAC 使用应用相同的 192k 编码并全量解码。表中时间为**模型加载、推理与 WAV 写出的适配器耗时**，不含 Java 排队、WebDAV、源解码、AAC、传输，不能当作用户端到端等待时间。冷启动结果还受设备与算子缓存影响，小样本不能代表稳定分位数。

### 112.066667 秒 FLAC

| 路径 | 耗时 | 模型加载 | 模型复用 |
|---|---:|---:|---|
| 整曲参考，第 1 次 | 34.279s | 0.792s | 否 |
| 整曲参考，下一独立进程 | 24.626s | 0.735s | 否 |
| 分段合并，冷进程 | 34.336s | 0.727s | 否 |
| 同进程连续生成 | 22.560s | 0.001s | 是 |
| 取消后的新进程 | 24.410s | 0.559s | 否 |

连续生成对比参考后续独立进程约快 8.4%，冷启动没有明显改善。每次完整生成 4,942,140 帧，完成 20 次 GPU forward；三份新输出与参考 PCM 最大绝对误差均为 0。取消在第 2 个 forward 后发送，整个被取消请求用时 2.387 秒，此值不是取消信号响应延迟。随后完整生成恢复成功；测试配置空闲 3 秒后进程释放，生产默认仍为 60 秒。AAC 完整解码和时长检查通过。

此 FLAC 原件存在 `invalid sync code / invalid frame header`，本机严格解码也失败。原件校验与 NAS 一致，未修改原件；可解码输出覆盖上述完整帧数。该素材可用于新旧算法等价比较，但不能单独作为干净源文件的验收，因此另用严格解码通过的实际 MKV 第一音轨补验。

### 256.52 秒 MKV 第一音轨

该文件有两条现有音轨。本次取第一音轨验证推理适配器和长曲内存，不改变产品中“双音轨先识别/纠正、单音轨才生成伴奏”的门禁。提取与应用一致，使用 `aresample=async=1:first_pts=0,apad` 和指定时长；本机与 NAS 严格解码通过，没有解码告警。

| 路径 | 耗时 | 模型加载 | 模型复用 |
|---|---:|---:|---|
| 整曲参考，冷进程 | 61.691s | 0.852s | 否 |
| 分段合并，冷进程 | 51.751s | 0.638s | 否 |
| 同进程连续生成 | 49.612s | 0.001s | 是 |

两份新输出均为 11,312,532 帧、44 次 GPU forward，参考 PCM 最大绝对误差均为 0；AAC 完整解码、时长及空闲退出通过。整曲参考进程 RSS 高水位约 2.94GB，新进程每 250ms 采样峰值约 1.17GB。两种内存采集方法不同，不按该表计算精确节省比例；缓冲帧数在两种曲长下均为 343,980。参考冷进程还包含首次设备/算子缓存成本，不能据此宣称稳定提速约 20%。

### 环境与范围

隔离证据保存在 NAS 部署目录的 `verification/audio-optimization-20261108-r3/` 及 `verification/audio-optimization-20261108-clean/`。最初的隔离测试因工作目录优先加载旧镜像模块失败，修正为只读代码目录后才执行新协议；失败证据保留，未作为通过结果。

两种素材完整运行中，新增 GPU 内核错误/重置/Hang 为零，容器未 OOM；原件 SHA256 前后一致。生产 Java、前端及 compute 前后健康、启动时间及重启次数一致，公共健康接口 HTTP 200。测试容器结束后移除，仅保留独立证据。没有覆盖生产镜像或重启生产服务。NAS 验证后仅调整排队计时的 Java 归属及对应 compute 结果字段，推理适配器与工作进程保持所测代码。

## 剩余限制

- 生产部署尚未切换到本次代码。真实 GPU 结果覆盖隔离推理和编码；本机真实 Java/Python 集成通过，尚无本次生产 WebDAV → Java → 新 GPU → TV/手机的完整端到端耗时。
- 伴奏仍需完整生成、AAC 编码及校验后发布，尚未实现边生成边播放；不以降低重叠、换小模型或降低采样率换取速度。
- 当前新增驻留和有界合并仅验收 `rocm-native`。没有本次 CUDA、OpenVINO、MPS、其它 NAS、TV 或手机真机结果；没有真实隔离 MySQL 验证。
- 驻留模型增加空闲内存，实时播放会主动释放；重建仍有冷启动成本。长曲缓冲有界不等于整个任务磁盘、模型或运行时内存不受限。
- 30 秒兜底仍会全量重建索引，增量容器也仍为 O(N)。后续如实库长期测量确认它占主要成本，再改索引结构或刷新机制；不把批量 Mock 数字当真实 NAS SQL 性能。
- 输入损坏需要单独定位和更换/修复素材，不能由 GPU 成功或 AAC 可解码证明原件无损。
