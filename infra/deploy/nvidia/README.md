# NVIDIA 独立计算节点部署

用于 Linux AMD64，宿主需先安装兼容 CUDA 12.4 的 NVIDIA 驱动和 NVIDIA Container Toolkit，确认 `nvidia-smi` 正常。固定镜像使用 Torch 2.5.1+cu124；RTX 3070/A3000 等设备仍需在自己的机器上完成试跑与真实歌曲验收。

## 选择入口

完整仓库用户在仓库根使用 `infra/docker/compose/compose.compute.nvidia.yaml`。需要 NAS 源码上传包的维护者先在仓库根执行：

```sh
make compute-package-nvidia NVIDIA_PACKAGE_OUTPUT=/tmp/SingVerse-NVIDIA-source.zip
```

解压用户进入 `SingVerse-NVIDIA` 后使用包内 `docker-compose.yaml`。下文以解压包为例；仓库用户将每条命令的 `-f docker-compose.yaml` 换为 `-f infra/docker/compose/compose.compute.nvidia.yaml`，并保留 `--project-directory .`。

需要 Docker、Buildx/BuildKit 和 Compose 插件 2.24.4+。这是联网源码构建，不含预构建镜像、模型、媒体或凭据。无法联网时，在联网 Linux AMD64 构建机生成同一 target 镜像，用 `docker save` / `docker load` 转移，在目标 Compose 使用同一标签并以 `up -d --no-build --pull never` 启动；模型另行导入。

## 配置与启动

1. 业务 Go、前端与 compute 使用配套版本。升级时在管理页停止新任务并等待或取消在途任务，保留原认证、模型和缓存卷。
2. 所有节点参数直接写在 YAML，无需 `.env`。默认只监听 `127.0.0.1:18090`；跨主机连接时，将端口绑定的 `127.0.0.1` 替换为 **GPU 主机实际的局域网 IP**，限制入站仅业务主机可访问，不开放公网。
3. 无需 media 目录、曲库挂载或在 compute 中配置 Go 地址。默认容器上限 6GiB、音频进程预算 4GiB；调整时为系统、运行时和视频任务保留内存。
4. 源码包先校验 `sha256sum -c SHA256SUMS`，然后执行：

```sh
docker compose --project-directory . -f docker-compose.yaml config --quiet
docker compose --project-directory . -f docker-compose.yaml build singverse-compute
docker compose --project-directory . -f docker-compose.yaml up -d --wait --wait-timeout 180
docker compose --project-directory . -f docker-compose.yaml ps -a
docker compose --project-directory . -f docker-compose.yaml logs --tail 80 singverse-compute
```

构建会下载 CUDA/Python 等依赖，不包含在启动的 180 秒等待时间内。NAS Docker 项目界面需要将完整解压目录作为构建上下文，不能只上传 YAML。

## 配对、模型和验收

Go 访问 `http://<GPU主机IP>:18090`；compute 还需反向访问业务入口。业务部署的 `SINGVERSE_CALLBACK_ORIGIN` 配置为 compute 可达的 `http://<NAS-IP>:18080`，无尾部 `/`，不使用 localhost 或 Docker 临时 IP。配置只放业务侧，重建 Go 容器使其生效。

在业务管理页“设备与服务 → 性能节点配置 → 连接设置”扫描局域网并连接，或手工填写节点地址及已有连接密钥。首次配对信任受限局域网；已配对节点按持久身份校验。连接测试会验证反向认证回源，只有节点健康不够。

本地曲库只读挂载给 Go，WebDAV 凭据也只保存在业务节点。compute 按任务授权和 HTTP Range 取媒体，不接受用户文件路径。模型在管理页下载/导入到模型卷，校验后对 CUDA 执行试跑。

验收 GPU 时关闭自动 CPU 降级，选择已通过试跑的 CUDA，处理一首完整实际歌曲，检查任务实际后端、无回退、输出时长与完整伴奏播放。视频 NVENC 单独使用实际视频素材测试；CUDA 可用不代表视频编解码或音质验收完成。

## 保留数据和排查

停止使用 `docker compose --project-directory . -f docker-compose.yaml down`，**不加 `-v`**。项目名固定为 `singverse-compute-node`，与通用独立节点入口相同，作为替代配置使用，不同时启动两份。

- `singverse-compute-auth`：节点认证及配对身份，保留并备份。
- `singverse-audio-models`：模型权重与模型配置，保留。
- `singverse-transcode-cache`：临时产物、任务记录、硬件设置、模型选择及试跑资格；升级保留，更换驱动/镜像/设备后重新试跑，不当作纯临时卷清空。

GPU 未发现时依次检查宿主驱动、Container Toolkit、Compose 的 GPU reservation 与容器 `nvidia-smi`。退出 137 检查整机/容器 OOM；不要只增加并发。回源失败检查双向网络、固定回源地址与业务端认证日志，不关闭鉴权。

更新先记录旧镜像 ID 并备份身份/模型及部署配置，重新 build/up；restart 不会替换镜像。此包不修改业务数据库、曲库或业务项目，不代替现场长曲、音质与稳定性验收。

可执行的停机备份、空卷恢复及更新步骤见随包附带的 [验收与运维指南](README.operations.md)。
