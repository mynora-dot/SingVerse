# Docker 配置入口

所有仓库内命令均从仓库根执行。使用 `--project-directory .` 保持根 `.env`、构建上下文、相对挂载路径和开发 MySQL 项目名与迁移前一致；Make 入口已包含该参数。

| 目录 | 内容 |
|---|---|
| [compose/](compose/) | `compose.app.yaml` 应用栈、`compose.yaml` 开发 MySQL、`compose.compute.yaml` 独立计算节点及硬件、音频运行时、HTTPS 覆盖配置 |
| [build/](build/) | `docker-bake.hcl` 计算镜像矩阵、`.dockerignore` 源码白名单 |
| [../deploy/](../deploy/) | NAS 应用和 RK3566 等部署包模板 |

Dockerfile 保留在各服务目录。两个服务的 `Dockerfile.dockerignore` 通过相对符号链接共用 `build/.dockerignore`，因此从仓库根构建时仍应用源码白名单。打包脚本将该规则复制为部署包根 `.dockerignore`。

```sh
make docker-up
make docker-check
make docker-down
# 独立开发 MySQL（首次按根 .env.example 配置，保留已有 .env）
make db-mysql-up
docker compose --project-directory . -f infra/docker/compose/compose.yaml stop mysql
# 计算镜像矩阵
make compute-images
```

应用默认使用 SQLite，MySQL 为独立开发项目。停止保留命名卷，不加 `-v`；修改初始化密码不会更新已有卷账号。应用、计算节点、硬件与局域网访问详见 [Docker 说明](../../docs/docker.md) 和 [compute README](../../services/singverse-compute/README.md)。
