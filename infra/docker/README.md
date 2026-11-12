# SingVerse Docker 部署

只保留源码构建和预构建镜像两条路线。业务服务是 Go 二进制，内置完整管理页与亲友 H5；不再部署独立 Nginx。compute 是可选媒体计算节点，关闭时不影响业务健康。

所有仓库命令均从仓库根执行，并保留 `--project-directory .`。需要 Docker Engine、Buildx、Docker Compose 插件及 Python 3（运行检查脚本时）；源码构建需要访问固定基础镜像和依赖下载源，目标机器不必安装 Go、Node 或 Python。

## 1. 配置

首次执行 `test -f .env || cp .env.example .env`，已有 `.env` 不覆盖。Compose 默认使用 GHCR：

```dotenv
IMAGE_PREFIX=ghcr.io/mynora-dot
SINGVERSE_VERSION=latest
SINGVERSE_HTTP_BIND=127.0.0.1
SINGVERSE_HTTP_PORT=18080
COMPUTE_NODE_URL=
COMPUTE_RUNTIME=base
```

生产推荐固定到已发布版本，例如 `SINGVERSE_VERSION=0.1.0`；示例不是已发布承诺。`IMAGE_PREFIX` 包含 Registry 和命名空间，不带末尾斜线：GHCR 为 `ghcr.io/<命名空间>`，Docker Hub 为 `docker.io/<命名空间>`，阿里云为 `<Registry域名>/<命名空间>`。各平台同版本来自同一构建产物，不自动切换平台。

默认只运行 `singverse-go-backend`。在部署机访问 `http://127.0.0.1:18080/`，管理兼容 `/admin/`，亲友 H5 为 `/party/`，健康为 `/api/health`。首次账号密码 `admin/admin`，登录后修改密码并按需启用 MFA。供手机和电视访问时，把绑定地址改为 NAS 的受限局域网地址，二维码使用该地址。

## 2. 路线一：源码构建

取得完整同版本源码，保留符号链接和根 VERSION：

```sh
make docker-build
make docker-up
make docker-check
```

等价的构建配置由 `compose.yaml` 加 `compose.build.yaml` 组成。业务镜像标记 `singverse:local`，compute 标记 `singverse-compute:local-<运行库>`；版本身份从源码 VERSION 注入。源码更新后重新 build、up，不使用 `docker-image-up` 替代源码升级。

## 3. 路线二：预构建镜像

只需配置文件，不需要构建工具或源码应用目录。下载同版本的 `infra/docker/compose/` 配置及 `.env.example`，在自己的部署目录设置 `.env`，保持命令中的项目根目录指向该目录。

```sh
make docker-pull
make docker-image-up
# 不带 Makefile 的部署目录：
docker compose --project-directory . -f infra/docker/compose/compose.yaml pull
docker compose --project-directory . -f infra/docker/compose/compose.yaml up -d --no-build --pull always --wait
```

基础配置没有 `build`，不会编译应用。源码构建配置不能混入该路线。正式镜像需先由维护者发布，发布前拉取失败是正常错误，不会静默改成本机构建。

## 4. 同机启用 compute

在 `.env` 增加以下配置；保留所选路线的 build 覆盖文件：

```dotenv
COMPOSE_PROFILES=compute
COMPUTE_NODE_URL=http://singverse-compute:8090
COMPUTE_RUNTIME=base
```

然后重新执行对应路线的启动命令。同机 compute 不发布宿主端口，Go 读取共享的认证凭据，compute 通过授权回调读取媒体，不直接挂载原始曲库。`base` 提供媒体工具和独立 ONNX CPU worker，不含 Demucs 主运行库或模型权重。其他运行库、GPU 设备映射和独立部署见 [硬件与计算节点](GPU.md)。

关闭 compute 时清空 `COMPUTE_NODE_URL` 和 `COMPOSE_PROFILES`，先显式停止计算容器，再重建业务服务：

```sh
docker compose --project-directory . -f infra/docker/compose/compose.yaml stop singverse-compute
# 按原路线重新执行启动命令
```

健康 UP、运行库安装成功均不代表特定素材或 GPU 已通过试跑。

## 5. 本地媒体与下载

WebDAV-only 部署无需媒体绑定。本地媒体通过 `compose.media.yaml` 挂载，设置 `SINGVERSE_MEDIA_PATH` 为已存在的绝对路径；原件只读。下载可用独立命名卷，或通过 `compose.downloads.yaml` 和 `SINGVERSE_DOWNLOAD_PATH` 绑定已存在的可写目录。

```sh
docker compose --project-directory . -f infra/docker/compose/compose.yaml \
  -f infra/docker/compose/compose.media.yaml up -d --no-build
```

源码路线在基础配置后追加 `compose.build.yaml` 并使用 `--build`；硬件、媒体和下载覆盖文件始终随相同的 build、up、down、维护命令携带。缺失主机目录时失败，不自动创建空目录。

## 6. HTTPS 与维护

HTTPS 使用你已有的外部反向代理，转发至业务 HTTP 入口，正确处理媒体流、Range、HEAD 和上传限制。只有明确配置受信任代理后才启用转发头信任；按实际入口设置 `SINGVERSE_PUBLIC_ORIGIN` 和 `SINGVERSE_TRUSTED_PROXIES`。不在应用内提供自签名证书、独立 Nginx 或全局关闭证书验证。

查看状态、日志和停止：`make docker-status`、`make docker-logs`、`make docker-down`。停止保留卷。备份、升级和旧部署切换见 [运维](OPERATIONS.md)，发布配置见 [镜像发布](PUBLISHING.md)。
