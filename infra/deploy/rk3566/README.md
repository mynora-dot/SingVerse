# SingVerse RK3566 / fnOS 计算节点部署包

这是独立计算节点，连接已有 SingVerse Java/管理端；不含 Java、管理端、数据库或离线 Docker 镜像。当前默认可测试 UVR ONNX CPU 分离；**没有转换好的 RKNN 模型，NPU 不能直接启用**。CPU 基准为手动命令，不接入管理端批量伴奏任务；管理端的 RKNN 生成仍需转换模型和板端试跑。

## 启动

1. 将整个 ZIP 解压到 NAS 一个新目录；在飞牛 Docker Compose 项目中使用该目录下的 `docker-compose.yaml`。首次构建需要网络访问 Python/Debian 镜像、GitHub 和 Python 包源，不是离线安装包。
2. 复制 `.env.example` 为 `.env`，填写 NAS 的局域网 IP（`NAS_BIND_IP`）。本包默认监听 NAS 受限局域网地址 `10.100.1.115:18090`，更换设备时修改 IP；将端口访问限制在 Java 所在的受限网络。
3. `mkdir -p test-output`；给 `test-output` 目录授予容器 UID 10001 写入权限（例如仅对此目录执行 `sudo chown 10001:10001 test-output`）。原始媒体仅由业务节点管理，不修改歌曲文件权限或所有者。
4. 在解压目录执行：

```sh
docker compose up -d --build
docker compose ps
```

镜像按 ARM64 在 NAS 构建；未在 RK3566/fnOS 实际构建验收。默认容器内存上限 6GiB，音频任务预算 4GiB；低内存板卡需要先确认余量，不能保证运行成功。首次安装 Torch 等依赖耗时较长。

## CPU 基准测试

先将一段自备音频转为双声道 44.1kHz WAV（修改输入路径）：

```sh
docker compose exec -T singverse-compute ffmpeg -nostdin -i /test-output/example.mp4 -vn -ac 2 -ar 44100 -t 30 /test-output/input.wav
docker compose exec -T singverse-compute python -m singverse_compute.infrastructure.uvr_audio --model /opt/uvr-model/UVR_MDXNET_9482.onnx --output /test-output/result /test-output/input.wav
```

生成 `test-output/result/htdemucs/input/vocals.wav` 和 `no_vocals.wav`；目录名 htdemucs 沿用内部约定，实际执行 UVR 模型。试听后再评估全曲，记录耗时与内存；不能把 CPU 成功当作 NPU 成功。

## 连接已有管理端

节点地址填 `http://<NAS局域网IP>:18090`，通过节点“认证凭据”配对。部署管理员可在本机终端执行以下命令读取 token，只填入可信管理端，不发到聊天或日志：

```sh
docker compose exec -T singverse-compute cat /run/singverse-compute/token
```

原始媒体只由 Java 管理；所有任务通过 Java 随请求提供的回源地址及任务授权读取，不需要节点媒体挂载或 Java 地址配置。健康状态不代表音频模型已通过资格试跑。

## 后续 RKNN 实验

在 RKNN Toolkit2 2.3.2 支持的独立转换机器中安装该工具，执行：

```sh
python scripts/convert-rknn-audio.py models/uvr-mdxnet-9482/UVR_MDXNET_9482.onnx --target rk3566 --output models/uvr-mdxnet-9482-rknn
```

没有实测转换成功记录；构建失败须保留错误，不能用 ONNX 改后缀替代。成功后将输出目录完整复制回 NAS。确认 fnOS 内核 RKNPU 驱动和匹配的 `librknnrt.so` 就绪，找到真正 NPU 设备及其组 ID（示例 renderD129 不一定正确），在 `.env` 设置 `RKNPU_DEVICE` 与 `RKNPU_GID`，再执行：

```sh
docker compose -f docker-compose.yaml -f compose.rknn.yaml up -d --build
```

该配置不映射 MPP/RGA，不宣称启用视频硬件加速。缺少设备、库或转换模型时先使用默认 CPU 基准。管理端刷新 RKNN 模型，试跑通过后再生成伴奏；首次验收关闭 CPU 自动降级，确认实际后端为 rknn。

## 停止与数据

`docker compose down` 保留命名卷；不要加 `-v`。持久化数据为认证、缓存、模型命名卷及本目录 `test-output`。升级先保留这些数据和本地 `.env`。


## 修复旧包源码读取权限错误

若日志显示 `/opt/singverse-compute/singverse_compute/__init__.py` Permission denied，用新版 Dockerfile 和源码替换旧文件，再执行（保留已有 `.env` 和持久化数据）：

```sh
docker compose build singverse-compute
docker compose up -d --force-recreate singverse-compute
docker compose logs --tail=50 singverse-compute
```

新版 ZIP 文件权限为 644、目录为 755，Dockerfile 在切换 UID 10001 前再次规范源码权限。无需改为 root 运行，也不需要删除卷。首次重建通常可复用依赖缓存；若仍使用旧镜像，请确认 Compose 使用当前目录下的 Dockerfile。


### R3 修复：伴奏状态接口 500

日志出现 `core/build.json` 或 `requirements-audio-common.lock` Permission denied 时，原因是生成的镜像元数据及锁文件权限遗漏，服务健康查询仍可能正常。R3 Dockerfile 显式规范这两类文件，并在 UID 10001 下检查构建元数据与全部锁文件可读性。

现有容器可立即修复，不必重新下载依赖：

```sh
docker compose exec --user 0:0 -T singverse-compute sh -c 'chmod 644 /opt/singverse-compute/singverse_compute/core/build.json /opt/singverse-compute/requirements*.lock'
docker compose restart singverse-compute
```

命令只修改镜像内非敏感的构建信息及锁文件，不修改认证凭据或媒体。重建/重新创建容器后应使用 R3 Dockerfile 永久修复；保留 `.env` 和命名卷，不删除数据。
