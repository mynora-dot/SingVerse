# Linux AMD64 业务节点离线镜像包

适用于 Linux x86_64 NAS/主机，与 GPU 品牌无关。内含 Nginx、Go 运行镜像及 SQLite 配置，**不含 compute、模型、歌曲或手机/TV APK**。不需要在线拉镜像、npm、Go 模块或源码构建。ARM64 不能使用此包。

需要 Docker 与 Compose 插件 2.24.4+。当前从空库以 `202611070001_initial_schema.sql` 初始化，不包含旧数据库或账号，不支持旧迁移链升级。已有旧业务项目时，使用全新的项目名（所有命令统一加 `-p <新项目名>`）及空闲端口，不复用或删除原卷。

## 部署

1. 解压**离线镜像 ZIP**并进入 `SingVerse-Business`，确认有 `images.tar`。源码包里附带此说明不代表源码包含有镜像。
2. 默认 `docker-compose.yaml` 绑定 `127.0.0.1:18080`。TV/手机访问时改为 NAS 的实际局域网 IP；有独立 compute 时，将 Go 的 `SINGVERSE_CALLBACK_ORIGIN` 填为 compute 可达的业务 URL，例如 `http://<NAS-IP>:18080`。
3. 执行：

```sh
sha256sum -c SHA256SUMS
docker load -i images.tar
mkdir -p media
docker compose -f docker-compose.yaml config --quiet
docker compose -f docker-compose.yaml up -d --no-build --pull never --wait --wait-timeout 180
docker compose -f docker-compose.yaml ps -a
curl --fail http://127.0.0.1:18080/api/health
```

最后的检查 URL 使用默认回环；改了绑定地址后请使用实际 NAS 地址。macOS 校验可用 `shasum -a 256 -c SHA256SUMS`。NAS 项目界面部署前先在终端执行 `docker load`，再使用包内无 build、`pull_policy: never` 的 Compose 创建项目，不覆盖已有计算节点的配置。

管理页 `http://<NAS-IP>:18080/`，全新安装账号密码均为 `admin`，登录后改密。歌曲可放到 `media/`，本地来源填写 `/media`，或直接使用 WebDAV；原件只读挂载给 Go。容器 UID 10001 需要读取及目录遍历权限。

独立计算节点需要同版本镜像和模型，单独导入/部署。在管理页性能节点配置中扫描配对或手工连接，验证 Go 与 compute 双向访问；随后下载/导入模型、GPU 试跑和完整歌曲验收。内网 HTTP 的 `SINGVERSE_PUBLIC_ORIGIN` 留空。没有独立 compute 时仍可使用业务入口和原片播放，计算功能不可用。

## 数据保留与升级

SQLite 与业务衍生文件保存在 `singverse-data`，加密主密钥在 `singverse-secrets`，洛雪下载歌曲与附件在可写的 `singverse-downloads:/downloads`，实际卷名带项目名前缀。使用歌曲下载前，在后台添加 `/downloads` 本地媒体库并指定下载用途，再导入自备音源。停止使用 `docker compose -f docker-compose.yaml down`，不加 `-v`。备份前停止写入，成套备份这些卷及配置，媒体另行保存；不能只复制运行中的 SQLite 主文件。此包不会迁移 MySQL 数据，也不操作原计算节点项目。

更新时先备份、记录原镜像 ID，加载相同架构及配套版本的新镜像，确认原项目 Compose 中的两个镜像标签与新包 `images.json` 的 `reference` 一致，再用原项目和配置执行 `up -d --no-build --pull never --force-recreate`。`restart` 不会使用新镜像。数据库不兼容时不能仅回退镜像，需恢复原配套备份。

日志：`docker compose -f docker-compose.yaml logs --tail 100 singverse-go-backend singverse-frontend`。离线镜像能启动不代表 GPU、跨主机回源或真实歌曲验收完成。

随包附带的 [验收与运维指南](README.operations.md) 提供停机备份、恢复验证与维护命令；备份使用本机已有镜像，不需要额外联网拉取工具镜像。

## 维护者制作离线包

在可联网构建的完整仓库根执行；导出的是干净运行镜像，不是容器文件系统或业务卷：

```sh
docker buildx build --platform linux/amd64 --load \
  -f infra/docker/build/go.Dockerfile --target singverse-go-backend \
  -t singverse-go-backend:offline-amd64 .
docker buildx build --platform linux/amd64 --load \
  -f infra/docker/build/go.Dockerfile --target singverse-frontend \
  -t singverse-frontend:offline-amd64 .
make app-package-offline APP_OFFLINE_PACKAGE_OUTPUT=/tmp/SingVerse-Business-offline-amd64.zip \
  APP_OFFLINE_BACKEND_IMAGE=singverse-go-backend:offline-amd64 \
  APP_OFFLINE_FRONTEND_IMAGE=singverse-frontend:offline-amd64
```

打包器检查两个显式指定镜像均为 `linux/amd64`，保存前后复查镜像 ID 不变，并保留输入标签以兼容经典及 containerd Docker 镜像存储。Compose 移除源码 build 配置并禁止拉取；`images.json` 记录输入标签和实际 ID，包内 `VERSION` 与 `SHA256SUMS` 用于核对版本及传输完整性。不要把旧 Java 前端标签当作本次 Go 前端；两个镜像必须来自同次配套构建。输出已存在时失败。compute 镜像可另用 `docker save` / `docker load` 转移对应架构的镜像；模型通过管理页导入，不能把运行中的业务卷打进镜像。
