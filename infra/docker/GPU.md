# 硬件与计算节点

音频推理运行库由 `COMPUTE_RUNTIME` 选择，视频设备由覆盖文件映射，两者分开配置。镜像不包含模型权重，不安装宿主驱动，不启用 privileged。模型在管理端下载或导入，实际能力以素材试跑为准。

| 运行库 | 镜像架构 | 硬件覆盖 / 用途 |
|---|---|---|
| base | AMD64、ARM64 | FFmpeg、媒体探测、独立 ONNX CPU worker |
| cpu | AMD64、ARM64 | Demucs CPU |
| uvr | AMD64、ARM64 | UVR ONNX 基线 |
| cuda | AMD64 | `compose.nvidia.yaml`；兼容 NVIDIA 驱动和 Container Toolkit |
| rocm | AMD64 | `compose.rocm.yaml`；兼容 AMD 驱动和 GPU |
| rocm-native | AMD64 | 同上；现有锁定的 gfx1103 运行库，不强制伪造 GPU 标识 |
| openvino | AMD64 | `compose.dri.yaml`；Intel 推理，视频 QSV 单独选择 |
| rknn | ARM64 | `compose.rknn.yaml`；匹配的瑞芯微 BSP、运行库和已转换模型 |

视频 NVIDIA 使用 `compose.nvidia.yaml`，VAAPI/QSV 使用 `compose.dri.yaml`，瑞芯微 MPP/RGA 使用 `compose.rockchip.yaml`；基础镜像也可按其实际 FFmpeg 能力使用硬件视频处理。RKNN 音频 NPU 与 MPP 视频映射不能相互替代。

## 同机 GPU

先按部署指南启用 compute，再选择运行库与覆盖文件。示例为 NVIDIA 预构建镜像：

```sh
COMPOSE_PROFILES=compute COMPUTE_NODE_URL=http://singverse-compute:8090 COMPUTE_RUNTIME=cuda \
  docker compose --project-directory . -f infra/docker/compose/compose.yaml \
  -f infra/docker/compose/compose.nvidia.yaml up -d --no-build --pull always --wait
```

源码路线在基础配置后增加 `-f infra/docker/compose/compose.build.yaml`，使用 `--build --pull missing`。AMD 设置 `COMPUTE_RENDER_GID` 和 `COMPUTE_VIDEO_GID`；DRI 设置 `COMPUTE_RENDER_GID`，通过 `COMPUTE_HARDWARE_DEVICE` 选择宿主 render 设备。视频后端可用 `COMPUTE_HARDWARE_BACKEND` 显式选择，默认 auto，不代表已经合格。

RKNN 需设置 `RKNPU_CARD_GID`、`RKNPU_GID`、`RKNN_MODEL_PATH`，必要时设置设备路径及 `RKNN_RUNTIME_LIBRARY`。模型目录必须已经存在，宿主提供兼容 `librknnrt.so`；转换和真机验证按 compute 模块说明执行，不把模型打入公共镜像。

## 独立计算节点

在计算主机运行，不启动业务服务：

```sh
# 镜像路线；先在 .env 选择 Registry、版本及 COMPUTE_RUNTIME
# GPU 场景追加相应硬件覆盖文件
docker compose --project-directory . -f infra/docker/compose/compose.compute.yaml \
  up -d --no-build --pull always --wait
# 源码路线
docker compose --project-directory . -f infra/docker/compose/compose.compute.yaml \
  -f infra/docker/compose/compose.compute-build.yaml up -d --build --wait
```

默认只监听 `127.0.0.1:18090`。跨主机时设置 `COMPUTE_HTTP_BIND` 为受限 LAN 地址，在管理端性能节点页面配对或配置连接密钥；必要时通过受控管理员终端读取：

```sh
docker compose --project-directory . -f infra/docker/compose/compose.compute.yaml \
  exec -T singverse-compute cat /run/singverse-compute/token
```

密钥是凭据，不复制到仓库或公开日志。Go 的回调地址必须能由计算主机访问，通过管理端或 `SINGVERSE_CALLBACK_ORIGIN` 配置。对跨网络访问落实 TLS 或受限网络与防火墙，客户端仍只连接 Go。

## macOS

Docker Desktop 的 Linux 容器不提供 Apple MPS / VideoToolbox。继续使用 `scripts/macos-compute.py` 管理本机原生节点，见 compute 模块 README；将业务连接地址设置为 `http://host.docker.internal:18090`，回调使用原生节点可访问的 NAS HTTP 地址。不要同时把 Docker compute 当作 Apple GPU 节点。

## 验证限制

CI 检查架构、包导入、依赖一致性和构建身份，不宣称已经取得 GPU、NPU 或具体素材资格。宿主驱动、显存、色调映射、硬解和零拷贝必须在目标主机试跑并单独记录。
