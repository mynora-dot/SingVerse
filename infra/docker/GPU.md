# GPU 视频加速与 AI 伴奏分离

先按 [Docker 部署指南](README.md) 完成基础部署。以下命令在仓库根执行，默认升级应用栈中的 compute；独立 GPU 主机将 `compose.app.yaml` 换为 `compose.compute.yaml`，并按主指南配置双向回源。若启用了 `compose.media.yaml`、HTTPS 或其他覆盖文件，也继续携带它们。

**视频编解码和 AI 推理分别验收**：VA-API/QSV/NVENC 可用不代表 ROCm/CUDA/OpenVINO 分离可用。原片直放也不会自动使用服务端转码 GPU。

## 运行时选择

| 用途/设备 | 基础配置后追加的文件 | 架构与前提 |
|---|---|---|
| CPU AI 分离 | `compose.audio-cpu.yaml` | Linux AMD64 / ARM64；内存足够 |
| NVIDIA AI | `compose.audio-cuda.yaml` | Linux AMD64、兼容驱动、NVIDIA Container Toolkit |
| AMD ROCm 6.2 运行时 | `compose.audio-rocm.yaml` | Linux AMD64、相符 GPU 设备库；不适合本次实测的 gfx1103 |
| AMD gfx1103 原生运行时 | **先** `compose.audio-rocm.yaml`，**再** `compose.audio-rocm-native.yaml` | Linux AMD64、匹配的 amdgpu/KFD 与固件；Radeon 780M 单机已验证 |
| Intel AI | `compose.audio-openvino.yaml` | Linux AMD64、Intel render 设备和组权限；需逐台试跑 |
| Intel QSV / AMD VA-API 视频 | `compose.compute.dri.yaml` | Linux render 设备；设置 `COMPUTE_HARDWARE_BACKEND=qsv` 或 `vaapi` |

表中的文件均在 `infra/docker/compose/`。`base` 不含 AI 运行库；`cpu`、`cuda`、`rocm`、`rocm-native`、`openvino` 均不内置模型权重。OpenVINO、CUDA 与其它 AMD GPU 的完整效果/长曲以各设备实际验收为准。Apple GPU 应使用 macOS 原生计算节点，Docker 内不能使用 MPS；RKNN 属实验性原生路径，见 [compute README](../../services/singverse-compute/README.md)。

默认 AI 覆盖文件将 compute 内存上限改为 6GiB，音频进程预算仍为 4GiB。可在 `.env` 调整 `COMPUTE_CONTAINER_MEMORY_LIMIT`、`COMPUTE_AUDIO_MEMORY_BYTES`、`COMPUTE_PROCESS_MEMORY_BYTES`、`COMPUTE_AUDIO_THREADS` 和 `COMPUTE_CPU_LIMIT`。工作进程预算不能顶满容器；视频与音频并发、运行时常驻、Java 和 NAS 系统也需要余量。默认先保持单路视频任务，不以增加并发作为起播优化方式。

权重放在模型卷，模型选择/试跑资格及视频硬件设置放在 `singverse-transcode-cache`，升级保留两卷。首次完成后按 [验收、备份与更新](OPERATIONS.md) 维护；分离总耗时还包括进程启动、回源和编码，GPU 利用率低时先定位各阶段，不直接提高并发或声称未使用 GPU。

管理页保存的视频硬件方式优先于 `COMPUTE_HARDWARE_BACKEND`。修改部署后，在硬件加速设置中选择“遵循部署配置”，或明确选择新的可用方式，再执行测试；保留缓存卷也会保留原来保存的设置。

## CPU：先验证完整链路

```sh
docker compose --project-directory . \
  -f infra/docker/compose/compose.app.yaml \
  -f infra/docker/compose/compose.audio-cpu.yaml config --quiet
docker compose --project-directory . \
  -f infra/docker/compose/compose.app.yaml \
  -f infra/docker/compose/compose.audio-cpu.yaml build singverse-compute
docker compose --project-directory . \
  -f infra/docker/compose/compose.app.yaml \
  -f infra/docker/compose/compose.audio-cpu.yaml up -d --no-deps --wait --wait-timeout 180 singverse-compute
```

随后按下面的“模型安装与实际歌曲验收”完成下载、CPU 试跑和真实任务。切换到 GPU 镜像时继续使用原项目及模型卷，无需重新创建数据库。

## AMD Radeon 780M / gfx1103：ROCm

### 1. 检查 Linux 主机或 NAS 来宾

```sh
uname -r
lspci -nnk
ls -l /dev/kfd /dev/dri
stat -c '%n gid=%g mode=%a' /dev/kfd /dev/dri/renderD* /dev/dri/card*
```

NAS 若在 PVE/KVM 中，GPU 必须先正确直通并在 **NAS 来宾内**由 amdgpu 初始化；PVE 宿主看到 GPU 并不足够。容器共享来宾内核驱动，不会修复缺失 VBIOS/VFCT、IOMMU 映射、固件加载失败或直通重置问题。检查来宾 `dmesg` 中的 amdgpu/KFD 错误，再处理容器权限。

在根 `.env` 填写实际设备的数字组 ID，不能照抄另一台机器的值：

```dotenv
SINGVERSE_RENDER_GID=<render设备的数字gid>
SINGVERSE_VIDEO_GID=<card设备的数字gid>
```

`/dev/kfd` 也必须可由容器 UID 10001 通过这些组访问；若属于另一组，需要在自己的覆盖文件中额外 `group_add`。不必设置 `privileged`、放开所有设备或对设备执行 `chmod 777`。

### 2. 选择原生运行时并重建

```sh
docker compose --project-directory . \
  -f infra/docker/compose/compose.app.yaml \
  -f infra/docker/compose/compose.audio-rocm.yaml \
  -f infra/docker/compose/compose.audio-rocm-native.yaml config --quiet
docker compose --project-directory . \
  -f infra/docker/compose/compose.app.yaml \
  -f infra/docker/compose/compose.audio-rocm.yaml \
  -f infra/docker/compose/compose.audio-rocm-native.yaml build singverse-compute
docker compose --project-directory . \
  -f infra/docker/compose/compose.app.yaml \
  -f infra/docker/compose/compose.audio-rocm.yaml \
  -f infra/docker/compose/compose.audio-rocm-native.yaml up -d --no-deps --wait --wait-timeout 180 singverse-compute
```

必须保留三个文件及顺序：ROCm 基础覆盖文件负责 `/dev/kfd`、`/dev/dri`、render/video 组、256MiB 共享内存和 memlock；最后一个文件切换固定原生运行时。若 `.env` 之前写过 `COMPUTE_IMAGE`，需同步改为当前已构建镜像标签或清除此项，让配置使用 `singverse-compute:local-rocm-native`，避免不同运行时共用错误标签。

该镜像固定 Python 3.12、Torch `2.11.0+rocm10.0.0` 和 gfx1103 设备库；实际 HIP 版本是 `7.15.26333`。旧 `rocm` 镜像使用 Torch 2.5.1 / ROCm 6.2，不能替代此配置。不要通过 `HSA_OVERRIDE_GFX_VERSION` 伪造设备架构，也不要在运行中的容器里临时 `pip install` 替换锁定依赖。

容器内只检查枚举信息（仍不代表推理通过）：

```sh
docker compose --project-directory . \
  -f infra/docker/compose/compose.app.yaml \
  -f infra/docker/compose/compose.audio-rocm.yaml \
  -f infra/docker/compose/compose.audio-rocm-native.yaml exec -T singverse-compute \
  python -c "import torch; print(torch.__version__, torch.version.hip); print('GPU available:', torch.cuda.is_available()); print(torch.cuda.get_device_properties(0) if torch.cuda.is_available() else 'No GPU')"
```

PyTorch 的 ROCm 接口也使用 `torch.cuda` 命名；应用任务报告的实际后端应为 **`rocm`**。

### 3. 这次 GPU Hang 修复的适用范围

2026-11-08 的 Radeon 780M（gfx1103）/ PVE NAS 单机验证表明：旧运行库缺失原生算子；换为原生运行库后仍曾触发 MES/GPU Hang。该机 GC IP 为 11.0.1，更新匹配的 ME、MEC、MES_2、PFP 固件（MES 版本由 `0x8b` 到 `0x94`），在不改变 PVE 直通配置的条件下完成驱动重载后，通过多次完整 112 秒真实歌曲分离、Java 回源/入库以及同时 VA-API 转码测试，无隐式 CPU 回退。

所用上游固件固定在 [linux-firmware 提交 9ec083c3](https://gitlab.com/kernel-firmware/linux-firmware/-/tree/9ec083c3aba932190e3c160e52cbb7e264148b61/amdgpu)。**这不是通用固件安装脚本或所有 gfx1103 的兼容承诺**。另一台 NAS 应由管理员核对真实 IP block、内核支持及厂商固件更新方式，保留原固件和启动镜像。PVE 下缺 VBIOS/VFCT 的初始化问题与 MES 运行时挂死是不同阶段，不能互相代替修复；不要复制其他设备的 ROM 或 VFCT。

容器配置不会更新宿主固件、改 PVE 或重启 NAS。固件、驱动处理可能中断所有 GPU 使用方，须由设备管理员安排。上述单机尚未完成 NAS 冷启动与 24 小时稳定性验收，部署到其他机器仍需完整复验。

## NVIDIA CUDA

宿主先安装相符的 NVIDIA 驱动及 NVIDIA Container Toolkit，确认 `nvidia-smi` 正常，再叠加 `compose.audio-cuda.yaml`：

```sh
docker compose --project-directory . \
  -f infra/docker/compose/compose.app.yaml \
  -f infra/docker/compose/compose.audio-cuda.yaml build singverse-compute
docker compose --project-directory . \
  -f infra/docker/compose/compose.app.yaml \
  -f infra/docker/compose/compose.audio-cuda.yaml up -d --no-deps --wait --wait-timeout 180 singverse-compute
```

该配置申请一张 GPU；镜像保留 `compute,video,utility` 能力。固定运行库是 CUDA 12.4 / Torch 2.5.1，不在容器中安装宿主驱动。独立节点也可使用 [NVIDIA 固定配置模板](../deploy/nvidia/README.md)。

## Intel OpenVINO 与视频 DRI

使用 `stat -c '%n %g' /dev/dri/renderD*` 确认设备与组。在 `.env` 配置 `COMPUTE_RENDER_GID`；有多个 GPU 时用 `COMPUTE_HARDWARE_DEVICE` 指定实际 render 节点，默认 `/dev/dri/renderD128`。

AI 使用 `compose.audio-openvino.yaml`，按 CPU 示例的相同流程替换音频覆盖文件进行 build/up。它包含固定 OpenVINO 运行库并传入 render 设备；模型需使用管理页支持的 OpenVINO 模型，不能把普通 checkpoint 重命名为 XML/BIN。

仅使用 Intel QSV 或 AMD VA-API 视频时，不必安装 AI 镜像。配置 `.env`：

```dotenv
COMPUTE_RENDER_GID=<所选render设备数字gid>
COMPUTE_HARDWARE_DEVICE=/dev/dri/renderD128
COMPUTE_HARDWARE_BACKEND=vaapi
```

AMD 使用 `vaapi`，Intel 使用 `qsv`，然后在基础 Compose 后追加 `compose.compute.dri.yaml` 并 `up -d`。CPU AI 组合也可叠加此文件启用视频设备。**ROCm 和 OpenVINO 组合已经映射设备并读取后端/设备变量**，只需设置对应的 `COMPUTE_HARDWARE_BACKEND` 与 `COMPUTE_HARDWARE_DEVICE` 后重新 `up -d`，无需重复叠加 DRI 设备和组。

## 模型安装与实际歌曲验收

原生 ROCm 连续生成默认复用模型子进程，空闲 60 秒释放；在主应用或独立 compute 的 `.env` 中设置 `COMPUTE_AUDIO_WORKER_IDLE_SECONDS=0` 可回到逐次独立进程，范围为 0～3600 秒。设置后重新创建计算节点容器，保留模型与缓存卷；单纯 `restart` 不更新环境。取消、失败和实时播放抢占会释放驻留模型，音频任务并发仍为 1。其它运行时沿用原有生命周期。

验收记录同时保存冷启动和连续两首的耗时、实际后端及 `inference.modelReused`，比较 `timingsMs` 中的排队、提取、分离、编码和回传，避免把推理时间当作总耗时。分段合并减少长曲内存及无用人声 WAV，仍需完整生成并校验后才提供伴奏；保留默认模型和重叠参数。完整字段及 CPU 参考对照命令见 [compute README](../../services/singverse-compute/README.md)。

本轮隔离 NAS 真机测量和未验证范围见 [伴奏优化验证记录](../../services/singverse-compute/VERIFICATION.audio-2026-11-08.md)，不可将隔离结果当作生产升级已完成。`COMPUTE_AUDIO_WORKER_IDLE_SECONDS=0` 仅禁用驻留，仍保留分段合并和校验。

1. 在管理端“设备与服务 → 性能节点配置”完成连接测试；同栈选择自动识别，独立节点检查双向回源。
2. 在“伴奏模型”区域检查运行环境，下载或导入所选固定模型。模型进入 `singverse-audio-models`，重建容器后仍保留。不要将模型放到媒体原件目录，也不要将宿主模型路径直接填成容器内不存在的路径。
3. 等待校验结束，对所选后端执行“试跑”，达到可用状态。测试 ROCm/CUDA 时先关闭“加速不可用时自动降级到 CPU”，明确选择已试跑的设备，防止把 CPU 成功当作 GPU 成功；此设置也影响视频任务。
4. 使用自己允许处理的一首完整歌曲，最好包含实际播放过的 WebDAV 素材，在音频处理页生成伴奏。检查任务的**实际后端、模型版本、是否回退、错误及完整时长**，确认 Java 已接收衍生结果。
5. 播放完整伴奏，确认原唱和伴奏均可切换、时长/采样率合理；检查内存、磁盘、GPU 使用及内核日志。再测试重复任务、多首/较长歌曲，以及同时播放时的负载。
6. 若需要视频加速，另用实际视频素材执行硬件方式测试和播放，核对实际后端与是否发生软件回退。AI 试跑不能替代视频验收。

环境、模型、镜像、驱动或固件改变后，试跑资格可能失效；重新检测、试跑再提交正式批次。试跑是小样本门禁，不能代表长曲音质、冷启动和长时间稳定性。

## 故障定位

| 现象 | 定位顺序 |
|---|---|
| `IMAGE_MISSING` / `RUNTIME_MISSING` | 实际容器镜像/target → 是否重新 build 和 up → 是否被旧 `COMPUTE_IMAGE` 覆盖 |
| 没有 `/dev/kfd` 或 render 设备 | NAS 来宾驱动初始化 → PVE 直通 → 宿主设备 → Compose devices → 数字组权限 |
| `invalid device function` / `no kernel image` | 真实 gfx 架构与运行库设备库是否相符；gfx1103 使用 native 配置，不伪造架构 |
| `MES failed` / `GPU Hang` / ring timeout | 先保存来宾内核日志、内核/固件版本、PVE 配置及任务实际后端；停止重试任务。核对固件/驱动，再分层测试简单 GPU 运算和完整模型；容器自动重启不能修复挂死驱动 |
| `Permission denied` | UID 10001 的设备组、模型/缓存卷权限；不要改成全特权容器 |
| 任务成功但 GPU 没有负载 | 检查实际 backend 和 fallbackReason；原片直放/缓存命中也不会产生 GPU 负载 |
| 内存不足或退出 137 | `docker stats`、OOM/内核日志、容器上限与整机余量、工作进程预算；减少并发后复验 |

常用只读检查：`docker stats --no-stream`、`docker compose ... logs --tail 100 singverse-compute`，以及在 NAS 来宾执行 `dmesg`（权限不足时由管理员读取）。分享日志前移除访问令牌、媒体 URL 查询参数和私人路径。

官方参考：[Compose 设备与 GPU](https://docs.docker.com/compose/how-tos/gpu-support/)、[AMD ROCm 容器设备要求](https://rocm.docs.amd.com/projects/install-on-linux/en/latest/how-to/docker.html)。这些是宿主与容器的前提，最终支持范围仍由项目锁定运行库和实际歌曲验收决定。
