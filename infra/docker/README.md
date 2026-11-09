# Docker 部署指南

本文用于从空数据库部署 SingVerse。所有仓库版命令均在**完整仓库根目录**执行，保留 `--project-directory .`：构建上下文、根 `.env` 和相对挂载路径都以此为准。源码应来自同一版本，保留符号链接；不要只复制 Compose 文件到其他目录。

## 选择部署方式

| 场景 | 入口 | 包含内容 |
|---|---|---|
| 首次部署，业务与计算在同一台机器 | 本文的 `compose.app.yaml` | Nginx、Java、compute；SQLite 在 Java 数据卷内 |
| AI 伴奏分离或 GPU 视频转码 | 先完成本文，再看 [GPU 与 AI 部署](GPU.md) | 按硬件选择计算镜像、传入设备、安装模型并试跑 |
| 业务 NAS 与 GPU 主机分开 | [业务节点部署包](../deploy/app/README.md) + [独立计算节点](#独立计算节点) | 业务包只启动 Nginx、Java；GPU 主机独立运行 compute |
| 目标 NAS 无法联网构建 | [离线业务镜像包](../deploy/app/README.offline.md) | Linux AMD64 业务镜像；compute 镜像和模型另行准备 |

默认 compute 是 `base` 镜像：包含 FFmpeg、媒体探测和视频处理，**不含 AI 推理运行库或模型权重**。健康为 UP 不等于 GPU 或伴奏分离可用。手机和 TV APK 需要另行构建或取得同版本发布包，不在服务端 Docker 镜像中。

部署后按 [验收与运维指南](OPERATIONS.md) 固定维护命令，执行停机备份、恢复验证、更新和回退。该指南也随业务源码包、离线包和 NVIDIA 包交付为 `README.operations.md`，离开完整仓库后仍可查阅。

## 1. 准备环境

- Linux AMD64 或 ARM64，Docker Engine、BuildKit/Buildx 和 **Docker Compose 插件 2.24.4 或更新版本**。使用 `docker compose`，旧版 `docker-compose` 不适用；HTTPS 配置使用 `!override` 合并语义。
- 部署不要求宿主安装 Node、Java 或 Maven；这些在构建容器中执行。`make` 和 Python 3 仅用于仓库的便捷命令与自检。
- 首次源码构建需要访问基础镜像仓库、npm、Maven、dbmate、Python 依赖及固定的 Jellyfin FFmpeg 下载地址。GPU 镜像还需对应厂商的运行库下载源。构建耗时不属于服务启动超时。
- 为镜像、构建缓存、模型和衍生文件留出磁盘空间。Java 首次默认歌曲缓存预算 10GiB、最低磁盘空闲量 2GiB；已有部署的缓存设置以管理页保存的值为准。这不是总磁盘用量上限，compute 临时产物和模型使用另一些卷。
- 基础 compute 容器内存上限 2GiB；AI 配置默认 6GiB，音频工作进程预算 4GiB。主机还需为 Java、NAS 系统及其他服务保留内存，不能把容器上限当作整机最低内存。

```sh
docker version
docker compose version
docker buildx version
# 仅首次创建；已有 .env 请保留并按 .env.example 补充字段
test -f .env || cp .env.example .env
```

`.env` 由 Compose 读取，不会整体注入容器，也不会由主机上的 Spring Boot 自动加载。只有 YAML 中声明的变量才生效。默认不需要 MySQL；根 `.env.example` 中的 MySQL 配置仅供可选开发环境使用。

## 2. 构建与启动

保持 `.env` 中的 `SINGVERSE_HTTP_BIND=127.0.0.1`，先在部署机检查：

```sh
docker compose --project-directory . -f infra/docker/compose/compose.app.yaml config --quiet
docker compose --project-directory . -f infra/docker/compose/compose.app.yaml build
docker compose --project-directory . -f infra/docker/compose/compose.app.yaml up -d --wait --wait-timeout 180
docker compose --project-directory . -f infra/docker/compose/compose.app.yaml ps -a
curl --fail http://127.0.0.1:18080/api/health
```

预期三个服务健康，HTTP 返回 `{"status":"UP"}`。初次构建会执行前端类型检查、构建及 Java 测试；npm 行为测试由前端检查流程另行运行。数据库由 Java 自动以 `202611070001_initial_schema.sql` 从空库初始化，无需手工导入 SQL 或安装 MySQL。

已有旧迁移链的数据库不能直接接入此基线。另建项目验证时，所有命令统一加 `-p <新项目名>` 并选择空闲 HTTP 端口，创建独立卷；不要删除或复用原业务卷。完成首次安装后固定项目名，防止后续启停意外切换数据库或密钥。

使用默认组合时，可以用 `make docker-up` / `make docker-check` / `make docker-down`。**使用 GPU、媒体挂载或 HTTPS 覆盖文件后，后续 build、up、检查及维护应继续携带相同的 `-f` 组合**；`make docker-up` 只选择基础组合，会丢失本次的覆盖设置。

### 允许 TV、手机和微信访问

在 `.env` 将 `SINGVERSE_HTTP_BIND` 改为本机实际的受限局域网地址，端口默认 `18080`，然后用原配置重新执行 `up -d`。例如 `192.168.1.10` 是示例，必须替换成自己的 NAS 地址。

- 管理页：`http://<NAS局域网IP>:18080/`，兼容 `/admin/`。
- 亲友 H5：`http://<NAS局域网IP>:18080/party/`；聚会二维码由实际会话生成。
- 全新安装账号与密码均为 `admin`，登录后修改密码，可配置 TOTP。
- TV 和手机填写相同的 NAS 业务地址。`localhost` 指向设备自己，Docker 容器 IP 也不适合作为客户端地址。

只向受限网络开放业务端口；Java `8080` 与同栈 compute `8090` 不发布到宿主。默认 HTTP 部署的 `SINGVERSE_PUBLIC_ORIGIN` 留空，该字段只接受显式 HTTPS 公共入口。

## 3. 添加歌曲

### WebDAV

无需额外媒体挂载。在管理端“曲库 → 媒体库管理”添加 WebDAV 地址和凭据，选择解析方案并扫描。WebDAV 凭据只由 Java 保存和使用；compute 通过 Java 的短期任务授权回源读取文件。

扫描主要读取目录和元数据；播放时原片可按需分块缓存在 Java 数据卷。兼容版本与伴奏另存为衍生文件，不改写 WebDAV 原件。缓存设置在管理端“曲库 → 歌曲缓存”调整。

### NAS 本地文件

在 `.env` 设置 `SINGVERSE_MEDIA_PATH` 为**已经存在的绝对目录**，例如 `/srv/music`，再添加 `compose.media.yaml`：

```sh
docker compose --project-directory . \
  -f infra/docker/compose/compose.app.yaml \
  -f infra/docker/compose/compose.media.yaml config --quiet
docker compose --project-directory . \
  -f infra/docker/compose/compose.app.yaml \
  -f infra/docker/compose/compose.media.yaml up -d --wait --wait-timeout 180
```

在管理页填写**容器路径 `/media`**。该覆盖文件替换空的媒体命名卷，仅给 Java 只读挂载；compute 不需要重复挂载曲库。路径不存在会报错，不会静默创建一个空曲库目录。Java 使用 UID/GID `10001:10001`，需要目录遍历和文件读取权限；使用 NAS ACL 授予必要权限，不必更改原件所有权或开放所有目录。

本地媒体与 GPU/AI 同时启用时，在完整命令中同时携带 `compose.media.yaml` 和所选 GPU/AI 覆盖文件。

### 洛雪下载保存位置

Java 默认另挂持久可写卷 `singverse-downloads:/downloads`。在媒体库添加本地目录 `/downloads` 并勾选「用于保存洛雪下载」后，再到歌曲下载导入自备音源。该目录与原件只读的 `/media` 分离。若使用 NAS 实际文件夹，在 `.env` 填写已有绝对路径 `SINGVERSE_DOWNLOAD_PATH`，授予容器 UID/GID `10001:10001` 写入及遍历权限，叠加 `infra/docker/compose/compose.downloads.yaml`；后续维护保持该组合。文件夹不存在时部署失败，不自动创建。操作、错误处理及限制见[歌曲下载](../../docs/lx-downloads.md)。

## 独立计算节点

GPU 主机使用相同版本仓库，在其 `.env` 设置 `COMPUTE_HTTP_BIND` 为该主机的局域网地址，`COMPUTE_HTTP_PORT` 默认 `18090`。以下启动基础节点；AI 部署继续叠加 [GPU 指南](GPU.md) 中相同的音频配置：

```sh
docker compose --project-directory . -f infra/docker/compose/compose.compute.yaml config --quiet
docker compose --project-directory . -f infra/docker/compose/compose.compute.yaml build singverse-compute
docker compose --project-directory . -f infra/docker/compose/compose.compute.yaml up -d --wait --wait-timeout 180
```

Java 与 compute 必须**双向可达**：Java 访问 `http://<GPU主机IP>:18090`；compute 访问 Java 的业务入口 `http://<NAS-IP>:18080`。业务节点配置 `SINGVERSE_CALLBACK_ORIGIN` 为后一个地址（无尾部 `/`），不在 compute 上设置该字段。完整仓库应用栈和业务部署包均提供此字段；修改后重建 Java 容器。

在管理端“设备与服务 → 性能节点配置 → 连接设置”扫描局域网并连接节点，或手工填写地址及已有连接密钥。扫描与首次配对只用于受限局域网；计算端口限制为业务主机可访问。连接测试同时验证回源，不能只检查 compute 的 `/health`。不需要复制歌曲、挂载媒体或将 WebDAV 密码交给 GPU 主机。

同栈自动连接已有内部回源地址，无需设置 `SINGVERSE_CALLBACK_ORIGIN`。只有实际选择独立节点时才配置外部回源地址；错误的固定地址也会覆盖同栈的自动地址。

独立节点项目名为 `singverse-compute-node`，应用栈为 `singverse-app`，两者拥有不同卷。应用栈可手选独立节点，但原同栈 compute 不会自动停止；希望只部署两个业务容器时使用 [业务节点包](../deploy/app/README.md)。NVIDIA 另有 [固定配置与部署模板](../deploy/nvidia/README.md)。

## HTTPS 入口

已有 HTTPS 反向代理时，配置根 `.env`：

```dotenv
SINGVERSE_PUBLIC_ORIGIN=https://ktv.example.com
SINGVERSE_TRUSTED_PROXIES=<Java实际看到的可信代理IP或CIDR>
```

替换示例值，叠加 `infra/docker/compose/compose.https.yaml` 后重新 `config --quiet`、`up -d`。外部代理转发至本机 `127.0.0.1:18080`，保留 Host 与正确的转发头。此配置不申请证书或安装外部代理；可信范围应对应 Java 看到的 Nginx/代理地址，不设置 `0.0.0.0/0`。代理与应用不在同一宿主时应另行设计受限连接，不能直接沿用回环绑定。

## 数据、备份与更新

| 卷（实际名称带项目名前缀） | 内容 | 处理方式 |
|---|---|---|
| `singverse-data` | SQLite、封面/元数据、持久歌曲缓存、兼容版本、AI 伴奏 | 与密钥卷一起备份 |
| `singverse-secrets` | Java 凭据加密主密钥 | 与原数据库配套，丢失后不能解密原凭据 |
| `singverse-compute-auth` | compute 连接认证与配对身份 | 保留；同栈 Java 只读共享 |
| `singverse-audio-models` | 下载/导入的模型权重与模型配置文件 | 保留，换环境后重新试跑 |
| `singverse-transcode-cache` | compute 临时执行产物、任务记录、硬件设置、模型选择及试跑资格 | 保留；不能把整个卷当作可随意清空的缓存 |
| `singverse-media` | 未添加媒体覆盖文件时的空媒体卷 | 本地歌曲通常改为只读宿主挂载 |
| `singverse-downloads` | 洛雪下载音频、同名歌词/封面及私有临时区域 | 可写；随曲库备份，NAS 绑定时备份实际目录 |

备份前暂停新任务，等待在途任务完成并停止相关服务写入；用 NAS 快照/备份工具保存业务数据卷和密钥卷、compute 身份/模型/设置卷、Compose 配置及私有 `.env`。不能只复制运行中的 SQLite 主文件；媒体原件另行备份。恢复时成套恢复原卷和权限，再启动原版本验证登录、媒体来源与播放。可执行的只读卷备份与空卷恢复示例见 [运维指南](OPERATIONS.md#停机备份示例)。

升级前记录源码版本、当前镜像 ID、所用覆盖文件和项目名，并完成备份。取同一发行版本的 Java、前端与 compute 源码，重新 `build`，再按原 `-f` 组合 `up -d --wait --wait-timeout 180`。预构建交付则使用实际已发布的固定标签或摘要，先 `pull` / `docker load`，再 `up -d --no-build`；仓库有发布工作流不代表注册表里一定存在对应镜像。`restart` 不会替换镜像或重新应用环境配置。

回退镜像前检查数据库契约是否兼容；不兼容时需恢复升级前**成套备份**。更换内核、驱动、固件、GPU、推理运行库或模型后重新进行 GPU/AI 试跑和完整歌曲验证。

停止使用相同项目及配置的 `docker compose ... down`，**不加 `-v`**。清理 Docker 空间前确认没有误删业务卷，不使用包含卷删除的全局清理命令。

## 自检与故障定位

```sh
# 不依赖 Docker daemon：解析部署组合、保护挂载与构建输入
make docker-config-check
# 已运行的默认应用栈：页面、资源、登录、API、compute 鉴权与回源
make docker-check
# 日志与状态；自定义部署继续使用原 -f 组合
docker compose --project-directory . -f infra/docker/compose/compose.app.yaml ps -a
docker compose --project-directory . -f infra/docker/compose/compose.app.yaml logs --tail 100 singverse-java-backend singverse-compute singverse-frontend
```

`docker-check` 会登录并退出管理员会话；改密后通过 `SINGVERSE_CHECK_PASSWORD` 提供当前密码，MFA 使用 `SINGVERSE_CHECK_CODE`，不把凭据提交到仓库或分享日志中。它验证 HTTP 与基础媒体能力，不能代替 GPU 真机验收。

| 现象 | 检查 |
|---|---|
| 其他设备打不开网页 | HTTP 是否仍绑定回环、NAS 防火墙、端口占用、无线客户端隔离；客户端使用 NAS 局域网 IP |
| 启动超时 / Java unhealthy | 先看 Java 日志，检查空库基线、数据/密钥卷权限、内存和磁盘；不要删库试错 |
| 本地曲库为空 | 管理端填 `/media`、宿主目录是否正确挂载、UID 10001 的读取/遍历权限 |
| 节点 UP 但任务失败 | 性能节点连接测试、双向回源、模型是否就绪、所选设备是否已试跑 |
| GPU 缺失 / 解码失败 / GPU Hang | [GPU 与 AI 排查](GPU.md#故障定位)，分别检查宿主驱动、设备传入、运行时和实际任务 |
| 模型下载失败 | 节点出口网络和模型目录写权限；按管理页提示导入固定模型文件/包（标准 Demucs 为原始 `.th` 文件），不关闭摘要校验 |
| AI 进程被杀 / 退出 137 | 容器 OOM、整机可用内存、工作进程预算、并发任务；不要只提高预算而不留系统余量 |
| Maven XML/UTF-8 错误、Mac 上传后构建失败 | 使用完整源码和正确权限；确认 XML 旁的 `._*`、`.DS_Store` 或 `__MACOSX` 元数据未作为源码参与构建，优先使用项目生成的 ZIP |

## 文件位置

Compose 配置在 [compose/](compose/)，Bake 镜像矩阵和共享源码白名单在 [build/](build/)，部署包模板在 [../deploy/](../deploy/)。Dockerfile 保留在各服务目录，两个 `Dockerfile.dockerignore` 符号链接共用 `build/.dockerignore`。打包脚本将其复制为包根 `.dockerignore`，不收集本地凭据、数据库、媒体或模型。

更多参数和运行库细节见 [compute README](../../services/singverse-compute/README.md)，数据库基线见 [database/README.md](../../database/README.md)。
