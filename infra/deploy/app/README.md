# 远端业务节点部署

前端 Nginx、Java 业务服务与持久化 SQLite 数据库，无需独立数据库容器。
数据库由 Java 使用单一 `202611070001_initial_schema.sql` 自动初始化，保存于 `singverse-data` 命名卷的 `/data/singverse.db`；
凭据加密主密钥保存于 `singverse-secrets`，备份或迁移必须同时保留这两个卷。
这是全新安装，不包含本机已有数据库、账号、连接密钥或曲库，不支持旧迁移链数据库升级。若已有旧业务项目，请使用一个尚未用过的 Compose 项目名（例如所有命令均加 `-p singverse-business-fresh`），创建独立数据库和密钥卷；不要将旧卷挂到这份基线。端口冲突时停止原业务项目或修改新项目端口，无需删除旧卷。

从仓库根运行 `make app-package` 生成源码部署 ZIP。包内的 `docker-compose.yaml`
可以直接用于飞牛 Docker 项目；所有部署参数均在此文件中，不使用 `.env`。
首次构建需要联网下载 Node、Java、Nginx 镜像及 npm、Maven、dbmate 依赖。
包内包含完整前端和 Java 源码、测试与数据库迁移，不包含预构建镜像。
`python3 scripts/check-database-baseline.py` 可在安装 dbmate 后独立验证临时 SQLite 的从零建库及回退重建，不操作部署数据库。
远端无法访问镜像仓库时，可使用[离线镜像包](README.offline.md)。维护者先以
`docker buildx build --platform linux/amd64 --load` 分别构建 Dockerfile 的
`singverse-java-backend`、`singverse-frontend` target，标记为对应的 `:offline-amd64` 镜像，
再运行 `make app-package-offline`；打包器检查架构并导出运行镜像，不导出容器或业务卷。
支持所用基础镜像覆盖的 Linux AMD64/ARM64，实际 NAS 仍需现场验证。

## 部署

1. 解压 ZIP，进入 `SingVerse-Business` 目录；不要覆盖已有性能节点的 Compose。
2. 默认业务主机 IP 为 `10.100.1.12`，HTTP 端口为 `18080`。
   若部署到其他 IP 或端口，同时修改端口绑定和 `SINGVERSE_CALLBACK_ORIGIN`；
   回源地址必须从性能容器内可达，不能用 localhost。
   `SINGVERSE_PUBLIC_ORIGIN` 在本内网 HTTP 部署中留空，只有正式 HTTPS 入口才可填写它。
3. 将歌曲放到解压目录下的 `media/` 子目录；只有业务容器只读挂载它。
4. 执行以下命令：

```sh
mkdir -p media
docker compose -f docker-compose.yaml config --quiet
docker compose -f docker-compose.yaml --progress plain build
docker compose -f docker-compose.yaml up -d --wait
docker compose -f docker-compose.yaml ps -a
curl --fail http://10.100.1.12:18080/api/health
```

打开 `http://10.100.1.12:18080/`，全新安装初始账号与密码均为 `admin`，登录后改密。
添加本地媒体来源时填写容器路径 `/media`，也可以添加 WebDAV 来源。
在“设备与服务 → 性能节点配置 → 连接设置”中点击“扫描局域网计算节点”，
选择已有节点并点击“连接此节点”，服务端自动配对、测试并保存，无需填写密钥。
首次配对信任受限局域网；性能节点须同步升级且允许局域网配对。旧节点仍可手填
`http://10.100.1.12:18090` 与现有密钥。密钥沿用性能节点认证卷，不在本包中预填；业务配置及配对身份加密保存在数据库中。
客户端只访问 18080；18090 仍限制在业务节点可访问的受限网络。
业务和性能节点即使在同一台宿主机，也统一通过任务授权回源读取媒体。

本包不启动、不重建或删除现有性能节点。性能节点继续使用原 Compose、认证、模型与缓存卷。
业务和性能节点必须都支持 callbackOrigin 契约；未完成回源测试时不要提交媒体任务。

## 检查与保留数据

```sh
docker compose -f docker-compose.yaml logs --tail 100 singverse-java-backend
docker compose -f docker-compose.yaml logs --tail 100 singverse-frontend
docker compose -f docker-compose.yaml down
```

停止时不要加 `-v`。首次选定项目名后，后续启停继续使用同一名称，避免误用另一组卷。
备份时先停止业务写入，成对保留数据库卷及密钥卷；不能仅复制运行中的 SQLite 主文件，不能用新主密钥替换原密钥。媒体原件另行保存，WebDAV 凭据不下发性能节点。
若原本使用 MySQL，本包不会自动迁移为 SQLite。

本包与本次回源修复使用同一份工作区源码。源码测试通过不代表 NAS、跨主机回源或真实歌曲已验收。
