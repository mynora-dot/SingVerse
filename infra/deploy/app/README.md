# 独立业务节点部署包

本包运行 Nginx、Go 和持久化 SQLite，适合业务 NAS 与计算节点分开部署。**不包含 compute、GPU 运行库、模型权重或手机/TV APK**。完整三容器部署及各类 GPU 配置见仓库 `infra/docker/README.md` 和 `infra/docker/GPU.md`。

需要 Docker、Buildx/BuildKit、Compose 插件 2.24.4+。源码包首次构建需联网访问基础镜像、npm、Go 模块和 dbmate；宿主无需安装 Node/Go。支持所用基础镜像覆盖的 Linux AMD64/ARM64，目标 NAS 仍需现场验证。不能联网构建时取得相同版本的[离线业务镜像包](README.offline.md)，源码包本身不带镜像。

## 从零安装

1. 解压源码 ZIP，进入 `SingVerse-Business`。用 `sha256sum -c SHA256SUMS` 校验；macOS 可用 `shasum -a 256 -c SHA256SUMS`。不要覆盖已有计算节点的目录或 Compose。
2. 检查 `docker-compose.yaml`。所有必要配置在此文件中，无需 `.env`；两个缓存预算可选地通过同名环境变量覆盖。
3. 默认端口是 `127.0.0.1:18080:80`，仅本机访问。需要 TV/手机访问时，将绑定地址的 `127.0.0.1` 替换为 **NAS 实际局域网 IP**，不要使用另一个人的 IP。更改端口时同时调整客户端地址。
4. 若要连接独立 compute，将 Go 的 `SINGVERSE_CALLBACK_ORIGIN` 填成 `http://<NAS-IP>:18080`（无尾部 `/`），该地址必须从 compute 容器内可达。只部署业务且暂不运行媒体计算任务时可留空。内网 HTTP 下 `SINGVERSE_PUBLIC_ORIGIN` 保持空，只有正式 HTTPS 入口才设置它。
5. 执行下列命令。示例检查 URL 使用默认回环；已改局域网绑定时改成自己的 NAS 地址。

```sh
mkdir -p media
sha256sum -c SHA256SUMS
docker compose -f docker-compose.yaml config --quiet
docker compose -f docker-compose.yaml build
docker compose -f docker-compose.yaml up -d --wait --wait-timeout 180
docker compose -f docker-compose.yaml ps -a
curl --fail http://127.0.0.1:18080/api/health
```

飞牛等 NAS Docker 项目界面应以解压目录为项目目录，并使用包内 `docker-compose.yaml`；先确认 `media/` 已存在。构建目录必须包含完整源码，不能只上传 YAML。原始文件只读挂载给 Go，目录不存在会报错；WebDAV 用户可以保留空的 `media/`。

打开 `http://<NAS-IP>:18080/`，全新安装初始账号与密码均为 `admin`，登录后改密。添加本地来源时填写容器路径 `/media`；也可直接添加 WebDAV。容器 UID/GID 为 `10001:10001`，本地文件需要读取/目录遍历权限，使用 NAS ACL 授权，不要更改媒体原件或全局放开权限。

数据库由 Go 调用固定 dbmate 顺序应用基线与增量迁移。默认项目名 `singverse-go-business`，与旧 Java 项目和数据卷隔离。本包不含已有数据库、账号、密钥或曲库；不支持旧迁移链数据库升级。已有旧业务项目时，所有命令使用一个未用过的项目名，例如 `-p singverse-go-business-fresh`，并使用空闲端口，创建独立数据与密钥卷；不要删除旧卷或将旧库挂入本基线。首次选定项目名后，后续启停继续使用它。

## 连接计算节点

在“设备与服务 → 性能节点配置 → 连接设置”扫描局域网并连接节点，可自动配对、测试和保存；也可填写 `http://<GPU主机IP>:18090` 与已有连接密钥。首次配对信任受限局域网，节点需运行匹配版本并允许配对。节点端口仅允许业务主机访问，不对公网开放。

连接测试需验证两个方向：Go → compute，compute → `SINGVERSE_CALLBACK_ORIGIN` 的业务入口。不能填 localhost、Docker 临时 IP 或仅浏览器能访问的域名。若不固定回源地址，需要通过 compute 可达的 URL 登录管理页后再提交任务，Go 重启后也需重新确认；长期部署建议固定。

只有 Go 挂载媒体并保存 WebDAV 凭据，compute 通过短期任务授权及 HTTP Range 回源。不要向 compute 提交本地媒体路径或复制 WebDAV 密码。连接成功后，还需安装模型、对实际 GPU 试跑，并用完整歌曲验收；节点健康或视频硬解可用不代表 AI 可用。本包不启动、重建或删除独立计算节点，继续保留它原有的模型、认证及缓存卷。

## 日志、停止与备份

```sh
docker compose -f docker-compose.yaml logs --tail 100 singverse-go-backend singverse-frontend
docker compose -f docker-compose.yaml down
```

停止不加 `-v`。`singverse-data` 保存 SQLite 与业务衍生文件，`singverse-secrets` 保存凭据加密主密钥，`singverse-downloads` 保存洛雪下载歌曲与附件；实际卷名带项目名前缀。在后台将 `/downloads` 添加为本地媒体库并指定为下载保存位置后，才可配合自备音源使用歌曲下载。备份前停止写入，成套保存这些卷、部署 YAML 及媒体原件。不能只复制运行中的 SQLite 主文件，也不能用新密钥替换原密钥。MySQL 数据不会自动迁移为 SQLite。

更新前记录版本、镜像 ID 和项目名，备份后用同版本完整源码重新 build/up；restart 不会更换镜像。回退需确认数据库契约兼容，必要时恢复原配套备份。首次源码构建、镜像构建或本机测试不能替代 NAS 上真实歌曲和跨主机验收。

随包附带 [验收与运维指南](README.operations.md)，包含固定 Compose 组合、只读卷备份、空卷恢复验证和更新/回退步骤。维护独立计算节点时，还要保留它的身份、模型及保存硬件/模型设置的缓存卷。

## 维护者生成部署包

以下命令在完整仓库根执行，不在解压包内执行：

```sh
make app-package APP_PACKAGE_OUTPUT=/tmp/SingVerse-Business-source.zip
```

输出已存在时失败，避免覆盖正在使用的交付包。打包只收集构建所需源码、锁文件、契约、迁移和说明，不包含本机配置、数据库、凭据或媒体；`SHA256SUMS` 用于传输校验。离线镜像的构建和打包步骤见 `README.offline.md`。
