# Linux AMD64 业务节点离线镜像包

适用于 NVIDIA RTX 3070 / A3000 所在的 Linux x86_64 主机。
内含前端 Nginx 和 Java 后端运行镜像，数据库使用持久化 SQLite。
不需要从 Docker Hub 拉镜像，不需要 npm、Maven 或源码构建，不使用 `.env`。
这是全新安装包，不包含本机已有数据库、密钥或歌曲。当前数据库使用 `202611070001_initial_schema.sql` 从空库初始化，不支持旧迁移链数据库升级。

## 部署

解压后进入 `SingVerse-Business` 目录，再执行：

```sh
sha256sum -c SHA256SUMS
docker load -i images.tar
mkdir -p media
docker compose -f docker-compose.yaml config --quiet
docker compose -f docker-compose.yaml up -d --no-build --pull never --wait
docker compose -f docker-compose.yaml ps -a
curl --fail http://10.100.1.12:18080/api/health
```

飞牛 Docker 项目界面部署前，先在终端运行 `docker load -i images.tar` 导入镜像。
再用本包的 `docker-compose.yaml` 创建首次部署的业务项目；文件不包含 build，且设置 pull_policy: never。
若已有旧业务项目，所有命令使用一个尚未用过的项目名（例如加 `-p singverse-business-fresh`），创建独立数据库和密钥卷，不复用旧卷；端口冲突时停止原业务项目或修改新项目端口。完成本基线首次安装后，后续启停继续使用选定项目名与同一组卷。
此包不操作原有 NVIDIA 性能节点项目。不要将它的 Compose 覆盖到性能节点目录。

业务入口 `http://10.100.1.12:18080/`，全新安装初始账号与密码均为 `admin`，登录后改密。
歌曲放在解压目录的 `media/`，管理页本地来源路径填写 `/media`；也可以添加 WebDAV。
在性能节点连接设置里扫描局域网，选择节点后点击“连接此节点”，可自动配对、测试并保存，无需填写密钥。性能节点须同步升级并允许局域网配对；旧节点仍可手填 `http://10.100.1.12:18090` 和原连接密钥。
只有业务容器挂载歌曲；性能节点通过任务授权回源读取。

如果业务部署到其他地址，同时修改 Compose 中的端口绑定和 SINGVERSE_CALLBACK_ORIGIN。
回源地址必须从性能容器内可达，不能使用 localhost。
SINGVERSE_PUBLIC_ORIGIN 在内网 HTTP 部署中保持为空；只有正式 HTTPS 入口才可填写此项。
所有参数都在 Compose 中，客户端只访问 Java 的业务入口。

SQLite 数据与衍生文件保存在 `singverse-data` 卷，凭据加密主密钥保存在 `singverse-secrets` 卷。
停止可用 `docker compose down`，不要加 `-v`。备份与迁移必须同时保留这两个卷。
旧本机数据不在此包中，MySQL 数据不会自动迁移到 SQLite。

检查日志：`docker compose logs --tail 100 singverse-java-backend singverse-frontend`。
本包包含回源地址修复；镜像构建与本机检查不能代替 NAS 上的歌曲、GPU 和跨主机验收。
